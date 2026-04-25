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
        settingsMap.enableBuiltinAppRead = true
        settingsMap.enableHubAdminRead = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    private void enableReadOnly() {
        settingsMap.enableBuiltinAppRead = true
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

        then:
        result.success == true  // write itself succeeded
        result.configPageError?.contains("'size'")
        result.warning?.contains("rendering error")
        result.restoreHint?.contains("restore_item_backup")
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
}
