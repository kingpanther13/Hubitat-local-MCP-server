package server

import support.ToolSpecBase
import groovy.json.JsonOutput

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

    def "removeAction throws when delAct click is silently no-oped (action still present)"() {
        given:
        enableHubAdminWrite()
        // statusJson always returns the same indices => RM no-oped the delete
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

        then: "the silent no-op is surfaced as success: false with a clear error pointing at the still-present index"
        result.success == false
        result.error?.contains("still in rule")
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

    def "addRequiredExpression cond=b open-paren write lands in applied (renderShift detection on wizard-consumed picker)"() {
        given:
        enableHubAdminWrite()
        // STPage schema stays identical across writes (cond + doneST) BUT the
        // rendered paragraphs shift when cond=b is set — RM's "(" marker
        // appears in the expression paragraph. Without renderShift detection,
        // the heuristic would falsely route cond=b to silent_rejection
        // (schema unchanged + value consumed by wizard = looks like no-op).
        // This test guards the renderShift path: paragraph shift → applied.
        def stPageInputs = [
            [name: "cond", type: "enum", options: ["": "Click to set", "a": "--> New Condition", "b": "--> ( sub-expression"]],
            [name: "doneST", type: "button"]
        ]
        // Track whether the cond=b POST has fired. Pre-write fetches return
        // empty paragraphs; post-write fetches return paragraphs with "(".
        def condBPosted = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body["settings[cond]"] == "b") {
                condBPosted = true
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", [[name: "useST", type: "bool"]]) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", [[name: "useST", type: "bool"]]) }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // Same schema both pre- and post-write, but paragraphs SHIFT after
            // cond=b posts so renderHash detects the wizard advance.
            def paragraphs = condBPosted ? ["Expression:", "(", "Click to set"] : ["Expression:", "Click to set"]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                             sections: [[title: "", input: stPageInputs, paragraphs: paragraphs]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"M1"}' }
        hubGet.register('/device/fullJson/10') { params -> '{"id":"10","name":"C1"}' }

        when: "addRequiredExpression with a single outer subExpression triggers cond=b first"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [
                [subExpression: [conditions: [
                    [capability: "Motion", deviceIds: [9], state: "active"],
                    [capability: "Contact", deviceIds: [10], state: "open"]
                ], operator: "OR"]]
            ]],
            confirm: true
        ])

        then: "cond(open-paren) lands in settingsApplied — renderHash detected the paragraph shift even though schema-keys + cond field value were identical"
        result?.settingsApplied?.contains("cond(open-paren)")

        and: "cond(open-paren) is NOT routed to settingsSkipped with silent_rejection"
        !result?.settingsSkipped?.any { it?.label == "cond(open-paren)" && it?.reason == "silent_rejection" }
    }

    def "addTrigger surfaces verificationFetchFailed=true when post-commit selectTriggers fetch errors"() {
        given:
        enableHubAdminWrite()
        // Fail ONLY the post-commit selectTriggers fetch (the one that runs
        // AFTER the hasAll button click). Pre-hasAll writes need the schema
        // to succeed, so we gate the failure on whether hasAll has fired.
        def hasAllFired = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "hasAll") {
                hasAllFired = true
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            if (hasAllFired) {
                throw new RuntimeException("simulated post-commit selectTriggers fetch failure")
            }
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

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "the verificationFetchFailed flag is surfaced (caller can detect the bake-check was bypassed)"
        // Either it's directly on the result (single addTrigger path) or on
        // an inner trigger entry (bulk path). Both shapes are valid.
        result.verificationFetchFailed == true ||
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
}
