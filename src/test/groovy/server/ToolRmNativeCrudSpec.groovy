package server

import support.TestLocation
import support.ToolSpecBase
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Spec for the native Rule Machine CRUD tools in hubitat-mcp-server.groovy:
 *
 *   toolCreateRmRule          -> create_rm_rule
 *   toolUpdateRmRule          -> update_rm_rule
 *   toolDeleteRmRule          -> delete_rm_rule
 *
 * Reading is via the existing get_app_config (manage_installed_apps gateway).
 * Backup enumeration + restore is via the existing list_item_backups +
 * restore_item_backup (manage_apps_drivers gateway) — rule snapshots use
 * type="rm-rule" and restore_item_backup dispatches them through the private
 * _rmRestoreFromBackup helper. The cross-tool restore path is exercised
 * here too to guard the dispatch + replay shape.
 *
 * Every write tool runs through requireHubAdminWrite, so golden-path tests seed:
 *   settingsMap.enableHubAdminWrite = true
 *   stateMap.lastBackupTimestamp    = 1234567890000L   (matches fixed now())
 *   args.confirm                    = true
 *
 * Mocking strategy (see docs/testing.md for the three dispatch classes):
 *   - hubInternalGet          — HarnessSpec already routes to hubGet.register(path) closures.
 *   - hubInternalGetRaw       — stubbed per-test on script.metaClass (returns [status, location, data]).
 *   - hubInternalPostForm     — stubbed per-test on script.metaClass (returns [status, location, data]).
 *   - uploadHubFile / downloadHubFile / deleteHubFile — stubbed per-test on script.metaClass.
 *
 * The critical regression under test: _rmBuildSettingsBody MUST emit the full
 * 3-field payload group (settings[name]=csv, name.type=capability.X,
 * name.multiple=true) for multi-device capability inputs — omitting the
 * .multiple=true sidecar silently flips the AppSetting DB flag and breaks
 * the rule's rendering. The "3-field contract" test below catches any
 * regression on that code path.
 */
class ToolRmNativeCrudSpec extends ToolSpecBase {

    // Shared location stub for tests that exercise location.modes (Mode trigger)
    // or location.hub (future). AppExecutor.getLocation() is wired in setupSpec
    // below. Tests that need specific modes assign sharedLocation.modes in their
    // given: block; cleanup() resets to the empty default so tests stay isolated.
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        // Wire location reads to the shared TestLocation stub so code paths that
        // call location.modes (addTrigger Mode, get_modes, toolSetMode) get
        // deterministic results instead of NPE from the @AutoImplement null default.
        // HarnessSpec.setupSpec() runs first (Spock calls all setupSpec in hierarchy),
        // so appExecutor is already a Mock when this override runs.
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanup() {
        // Reset modes after each test so a test that assigns sharedLocation.modes
        // cannot leak into unrelated tests.
        sharedLocation.modes = []
    }

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        settingsMap.enableBuiltinApp = true
        settingsMap.enableHubAdminRead = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    private void enableReadOnly() {
        settingsMap.enableBuiltinApp = true
        settingsMap.enableHubAdminRead = true
    }

    // Minimal RM parent discovery response (/hub2/appsList tree with a
    // Rule Machine parent at id=21 so _rmDiscoverParentAppId finds it).
    private String appsListJson(int rmId = 21) {
        JsonOutput.toJson([apps: [
            [data: [id: rmId, name: "Rule Machine", type: "Rule Machine", user: false, hidden: false], children: []]
        ]])
    }

    // Minimal RM rule config JSON. Schema inputs drive _rmBuildSettingsBody's
    // 3-field group logic, so tests shape the sections/inputs as needed.
    private String ruleConfigJson(int ruleId, String label = "BAT-RM-test", List inputs = []) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: label, trueLabel: label, installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null, sections: [
                [title: "", input: inputs]
            ]],
            settings: [:],
            childApps: []
        ])
    }

    private String statusJson(int ruleId, List appSettings = [], int subs = 1) {
        JsonOutput.toJson([
            installedApp: [id: ruleId],
            appSettings: appSettings,
            eventSubscriptions: (1..subs).collect { [name: "evt${it}"] },
            scheduledJobs: [],
            appState: [:],
            childAppCount: 0,
            childDeviceCount: 0
        ])
    }

    // ---------- create_rm_rule ----------

    def "create_rm_rule requires confirm=true"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolCreateNativeApp([name: "BAT-RM-demo"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("SAFETY CHECK FAILED")
    }

    def "create_rm_rule throws when name is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolCreateNativeApp([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("name is required")
    }

    def "create_rm_rule discovers RM parent, creates child, sets label, clicks updateRule"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/hub2/appsList') { params -> appsListJson(21) }
        hubGet.register('/installedapp/configure/json/974') { params -> ruleConfigJson(974, "", [[name: "origLabel", type: "text"]]) }
        hubGet.register('/installedapp/statusJson/974') { params -> statusJson(974) }

        def posts = []
        def createCalls = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            createCalls << path
            [status: 302, location: "/installedapp/configure/974", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolCreateNativeApp([name: "BAT-RM-demo", confirm: true])

        then: "createchild hit with the RM parent id"
        createCalls.any { it == "/installedapp/createchild/hubitat/Rule-5.1/parent/21" }

        and: "label POSTed to /installedapp/update/json with correct shape"
        def labelPost = posts.find { it.path == "/installedapp/update/json" }
        labelPost != null
        labelPost.body["settings[origLabel]"] == "BAT-RM-demo"
        labelPost.body["origLabel.type"] == "text"
        labelPost.body.id == "974"

        and: "updateRule button clicked so initialize() fires"
        def btnClick = posts.find { it.path == "/installedapp/btn" }
        btnClick?.body?.name == "updateRule"

        and: "result carries the new rule id and note"
        result.success == true
        result.appId == 974
        result.appType == "rule_machine"
        result.name == "BAT-RM-demo"
        result.parentAppId == 21
    }

    def "create_rm_rule force-deletes orphan when setup fails after createchild succeeds"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/hub2/appsList') { params -> appsListJson(21) }
        hubGet.register('/installedapp/configure/json/975') { params -> throw new RuntimeException("boom — config page broken") }

        def rawCalls = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            rawCalls << path
            [status: 302, location: "/installedapp/configure/975", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolCreateNativeApp([name: "BAT-RM-orphan", confirm: true])

        then:
        result.success == false
        result.orphanCleanup == "attempted"
        rawCalls.any { it == "/installedapp/forcedelete/975/quiet" }
    }

    def "create_native_app caches RM parent id in state.parentAppIds.rule_machine"() {
        given:
        enableHubAdminWrite()
        def appsListCalls = 0
        hubGet.register('/hub2/appsList') { params ->
            appsListCalls++
            appsListJson(21)
        }
        hubGet.register('/installedapp/configure/json/974') { params -> ruleConfigJson(974, "", [[name: "origLabel", type: "text"]]) }
        hubGet.register('/installedapp/statusJson/974') { params -> statusJson(974) }

        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/installedapp/configure/974", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        script.toolCreateNativeApp([name: "BAT-RM-demo", confirm: true])
        def cached = stateMap.parentAppIds?.rule_machine

        then:
        cached == 21
        appsListCalls == 1
    }

    // ---------- update_rm_rule ----------

    def "update_rm_rule requires confirm=true"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateNativeApp([appId: 100, settings: [a: 1]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("SAFETY CHECK FAILED")
    }

    def "update_rm_rule requires settings, button, or addTrigger"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateNativeApp([appId: 100, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("settings")
        ex.message.contains("button")
        ex.message.contains("addTrigger")
    }

    def "update_rm_rule emits the 3-field capability contract for multi-device inputs"() {
        given: "a rule with a multi-device capability input"
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "tDev0", type: "capability.switch", multiple: true]])
        }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "tDev0", type: "capability.switch", multiple: true, value: "8,9"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when: "settings pass a List for the multi-device input"
        def result = script.toolUpdateNativeApp([appId: 100, settings: [tDev0: [8, 9]], confirm: true])

        then: "settings POST carries all three fields together"
        def updatePost = posts.find { it.path == "/installedapp/update/json" }
        updatePost.body["settings[tDev0]"] == "8,9"
        updatePost.body["tDev0.type"] == "capability.switch"
        updatePost.body["tDev0.multiple"] == "true"
        updatePost.body.id == "100"

        and: "updateRule is clicked after the settings write so subscriptions repopulate"
        posts.any { it.path == "/installedapp/btn" && it.body.name == "updateRule" }

        and: "success is returned with the unified-manifest backup key"
        result.success == true
        result.backup?.backupKey?.startsWith("rm-rule_100_")
        result.backup?.fileName?.startsWith("mcp-rm-backup-100-")
        result.backup?.type == "rm-rule"
    }

    def "update_rm_rule retries once when multiple flag flips to false after the write"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "tDev0", type: "capability.switch", multiple: true]])
        }
        def statusCalls = 0
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusCalls++
            // 1st call = pre-backup snapshot; 2nd = post-write verify (poisoned);
            // 3rd = post-retry verify (recovered); 4th+ = final page-error probe.
            if (statusCalls == 2) {
                statusJson(100, [[name: "tDev0", type: "capability.switch", multiple: false, value: "8,9"]])
            } else {
                statusJson(100, [[name: "tDev0", type: "capability.switch", multiple: true, value: "8,9"]])
            }
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, settings: [tDev0: [8, 9]], confirm: true])

        then: "settings POSTed twice (once initially, once on retry)"
        posts.findAll { it.path == "/installedapp/update/json" }.size() == 2

        and: "still succeeds — the retry recovered the flag"
        result.success == true
    }

    def "update_rm_rule surfaces error + restoreHint when page.error is non-null after write"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true],
                configPage: [name: "mainPage", title: "Edit", install: true, sections: [[title: "", input: []]], error: "Command 'size' is not supported"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, button: "updateRule", confirm: true])

        then: "success=false when health check sees the configPage.error — fail-loud so the LLM sees the broken state"
        result.success == false
        result.configPageError?.contains("'size'")
        result.warning?.contains("rendering error")
        result.restoreHint?.contains("restore_item_backup")
        result.health?.ok == false
        result.health?.configPageError?.contains("'size'")
    }

    def "update_rm_rule button mode clicks without writing settings"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, button: "pausRule", confirm: true])

        then:
        posts.any { it.path == "/installedapp/btn" && it.body.name == "pausRule" }
        !posts.any { it.path == "/installedapp/update/json" }
        result.success == true
        result.buttonClicked == "pausRule"
    }

    // ---------- delete_rm_rule ----------

    def "delete_rm_rule snapshots, then force-deletes when force=true"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/200') { params -> ruleConfigJson(200, "to-delete") }
        hubGet.register('/installedapp/statusJson/200') { params -> statusJson(200) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def rawCalls = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            rawCalls << path
            [status: 302, location: "/installedapps", data: ""]
        }

        when:
        def result = script.toolDeleteNativeApp([appId: 200, force: true, confirm: true])

        then:
        rawCalls.any { it == "/installedapp/forcedelete/200/quiet" }
        result.success == true
        result.mode == "forcedelete"
        result.backup?.backupKey?.startsWith("rm-rule_200_")
        result.backup?.type == "rm-rule"

        and: "the snapshot is registered in the unified item-backup manifest so list_item_backups picks it up"
        stateMap.itemBackupManifest?.values()?.any { it.type == "rm-rule" && it.ruleId == 200 }
    }

    def "delete_rm_rule soft-delete surfaces hubMessage on refusal"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/201') { params -> ruleConfigJson(201, "blocked") }
        hubGet.register('/installedapp/statusJson/201') { params -> statusJson(201) }
        hubGet.register('/installedapp/delete/201') { params ->
            JsonOutput.toJson([success: false, message: "Cannot delete: rule has child devices"])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def result = script.toolDeleteNativeApp([appId: 201, confirm: true])

        then:
        result.success == false
        result.hubMessage?.contains("child devices")
        result.note?.contains("force=true")
        result.backup != null
    }

    // ---------- unified backup integration: list_item_backups + restore_item_backup ----------

    def "list_item_backups surfaces rm-rule entries with rule-specific metadata alongside app/driver entries"() {
        given:
        enableReadOnly()
        stateMap.itemBackupManifest = [
            "app_50": [type: "app", id: "50", fileName: "mcp-backup-app-50.groovy", version: 12, timestamp: 1000, sourceLength: 100],
            "rm-rule_100_20260101-000001": [type: "rm-rule", id: 100, ruleId: 100, fileName: "mcp-rm-backup-100-20260101-000001.json",
                                            reason: "pre-update", appLabel: "rule A", timestamp: 2000, sourceLength: 500],
            "rm-rule_200_20260102-000001": [type: "rm-rule", id: 200, ruleId: 200, fileName: "mcp-rm-backup-200-20260102-000001.json",
                                            reason: "pre-delete", appLabel: "rule B", timestamp: 3000, sourceLength: 600]
        ]

        when:
        def result = script.toolListItemBackups()

        then: "all three entries returned, newest first"
        result.total == 3
        result.backups[0].type == "rm-rule"
        result.backups[0].ruleId == 200
        result.backups[1].type == "rm-rule"
        result.backups[1].appLabel == "rule A"
        result.backups[2].type == "app"

        and: "type-specific fields surface only on the matching entry shape"
        result.backups[0].reason == "pre-delete"
        !result.backups[0].containsKey("version")
        result.backups[2].version == 12
        !result.backups[2].containsKey("ruleId")
    }

    def "restore_item_backup dispatches rm-rule entries through the rule restore path (in-place when rule exists)"() {
        given:
        enableHubAdminWrite()
        def snapshot = [
            schemaVersion: 1,
            ruleId: 300,
            reason: "pre-update",
            timestamp: 1000,
            timestampIso: "2026-01-01T00:00:00Z",
            appLabel: "restored",
            configJson: [
                app: [id: 300, label: "restored"],
                configPage: [sections: [[title: "", input: [
                    [name: "tDev0", type: "capability.switch", multiple: true]
                ]]]],
                settings: [tDev0: [8, 9]]
            ],
            statusJson: [:]
        ]
        def snapshotBytes = JsonOutput.toJson(snapshot).getBytes("UTF-8")
        stateMap.itemBackupManifest = [
            "rm-rule_300_x": [type: "rm-rule", id: 300, ruleId: 300,
                              fileName: "mcp-rm-backup-300-x.json", reason: "pre-update",
                              appLabel: "restored", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn ->
            fn == "mcp-rm-backup-300-x.json" ? snapshotBytes : null
        }
        hubGet.register('/installedapp/configure/json/300') { params ->
            ruleConfigJson(300, "restored", [[name: "tDev0", type: "capability.switch", multiple: true]])
        }
        hubGet.register('/installedapp/statusJson/300') { params ->
            statusJson(300, [[name: "tDev0", type: "capability.switch", multiple: true, value: "8,9"]])
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: "rm-rule_300_x", confirm: true])

        then: "rule id preserved, settings replayed, multiple contract honored"
        result.success == true
        result.type == "rm-rule"
        result.ruleId == 300
        result.originalRuleId == 300
        result.recreated == false
        def settingsPost = posts.find { it.path == "/installedapp/update/json" }
        settingsPost.body["settings[tDev0]"] == "8,9"
        settingsPost.body["tDev0.multiple"] == "true"
    }

    def "restore_item_backup recreates the rule with a fresh id when the original was deleted"() {
        given:
        enableHubAdminWrite()
        def snapshot = [
            schemaVersion: 1,
            ruleId: 400,
            reason: "pre-delete",
            timestamp: 1000,
            timestampIso: "2026-01-01T00:00:00Z",
            appLabel: "gone-rule",
            configJson: [
                app: [id: 400, label: "gone-rule"],
                configPage: [sections: [[title: "", input: [[name: "origLabel", type: "text"]]]]],
                settings: [origLabel: "gone-rule"]
            ],
            statusJson: [:]
        ]
        def snapshotBytes = JsonOutput.toJson(snapshot).getBytes("UTF-8")
        stateMap.itemBackupManifest = [
            "rm-rule_400_y": [type: "rm-rule", id: 400, ruleId: 400,
                              fileName: "mcp-rm-backup-400-y.json", reason: "pre-delete",
                              appLabel: "gone-rule", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn -> snapshotBytes }
        hubGet.register('/hub2/appsList') { params -> appsListJson(21) }
        hubGet.register('/installedapp/configure/json/400') { params ->
            throw new RuntimeException("404 — rule gone")
        }
        hubGet.register('/installedapp/configure/json/401') { params ->
            ruleConfigJson(401, "gone-rule", [[name: "origLabel", type: "text"]])
        }
        hubGet.register('/installedapp/statusJson/401') { params -> statusJson(401) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/installedapp/configure/401", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: "rm-rule_400_y", confirm: true])

        then:
        result.success == true
        result.type == "rm-rule"
        result.recreated == true
        result.ruleId == 401
        result.originalRuleId == 400
    }

    // ---------- wire-format invariants ----------
    // These tests guard the wire-format fixes documented in the PR. Each is
    // anchored to a specific live-hub failure and the corresponding fix
    // commit. A regression here breaks user rules without producing any
    // hub-side error, so the unit tests are the gate.

    def "_rmBuildSettingsBody emits deviceList sidecar on capability writes"() {
        given: "a rule with a capability.switch input"
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "tDev0", type: "capability.switch", multiple: true]])
        }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "tDev0", type: "capability.switch", multiple: true, value: "8,9"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        script.toolUpdateNativeApp([appId: 100, settings: [tDev0: [8, 9]], confirm: true])

        then: "the update POST carries the deviceList=<keyname> sidecar marker"
        def updatePost = posts.find { it.path == "/installedapp/update/json" }
        updatePost.body["deviceList"] == "tDev0"
    }

    def "_rmClickAppButton emits bracket-form settings[btn]=clicked + form-context fields"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when: "we click a wizard-Done button via the button mode (which routes through _rmClickAppButton)"
        script.toolUpdateNativeApp([appId: 100, button: "hasAll", pageName: "selectTriggers", confirm: true])

        then: "the button POST carries the bracket form + form-context fields together"
        def btnPost = posts.find { it.path == "/installedapp/btn" }
        btnPost.body.id == "100"
        btnPost.body.name == "hasAll"
        btnPost.body["settings[hasAll]"] == "clicked"
        btnPost.body["hasAll.type"] == "button"
        btnPost.body.formAction == "update"
        btnPost.body.currentPage == "selectTriggers"
        btnPost.body.pageBreadcrumbs == '["mainPage"]'
    }

    def "addAction waitEvents click for anotherWait carries stateAttribute=anotherWait"() {
        given: "a rule with selectActions schema and waitEvents action subtype"
        enableHubAdminWrite()
        // doActPage schema progresses across writes — the test stub returns
        // a schema that contains tCapab-1, tCapab-2 (after first hasAll), etc.
        // We just need to verify ONE shape: the anotherWait click body.
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["delayActs": "Delay, Wait, Exit or Comment"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum"],
                [name: "actSubType.1", type: "enum"],
                [name: "tCapab-1", type: "enum", options: ["Switch"]],
                [name: "tCapab-2", type: "enum", options: ["Motion"]]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when: "we explicitly invoke the anotherWait button click (matching the live-UI XHR captured 2026-04-26)"
        script.toolUpdateNativeApp([appId: 100, button: "anotherWait", stateAttribute: "anotherWait", pageName: "doActPage", confirm: true])

        then: "the click POST carries stateAttribute=anotherWait (without it RM no-ops the click and tCapab-N+1 never appears)"
        def btnPost = posts.find { it.path == "/installedapp/btn" }
        btnPost.body.name == "anotherWait"
        btnPost.body["settings[anotherWait]"] == "clicked"
        btnPost.body.stateAttribute == "anotherWait"
    }

    def "addRequiredExpression sub-page writes use pageBreadcrumbs=[] (not [\"mainPage\"]) on STPage"() {
        given:
        enableHubAdminWrite()
        // STPage schema after useST=true is set; cond enum is the in-flight
        // wizard picker. addRequiredExpression's _rmWriteSubPageField path
        // must use pageBreadcrumbs=[] for STPage writes per the live UI
        // capture; sending ["mainPage"] re-fires the action_href and
        // resets the in-flight wizard accumulator.
        def stPageInputs = [
            [name: "cond", type: "enum", options: ["": "Click to set", "a": "--> New Condition", "b": "--> ( sub-expression"]],
            [name: "doneST", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", [[name: "useST", type: "bool"]]) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", [[name: "useST", type: "bool"]]) }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> ruleConfigJson(100, "r", stPageInputs) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when: "addRequiredExpression starts the STPage wizard"
        try {
            script.toolUpdateNativeApp([
                appId: 100,
                addRequiredExpression: [conditions: [[capability: "Switch", deviceIds: [8], state: "on"]]],
                confirm: true
            ])
        } catch (Exception ignored) { /* schema stubs are minimal; partial walk is fine, we only assert the wire format */ }

        then: "every STPage settings write uses pageBreadcrumbs=[]"
        def stPageWrites = posts.findAll { it.path == "/installedapp/update/json" && it.body.currentPage == "STPage" }
        stPageWrites.size() > 0
        stPageWrites.every { it.body.pageBreadcrumbs == '[]' }

        and: "no STPage write carries the action_href marker (those belong only on the navigation POST)"
        stPageWrites.every { write ->
            !write.body.keySet().any { k -> k.toString().startsWith("_action_href_") }
        }
    }

    def "addRequiredExpression sub-expression open writes cond=b VALUE not literal label"() {
        given:
        enableHubAdminWrite()
        def stPageInputs = [
            [name: "cond", type: "enum", options: ["": "Click to set", "a": "--> New Condition", "b": "--> ( sub-expression"]],
            [name: "doneST", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", [[name: "useST", type: "bool"]]) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", [[name: "useST", type: "bool"]]) }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> ruleConfigJson(100, "r", stPageInputs) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"M1"}' }
        hubGet.register('/device/fullJson/10') { params -> '{"id":"10","name":"C1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when: "addRequiredExpression with the OUTER cond as a subExpression triggers cond=b first"
        // Single outer condition that's itself a subExpression — guarantees the
        // open-paren cond=b write fires before any rCapab_<N> lookup that
        // would short-circuit the walk against a minimal schema stub.
        try {
            script.toolUpdateNativeApp([
                appId: 100,
                addRequiredExpression: [conditions: [
                    [subExpression: [conditions: [
                        [capability: "Motion", deviceIds: [9], state: "active"],
                        [capability: "Contact", deviceIds: [10], state: "open"]
                    ], operator: "OR"]]
                ]],
                confirm: true
            ])
        } catch (Exception ignored) { /* schema stubs are minimal; we only assert the wire format */ }

        then: "the open-paren write uses settings[cond]=b (the option VALUE) — never the label '--> ( sub-expression'"
        def condBWrite = posts.find { it.path == "/installedapp/update/json" && it.body["settings[cond]"] == "b" }
        condBWrite != null
        !posts.any { it.body["settings[cond]"]?.toString()?.contains("sub-expression") }
    }

    // ---------- Issue #77 -- ghost ifThen predCapabs clear (Step 4b) ----------
    //
    // After addRequiredExpression completes, RM's atomicState.predCapabs retains
    // the RE's condition context. A subsequent addAction for a plain (non-expression)
    // action opens doActPage and hits actionDone with stale predCapabs, wrapping
    // the action in IF(**Broken Condition**).
    //
    // Fix: _rmAddRequiredExpression Step 4b fires a "ghost ifThen" sequence:
    //   N click on selectActions -> actType=condActs + actSubType=getIfThen writes
    //   on doActPage -> actionCancel (NOT actionDone) -> nav doActPage->selectActions.
    // This triggers RM's ifThen initializer (re-initializes predCapabs) then
    // cancels before commit, leaving the rule clean and predCapabs zeroed.
    //
    // Key invariants under test:
    //  (a) The N click fires on selectActions with stateAttribute=doActN.
    //  (b) actType.{idx}=condActs and actSubType.{idx}=getIfThen are written.
    //  (c) actionCancel fires on doActPage (NOT actionDone).
    //  (d) The doActPage->selectActions nav fires after the cancel.
    //  (e) No action is baked into the rule (actionDone never fired).
    //  (f) When the ghost clear fails (exception), a warn log fires and the
    //      overall addRequiredExpression still succeeds (best-effort).
    //
    // These tests use the shared stPageCondSchemaJson helper (which includes
    // rCapab_1/rDev_1/state_1/hasAll) so the STPage walk completes through to
    // Step 4b. Both an STPage Done POST (currentPage=STPage, _action_previous=Done)
    // and the ghost ifThen N click (currentPage=selectActions) must appear.

    def "addRequiredExpression Step 4b fires ghost ifThen: N on selectActions, condActs+getIfThen, actionCancel, nav"() {
        // Regression gate for issue #77.
        // Verifies the exact wire sequence of the ghost ifThen clear:
        // N click (selectActions) -> actType=condActs -> actSubType=getIfThen
        // -> actionCancel (doActPage) -> nav doActPage->selectActions.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        // mainPage returns a baked RE paragraph so the post-commit check passes.
        hubGet.register('/installedapp/configure/json/100') {
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') {
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph", description: "IF S1 is on"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') {
            fetchSeq++
            stPageCondSchemaJson(100, fetchSeq)
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') {
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') {
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')           { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when: "addRequiredExpression completes (full STPage walk via stPageCondSchemaJson stubs)"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Switch", deviceIds: [8], state: "on"]]],
            confirm: true
        ])

        then: "RE committed successfully (bake-check passed with baked paragraph)"
        result.success == true

        and: "(a) N button clicked on selectActions with stateAttribute=doActN"
        def nClick = posts.find { it.path == "/installedapp/btn" &&
                                   it.body?.name == "N" &&
                                   it.body?.currentPage == "selectActions" }
        nClick != null
        nClick.body?.stateAttribute == "doActN"

        and: "(b) actType.{idx}=condActs written on doActPage"
        posts.any { it.path == "/installedapp/update/json" &&
                    it.body?.currentPage == "doActPage" &&
                    it.body?.any { k, v -> k?.toString()?.startsWith("settings[actType.") && v == "condActs" } }

        and: "(b) actSubType.{idx}=getIfThen written on doActPage"
        posts.any { it.path == "/installedapp/update/json" &&
                    it.body?.currentPage == "doActPage" &&
                    it.body?.any { k, v -> k?.toString()?.startsWith("settings[actSubType.") && v == "getIfThen" } }

        and: "(c) actionCancel clicked on doActPage -- NOT actionDone"
        def cancelClick = posts.find { it.path == "/installedapp/btn" &&
                                        it.body?.name == "actionCancel" &&
                                        it.body?.currentPage == "doActPage" }
        cancelClick != null
        !posts.any { it.path == "/installedapp/btn" &&
                     it.body?.name == "actionDone" &&
                     it.body?.currentPage == "doActPage" }

        and: "(d) doActPage->selectActions nav fires after the cancel"
        posts.any { it.path == "/installedapp/update/json" &&
                    it.body?.currentPage == "doActPage" &&
                    it.body?.any { k, v -> k?.toString()?.contains("_action_href_name|selectActions|") } }

        and: "(e) no actionDone fired on doActPage anywhere in the sequence"
        !posts.any { it.path == "/installedapp/btn" && it.body?.name == "actionDone" }
    }

    def "addRequiredExpression Step 4b ghost clear fires AFTER STPage Done (not before)"() {
        // The ghost ifThen clear must occur AFTER _rmSubmitSubPageDone exits STPage.
        // Ordering invariant: the STPage Done back-nav (currentPage=STPage,
        // _action_previous=Done) appears in the POST log BEFORE the selectActions
        // N click.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        hubGet.register('/installedapp/configure/json/100') {
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') {
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph", description: "IF S1 is on"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') {
            fetchSeq++
            stPageCondSchemaJson(100, fetchSeq)
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') {
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') {
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')           { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def seenPosts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            seenPosts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Switch", deviceIds: [8], state: "on"]]],
            confirm: true
        ])

        then: "STPage Done back-nav POST fires before the selectActions N click"
        // STPage Done: _action_previous=Done is in the POST body (set by _rmSubmitSubPageDone)
        def stPageDoneIdx = seenPosts.findIndexOf { it.path == "/installedapp/update/json" &&
                                                     it.body?.currentPage == "STPage" &&
                                                     it.body?._action_previous == "Done" }
        // Ghost clear N click
        def nClickIdx = seenPosts.findIndexOf { it.path == "/installedapp/btn" &&
                                                 it.body?.name == "N" &&
                                                 it.body?.currentPage == "selectActions" }
        stPageDoneIdx != -1
        nClickIdx != -1
        stPageDoneIdx < nClickIdx
    }

    def "addRequiredExpression Step 4b ghost clear failure warns and still returns success"() {
        // If the ghost ifThen sequence throws (e.g. N click fails), addRequiredExpression
        // must still return success=true (the RE itself committed successfully).
        // A warn mcpLog must fire so the operator can diagnose issue #77 risk.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        hubGet.register('/installedapp/configure/json/100') {
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') {
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph", description: "IF S1 is on"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') {
            fetchSeq++
            stPageCondSchemaJson(100, fetchSeq)
        }
        // selectActions fetch returns empty schema (N button absent) so N click
        // button-version-fetch succeeds but the schema is empty -- this is enough
        // for the N button POST to fire (pageName triggers the version fetch path,
        // which succeeds fine). We need the N btn POST itself to throw instead.
        hubGet.register('/installedapp/configure/json/100/selectActions') {
            ruleConfigJson(100, "r", [])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')           { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def warnLogs = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            if (level == "warn") warnLogs << [level: level, component: component, msg: msg]
        }

        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // N button click on selectActions throws -- simulates ghost clear failure
            if (path == "/installedapp/btn" && body?.name == "N") {
                throw new RuntimeException("hub returned 500 on N click (simulated ghost clear failure)")
            }
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Switch", deviceIds: [8], state: "on"]]],
            confirm: true
        ])

        then: "addRequiredExpression still returns a result -- ghost clear is best-effort, not fatal"
        result != null
        result.success == true

        and: "a warn log fires naming the failure and noting the IF Broken Condition risk"
        warnLogs.any { it.msg?.contains("ghost ifThen clear failed") && it.msg?.contains("Broken Condition") }
    }

    def "addTrigger writes isCondTrig.<N>=false finalize for non-conditional triggers"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            ruleConfigJson(100, "r", [
                [name: "tCapab1", type: "enum", options: ["Switch"]],
                [name: "tDev1", type: "capability.switch", multiple: true],
                [name: "tstate1", type: "enum", options: ["on", "off"]],
                [name: "isCondTrig.1", type: "bool"],
                [name: "hasAll", type: "button"]
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        try {
            script.toolUpdateNativeApp([
                appId: 100,
                addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
                confirm: true
            ])
        } catch (Exception ignored) { /* partial schema is fine for invariant assertion */ }

        then: "addTrigger explicitly writes isCondTrig.1=false (avoids phantom Broken Trigger N+1)"
        def isCondTrigWrite = posts.find { it.path == "/installedapp/update/json" && it.body.containsKey("settings[isCondTrig.1]") }
        isCondTrigWrite != null
        isCondTrigWrite.body["settings[isCondTrig.1]"] == "false"
    }

    def "_rmValidateDeviceIdsExist rejects unknown device IDs in addTrigger before any write"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        // Bogus device ID — /device/fullJson/99999 returns empty
        hubGet.register('/device/fullJson/99999') { params -> "" }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [99999], state: "on"],
            confirm: true
        ])

        then: "the call fails with a clear error before any wizard-edit POST fires"
        result.success == false
        result.error?.contains("99999")
        result.error?.contains("does not exist")

        and: "no /installedapp/update/json POST went out (validation runs pre-write)"
        !posts.any { it.path == "/installedapp/update/json" }
    }

    def "patches batch outer success rolls up inner sub-item success"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        // 99999 returns empty so deviceId pre-validation rejects it
        hubGet.register('/device/fullJson/99999') { params -> "" }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when: "patches contains addTriggers with one valid + one bogus-id spec"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            patches: [[addTriggers: [
                [capability: "Switch", deviceIds: [8], state: "on"],
                [capability: "Switch", deviceIds: [99999], state: "on"]
            ]]],
            confirm: true
        ])

        then: "outer patches[0].success is FALSE because one inner item failed"
        result.patches.size() == 1
        result.patches[0].op == "addTriggers"
        result.patches[0].success == false
        result.patches[0].results.size() == 2
        result.patches[0].results[1].success == false
        result.patches[0].results[1].error?.contains("99999")

        and: "the user-visible result.success is also false"
        result.success == false
    }

    // ---------- structured-shortcut coverage (issue #141 part A) ----------

    def "addLocalVariable golden path: walks moreVar, writes hbVar/varType/varValue, retries verify"() {
        given:
        enableHubAdminWrite()
        // selectActions schema progresses through the moreVar wizard. Initially
        // moreVar button visible; after click, hbVar+varType+varValue inputs.
        def selActsAfter = [
            [name: "hbVar", type: "text"],
            [name: "varType", type: "enum", options: ["Number", "Decimal", "String", "Boolean", "DateTime"]],
            [name: "varValue", type: "text"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", selActsAfter) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100],
                appSettings: [],
                eventSubscriptions: [],
                scheduledJobs: [],
                appState: [[name: "allLocalVars", value: [counter: [type: "integer", value: 42]]]],
                state: [:]
            ])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addLocalVariable: [name: "counter", type: "Number", value: 42],
            confirm: true
        ])

        then: "the wizard moreVar button is clicked, then hbVar/varType/varValue are written"
        posts.any { it.path == "/installedapp/btn" && it.body.name == "moreVar" }
        posts.any { it.path == "/installedapp/update/json" && it.body["settings[hbVar]"] == "counter" }
        posts.any { it.path == "/installedapp/update/json" && it.body["settings[varType]"]?.toString() == "Number" }

        and: "result reflects the committed variable name + type + value"
        result.success == true
        result.variable?.name == "counter"
        result.variable?.type == "Number"
    }

    def "addLocalVariable Boolean coercion writes 'true'/'false' STRING (not Groovy primitive)"() {
        given:
        enableHubAdminWrite()
        def selActsAfter = [
            [name: "hbVar", type: "text"],
            [name: "varType", type: "enum"],
            [name: "varValue", type: "text"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", selActsAfter) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100], appSettings: [], eventSubscriptions: [], scheduledJobs: [],
                appState: [[name: "allLocalVars", value: [flag: [type: "boolean", value: true]]]], state: [:]
            ])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        script.toolUpdateNativeApp([
            appId: 100,
            addLocalVariable: [name: "flag", type: "Boolean", value: true],
            confirm: true
        ])

        then: "the varValue write carries the literal string 'true' — RM 5.1 silently rejects the Groovy primitive"
        def varValueWrite = posts.find { it.path == "/installedapp/update/json" && it.body.containsKey("settings[varValue]") }
        varValueWrite != null
        varValueWrite.body["settings[varValue]"] == "true"
    }

    def "removeAction throws when delAct click is silently no-oped (action still present after all retries)"() {
        given:
        enableHubAdminWrite()
        // statusJson ALWAYS returns the same indices across all 4 retry attempts =>
        // the deletion never propagates => all retries exhaust => IllegalStateException.
        // pauseExecution is a no-op on the AppExecutor mock so the test runs at full speed.
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "actType.1", value: "delayActs"], [name: "actType.2", value: "switchActs"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 1], confirm: true])

        then: "the silent no-op is surfaced as success: false with the verify-via-get_app_config recovery hint"
        result.success == false
        result.error?.contains("still present in rule")
        result.error?.contains("Verify via get_app_config")
    }

    def "removeAction succeeds immediately when deletion propagates on the first post-click fetch"() {
        given:
        enableHubAdminWrite()
        // After the delAct POST fires, the first post-click statusJson fetch
        // sees action 1 gone => _rmDeleteAction returns success on attempt 0
        // without any pauseExecution retry. pauseExecution is a no-op on the
        // AppExecutor mock so there is no actual sleep even if a retry fires.
        // verificationFetches counts only the fetches that _rmDeleteAction's
        // retry loop makes (stops counting once action 1 disappears from the
        // response so post-success health-check fetches don't inflate the count).
        def delActFired = false
        def verificationFetches = 0
        def deletionConfirmed = false
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (delActFired && !deletionConfirmed) {
                verificationFetches++
                // action 1 gone — deletion propagated immediately
                deletionConfirmed = true
                statusJson(100, [[name: "actType.2", value: "switchActs"]])
            } else if (!delActFired) {
                // pre-click (backup + beforeIndices): both actions present
                statusJson(100, [[name: "actType.1", value: "delayActs"], [name: "actType.2", value: "switchActs"]])
            } else {
                // post-success health check and any subsequent fetches
                statusJson(100, [[name: "actType.2", value: "switchActs"]])
            }
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "delAct") delActFired = true
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 1], confirm: true])

        then: "success on the first check — no retries needed"
        result.success == true
        result.removedIndices?.contains(1) || result.note?.contains("Removed action 1")

        and: "only 1 verification fetch: attempt 0 sees action gone immediately (no retry fired)"
        verificationFetches == 1
    }

    def "removeAction succeeds on second check when deletion propagates after first retry (race recovery)"() {
        given:
        enableHubAdminWrite()
        // After the delAct POST fires, the first post-click statusJson fetch
        // still shows action 1 (async dispatch race). The second post-click
        // fetch (after 100ms pauseExecution no-op) sees it gone => success on
        // attempt 1. verificationFetches counts only the retry-loop fetches;
        // it stops once action 1 disappears so the health-check fetch after
        // _rmDeleteAction returns does not inflate the count.
        // pauseExecution is a no-op on the AppExecutor mock.
        def delActFired = false
        def verificationFetches = 0
        def deletionConfirmed = false
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (delActFired && !deletionConfirmed) {
                verificationFetches++
                if (verificationFetches == 1) {
                    // first post-click check: action still present (race)
                    statusJson(100, [[name: "actType.1", value: "delayActs"], [name: "actType.2", value: "switchActs"]])
                } else {
                    // second post-click check: action 1 gone (deletion propagated)
                    deletionConfirmed = true
                    statusJson(100, [[name: "actType.2", value: "switchActs"]])
                }
            } else if (!delActFired) {
                // pre-click (backup + beforeIndices): both actions present
                statusJson(100, [[name: "actType.1", value: "delayActs"], [name: "actType.2", value: "switchActs"]])
            } else {
                // post-success health check and any subsequent fetches
                statusJson(100, [[name: "actType.2", value: "switchActs"]])
            }
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "delAct") delActFired = true
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 1], confirm: true])

        then: "success after one retry — race-recovery path"
        result.success == true
        result.removedIndices?.contains(1) || result.note?.contains("Removed action 1")

        and: "2 verification fetches: attempt 0 (still present, race) + attempt 1 (gone, recovered)"
        verificationFetches == 2
    }

    def "removeAction succeeds on third check when deletion propagates after second retry (last-chance recovery)"() {
        given:
        enableHubAdminWrite()
        def delActFired = false
        def verificationFetches = 0
        def deletionConfirmed = false
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (delActFired && !deletionConfirmed) {
                verificationFetches++
                if (verificationFetches < 3) {
                    // attempts 0 and 1: action still present
                    statusJson(100, [[name: "actType.1", value: "delayActs"], [name: "actType.2", value: "switchActs"]])
                } else {
                    // attempt 2: action gone (last-chance recovery)
                    deletionConfirmed = true
                    statusJson(100, [[name: "actType.2", value: "switchActs"]])
                }
            } else if (!delActFired) {
                statusJson(100, [[name: "actType.1", value: "delayActs"], [name: "actType.2", value: "switchActs"]])
            } else {
                statusJson(100, [[name: "actType.2", value: "switchActs"]])
            }
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "delAct") delActFired = true
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 1], confirm: true])

        then: "success on the third check -- last-chance recovery"
        result.success == true
        result.removedIndices?.contains(1) || result.note?.contains("Removed action 1")

        and: "3 verification fetches: attempts 0 and 1 (still present) + attempt 2 (gone)"
        verificationFetches == 3
    }

    def "clearActions throws when trashActs never enters schema after trashAll click"() {
        given:
        enableHubAdminWrite()
        // selectActions schema never gains trashActs even after the trashAll click
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "actType.1", value: "delayActs"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, clearActions: true, confirm: true])

        then: "result surfaces the failure to enter trash mode rather than silently returning empty"
        result.success == false
        result.error?.toLowerCase()?.contains("trashacts")
    }

    def "replaceActions atomically clears then bulk-adds, rolling per-item failures into addedResults"() {
        given:
        enableHubAdminWrite()
        def selectActionsSchema = [
            [name: "actType.1", type: "enum", options: ["switchActs"]],
            [name: "trashActs", type: "enum", multiple: true]
        ]
        def doActPageSchema = [
            [name: "actType.1", type: "enum"],
            [name: "actSubType.1", type: "enum"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", selectActionsSchema) }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> ruleConfigJson(100, "r", doActPageSchema) }
        // statusJson must reflect the trashActs write taking effect: BEFORE
        // the trashActs POST goes out, the rule has actType.1; AFTER, no
        // actType.<N> entries (cleared). Toggle is hubInternalPostForm-side
        // since the ordering of statusJson fetches is hard to count: backup
        // snapshot fetches it pre-write, then _rmClearActions fetches it
        // pre+post-trashActs, then _rmAddAction fetches it for index discovery.
        def cleared = false
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, cleared ? [] : [[name: "actType.1", value: "switchActs"]])
        }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        hubGet.register('/device/fullJson/99999') { params -> "" }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // The trashActs write is what semantically clears the actions.
            // Flip the cleared flag once it lands so the subsequent
            // post-condition statusJson sees an empty action list.
            if (path == "/installedapp/update/json" && body?.containsKey("settings[trashActs]")) {
                cleared = true
            }
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            replaceActions: [
                [capability: "switch", action: "off", deviceIds: [8]],
                [capability: "switch", action: "off", deviceIds: [99999]]
            ],
            confirm: true
        ])

        then: "the result carries removedIndices (the cleared indices) AND addedActions (per-spec result) for every replaceActions[i]"
        result.removedIndices != null
        result.removedIndices?.size() == 1
        result.addedActions?.size() == 2

        and: "the bogus-id sub-spec fails inline rather than aborting the batch"
        def failedItem = result.addedActions.find { it.success == false && it.error?.toString()?.contains("99999") }
        failedItem != null
    }

    def "moveAction rejects unknown direction at the dispatcher"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, [[name: "actType.1", value: "switchActs"]]) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, moveAction: [index: 1, direction: "sideways"], confirm: true])

        then:
        result.success == false
        result.error?.contains("up") || result.error?.toLowerCase()?.contains("direction")
    }

    // ---------- Finding #11: state.editAct pre-flight detection ----------

    def "removeAction pre-flight detects stuck state.editAct and throws immediately"() {
        // Finding #11: when state.editAct is set, RM silently no-ops delAct
        // clicks. Without pre-flight detection the caller would burn 10s of
        // retries before hitting a confusing generic timeout message.
        // With pre-flight detection, _rmDeleteAction should throw
        // IllegalStateException immediately with a descriptive message
        // naming the stuck index and listing recovery options.
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // appState carries editAct=3 (action 3 is in-flight from a prior
        // interrupted edit). appSettings carries actType.1 so beforeIndices
        // contains index 1 (the one we want to delete) -- pre-flight fires
        // AFTER the index-existence check, so the rule must have the target.
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100],
                appSettings: [[name: "actType.1", value: "switchActs"]],
                eventSubscriptions: [[name: "evt1"]],
                scheduledJobs: [],
                appState: [[name: "editAct", value: 3]],
                childAppCount: 0, childDeviceCount: 0
            ])
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 1], confirm: true])

        then: "pre-flight detects stuck editAct and reports it as an error"
        result.success == false
        result.error?.contains("state.editAct=3")
        result.error?.contains("Recovery options")

        and: "delAct button click never fires -- no hub mutation"
        !posts.any { it.body?.get("stateAttribute") == "delAct" }
    }

    def "moveAction pre-flight detects stuck state.editAct and throws immediately"() {
        // Finding #11 (moveAction site): same pre-flight guard as removeAction.
        // state.editAct set => moveAction should throw immediately with a
        // descriptive message rather than proceeding to the click and
        // silently producing a position-unchanged result.
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // Two actions present so moveAction(1, "down") is not a boundary move.
        // editAct=2 is stuck from a prior interrupted edit.
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100],
                appSettings: [[name: "actType.1", value: "switchActs"], [name: "actType.2", value: "delayActs"]],
                eventSubscriptions: [[name: "evt1"]],
                scheduledJobs: [],
                appState: [[name: "editAct", value: 2]],
                childAppCount: 0, childDeviceCount: 0
            ])
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, moveAction: [index: 1, direction: "down"], confirm: true])

        then: "pre-flight detects stuck editAct and reports it as an error"
        result.success == false
        result.error?.contains("state.editAct=2")
        result.error?.contains("Recovery options")

        and: "no move-arrow button click fires -- no hub mutation"
        !posts.any { it.body?.get("stateAttribute") == "arrowDn" }
    }

    def "removeAction proceeds normally when state.editAct is not set"() {
        // Finding #11 no-false-positive: when appState has no editAct entry,
        // pre-flight must pass through silently and the normal delete flow runs.
        // This guards against regressions where pre-flight incorrectly blocks
        // a clean delete. Uses a single-retry success scenario (delAct fires,
        // first post-click fetch sees action gone).
        given:
        enableHubAdminWrite()
        def delActFired = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "delAct") delActFired = true
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // appState is empty (no editAct entry) -- pre-flight returns null, passes through.
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (delActFired) {
                // post-click: action 1 gone (deletion propagated immediately)
                statusJson(100, [[name: "actType.2", value: "switchActs"]])
            } else {
                // pre-click: both actions present, appState empty (no stuck state)
                statusJson(100, [[name: "actType.1", value: "delayActs"], [name: "actType.2", value: "switchActs"]])
            }
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 1], confirm: true])

        then: "no stuck state -- delete proceeds and succeeds"
        result.success == true
        delActFired == true
        result.note?.contains("Removed action 1") || result.removedIndices?.contains(1)
    }

    def "walkStep introspect returns schema for a page without mutating"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            ruleConfigJson(100, "r", [
                [name: "tCapab1", type: "enum", options: ["Switch", "Motion"]],
                [name: "moreCond", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            walkStep: [page: "selectTriggers", operation: "introspect"],
            confirm: true
        ])

        then: "introspect is a pure read — no /installedapp/update/json or /installedapp/btn POST fires"
        result.success == true
        !posts.any { it.path == "/installedapp/update/json" }
        // The dispatcher's auto-mainPage Done click after walkStep is allowed; the
        // assertion is specifically that introspect itself doesn't write or click.

        and: "the response carries the page name + before/after schemas with the page's inputs"
        result.page == "selectTriggers"
        result.before?.inputs != null
        result.before.inputs.find { it.name == "tCapab1" } != null
        // introspect leaves before == after (no mutation)
        result.after?.inputs == result.before.inputs
    }

    def "addTriggers bulk shortcut returns partial: true when one inner spec fails"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            ruleConfigJson(100, "r", [
                [name: "tCapab1", type: "enum", options: ["Switch"]],
                [name: "tDev1", type: "capability.switch", multiple: true],
                [name: "tstate1", type: "enum", options: ["on", "off"]],
                [name: "isCondTrig.1", type: "bool"],
                [name: "hasAll", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        hubGet.register('/device/fullJson/99999') { params -> "" }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when: "bulk addTriggers with one valid + one bogus-id spec"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTriggers: [
                [capability: "Switch", deviceIds: [8], state: "on"],
                [capability: "Switch", deviceIds: [99999], state: "on"]
            ],
            confirm: true
        ])

        then: "the result aggregates per-spec outcomes — bulk shortcut returns triggers[] with per-item results"
        def triggers = (result.triggers ?: result.triggerResults ?: []) as List
        triggers.size() == 2
        // The bogus-id sub-spec is rejected by _rmValidateDeviceIdsExist with
        // a clear "device ID '99999' does not exist" error.
        def bogusEntry = triggers.find { it?.error?.toString()?.contains("99999") }
        bogusEntry != null
        bogusEntry.success == false

        and: "the user-visible success rolls down to false because one inner item failed"
        result.success == false || result.partial == true
    }

    def "check_rule_health surfaces ok=true with no broken markers on a clean rule"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Healthy Rule", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == true
        result.label == "Healthy Rule"
        result.brokenMarkers == [] || result.brokenMarkers?.isEmpty()
        result.multipleFlagPoison == [] || result.multipleFlagPoison?.isEmpty()
    }

    def "check_rule_health flags BROKEN marker in label as ok=false"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Some Rule *BROKEN*", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == false
        result.issues != null
        result.issues.any { it.toString().toLowerCase().contains("broken") }
    }

    // ---------- post-write verification heuristic itself ----------
    // The silent-rejection / verification-fetch-failed paths are load-bearing
    // for the entire write-bookkeeping subsystem. Without these tests, a
    // regression that flips the heuristic logic would still pass every
    // higher-level shortcut test (mocks return success either way).

    def "_rmWriteSettingOnPage routes a no-shift write to skipped with reason=silent_rejection"() {
        given:
        enableHubAdminWrite()
        // selectTriggers schema is identical before and after the write —
        // RM 5.1 returns 200 but the field never landed. The verification
        // logic should detect: schema unchanged + value not landed +
        // sections render hash unchanged → silent_rejection.
        def stableInputs = [
            [name: "tCapab1", type: "enum", options: ["Switch"]],
            [name: "doneST", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params -> ruleConfigJson(100, "r", stableInputs) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // 200 OK but pretend RM ignored the write — the next fetch
            // returns identical schema, identical sections, identical value.
            [status: 200, location: null, data: '']
        }

        when: "we write a setting via the raw `settings` mode targeting selectTriggers"
        // Raw settings mode hits _rmUpdateAppSettings on mainPage, which
        // doesn't exercise _rmWriteSettingOnPage's renderHash path. To
        // exercise the helper directly, drive it via walkStep operation=write
        // on a sub-page.
        def result = script.toolUpdateNativeApp([
            appId: 100,
            walkStep: [page: "selectTriggers", operation: "write", write: [tCapab1: "Switch"]],
            confirm: true
        ])

        then: "the heuristic detects no shift and the field does NOT route to applied"
        result != null
        // walkStep doesn't expose applied/skipped at the top level the same
        // way addTrigger does, but it surfaces silentRejection for write ops.
        result.silentRejection == true || result.opResult?.silentRejection == true
    }

    // NOTE: the renderShift heuristic in _rmWriteSubPageField is exercised
    // end-to-end by the existing test "addRequiredExpression sub-expression
    // open writes cond=b VALUE not literal label" — STPage cond=b is the
    // canonical wizard-consumed picker case (schema unchanged, paragraph
    // shifts to show "("). A regression that breaks renderShift detection
    // would surface there as the cond=b POST still going out (test passes)
    // but the live BAT T404 scenario rendering as "Broken Condition" again.
    // Direct unit-testing the heuristic through the public API requires
    // progressively-stubbed schema across many fetches that diverge from
    // the actual hub behavior, which would test the stub more than the
    // helper. Keeping coverage at the BAT level for this signal.


    def "addTrigger surfaces verificationFetchFailed=true when post-commit mainPage fetch errors"() {
        given:
        enableHubAdminWrite()
        // The verificationFetchFailed flag is set inside _rmAddTrigger when
        // the post-commit "did the trigger bake?" mainPage fetch throws.
        // To exercise it: stub selectTriggers normally so the writes go
        // through (paragraphs evolve to keep renderShift detection happy),
        // and ONLY make the post-hasAll mainPage fetch throw.
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            // Each fetch returns DIFFERENT paragraphs so renderHash detects
            // every write — keeps the trigger flow moving past silent_rejection.
            fetchSeq++
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "tCapab1", type: "enum", options: ["Switch"]],
                                 [name: "tDev1", type: "capability.switch", multiple: true],
                                 [name: "tstate1", type: "enum", options: ["on", "off"]],
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        // ONLY the mainPage fetch throws — that's the post-commit "trigger
        // baked?" verification path inside _rmAddTrigger that sets
        // verificationFetchFailed=true.
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            throw new RuntimeException("simulated post-commit mainPage fetch failure")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "the verificationFetchFailed flag is surfaced (caller knows the bake-check was bypassed)"
        // Single addTrigger path returns the flag directly on result; bulk
        // path nests under triggers[]. Either shape is valid.
        result?.verificationFetchFailed == true ||
            result?.triggers?.any { it?.verificationFetchFailed == true }
    }

    // ---------- additional coverage from round-2 review ----------

    def "_rmValidateDeviceIdsExist applies to addAction.deviceIds (not just addTrigger)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        // Bogus device — /device/fullJson/77777 returns 404-equivalent (empty body)
        hubGet.register('/device/fullJson/77777') { params -> "" }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "switch", action: "off", deviceIds: [77777]],
            confirm: true
        ])

        then: "addAction's deviceIds validation rejects the bogus ID before any wizard-edit POST"
        result.success == false
        result.error?.contains("77777")
        result.error?.contains("does not exist")

        and: "no /installedapp/update/json POST went out — validation is pre-write"
        !posts.any { it.path == "/installedapp/update/json" }
    }

    def "_rmValidateDeviceIdsExist applies to addRequiredExpression.conditions[].deviceIds"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", [[name: "useST", type: "bool"]]) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/66666') { params -> "" }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Switch", deviceIds: [66666], state: "on"]]],
            confirm: true
        ])

        then: "addRequiredExpression rejects the bogus ID before walking the STPage wizard"
        result.success == false
        result.error?.contains("66666")
        result.error?.contains("does not exist")

        and: "no STPage write POST went out"
        !posts.any { it.path == "/installedapp/update/json" && it.body?.currentPage == "STPage" }
    }

    def "custom_* dispatch — every renamed tool name is routable (no Unknown tool error)"() {
        given:
        settingsMap.enableCustomRuleEngine = true

        // All 10 custom_* renames split across dispatch paths:
        //   - executeTool top-level switch: every custom_* has a case there
        //   - manage_rules_admin gateway:    delete/test/export/import/clone
        //   - manage_diagnostics gateway:    custom_get_rule_diagnostics
        // The point of this test is the dispatch wiring: a regression that
        // typos a case label or drops a tool from a gateway tools[] list
        // would surface as "Unknown tool" — that's the failure we're guarding.
        // We don't exercise the full underlying tool* impls; their behaviour
        // is covered elsewhere and would require setting up child apps,
        // requireHubAdminWrite, and the rule-engine state machine.

        when: "every renamed tool name is dispatched"
        // Top-level executeTool has cases for all 10 renames including
        // custom_get_rule_diagnostics (which is also a manage_diagnostics
        // sub-tool but routes through the same top-level switch).
        def topLevel = ["custom_list_rules", "custom_get_rule", "custom_create_rule",
                        "custom_update_rule", "custom_delete_rule", "custom_test_rule",
                        "custom_get_rule_diagnostics",
                        "custom_export_rule", "custom_import_rule", "custom_clone_rule"]
        def topLevelErrors = topLevel.collect { name ->
            try { script.executeTool(name, [:]); return null }
            catch (IllegalArgumentException e) { return e.message }
            catch (Exception ignored) { return null }
        }

        // manage_rules_admin gateway lists 5 tools — verify gateway routes them.
        def rulesAdminTools = ["custom_delete_rule", "custom_test_rule",
                               "custom_export_rule", "custom_import_rule", "custom_clone_rule"]
        def rulesAdminErrors = rulesAdminTools.collect { name ->
            try { script.handleGateway("manage_rules_admin", name, [:]); return null }
            catch (IllegalArgumentException e) { return e.message }
            catch (Exception ignored) { return null }
        }

        // manage_diagnostics gateway exposes custom_get_rule_diagnostics.
        def diagErrors
        try { script.handleGateway("manage_diagnostics", "custom_get_rule_diagnostics", [:]); diagErrors = null }
        catch (IllegalArgumentException e) { diagErrors = e.message }
        catch (Exception ignored) { diagErrors = null }

        then: "no top-level dispatch returns 'Unknown tool: custom_*' for any of the 10 renames"
        topLevelErrors.every { msg -> msg == null || !msg.contains("Unknown tool") }

        and: "no manage_rules_admin gateway dispatch returns 'Unknown tool ... in manage_rules_admin'"
        rulesAdminErrors.every { msg -> msg == null || !(msg.contains("Unknown tool") && msg.contains("manage_rules_admin")) }

        and: "manage_diagnostics gateway routes custom_get_rule_diagnostics — no 'Unknown tool ... in manage_diagnostics'"
        diagErrors == null || !(diagErrors.contains("Unknown tool") && diagErrors.contains("manage_diagnostics"))
    }

    // ---------- addTrigger/addAction discover mode ----------

    def "addTrigger discover=true returns schema with discriminator and capabilities list"() {
        given: "only Built-in App tools enabled -- Hub Admin Write NOT required"
        enableReadOnly()

        when:
        def result = script.toolUpdateNativeApp([addTrigger: [discover: true]])

        then: "discriminator field is 'capability'"
        result.discriminator == "capability"

        and: "capabilities is a non-empty List"
        result.capabilities instanceof List
        !result.capabilities.isEmpty()

        and: "each capability entry has a name field"
        result.capabilities.every { it.name != null }

        and: "known capabilities are present"
        result.capabilities.any { it.name == "Switch" }
        result.capabilities.any { it.name == "Motion" }
        result.capabilities.any { it.name == "Periodic Schedule" }
        result.capabilities.any { it.name == "Mode" }
    }

    def "addAction discover=true returns schema with discriminator and capabilities list"() {
        given: "only Built-in App tools enabled -- Hub Admin Write NOT required"
        enableReadOnly()

        when:
        def result = script.toolUpdateNativeApp([addAction: [discover: true]])

        then: "discriminator field is 'capability'"
        result.discriminator == "capability"

        and: "capabilities is a non-empty List"
        result.capabilities instanceof List
        !result.capabilities.isEmpty()

        and: "each capability entry has a name field"
        result.capabilities.every { it.name != null }

        and: "known capabilities are present"
        result.capabilities.any { it.name == "switch" }
        result.capabilities.any { it.name == "dimmer" }
        result.capabilities.any { it.name == "ifThen" }
        result.capabilities.any { it.name == "log" }
    }

    def "addTrigger discover=true does not require Hub Admin Write"() {
        given: "enableHubAdminWrite is NOT set -- only Built-in App enabled"
        enableReadOnly()
        // Confirm that enableHubAdminWrite is absent/false
        settingsMap.remove("enableHubAdminWrite")

        when:
        def result = script.toolUpdateNativeApp([addTrigger: [discover: true]])

        then: "no exception -- discover bypasses the Hub Admin Write gate"
        notThrown(IllegalArgumentException)
        result.discriminator == "capability"
    }

    def "addAction discover=true does not require Hub Admin Write"() {
        given: "enableHubAdminWrite is NOT set -- only Built-in App enabled"
        enableReadOnly()
        settingsMap.remove("enableHubAdminWrite")

        when:
        def result = script.toolUpdateNativeApp([addAction: [discover: true]])

        then: "no exception -- discover bypasses the Hub Admin Write gate"
        notThrown(IllegalArgumentException)
        result.discriminator == "capability"
    }

    def "addTrigger discover=true does not call _rmBackupRuleSnapshot"() {
        given: "discover bypasses backup -- no enableBuiltinApp needed either"
        def backupCalls = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> backupCalls << fn }

        when:
        script.toolUpdateNativeApp([addTrigger: [discover: true]])

        then: "no file upload -- no backup taken"
        backupCalls.isEmpty()
    }

    def "addAction discover=true does not call _rmBackupRuleSnapshot"() {
        given: "discover bypasses backup -- no enableBuiltinApp needed either"
        def backupCalls = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> backupCalls << fn }

        when:
        script.toolUpdateNativeApp([addAction: [discover: true]])

        then: "no file upload -- no backup taken"
        backupCalls.isEmpty()
    }

    def "addTrigger discover=true works with enableBuiltinApp=false"() {
        given: "all feature gates disabled -- discover is gate-free"
        settingsMap.remove("enableBuiltinApp")
        settingsMap.remove("enableHubAdminWrite")
        settingsMap.remove("enableHubAdminRead")

        when:
        def result = script.toolUpdateNativeApp([addTrigger: [discover: true]])

        then: "returns schema regardless of feature-flag state"
        result.discriminator == "capability"
        result.capabilities instanceof List
    }

    // ---------- Finding #4: success/partial semantic matrix for _rmAddTrigger ----------

    /**
     * Helper: builds a selectTriggers schema JSON with an incrementing paragraph
     * so every _rmWriteSettingOnPage call sees a render-hash shift and routes the
     * key to `applied` rather than `skipped`.
     */
    private String selectTriggersSchemaJson(int ruleId, int seqNum) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                         sections: [[title: "", input: [
                             [name: "tCapab1", type: "enum", options: ["Switch"]],
                             [name: "tDev1", type: "capability.switch", multiple: true],
                             [name: "tstate1", type: "enum", options: ["on", "off"]],
                             [name: "isCondTrig.1", type: "bool"],
                             [name: "hasAll", type: "button"]
                         ], paragraphs: ["seq ${seqNum}".toString()]]]],
            settings: [:],
            childApps: []
        ])
    }

    /**
     * Helper: builds a mainPage JSON whose paragraph content controls whether
     * _rmAddTrigger detects the trigger as baked (no "Define Triggers" placeholder)
     * or not-baked (placeholder still present).
     */
    private String mainPageJson(int ruleId, String label = "r", boolean triggerBaked = true) {
        def paragraph = triggerBaked ? "Switch1 is on" : "Define Triggers"
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: label, trueLabel: label, installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                         sections: [[title: "", input: [], paragraphs: [paragraph]]]],
            settings: [:],
            childApps: []
        ])
    }

    def "addTrigger returns success=true partial=false on full success"() {
        // Finding #4 matrix case 1: all settings land, trigger bakes,
        // no broken label. The prior code returned success=false here
        // because health.ok was gated on !partial; after the fix,
        // success decouples from partial and reflects only "did API write happen."
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchSeq++
            selectTriggersSchemaJson(100, fetchSeq)
        }
        // mainPage says trigger IS baked (no "Define Triggers" placeholder).
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "full success -- API wrote at least one setting AND trigger row exists"
        result.success == true
        result.partial == false
    }

    def "addTrigger returns success=true partial=true when trigger does not bake (triggerNotBaked)"() {
        // Finding #4 matrix case 2 (variant): settings land (applied non-empty)
        // but mainPage still shows "Define Triggers" -- the trigger skeleton was
        // written but the row didn't commit. New contract: success=true (something
        // happened), partial=true (needs repair). Old contract was success=false.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchSeq++
            selectTriggersSchemaJson(100, fetchSeq)
        }
        // mainPage still shows the placeholder -- trigger did NOT bake.
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", false) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "partial success -- settings were written but trigger row needs repair"
        result.success == true
        result.partial == true
        result.repairHints?.any { it?.toString()?.contains("Define Triggers") || it?.toString()?.contains("trigger did not bake") || it?.toString()?.contains("mainPage still shows") }
    }

    def "addTrigger returns success=true partial=true when rule label has BROKEN marker"() {
        // Finding #4 matrix case 3: settings land but health check returns
        // hasBrokenLabel=true (label contains *BROKEN*). New contract:
        // success=true (something was written), partial=true (needs repair).
        // Old contract was success=false because health.ok was false.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            // Return main config with *BROKEN* in label for health check.
            ruleConfigJson(100, "Test Rule *BROKEN*", [])
        }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchSeq++
            selectTriggersSchemaJson(100, fetchSeq)
        }
        // mainPage shows trigger baked (but label still *BROKEN* from health check).
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "Test Rule *BROKEN*", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "partial=true captures the BROKEN label, success=true because something was written"
        result.partial == true
        result.success == true
        result.repairHints?.any { it?.toString()?.contains("BROKEN") || it?.toString()?.contains("broken") }
    }

    def "addTrigger returns success=true partial=true when rule has pre-existing brokenMarkers"() {
        // Finding #4 matrix (Fix 2): a PRIOR trigger on the rule is already broken
        // (health check returns brokenMarkers=["**Broken Trigger**"]). The new
        // trigger commits successfully (applied non-empty, new trigger bakes), but
        // the overall rule is in a known-bad state -- surface as partial=true so
        // the LLM sees it without a separate check_rule_health call.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        // /installedapp/configure/json/100 (no subpage) is the health-check fetch.
        // Return a mainPage whose rendered paragraphs include the broken-trigger marker.
        hubGet.register('/installedapp/configure/json/100') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "Clean Label", trueLabel: "Clean Label",
                      installed: true, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [],
                                         paragraphs: ["**Broken Trigger**"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchSeq++
            selectTriggersSchemaJson(100, fetchSeq)
        }
        // post-commit mainPage: new trigger baked (no "Define Triggers") but the
        // broken-marker paragraph is still present from the pre-existing bad trigger.
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "Clean Label", trueLabel: "Clean Label",
                      installed: true, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [],
                                         paragraphs: ["Switch2 is on", "**Broken Trigger**"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "new trigger committed (success=true) but pre-existing broken state drives partial=true"
        result.success == true
        result.partial == true
        result.health?.brokenMarkers?.contains("**Broken Trigger**")
    }

    def "addTrigger returns success=false when selectTriggers finalConfig has configPage error"() {
        // Finding #4 matrix case 4: hard failure -- API error present in the
        // selectTriggers configPage.error field. err != null -> success=false
        // regardless of how many settings may have landed in applied.
        // Uses a static schema (no render shift) so applied stays empty; the
        // test verifies that the err path alone drives success=false.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // selectTriggers always returns a configPage error on every fetch.
        // _rmAddTrigger will detect err != null in finalConfig and set success=false.
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false,
                             error: "tstate1 enum out of range -- state machine error",
                             sections: [[title: "", input: [
                                 [name: "tCapab1", type: "enum", options: ["Switch"]],
                                 [name: "tDev1", type: "capability.switch", multiple: true],
                                 [name: "tstate1", type: "enum", options: ["on", "off"]],
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "hard failure -- err != null gates success=false"
        result.success == false
        result.configPageError != null
    }

    def "addTrigger returns success=false when nothing was written (all settings silently rejected)"() {
        // Finding #4 matrix case 5: hard failure -- applied stays empty because
        // every _rmWriteSettingOnPage call produces no render-hash shift (the
        // hub ignores all writes). success=!err && !applied.isEmpty() => false.
        // This scenario uses a no-deviceIds capability (Mode) with a fully
        // static schema -- no schema shift, no value landing, no render shift
        // means every write routes to skipped. The flow still navigates normally,
        // but nothing accumulates in applied.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // STATIC schema -- zero paragraphs, zero render shift between fetches.
        // Every write fires a POST but the before/after schema is identical
        // so schemaShifted=false, valueLanded=false, renderShifted=false -> skipped.
        def staticInputs = [
            [name: "tCapab1", type: "enum", options: ["Mode"]],
            [name: "tstate1", type: "enum", options: ["Day", "Night"]],
            [name: "isCondTrig.1", type: "bool"],
            [name: "hasAll", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: staticInputs, paragraphs: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", false) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            // Mode trigger: no deviceIds, so no tDev schema-guard fires.
            addTrigger: [capability: "Mode", state: "Day"],
            confirm: true
        ])

        then: "hard failure -- nothing landed in applied, so success=false"
        result.success == false
        (result.settingsApplied == null || (result.settingsApplied as List).isEmpty())
    }

    // ---------- Finding #10: addTrigger auto-updateRule parity with addAction ----------

    def "addTrigger single-trigger path auto-fires updateRule after successful commit"() {
        // Finding #10: single addTrigger call should fire updateRule automatically
        // so subscriptions populate without a separate tool call. Mirrors the
        // addAction pattern where the wrapper fires updateRule after the commit.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchSeq++
            selectTriggersSchemaJson(100, fetchSeq)
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "updateRule fired automatically after successful commit"
        result.success == true
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "updateRule" }

        and: "response note mentions auto-fire (no manual updateRule instruction)"
        result.note?.toString()?.contains("updateRule fired")
    }

    def "addTrigger single-trigger path skips updateRule on hard failure (success=false)"() {
        // Finding #10: when _rmAddTrigger returns success=false (hard failure --
        // API error or nothing written), the wrapper should NOT fire updateRule.
        // Nothing committed, so the extra hub round-trip is wasteful.
        // Uses a static (non-shifting) schema so applied stays empty -> success=false.
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // STATIC schema -- no render shift, all writes go to skipped, applied stays empty.
        def staticInputs = [
            [name: "tCapab1", type: "enum", options: ["Mode"]],
            [name: "tstate1", type: "enum", options: ["Day", "Night"]],
            [name: "isCondTrig.1", type: "bool"],
            [name: "hasAll", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: staticInputs, paragraphs: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", false) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Mode", state: "Day"],
            confirm: true
        ])

        then: "hard failure -- nothing committed, success=false"
        result.success == false

        and: "updateRule NOT fired on failure path"
        !posts.any { it.path == "/installedapp/btn" && it.body?.name == "updateRule" }
    }

    def "addTrigger auto-fires updateRule even on partial success"() {
        // Finding #10: when success=true but partial=true (trigger row exists
        // but not all fields landed), the wrapper SHOULD still fire updateRule.
        // The trigger IS in the rule and subscriptions should bake from whatever
        // committed -- matching the addAction ergonomics. Uses triggerNotBaked
        // scenario (mainPage shows "Define Triggers") to produce partial=true.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchSeq++
            selectTriggersSchemaJson(100, fetchSeq)
        }
        // mainPage still shows "Define Triggers" placeholder -- trigger did not bake.
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", false) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "partial success -- trigger row exists (success=true) but needs repair (partial=true)"
        result.success == true
        result.partial == true

        and: "updateRule still fires even on partial -- subscriptions bake from whatever committed"
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "updateRule" }
    }

    def "addTriggers bulk path fires updateRule exactly once (not per-trigger)"() {
        // Finding #10: addTriggers[] (bulk path) must NOT fire updateRule per
        // item -- that would cause N reinits. The bulk wrapper fires one
        // updateRule after all triggers commit. The single-trigger auto-fire
        // must NOT activate on the bulk path (it lives only in the addTrigger
        // wrapper, which the bulk path bypasses). Two-trigger bulk: updateRule
        // count should be exactly 1.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def updateRuleCount = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "updateRule") updateRuleCount++
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchSeq++
            selectTriggersSchemaJson(100, fetchSeq)
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"S2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTriggers: [
                [capability: "Switch", deviceIds: [8], state: "on"],
                [capability: "Switch", deviceIds: [9], state: "off"]
            ],
            confirm: true
        ])

        then: "bulk path fires updateRule exactly once -- not once per trigger"
        updateRuleCount == 1

        and: "both triggers registered (non-empty results)"
        (result.triggers as List)?.size() == 2
    }

    def "addTrigger conditional=true auto-fires updateRule after successful commit"() {
        // Finding #10 coverage: conditional=true sets isCondTrig.<N>=true on the
        // trigger row and _rmBuildCondition drives the condition sub-wizard
        // internally, but _rmAddTrigger still commits and returns success=true
        // before handing back. The auto-updateRule guard (`trigResult?.success != false`)
        // does NOT inspect `conditional` -- so updateRule fires for conditional
        // triggers just as it does for plain ones. This test pins that behavior.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchSeq++
            selectTriggersSchemaJson(100, fetchSeq)
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on", conditional: true],
            confirm: true
        ])

        then: "conditional trigger commits successfully"
        result.success == true

        and: "updateRule fires even for conditional=true triggers"
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "updateRule" }
    }

    // ---------- Finding #4: success/partial semantic matrix for _rmAddAction ----------

    /**
     * Helper: builds a doActPage schema JSON with an incrementing paragraph
     * so every _rmWriteSettingOnPage call routes to `applied` rather than `skipped`.
     */
    private String doActPageSchemaJson(int ruleId, int seqNum) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "doActPage", title: "T", install: false, error: null,
                         sections: [[title: "", input: [
                             [name: "actType.1", type: "enum",
                              options: ["switchActs": "Turn on/off/toggle a switch or switches"]],
                             [name: "actSubType.1", type: "enum",
                              options: ["getOnOffSwitch": "Turn on, off, or toggle a switch"]],
                             [name: "onOffSwitch.1", type: "capability.switch", multiple: true],
                             [name: "onOff.1", type: "bool"],
                             [name: "actionDone", type: "button"]
                         ], paragraphs: ["seq ${seqNum}".toString()]]]],
            settings: [:],
            childApps: []
        ])
    }

    def "addAction returns success=true partial=false on full success"() {
        // Finding #4 matrix case 1 (action): all settings land, action bakes.
        // success=!err && !applied.isEmpty() => true; partial=false.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["switchActs": "Switches"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            doActPageSchemaJson(100, fetchSeq)
        }
        // mainPage says action IS baked (no "Define Actions" placeholder).
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Switch1 on"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "switch", action: "on", deviceIds: [8]],
            confirm: true
        ])

        then: "full success -- at least one setting written AND action row exists"
        result.success == true
        result.partial == false
    }

    def "addAction returns success=true partial=true when action does not bake (actionNotBaked)"() {
        // Finding #4 matrix case 2 (action): settings land (applied non-empty)
        // but mainPage still shows "Define Actions". New contract: success=true,
        // partial=true. Old contract was success=false.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["switchActs": "Switches"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            doActPageSchemaJson(100, fetchSeq)
        }
        // mainPage still shows placeholder -- action did NOT bake.
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Define Actions"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "switch", action: "on", deviceIds: [8]],
            confirm: true
        ])

        then: "partial success -- settings were written but action row needs repair"
        result.success == true
        result.partial == true
        result.repairHints?.any { it?.toString()?.contains("Define Actions") || it?.toString()?.contains("action did not bake") || it?.toString()?.contains("mainPage still shows") }
    }

    def "addAction returns success=false when selectActions finalConfig has configPage error"() {
        // Finding #4 matrix case 4 (action): hard failure -- API error present in
        // the selectActions configPage.error field. err != null -> success=false.
        // Uses a static doActPage schema so writes silently reject; the test
        // verifies that err alone drives success=false.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // selectActions always returns a configPage error on every fetch.
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectActions", title: "T", install: false,
                             error: "actType.1 invalid -- enum out of range",
                             sections: [[title: "", input: [
                                 [name: "actType.1", type: "enum",
                                  options: ["switchActs": "Switches"]]
                             ]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            doActPageSchemaJson(100, 1)
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Switch1 on"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "switch", action: "on", deviceIds: [8]],
            confirm: true
        ])

        then: "hard failure -- err != null gates success=false"
        result.success == false
        result.configPageError != null
    }

    def "addAction returns success=false when nothing was written (all settings silently rejected)"() {
        // Finding #4 matrix case 5 (action): hard failure -- applied stays empty
        // because every write produces no observable schema shift. The 'log'
        // capability has no field writes beyond actType/actSubType; using a
        // static doActPage schema with no render shift causes both to route to
        // skipped rather than applied.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // selectActions with the log capability in the actType options.
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["notificationActs": "Notifications and Logging"]]])
        }
        // STATIC doActPage schema -- no paragraphs, no render shift between fetches.
        // Both actType.1 and actSubType.1 writes silently reject -> applied stays [].
        def staticDoActInputs = [
            [name: "actType.1", type: "enum",
             options: ["notificationActs": "Notifications and Logging"]],
            [name: "actSubType.1", type: "enum",
             options: ["getLog": "Log a message"]],
            [name: "logType.1", type: "enum", options: ["info", "warn"]],
            [name: "logMessage.1", type: "text"],
            [name: "actionDone", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: staticDoActInputs, paragraphs: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Define Actions"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "log", message: "hello"],
            confirm: true
        ])

        then: "hard failure -- nothing landed in applied, so success=false"
        result.success == false
        (result.settingsApplied == null || (result.settingsApplied as List).isEmpty())
    }

    // ---------- ifThen condition wizard (Custom Attribute comparator fix) ----------

    /**
     * doActPage schema for the ifThen condition wizard. Contains all condition
     * fields with incrementing paragraphs so every _rmWriteSettingOnPage call
     * observes a renderShifted=true and routes to applied (not skipped).
     * Includes rCapab_1 so the condition-index-discovery fetch succeeds.
     * State_1 has no options so the enum-validation branch is skipped.
     */
    private String doActPageCondSchemaJson(int ruleId, int seqNum) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "doActPage", title: "T", install: false, error: null,
                         sections: [[title: "", input: [
                             [name: "actType.1", type: "enum",
                              options: ["condActs": "Conditional Actions"]],
                             [name: "actSubType.1", type: "enum",
                              options: ["getIfThen": "IF Expression THEN"]],
                             [name: "cond", type: "enum",
                              options: ["a": "New condition", "b": "Sub-expression"]],
                             [name: "rCapab_1", type: "enum",
                              options: ["Custom Attribute", "Switch", "Motion"]],
                             [name: "rDev_1", type: "capability.sensor", multiple: true],
                             [name: "rCustomAttr_1", type: "enum",
                              options: ["wickFilterLife", "battery", "rssi"]],
                             [name: "RelrDev_1", type: "enum",
                              options: ["<", "<=", ">", ">=", "=", "!="]],
                             [name: "state_1", type: "number"],
                             [name: "hasAll", type: "button"]
                         ], paragraphs: ["seq ${seqNum}".toString()]]]],
            settings: [:],
            childApps: []
        ])
    }

    def "addAction ifThen condition: Custom Attribute comparator writes RelrDev_N (not compareCond_N)"() {
        // Finding #12: on doActPage the comparator field is RelrDev_<N> ("Relr",
        // condition-wizard naming), NOT compareCond_<N> (selectTriggers-only) and
        // NOT ReltDev_<N> (trigger-row numeric comparator on selectTriggers).
        // Before the fix, compareCond_1 silently rejected and the condition rendered
        // as "wickFilterLife null" (broken). After the fix, RelrDev_1 lands in
        // settingsApplied and compareCond_1 is absent.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["condActs": "Conditional Actions"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            doActPageCondSchemaJson(100, fetchSeq)
        }
        // mainPage says action IS baked (no "Define Actions" placeholder).
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [],
                                         paragraphs: ["IF wickFilterLife < 20 THEN"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Custom Attribute",
                    deviceIds: [8],
                    attribute: "wickFilterLife",
                    comparator: "<",
                    value: 20
                ]]]
            ],
            confirm: true
        ])

        then: "RelrDev_1 lands in applied -- comparator write succeeded"
        result.success == true
        (result.settingsApplied as List).contains("RelrDev_1")

        and: "compareCond_1 is NOT in applied -- old (broken) field name was not written"
        !(result.settingsApplied as List).contains("compareCond_1")
    }

    def "addAction ifThen condition: no comparator (enum state) does NOT write RelrDev_N"() {
        // Regression guard: when no comparator is supplied (e.g. Switch capability with
        // state='on'), neither RelrDev_N nor compareCond_N must be written. The write
        // only fires inside the `if (cond.comparator != null)` guard.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["condActs": "Conditional Actions"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            // Schema with Switch capability + state_1 options, but no
            // RelrDev or compareCond fields (they should not be written).
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1", type: "enum",
                                  options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum",
                                  options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond", type: "enum",
                                  options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum",
                                  options: ["Switch", "Motion"]],
                                 [name: "rDev_1", type: "capability.switch", multiple: true],
                                 [name: "state_1", type: "enum", options: ["on", "off"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [],
                                         paragraphs: ["IF Switch1 is on THEN"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Switch",
                    deviceIds: [8],
                    state: "on"
                ]]]
            ],
            confirm: true
        ])

        then: "no RelrDev_N, ReltDev_N, or compareCond_N in any POST -- comparator guard is respected"
        result.success == true
        !posts.any { p -> (p.body as Map).any { k, v -> k?.toString()?.contains("RelrDev") } }
        !posts.any { p -> (p.body as Map).any { k, v -> k?.toString()?.contains("ReltDev") } }
        !posts.any { p -> (p.body as Map).any { k, v -> k?.toString()?.contains("compareCond") } }
    }

    // ---------- addRequiredExpression STPage value/state unification ----------

    /**
     * Full STPage schema for addRequiredExpression condition walks. Includes
     * rCapab_1 so the condition-index-discovery fetch succeeds. Contains
     * state_1 (NOT value_1) to match live STPage schema. Incrementing
     * paragraphs ensure renderShifted=true on every _rmWriteSubPageField
     * pre/post fetch pair, routing each write to applied rather than skipped.
     */
    private String stPageCondSchemaJson(int ruleId, int seqNum) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                         sections: [[title: "", input: [
                             [name: "cond", type: "enum",
                              options: ["a": "New condition", "b": "( sub-expression"]],
                             [name: "rCapab_1", type: "enum",
                              options: ["Custom Attribute", "Switch", "Contact"]],
                             [name: "rDev_1", type: "capability.sensor", multiple: true],
                             [name: "rCustomAttr_1", type: "enum",
                              options: ["wickFilterLife", "battery", "rssi"]],
                             [name: "RelrDev_1", type: "enum",
                              options: ["<", "<=", ">", ">=", "=", "!="]],
                             [name: "state_1", type: "number"],
                             [name: "hasAll", type: "button"],
                             [name: "doneST", type: "button"]
                         ], paragraphs: ["seq ${seqNum}".toString()]]]],
            settings: [:],
            childApps: []
        ])
    }

    def "addRequiredExpression Custom Attribute condition: value routes to state_N (not value_N)"() {
        // Bug (STPage path): the old code wrote value_${cIdx} for cond.value
        // but STPage only exposes state_${cIdx}. After the fix, cond.value
        // falls through to condStateOrValue and writes state_${cIdx}.
        // Asserts: 'state_1' in settingsApplied, 'value_1' NOT in settingsApplied
        // or any POST body. Also verifies RelrDev_1 lands (comparator fix parity).
        //
        // NOTE: write ORDER (RelrDev_ before state_) is not asserted at the
        // unit level because the schema stub doesn't model progressive disclosure
        // (state_1 is always present). Order correctness is verified live on
        // the hub (rule 1377, 2026-04-28): state_1 appears only after RelrDev_1
        // commits, so writing state_1 first silently rejects.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        // mainPage needs useST input so _rmWriteSettingOnPage(useST=true) finds it.
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            // Return body-format paragraph that does NOT say "Define Required Expression"
            // so the post-commit bake-check passes.
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph",
                                                 description: "IF wickFilterLife < 20 THEN"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            fetchSeq++
            stPageCondSchemaJson(100, fetchSeq)
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute",
                deviceIds: [8],
                attribute: "wickFilterLife",
                comparator: "<",
                value: 20
            ]]],
            confirm: true
        ])

        then: "state_1 lands in applied -- value correctly unified to state_N"
        result.success == true
        (result.settingsApplied as List).contains("state_1")

        and: "RelrDev_1 lands in applied -- comparator write succeeded on STPage too"
        (result.settingsApplied as List).contains("RelrDev_1")

        and: "value_1 was NOT written -- old broken field name absent from all POSTs"
        !posts.any { p -> (p.body as Map).any { k, v -> k?.toString()?.contains("value_1") } }
    }

    def "addRequiredExpression enum capability: cond.state still writes state_N (backward compat)"() {
        // Regression guard: the condStateOrValue unification must not break the
        // existing cond.state path. Caller passes state='open' (Contact capability)
        // with no comparator; state_1 should land in applied, RelrDev_1 should NOT
        // be written (comparator guard respected).
        // For enum capabilities, state_1 appears immediately after rDev_<N> so
        // write order is irrelevant to the Contact/Switch/Motion paths.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph",
                                                 description: "IF Contact1 is open"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            fetchSeq++
            // Contact capability: state_1 has enum options; no RelrDev_1 input.
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond", type: "enum",
                                  options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum",
                                  options: ["Contact", "Switch"]],
                                 [name: "rDev_1", type: "capability.contactSensor", multiple: true],
                                 [name: "state_1", type: "enum", options: ["open", "closed"]],
                                 [name: "hasAll", type: "button"],
                                 [name: "doneST", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Contact",
                deviceIds: [8],
                state: "open"
            ]]],
            confirm: true
        ])

        then: "state_1 lands in applied -- cond.state backward compat preserved"
        result.success == true
        (result.settingsApplied as List).contains("state_1")

        and: "RelrDev_1 was NOT written -- no comparator means no comparator write"
        !posts.any { p -> (p.body as Map).any { k, v -> k?.toString()?.contains("RelrDev") } }
    }

    // ---------- Custom Engine toggle visibility + source marker + read-only gate ----------

    def "custom_* visibility: full mode (engine ON) shows all 10 custom_* tools"() {
        // When enableCustomRuleEngine=true, all 10 custom_* tools must be present
        // in getToolDefinitions() regardless of enableBuiltinApp state.
        given:
        settingsMap.enableCustomRuleEngine = true
        settingsMap.enableBuiltinApp = false
        settingsMap.useGateways = false     // flat mode -- all tools visible by individual name

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: "all 10 custom_* tools are visible"
        ["custom_list_rules", "custom_get_rule", "custom_create_rule", "custom_update_rule",
         "custom_delete_rule", "custom_test_rule", "custom_get_rule_diagnostics",
         "custom_export_rule", "custom_import_rule", "custom_clone_rule"].every { names.contains(it) }
    }

    def "custom_* visibility: read-only mode (engine OFF + builtinApp ON) shows read subset only"() {
        // When enableCustomRuleEngine=false AND enableBuiltinApp=true, only the 5 read
        // tools should be visible; the 5 write/structural tools must be hidden.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableBuiltinApp = true
        settingsMap.useGateways = false

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: "read subset is visible"
        ["custom_list_rules", "custom_get_rule", "custom_update_rule",
         "custom_test_rule", "custom_get_rule_diagnostics"].every { names.contains(it) }

        and: "write/structural subset is hidden"
        ["custom_create_rule", "custom_delete_rule", "custom_export_rule",
         "custom_import_rule", "custom_clone_rule"].every { !names.contains(it) }
    }

    def "custom_* visibility: full-hide mode (engine OFF + builtinApp OFF) hides all 10 custom_* tools"() {
        // When both toggles are off, no custom_* tools should appear.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableBuiltinApp = false
        settingsMap.useGateways = false

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: "all 10 custom_* tools are hidden"
        ["custom_list_rules", "custom_get_rule", "custom_create_rule", "custom_update_rule",
         "custom_delete_rule", "custom_test_rule", "custom_get_rule_diagnostics",
         "custom_export_rule", "custom_import_rule", "custom_clone_rule"].every { !names.contains(it) }
    }

    def "custom_list_rules includes source: mcp_custom_engine on every rule"() {
        // The source marker lets LLMs distinguish MCP-managed rules from
        // native RM rules (list_rm_rules / manage_native_rules_and_apps).
        given:
        settingsMap.enableCustomRuleEngine = true
        def app1 = new support.TestChildApp(id: 11L, label: "Rule One")
        app1.ruleData = [id: "11", name: "Rule One", enabled: true, triggers: [], conditions: [], actions: []]
        def app2 = new support.TestChildApp(id: 22L, label: "Rule Two")
        app2.ruleData = [id: "22", name: "Rule Two", enabled: false, triggers: [], conditions: [], actions: []]
        childAppsList << app1 << app2

        when:
        def result = script.toolListRules()

        then: "source marker present on every rule entry"
        result.rules?.size() == 2
        result.rules.every { it.source == "mcp_custom_engine" }
    }

    def "custom_get_rule includes source: mcp_custom_engine in response"() {
        // get_rule must also carry the source marker so callers can identify
        // the rule type from a single-rule fetch.
        given:
        settingsMap.enableCustomRuleEngine = true
        def app = new support.TestChildApp(id: 42L, label: "My Rule")
        app.ruleData = [id: "42", name: "My Rule", enabled: true, triggers: [], conditions: [], actions: []]
        childAppsList << app

        when:
        def result = script.toolGetRule("42")

        then: "source marker injected into the response"
        result.source == "mcp_custom_engine"
        result.name == "My Rule"
    }

    def "custom_update_rule read-only mode: enabled field allowed"() {
        // In read-only mode, updating only the 'enabled' field must succeed.
        // The gate allows 'enabled' (and nothing else structural).
        given:
        def app = new support.TestChildApp(id: 55L, label: "Toggle Me")
        app.ruleData = [id: "55", name: "Toggle Me", enabled: true, triggers: [], conditions: [], actions: []]
        childAppsList << app

        when:
        def result = script.toolUpdateRule("55", [enabled: false], "readonly")

        then: "update succeeds with only the enabled field"
        result.success == true
        result.ruleId == "55"
    }

    def "custom_update_rule read-only mode: structural field rejected with legacy hint"() {
        // In read-only mode, passing triggers (or any non-enabled structural field)
        // must throw IllegalArgumentException that names the offending fields
        // and includes the legacy/redirect hint.
        given:
        def app = new support.TestChildApp(id: 55L, label: "Toggle Me")
        app.ruleData = [id: "55", name: "Toggle Me", enabled: true, triggers: [], conditions: [], actions: []]
        childAppsList << app

        when:
        script.toolUpdateRule("55", [triggers: [[type: "device_event"]]], "readonly")

        then: "structural change rejected in read-only mode"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("read-only mode")
        ex.message.contains("triggers")
        ex.message.contains("manage_native_rules_and_apps")
    }

    // ---------- runCommand parameter slot-allocation fix ----------

    def "addAction runCommand single parameter writes to cpType1/cpVal1 without moreParams click"() {
        // Bug fix: the first runCommand parameter must write directly to cpType1/cpVal1.
        // The old code always clicked moreParams first, landing the first param at
        // cpType2 and leaving cpType1 empty, which caused RM to render
        // "IF (**Broken Condition**) setMode(...)".
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def moreParamsClicks = 0
        def writtenFields = []  // field names from settings[*] keys in update POSTs

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") {
                moreParamsClicks++
            }
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields << m[0][1]
                }
            }
            [status: 200, location: null, data: '']
        }

        // Main page (backup snapshot pre-flight)
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // selectActions: expose modeActs option so runCommand's actType write lands
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // doActPage: always includes cpType1.1/cpVal1.1; seqNum changes per fetch
        // so _rmWriteSettingOnPage routes writes to 'applied' (paragraph shift signal).
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1", type: "enum",
                                  options: ["modeActs": "Run Custom Action"]],
                                 [name: "actSubType.1", type: "enum",
                                  options: ["getDefinedAction": "Run Custom Action"]],
                                 [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                                 [name: "devices.1", type: "capability.switch", multiple: true],
                                 [name: "cCmd.1", type: "text"],
                                 [name: "cpType1.1", type: "enum",
                                  options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                                 [name: "cpVal1.1", type: "text"],
                                 [name: "moreParams", type: "button"],
                                 [name: "actionDone", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["setMode on Device1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "setMode",
                deviceIds: [42],
                parameters: [[type: "STRING", value: "sleep"]]
            ],
            confirm: true
        ])

        then: "first parameter lands at cpType1.1 / cpVal1.1"
        result.success == true
        writtenFields.contains("cpType1.1")
        writtenFields.contains("cpVal1.1")
        !writtenFields.contains("cpType2.1")
        !writtenFields.contains("cpVal2.1")

        and: "no moreParams click was needed for the first parameter"
        moreParamsClicks == 0
    }

    def "addAction runCommand two parameters: first param to cpType1, second via moreParams to cpType2"() {
        // Second parameter requires moreParams to allocate its slot. After the
        // moreParams click, the schema should expose cpType2.1/cpVal2.1 as new fields.
        // Exactly ONE moreParams click should be recorded.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def moreParamsClicks = 0
        def writtenFields = []
        def moreParamsFired = false  // flips after the moreParams POST

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") {
                moreParamsClicks++
                moreParamsFired = true
            }
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields << m[0][1]
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // doActPage: before moreParams click shows cpType1.1/cpVal1.1 only;
        // after moreParams click also exposes cpType2.1/cpVal2.1.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "cpType1.1", type: "enum",
                 options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                [name: "cpVal1.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            if (moreParamsFired) {
                // After moreParams click: cpType2.1/cpVal2.1 are now present
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "cpVal2.1", type: "text"]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["setMode on Device1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "setMode",
                deviceIds: [42],
                parameters: [
                    [type: "STRING", value: "sleep"],
                    [type: "NUMBER", value: "5"]
                ]
            ],
            confirm: true
        ])

        then: "first param at slot 1, second param at slot 2"
        result.success == true
        writtenFields.contains("cpType1.1")
        writtenFields.contains("cpVal1.1")
        writtenFields.contains("cpType2.1")
        writtenFields.contains("cpVal2.1")

        and: "exactly one moreParams click (only for the second parameter)"
        moreParamsClicks == 1
    }

    def "addAction runCommand with no parameters writes no cpType fields"() {
        // A runCommand with an empty parameters list (or no parameters key)
        // should not write any cpType/cpVal fields and must not click moreParams.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def moreParamsClicks = 0
        def writtenFields = []

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") moreParamsClicks++
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields << m[0][1]
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1", type: "enum",
                                  options: ["modeActs": "Run Custom Action"]],
                                 [name: "actSubType.1", type: "enum",
                                  options: ["getDefinedAction": "Run Custom Action"]],
                                 [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                                 [name: "devices.1", type: "capability.switch", multiple: true],
                                 [name: "cCmd.1", type: "text"],
                                 [name: "moreParams", type: "button"],
                                 [name: "actionDone", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["refresh on Device1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "refresh",
                deviceIds: [42]
                // no parameters key
            ],
            confirm: true
        ])

        then: "no cpType or cpVal fields were written"
        result.success == true
        writtenFields.every { !it.startsWith("cpType") && !it.startsWith("cpVal") }

        and: "no moreParams click fired"
        moreParamsClicks == 0
    }

    def "executeTool rejects write tool custom_create_rule when customEngineMode is readonly"() {
        // Engine OFF + builtinApp ON => readonly mode. Write tools (create/delete/
        // export/import/clone) must be blocked at the executeTool dispatch gate,
        // not just inside toolCreateRule. This ensures the gate fires even when
        // the tool is reached via the top-level switch directly.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableBuiltinApp = true

        when:
        script.executeTool("custom_create_rule", [name: "x",
            triggers: [[type: "time", time: "00:00"]],
            actions: [[type: "log", message: "y"]]])

        then: "write tool blocked with read-only mode message"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("read-only mode")
        ex.message.contains("manage_native_rules_and_apps")
    }

    def "search_tools filters write custom_* tools in readonly mode"() {
        // Engine OFF + builtinApp ON => readonly mode. search_tools must not
        // surface write/structural custom_* tools (create/delete/export/import/clone)
        // even though they live in the cached full corpus. Read-subset tools
        // (list_rules, get_rule, etc.) must still appear in results.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableBuiltinApp = true
        // stateMap is cleared by setup() so corpus rebuilds fresh this test

        when:
        def result = script.toolSearchTools([query: "custom create rule", maxResults: 10])
        def resultNames = result.results*.tool as Set

        then: "write tools are filtered out of search results"
        !resultNames.contains("custom_create_rule")
        !resultNames.contains("custom_delete_rule")
        !resultNames.contains("custom_export_rule")
        !resultNames.contains("custom_import_rule")
        !resultNames.contains("custom_clone_rule")

        and: "only tools from the visible count are searched"
        result.totalToolsSearched < script.buildToolSearchCorpus().size()
    }

    def "search_tools filters all custom_* tools in off mode"() {
        // Engine OFF + builtinApp OFF => off mode. search_tools must not surface
        // ANY custom_* tools -- the full set of 10 must be excluded from scoring.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableBuiltinApp = false

        when: "searching for a query that would normally surface custom_list_rules"
        def result = script.toolSearchTools([query: "custom list rules", maxResults: 10])
        def resultNames = result.results*.tool as Set

        then: "no custom_* tools appear in off-mode search results"
        resultNames.every { !it.startsWith("custom_") }

        and: "visible corpus is smaller than full corpus by at least 10 (the hidden custom_* set)"
        def fullCorpusSize = script.buildToolSearchCorpus().size()
        result.totalToolsSearched <= fullCorpusSize - 10
    }

    def "executeTool rejects custom_create_rule when customEngineMode is off"() {
        // Engine OFF + builtinApp OFF => off mode. All custom_* tools must be
        // blocked at the executeTool gate with a message that names both toggles
        // and points to the recommended alternative (Built-in App Tools).
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableBuiltinApp = false

        when:
        script.executeTool("custom_create_rule", [name: "x",
            triggers: [[type: "time", time: "00:00"]],
            actions: [[type: "log", message: "y"]]])

        then: "off-mode gate fires with both-toggles-off message"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Both")
        ex.message.contains("Custom Rule Engine")
        ex.message.contains("Built-in App Tools")
    }

    def "executeTool rejects custom_list_rules in off mode (read tools also blocked)"() {
        // In off mode even read-only custom_* tools are blocked -- the entire
        // engine is off. This is different from readonly mode where the read
        // subset is allowed.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableBuiltinApp = false

        when:
        script.executeTool("custom_list_rules", [:])

        then: "read tool also blocked in full-off mode"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Both")
        ex.message.contains("Custom Rule Engine")
    }

    // -----------------------------------------------------------------------
    // Bug A regression: addTrigger with atTime but no time field
    // -----------------------------------------------------------------------
    //
    // Before the fix, the entire time block was guarded on
    // `triggerSpec.time != null`, so a caller providing only atTime="17:00"
    // (the natural LLM shape) skipped BOTH the time${idx} write AND the
    // atTime${idx} write.  The trigger committed with only tCapab=Certain Time
    // and rendered as "**Broken Trigger**".
    //
    // Fix: when atTime is non-null and time is null, effectiveTime is inferred
    // as "A specific time".  Both time${idx} and atTime${idx} then write.

    def "addTrigger with atTime but no time infers time='A specific time' and writes both fields"() {
        // Verifies Bug A fix: the inference path writes settings[time1]="A specific time"
        // AND settings[atTime1]="17:00" when the spec has atTime but omits time.
        // The schema includes tCapab1 (with "Certain Time" option), time1, and atTime1
        // so _rmWriteSettingOnPage doesn't skip them as "not in schema".
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        // Schema includes tCapab1 + time1 + atTime1 so both writes land.
        // moreCond click and hasAll click both hit the same static schema.
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [])
        }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "tCapab1", type: "enum",
                                  options: ["Certain Time (and optional date)", "Switch"]],
                                 [name: "time1", type: "enum",
                                  options: ["A specific time", "Sunrise", "Sunset"]],
                                 [name: "atTime1", type: "time"],
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            mainPageJson(100, "r", true)
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "addTrigger is called with atTime but no explicit time field"
        script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Certain Time (and optional date)",
                atTime: "17:00"
                // NOTE: no 'time' field -- caller relies on auto-inference
            ],
            confirm: true
        ])

        then: "time1='A specific time' was written (inferred from atTime presence)"
        def time1Write = posts.find { it.path == "/installedapp/update/json" &&
                                      it.body?.any { k, v ->
                                          k?.toString()?.contains("settings[time1]") && v == "A specific time"
                                      } }
        time1Write != null

        and: "atTime1='17:00' was also written (the HH:mm form passes through normalize unchanged)"
        posts.any { it.path == "/installedapp/update/json" &&
                    it.body?.any { k, v ->
                        k?.toString()?.contains("settings[atTime1]") && v == "17:00"
                    } }
    }

    // -----------------------------------------------------------------------
    // Bug B regression: addRequiredExpression ghost ifThen leaves app at
    //                   selectActions, not mainPage
    // -----------------------------------------------------------------------
    //
    // After the ghost ifThen sequence (_rmAddRequiredExpression Step 4b):
    //   N click (selectActions) -> condActs/getIfThen writes -> actionCancel
    //   -> nav doActPage->selectActions
    //
    // Before the Bug B fix, the sequence ended at selectActions. When the
    // browser user subsequently navigated to "Manage Conditions" (STPage href
    // on mainPage), RM saw the selectActions routing context and showed
    // "Required Fields missing or not passing validation".
    //
    // Fix: after nav doActPage->selectActions, add nav selectActions->mainPage.
    // This mirrors addTrigger's pattern (selectTriggers->mainPage nav at end)
    // and leaves the app in mainPage context for the browser's next visit.

    def "addRequiredExpression Step 4b ends with selectActions->mainPage nav (Bug B fix)"() {
        // Verifies the Bug B fix: after the doActPage->selectActions nav, a
        // second selectActions->mainPage nav fires, leaving the app in mainPage
        // context so browser STPage visits don't see a conflicting editAct context.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        hubGet.register('/installedapp/configure/json/100') {
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') {
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph", description: "IF S1 is on"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') {
            fetchSeq++
            stPageCondSchemaJson(100, fetchSeq)
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') {
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') {
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')           { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Switch", deviceIds: [8], state: "on"]]],
            confirm: true
        ])

        then: "doActPage->selectActions nav fires (existing invariant d)"
        def doActToSelectActionsIdx = posts.findIndexOf { it.path == "/installedapp/update/json" &&
                                                           it.body?.currentPage == "doActPage" &&
                                                           it.body?.any { k, v ->
                                                               k?.toString()?.contains("_action_href_name|selectActions|")
                                                           } }
        doActToSelectActionsIdx != -1

        and: "selectActions->mainPage nav fires AFTER doActPage->selectActions nav (Bug B fix)"
        def selectActionsToMainPageIdx = posts.findIndexOf { it.path == "/installedapp/update/json" &&
                                                              it.body?.currentPage == "selectActions" &&
                                                              it.body?.any { k, v ->
                                                                  k?.toString()?.contains("_action_href_name|mainPage|")
                                                              } }
        selectActionsToMainPageIdx != -1
        selectActionsToMainPageIdx > doActToSelectActionsIdx
    }

    // ---------- Pattern 1: settingsLanded detection (wizard-consumed fields) ----------
    //
    // Covers _rmWriteSettingOnPage's 4th detection mechanism (PR #134
    // zero-context validation 2026-05-02).  Wizard-consumed fields (RelrDev_N,
    // useLastDev.N, time1, etc.) have their value persist in the app's settings
    // map but the field's schema input descriptor does NOT reflect the value
    // back (either schema stays identical before/after OR the field disappears
    // from schema).  The 3 existing detectors all inspect configPage; the 4th
    // (settingsLanded) checks afterCfg?.settings.
    //
    // _rmWriteSettingOnPage is called from addTrigger / addAction / addRequiredExpression
    // (NOT from walkStep -- walkStep write bypasses it and calls hubInternalPostForm
    // directly).  To test via a public tool API, these tests go through addTrigger
    // for the tstate<N> write (Switch capability).  The mock controls what the
    // post-write configPage fetch returns: same schema keys + same no-value for
    // tstate1, but settings["tstate1"]="on" present.  The observable is
    // result.settingsSkipped -- the field should NOT appear there when settingsLanded
    // fires.

    def "addTrigger does not report tstate as skipped when value lands in settings but not in configPage schema -- settingsLanded case"() {
        given:
        enableHubAdminWrite()
        // Switch trigger.  The selectTriggers mock returns the SAME schema on all
        // fetches (schemaShifted=false, valueLanded=false, renderShifted=false) but
        // settings["tstate1"]="on" is always present in the response.
        //
        // What the 3 old detectors see for the tstate1 write:
        //   schemaShifted: beforeKeys=afterKeys (same 4 inputs), beforeValueStr=null,
        //                  afterValueStr=null (enum has no defaultValue) -> false
        //   valueLanded:   afterValueNorm is null -> false
        //   renderShifted: same paragraphs -> false
        //   OLD result: tstate1 goes to skipped (silent_rejection) -> partial=true
        //
        // What settingsLanded sees:
        //   afterCfg.settings["tstate1"] = "on" == newValueStr "on" -> true
        //   NEW result: tstate1 goes to applied -> no spurious partial
        def schemaInputs = [
            [name: "tCapab1", type: "enum", options: ["Switch"]],
            [name: "tDev1", type: "capability.switch", multiple: true],
            [name: "tstate1", type: "enum", options: ["on", "off"]],
            // No defaultValue on tstate1 -- RM does not echo the value back via schema
            [name: "isCondTrig.1", type: "bool"],
            [name: "hasAll", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            // Identical schema on every fetch; settings always carry the written
            // values.  This produces schemaShifted=false for every field write
            // and valueLanded=false (no defaultValue in schema input), so the
            // settingsLanded path is the only path that can route tstate1 to
            // applied.
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: schemaInputs,
                                         paragraphs: ["Switch 1 is on"]]]],
                settings: ["tCapab1": "Switch", "tstate1": "on"],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            // Return a paragraph showing the trigger baked (not "Define Triggers").
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "T", install: true, error: null,
                             sections: [[title: "", input: [],
                                         paragraphs: ["Switch is on"]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when: "addTrigger with Switch capability and state=on"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "tstate1 NOT in settingsSkipped -- settingsLanded routed it to applied"
        // If tstate1 is in settingsSkipped, the old silent_rejection path fired.
        // After the fix, settingsLanded detects settings["tstate1"]="on" and routes
        // to applied, so the field is absent from settingsSkipped.
        def skipped = result?.settingsSkipped ?: []
        !skipped.any { it?.key == "tstate1" }
    }

    def "addTrigger does not report tDev as skipped when device IDs land in settings as a List -- settingsLanded list-normalization case"() {
        given:
        enableHubAdminWrite()
        // Verify the list-normalization branch of settingsLanded: when settings
        // returns a List (Hubitat stores multi-select as a JSON array) and the
        // written value was also a List, the sorted-comma-join normalization must
        // match.  This tests the `settingsValue instanceof List` branch at line ~13327.
        def schemaInputs = [
            [name: "tCapab1", type: "enum", options: ["Switch"]],
            [name: "tDev1", type: "capability.switch", multiple: true],
            // No tstate -- we're testing tDev1's list-normalization path
            [name: "isCondTrig.1", type: "bool"],
            [name: "hasAll", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: schemaInputs,
                                         paragraphs: ["Switch 1 or 2"]]]],
                // settings["tDev1"] is a List (JSON array) -- simulates Hubitat's
                // multi-select storage format for capability.* inputs.
                settings: ["tDev1": ["8", "9"]],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"S2"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when: "addTrigger with two device IDs"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8, 9]],
            confirm: true
        ])

        then: "tDev1 NOT in settingsSkipped -- List-typed settings value matched via normalization"
        def skipped = result?.settingsSkipped ?: []
        !skipped.any { it?.key == "tDev1" }
    }

    def "addTrigger still reports true silent rejection in settingsSkipped when value absent from both schema AND settings"() {
        given:
        enableHubAdminWrite()
        // Regression guard for settingsLanded: when the value does NOT land in
        // settings (genuine write failure), the field must stay in settingsSkipped.
        // The fix must NOT over-promote writes that genuinely failed.
        //
        // Scenario: tstate1 write returns 200 OK but hub ignores it -- after the
        // write, settings["tstate1"] is absent and schema hasn't changed.
        def schemaInputs = [
            [name: "tCapab1", type: "enum", options: ["Switch"]],
            [name: "tDev1", type: "capability.switch", multiple: true],
            [name: "tstate1", type: "enum", options: ["on", "off"]],
            [name: "isCondTrig.1", type: "bool"],
            [name: "hasAll", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            // settings is EMPTY -- tstate1 truly was not written.
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: schemaInputs,
                                         paragraphs: ["no state set"]]]],
                settings: [:],   // <-- empty: tstate1 genuinely did not persist
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when: "addTrigger with state=on -- hub silently ignores the write"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "tstate1 IS in settingsSkipped -- genuine silent_rejection, settingsLanded did NOT fire"
        def skipped = result?.settingsSkipped ?: []
        skipped.any { it?.key == "tstate1" }
    }

    // ---------- Pattern 2: addTrigger Mode capability writes modesX<N> ----------
    //
    // Covers the Mode-trigger field routing fix (PR #134 zero-context
    // validation 2026-05-02).  Before the fix, addTrigger{capability:"Mode",
    // state:"Night"} wrote tstate<N>="Night" -- silently ignored by RM, leaving
    // the trigger as Broken Trigger.  After the fix, the code resolves the mode
    // name to an ID via location.modes and writes modesX<N>=[id].

    def "addTrigger Mode + state name resolves to mode ID and writes modesX<N>"() {
        given:
        enableHubAdminWrite()
        // Location has three modes.  The test uses "Night" which resolves to id "3".
        sharedLocation.modes = [[id: "1", name: "Home"], [id: "2", name: "Away"], [id: "3", name: "Night"]]

        // selectTriggers mock: after moreCond click, tCapab1 appears; after
        // writing tCapab1=Mode, modesX1 appears (no tDev1 for hub-state caps).
        // A single progressively-changing schema is simulated via a fetch counter.
        def fetchCount = 0
        def modesXWritten = []
        def tstateWritten = []

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchCount++
            // Fetch sequence for a Mode trigger (no upper bound -- modesX1 must
            // remain available through all writes, not just the first two):
            //   1  version-prefetch inside _rmClickAppButton(moreCond)
            //   2  schema-discovery (line 9862, finds tCapab1)
            //   3  _rmWriteSettingOnPage(tCapab1) before-write
            //   4  _rmWriteSettingOnPage(tCapab1) after-write
            //   5  _rmWriteSettingOnPage(modesX1) before-write  <-- needs modesX1 present
            //   6  _rmWriteSettingOnPage(modesX1) after-write
            //   7+ isCondTrig finalize + post-hasAll reads
            // Upper-bounding at 4 caused fetch 5 to return inputs=[] and modesX1
            // to be routed as not_in_schema (no POST issued).  No upper bound here.
            def inputs = []
            if (fetchCount >= 2) {
                inputs = [[name: "tCapab1", type: "enum", options: ["Mode", "Switch", "Motion"]],
                          [name: "isCondTrig.1", type: "bool"],
                          [name: "hasAll", type: "button"]]
            }
            if (fetchCount >= 3) {
                // After tCapab1=Mode is committed: modesX1 appears alongside
                // tCapab1.  No tstate1 or tDev1 -- Mode is a hub-state cap,
                // not device-based.  No upper bound so modesX1 is visible on
                // both the before-write AND after-write fetches for its own
                // _rmWriteSettingOnPage call (fetches 5 and 6).
                inputs = [[name: "tCapab1", type: "enum", options: ["Mode", "Switch", "Motion"]],
                          [name: "modesX1", type: "enum", multiple: true,
                           options: ["1": "Home", "2": "Away", "3": "Night"]],
                          [name: "isCondTrig.1", type: "bool"],
                          [name: "hasAll", type: "button"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["fetch ${fetchCount}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        // mainPage for trigger-baked check -- return a paragraph showing the mode trigger.
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            // Capture field writes of interest.
            body?.each { k, v ->
                def ks = k?.toString()
                if (ks?.startsWith("settings[modesX")) modesXWritten << [key: ks, value: v]
                if (ks?.startsWith("settings[tstate")) tstateWritten << [key: ks, value: v]
            }
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Mode", state: "Night"],
            confirm: true
        ])

        then: "modesX1 was written with the resolved mode ID -- not tstate1"
        modesXWritten.any { it.value?.contains("3") }

        and: "tstate was never written for a Mode trigger"
        tstateWritten.isEmpty()

        and: "result carries triggerIndex=1 and capability=Mode"
        result?.triggerIndex == 1
        result?.capability == "Mode"

        and: "modesX1 not in settingsSkipped (mock miscalibration guard)"
        // If the fetchCount upper-bound bug recurs, modesX1 routes as not_in_schema
        // and lands in settingsSkipped with partial=true.  Asserting it is absent
        // here makes future mock miscalibrations surface loudly instead of silently
        // producing a partial result that passes the modesXWritten check (which only
        // looks at POST body captures, not at what _rmWriteSettingOnPage reported).
        !(result?.settingsSkipped?.any { it?.key == "modesX1" })
    }

    def "addTrigger Mode + modeIds list writes modesX<N> directly without name resolution"() {
        given:
        enableHubAdminWrite()
        // Pass IDs directly -- location.modes is NOT consulted (no name resolution).
        // Even if location.modes is empty, the write should succeed.
        sharedLocation.modes = []  // would cause NPE/error if consulted

        def modesXWritten = []
        def fetchCount = 0

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchCount++
            def inputs = []
            if (fetchCount >= 2) {
                inputs = [[name: "tCapab1", type: "enum", options: ["Mode", "Switch"]],
                          [name: "isCondTrig.1", type: "bool"],
                          [name: "hasAll", type: "button"]]
            }
            if (fetchCount >= 3) {
                inputs = [[name: "tCapab1", type: "enum", options: ["Mode", "Switch"]],
                          [name: "modesX1", type: "enum", multiple: true,
                           options: ["3": "Night", "5": "Day"]],
                          [name: "isCondTrig.1", type: "bool"],
                          [name: "hasAll", type: "button"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["fetch ${fetchCount}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            body?.each { k, v ->
                if (k?.toString()?.startsWith("settings[modesX")) modesXWritten << [key: k, value: v]
            }
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Mode", modeIds: [3, 5]],
            confirm: true
        ])

        then: "modesX1 was written with the supplied IDs -- no name resolution needed"
        modesXWritten.any { it.value?.contains("3") && it.value?.contains("5") }
    }

    def "addTrigger Mode with unknown state name throws with clear error listing valid modes"() {
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Home"], [id: "2", name: "Away"], [id: "3", name: "Night"]]

        // Minimal selectTriggers setup so the pre-moreCond fetch doesn't fail.
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "tCapab1", type: "enum", options: ["Mode", "Switch"]],
                                 [name: "hasAll", type: "button"]
                             ]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Mode", state: "NotAMode"],
            confirm: true
        ])

        then: "clear IllegalArgumentException listing the valid mode names"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("NotAMode")
        ex.message.contains("Home")
        ex.message.contains("Away")
        ex.message.contains("Night")
    }

    def "addTrigger Mode never writes tstate<N> regardless of the state value passed"() {
        given:
        enableHubAdminWrite()
        // Verify the exclusive-or invariant: Mode always takes the modesX path
        // and NEVER falls through to the tstate path.  Even a "valid-looking"
        // state string like "on" must not produce a tstate write.
        sharedLocation.modes = [[id: "1", name: "Home"], [id: "2", name: "Away"]]

        def tstateWrites = []
        def modesXWrites = []
        def fetchCount = 0

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchCount++
            def inputs = fetchCount >= 2
                ? [[name: "tCapab1", type: "enum", options: ["Mode", "Switch"]],
                   [name: "modesX1", type: "enum", multiple: true,
                    options: ["1": "Home", "2": "Away"]],
                   [name: "isCondTrig.1", type: "bool"],
                   [name: "hasAll", type: "button"]]
                : []
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["fetch ${fetchCount}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            body?.each { k, v ->
                def ks = k?.toString()
                if (ks?.startsWith("settings[tstate")) tstateWrites << ks
                if (ks?.startsWith("settings[modesX")) modesXWrites << ks
            }
            [status: 200, location: null, data: '']
        }

        when:
        script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Mode", state: "Home"],
            confirm: true
        ])

        then: "tstate was NEVER written for a Mode trigger"
        tstateWrites.isEmpty()

        and: "modesX WAS written"
        !modesXWrites.isEmpty()
    }

    // ---------- Pattern 1 (mirror): settingsLanded in _rmWriteSubPageField ----------
    //
    // The same wizard-consumed-field false-positive that was fixed in
    // _rmWriteSettingOnPage (above) also exists in _rmWriteSubPageField, which
    // is called by addRequiredExpression's writeST closure for every STPage
    // write.  Live hub validation (rule 1318, 2026-05-02) showed RelrDev_3 in
    // settingsSkipped as silent_rejection even though "!=" landed in stored
    // settings -- because _rmWriteSubPageField's persisted calculation only
    // checked schemaShifted/valueLanded/renderShifted (not settings).
    //
    // These three tests mirror Pattern 1's three scenarios for the STPage
    // code path.  The STPage mock uses a STATIC schema (same keys, same
    // paragraphs on every fetch) so schemaShifted=false, valueLanded=false,
    // and renderShifted=false for every write -- forcing all detection to
    // fall through to the settingsLanded path.

    def "_rmWriteSubPageField settingsLanded: RelrDev_1 routes to applied when value in settings but not echoed in schema (STPage wizard-consumed)"() {
        given:
        enableHubAdminWrite()
        // STPage mock: STATIC schema (same keys + same paragraph on every fetch)
        // so schemaShifted=false, valueLanded=false, renderShifted=false.
        // settings["RelrDev_1"]="!=" is present on all fetches, so settingsLanded
        // fires on the post-verify fetch for the RelrDev_1 write and routes
        // the field to applied rather than silent_rejection / settingsSkipped.
        //
        // Without the fix, persisted = schemaShifted || valueLanded || renderShifted
        // = false || false || false = false, and RelrDev_1 lands in settingsSkipped.
        // With the fix, settingsLanded = ("!=" == "!=") = true, and persisted=true.
        def stPageJson = JsonOutput.toJson([
            app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                         sections: [[title: "", input: [
                             [name: "cond", type: "enum",
                              options: ["a": "New condition", "b": "( sub-expression"]],
                             [name: "rCapab_1", type: "enum",
                              options: ["Custom Attribute", "Switch", "Motion"]],
                             [name: "rDev_1", type: "capability.sensor", multiple: true],
                             [name: "rCustomAttr_1", type: "enum",
                              options: ["wickFilterLife", "battery", "rssi"]],
                             // RelrDev_1 has no defaultValue -- RM never echoes the written
                             // comparator back via schema (progressive-disclosure side-effect).
                             [name: "RelrDev_1", type: "enum",
                              options: ["<", "<=", ">", ">=", "=", "!="]],
                             [name: "state_1", type: "number"],
                             [name: "hasAll", type: "button"],
                             [name: "doneST", type: "button"]
                         ], paragraphs: ["static paragraph"]]]],  // STATIC -- renderShifted=false always
            // settings echoes the written comparator value -- settingsLanded fires here.
            settings: ["RelrDev_1": "!="],
            childApps: []
        ])
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            // Bake-check: paragraph must NOT say "Define Required Expression".
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph",
                                                 description: "IF wickFilterLife != 20 THEN"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageJson }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when: "addRequiredExpression with Custom Attribute + comparator writes RelrDev_1 via writeST -> _rmWriteSubPageField"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute",
                deviceIds: [8],
                attribute: "wickFilterLife",
                comparator: "!=",
                value: 20
            ]]],
            confirm: true
        ])

        then: "RelrDev_1 is in settingsApplied -- settingsLanded routed it to applied"
        // Without the fix, _rmWriteSubPageField returns persisted=false for RelrDev_1
        // (schemaShifted=false, valueLanded=false, renderShifted=false with static schema),
        // and writeST routes it to settingsSkipped as silent_rejection.
        // With the fix, settingsLanded detects settings["RelrDev_1"]="!=" and persisted=true.
        (result?.settingsApplied as List).contains("RelrDev_1")

        and: "RelrDev_1 is NOT in settingsSkipped"
        def skipped = result?.settingsSkipped ?: []
        !skipped.any { it?.key == "RelrDev_1" }
    }

    def "_rmWriteSubPageField settingsLanded list-normalization: rDev_1 routes to applied when device IDs land in settings as a List"() {
        given:
        enableHubAdminWrite()
        // Verify the List-normalization branch of settingsLanded in _rmWriteSubPageField.
        // settings["rDev_1"] is a List (Hubitat stores multi-select as a JSON array).
        // The written value is also a List ([8]).  The sorted-comma-join normalization
        // must produce "8" == "8" for settingsLanded=true.
        def stPageJson = JsonOutput.toJson([
            app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                         sections: [[title: "", input: [
                             [name: "cond", type: "enum",
                              options: ["a": "New condition", "b": "( sub-expression"]],
                             [name: "rCapab_1", type: "enum",
                              options: ["Switch", "Motion"]],
                             [name: "rDev_1", type: "capability.switch", multiple: true],
                             // No RelrDev_1 -- Switch has no comparator
                             [name: "state_1", type: "enum", options: ["on", "off"]],
                             [name: "hasAll", type: "button"],
                             [name: "doneST", type: "button"]
                         ], paragraphs: ["static paragraph"]]]],
            // settings["rDev_1"] is a List -- Hubitat's JSON array storage format.
            settings: ["rDev_1": ["8"]],
            childApps: []
        ])
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph",
                                                 description: "IF Switch1 is on THEN"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageJson }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when: "addRequiredExpression with Switch condition writes rDev_1 as a List via writeST -> _rmWriteSubPageField"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Switch",
                deviceIds: [8],
                state: "on"
            ]]],
            confirm: true
        ])

        then: "rDev_1 is in settingsApplied -- List-typed settings value matched via normalization"
        (result?.settingsApplied as List).contains("rDev_1")

        and: "rDev_1 is NOT in settingsSkipped"
        def skipped = result?.settingsSkipped ?: []
        !skipped.any { it?.key == "rDev_1" }
    }

    def "_rmWriteSubPageField settingsLanded regression: RelrDev_1 stays in settingsSkipped when value absent from both schema AND settings"() {
        given:
        enableHubAdminWrite()
        // Regression guard: when the value does NOT land in settings (genuine write
        // failure), _rmWriteSubPageField must still return persisted=false, and writeST
        // must route the field to settingsSkipped.  The fix must NOT over-promote
        // genuine silent rejections.
        //
        // Scenario: RelrDev_1 write returns 200 OK but hub ignores it -- after the
        // write, settings["RelrDev_1"] is absent and schema hasn't changed.
        def stPageJson = JsonOutput.toJson([
            app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                         sections: [[title: "", input: [
                             [name: "cond", type: "enum",
                              options: ["a": "New condition", "b": "( sub-expression"]],
                             [name: "rCapab_1", type: "enum",
                              options: ["Custom Attribute", "Switch", "Motion"]],
                             [name: "rDev_1", type: "capability.sensor", multiple: true],
                             [name: "rCustomAttr_1", type: "enum",
                              options: ["wickFilterLife", "battery", "rssi"]],
                             [name: "RelrDev_1", type: "enum",
                              options: ["<", "<=", ">", ">=", "=", "!="]],
                             [name: "state_1", type: "number"],
                             [name: "hasAll", type: "button"],
                             [name: "doneST", type: "button"]
                         ], paragraphs: ["static paragraph"]]]],
            settings: [:],   // EMPTY -- RelrDev_1 truly did not persist
            childApps: []
        ])
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph",
                                                 description: "IF wickFilterLife != 20 THEN"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageJson }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when: "addRequiredExpression with comparator -- hub silently ignores the RelrDev_1 write"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute",
                deviceIds: [8],
                attribute: "wickFilterLife",
                comparator: "!=",
                value: 20
            ]]],
            confirm: true
        ])

        then: "RelrDev_1 IS in settingsSkipped -- genuine silent_rejection, settingsLanded did NOT fire"
        def skipped = result?.settingsSkipped ?: []
        skipped.any { it?.key == "RelrDev_1" }
    }
}
