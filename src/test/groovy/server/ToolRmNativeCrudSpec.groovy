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
        // Reset modes and timezone after each test to prevent inter-test leaks.
        // sharedLocation.modes: a test that assigns modes cannot leak into unrelated tests.
        // sharedLocation.timeZone: TZ-sensitive tests (e.g. Between two times) set a specific
        // zone; resetting to UTC keeps all other tests deterministic.
        sharedLocation.modes = []
        sharedLocation.timeZone = TimeZone.getTimeZone("UTC")
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
    private String ruleConfigJson(int ruleId, String label = "BAT-RM-test", List inputs = [], Integer parentAppId = null) {
        def app = [id: ruleId, name: "Rule-5.1", label: label, trueLabel: label, installed: true,
                   appType: [name: "Rule-5.1", namespace: "hubitat"]]
        if (parentAppId != null) app.parentAppId = parentAppId
        JsonOutput.toJson([
            app: app,
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null, sections: [
                [title: "", input: inputs]
            ]],
            settings: [:],
            childApps: []
        ])
    }

    // Decode a URL-encoded form body (key=value&key=value...) back into a Map
    // for assertion-friendly access. The cloner POSTs go through
    // hubInternalPostFormRaw with a pre-encoded body string; tests want to
    // assert on logical fields, not the percent-escape level.
    private Map decodeForm(String encoded) {
        if (!encoded) return [:]
        Map out = [:]
        encoded.split('&').each { kv ->
            int eq = kv.indexOf('=')
            String k = eq < 0 ? kv : kv.substring(0, eq)
            String v = eq < 0 ? "" : kv.substring(eq + 1)
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return out
    }

    // Build a cloner-page-state response that exposes a `_action_href_name|<action>|<idx>`
    // marker so _appClonerFindActionHrefIdx can pick it up. Idx 0 matches the
    // post-clone confirmation page; idx 55+ matches the post-upload restore-or-import
    // page seen live.
    private String clonerPageStateWithIdx(String action, int idx) {
        return JsonOutput.toJson([configPage: [name: "main", sections: [
            [input: [], body: [[
                description: "<button name='_action_href_name|${action}|${idx}'>Go</button>"
            ]]]
        ]]])
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
        def cached = atomicStateMap.parentAppIds?.rule_machine

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
        atomicStateMap.itemBackupManifest?.values()?.any { it.type == "rm-rule" && it.ruleId == 200 }
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
        atomicStateMap.itemBackupManifest = [
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
        atomicStateMap.itemBackupManifest = [
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
        atomicStateMap.itemBackupManifest = [
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

    def "restore_item_backup uses rule_machine default when snapshot has no appType field (legacy snapshot)"() {
        given: 'a legacy rm-rule snapshot captured before the appType field was added'
        enableHubAdminWrite()
        def snapshot = [
            schemaVersion: 1,
            ruleId: 350,
            reason: "pre-delete",
            timestamp: 1000,
            timestampIso: "2026-01-01T00:00:00Z",
            appLabel: "legacy-rule",
            // no appType field: _rmRestoreFromBackup must default to "rule_machine"
            configJson: [
                app: [id: 350, label: "legacy-rule"],
                configPage: [sections: [[title: "", input: [[name: "origLabel", type: "text"]]]]],
                settings: [origLabel: "legacy-rule"]
            ],
            statusJson: [:]
        ]
        def snapshotBytes = JsonOutput.toJson(snapshot).getBytes("UTF-8")
        atomicStateMap.itemBackupManifest = [
            "rm-rule_350_z": [type: "rm-rule", id: 350, ruleId: 350,
                              fileName: "mcp-rm-backup-350-z.json", reason: "pre-delete",
                              appLabel: "legacy-rule", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn -> snapshotBytes }
        hubGet.register('/hub2/appsList') { params -> appsListJson(21) }
        hubGet.register('/installedapp/configure/json/350') { params ->
            throw new RuntimeException("404 -- rule gone")
        }
        hubGet.register('/installedapp/configure/json/351') { params ->
            ruleConfigJson(351, "legacy-rule", [[name: "origLabel", type: "text"]])
        }
        hubGet.register('/installedapp/statusJson/351') { params -> statusJson(351) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/installedapp/configure/351", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: "rm-rule_350_z", confirm: true])

        then: 'succeeds -- the missing appType field defaults to rule_machine so _appTypeRegistry lookup works'
        result.success == true
        result.type == "rm-rule"
        result.recreated == true
        result.ruleId == 351
        result.originalRuleId == 350
    }

    def "restore_item_backup surfaces success:false when settings replay throws mid-flow"() {
        given: 'rule still exists in-place; _rmUpdateAppSettings will throw mid-replay'
        enableHubAdminWrite()
        def snapshot = [
            schemaVersion: 1,
            ruleId: 360,
            reason: "pre-update",
            timestamp: 1000,
            timestampIso: "2026-01-01T00:00:00Z",
            appLabel: "replay-err",
            configJson: [
                app: [id: 360, label: "replay-err"],
                configPage: [sections: [[title: "", input: [[name: "origLabel", type: "text"]]]]],
                settings: [origLabel: "replay-err"]
            ],
            statusJson: [:]
        ]
        def snapshotBytes = JsonOutput.toJson(snapshot).getBytes("UTF-8")
        atomicStateMap.itemBackupManifest = [
            "rm-rule_360_w": [type: "rm-rule", id: 360, ruleId: 360,
                              fileName: "mcp-rm-backup-360-w.json", reason: "pre-update",
                              appLabel: "replay-err", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn -> snapshotBytes }
        hubGet.register('/installedapp/configure/json/360') { params ->
            ruleConfigJson(360, "replay-err", [[name: "origLabel", type: "text"]])
        }
        hubGet.register('/installedapp/statusJson/360') { params -> statusJson(360) }
        // hubInternalPostForm throws on the settings replay POST
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json")
                throw new RuntimeException("hub returned 500 during settings replay")
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def result = script.toolRestoreItemBackup([backupKey: "rm-rule_360_w", confirm: true])

        then: 'success is false with a message that includes the restore-partially-applied note'
        result.success == false
        result.type == "rm-rule"
        result.ruleId == 360
        result.error?.contains("failed during settings replay")
        result.note?.contains("incomplete settings")
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

    // ---------- ghost ifThen predCapabs clear (Step 4b) ----------
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
        // Regression gate: ghost IF/THEN wrap after Required Expression commit.
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
        // A warn mcpLog must fire so the operator can diagnose ghost IF/THEN wrap risk.
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

    def "patches dispatches every supported sub-operation and reports unrecognized ops as failures"() {
        given: 'minimal rule with no inputs; patch batch exercises all recognized op keys'
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100],
                appSettings: [],
                appState: [:],
                actions: [:],
                stateAttribute: null
            ])
        }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"Switch 8"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when: 'batch with addTrigger, addAction, addRequiredExpression, addLocalVariable, bogusOp'
        def result = script.toolUpdateNativeApp([
            appId: 100,
            patches: [
                [addTrigger: [capability: "Switch", deviceIds: [8], state: "on"]],
                [addAction: [action: "delayedAction", actions: [[action: "deviceControl",
                    capability: "Switch", deviceIds: [8], command: "on"]],
                    delay: [value: 1, unit: "Seconds"]]],
                // 'conditions' (not 'exprs') is the required field name in _rmAddRequiredExpression
                [addRequiredExpression: [conditions: [
                    [capability: "Switch", deviceIds: [8], state: "on"]
                ]]],
                [addLocalVariable: [name: "myVar", type: "Number", value: "42"]],
                [bogusOp: "should not be recognized"]
            ],
            confirm: true
        ])

        then: 'each op is dispatched to its handler (op key present); bogusOp surfaces the unrecognized-key error'
        result.patches.size() == 5
        result.patches[0].op == "addTrigger"
        result.patches[1].op == "addAction"
        // addRequiredExpression reached its handler (not rejected as unrecognized).
        // Handler may fail on hub stubs (complex wizard) but must NOT throw the
        // "conditions is required" IAE that the old wrong field name 'exprs' caused.
        result.patches[2].op == "addRequiredExpression"
        !result.patches[2].error?.contains("conditions is required")
        result.patches[3].op == "addLocalVariable"
        result.patches[4].success == false
        result.patches[4].error?.contains("no recognized operation key")
    }

    // ---------- structured-shortcut coverage (addLocalVariable + related) ----------

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

    @spock.lang.Unroll
    def "addLocalVariable writes varType=#rmType for #rmType type with value #rawValue"() {
        // Covers all 5 RM 5.1 local-variable types end-to-end. The helper
        // validates the type label up front (rejects unknown labels with
        // IllegalArgumentException) and canonicalizes case before any write,
        // so this spec pins the wire-encoding contract: the canonical label
        // survives to settings[varType] and the raw value reaches
        // settings[varValue]. Boolean coercion is owned by the dedicated
        // spec above; this one walks the type matrix.
        given:
        enableHubAdminWrite()
        def selActsAfter = [
            [name: "hbVar", type: "text"],
            [name: "varType", type: "enum", options: ["Number", "Decimal", "String", "Boolean", "DateTime"]],
            [name: "varValue", type: "text"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", selActsAfter) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100], appSettings: [], eventSubscriptions: [], scheduledJobs: [],
                appState: [[name: "allLocalVars", value: [(varName): [type: rmType.toLowerCase(), value: rawValue]]]],
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
            addLocalVariable: [name: varName, type: rmType, value: rawValue],
            confirm: true
        ])

        then: "the varType setting lands with the exact RM type label"
        def varTypeWrite = posts.find { it.path == "/installedapp/update/json" && it.body.containsKey("settings[varType]") }
        varTypeWrite != null
        varTypeWrite.body["settings[varType]"]?.toString() == rmType

        and: "the varValue setting lands with the type-coerced string"
        // varValue is wire-encoded as a string in every case. Boolean values
        // go through the dedicated coercion path covered by the spec above,
        // but the resulting wire shape is still the literal "true"/"false"
        // string, so the comparison below stays uniform across the 5 types.
        def varValueWrite = posts.find { it.path == "/installedapp/update/json" && it.body.containsKey("settings[varValue]") }
        varValueWrite != null
        varValueWrite.body["settings[varValue]"]?.toString() == rawValue.toString()

        and: "the result reflects the committed type"
        result.success == true
        result.variable?.type == rmType

        where:
        rmType     | varName       | rawValue
        "Number"   | "counter"     | 42
        "Decimal"  | "ratio"       | 0.5
        "String"   | "label"       | "ready"
        "Boolean"  | "flag"        | true
        "DateTime" | "lastSeenAt"  | "2026-05-17T14:30:00.000-0400"
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

    def "clearActions succeeds on second check when trashActs propagation lags (race-recovery path)"() {
        given:
        enableHubAdminWrite()
        def trashActsWritten = false
        def verificationFetches = 0
        def selectActionsSchema = [
            [name: "actType.1", type: "enum", options: ["switchActs"]],
            [name: "trashActs", type: "enum", multiple: true]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", selectActionsSchema) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (trashActsWritten) {
                verificationFetches++
                if (verificationFetches < 2) {
                    // attempt 0: action still present (race — RM applied write but GC hasn't propagated)
                    statusJson(100, [[name: "actType.1", value: "switchActs"]])
                } else {
                    // attempt 1: action gone (post-backoff, recovered)
                    statusJson(100, [])
                }
            } else {
                // pre-write: action present
                statusJson(100, [[name: "actType.1", value: "switchActs"]])
            }
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.containsKey("settings[trashActs]")) {
                trashActsWritten = true
            }
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, clearActions: true, confirm: true])

        then: "success after one retry — race-recovery path mirroring _rmDeleteAction"
        result.success == true
        result.removedIndices?.contains(1) || result.removedIndices?.contains("1")

        and: "retry loop fired at least twice (attempt 0 saw stale state; attempt 1 confirmed clear)"
        verificationFetches >= 2
    }

    def "clearActions throws after 4 retry attempts when actions never disappear (10s budget exhausted)"() {
        given:
        enableHubAdminWrite()
        def trashActsWritten = false
        def verificationFetches = 0
        def selectActionsSchema = [
            [name: "actType.1", type: "enum", options: ["switchActs"]],
            [name: "trashActs", type: "enum", multiple: true]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", selectActionsSchema) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (trashActsWritten) {
                verificationFetches++
                // never propagates — all 4 fetches return action still present
            }
            statusJson(100, [[name: "actType.1", value: "switchActs"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.containsKey("settings[trashActs]")) {
                trashActsWritten = true
            }
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, clearActions: true, confirm: true])

        then: "throws after retries exhaust"
        result.success == false
        result.error?.contains("after 10s of retries")

        and: "retry loop fired at least 4 times (the budget) before throwing"
        verificationFetches >= 4

        and: "error directs caller to verify via get_app_config and points at restore_item_backup for rollback"
        result.error?.contains("get_app_config")
        result.error?.contains("restore_item_backup")
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

    // W-spec-clearActions-text (singular): the stillThereWord ternary at L16398 (production)
    // routes the recovery-phrase "if the <action|actions> really did get clobbered". Pin
    // the singular form when exactly 1 action stays stuck.
    // Both-ways pending (orchestrator).
    def "clearActions error text says 'if the action really did get clobbered' singular for one stuck action"() {
        given:
        enableHubAdminWrite()
        def selectActionsSchema = [
            [name: "actType.1", type: "enum", options: ["switchActs"]],
            [name: "trashActs", type: "enum", multiple: true]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", selectActionsSchema) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "actType.1", value: "switchActs"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, clearActions: true, confirm: true])

        then: "error message uses singular form 'if the action really did get clobbered'"
        result.success == false
        result.error?.contains("if the action really did get clobbered")
        !result.error?.contains("if the actions really did get clobbered")
    }

    // W-spec-clearActions-text (plural): pin the plural form when multiple actions stay stuck.
    // Both-ways pending (orchestrator).
    def "clearActions error text says 'if the actions really did get clobbered' plural for two stuck actions"() {
        given:
        enableHubAdminWrite()
        def selectActionsSchema = [
            [name: "actType.1", type: "enum", options: ["switchActs"]],
            [name: "actType.2", type: "enum", options: ["switchActs"]],
            [name: "trashActs", type: "enum", multiple: true]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", selectActionsSchema) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [
                [name: "actType.1", value: "switchActs"],
                [name: "actType.2", value: "switchActs"]
            ])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, clearActions: true, confirm: true])

        then: "error message uses plural 'if the actions really did get clobbered'"
        result.success == false
        result.error?.contains("if the actions really did get clobbered")
        result.error?.contains("actions [1, 2] still present")
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

    // ---------- addActions bulk shortcut ----------

    def "addActions happy path: three specs each call _rmAddAction and updateRule fires once"() {
        given:
        enableHubAdminWrite()
        def doActPageSchema = [
            [name: "actType.1", type: "enum"],
            [name: "actSubType.1", type: "enum"],
            [name: "actionDone", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> ruleConfigJson(100, "r", doActPageSchema) }
        def callCount = 0
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, callCount > 0 ? [[name: "actType.${callCount}", value: "switchActs"]] : [])
        }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"S2"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "actionDone") callCount++
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addActions: [
                [capability: "switch", action: "on", deviceIds: [8]],
                [capability: "switch", action: "off", deviceIds: [9]],
                [capability: "log", message: "done"]
            ],
            confirm: true
        ])

        then: "result carries actions list with 3 items and overall success"
        result.actions?.size() == 3

        and: "updateRule fires exactly once (not once per action)"
        def updateRuleClicks = posts.count { it.path == "/installedapp/btn" && it.body?.name == "updateRule" }
        updateRuleClicks == 1
    }

    def "addActions partial failure: bogus deviceId fails inline, other specs succeed"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum"], [name: "actionDone", type: "button"]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        hubGet.register('/device/fullJson/99999') { params -> "" }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addActions: [
                [capability: "switch", action: "on", deviceIds: [8]],
                [capability: "switch", action: "on", deviceIds: [99999]]
            ],
            confirm: true
        ])

        then: "overall success is false because one sub-spec failed"
        result.success == false

        and: "actions list has 2 items: first succeeds, second carries the error"
        result.actions?.size() == 2
        result.actions[1].success == false
        result.actions[1].error?.contains("99999")
    }

    def "addActions mixed-type path: switch and log specs in one call"() {
        given:
        enableHubAdminWrite()
        def doActSchema = [
            [name: "actType.1", type: "enum"],
            [name: "actSubType.1", type: "enum"],
            [name: "logmsg.1", type: "textarea"],
            [name: "actionDone", type: "button"]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> ruleConfigJson(100, "r", doActSchema) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addActions: [
                [capability: "switch", action: "on", deviceIds: [8]],
                [capability: "log", message: "test message"]
            ],
            confirm: true
        ])

        then: "both action specs are dispatched"
        result.actions?.size() == 2

        and: "the log message write lands (switch actType + log message both appear in POSTs)"
        posts.any { it.body?.containsKey("settings[actType.1]") || it.body?.containsKey("settings[logmsg.1]") }
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

    // ---------- moveAction happy path ----------

    def "moveAction arrowDn: action moves forward one position and tier-1 ordering path is exercised"() {
        // Before: actions in display order [1, 2, 3]. Move index 1 down.
        // After:  actions in display order [2, 1, 3]. Position shifts from 0 to 1.
        // statusJson.actions map (tier-1 path) drives ordering for both before and
        // after fetches -- exercises the lexical-sort fix from _rmCollectActionIndices.
        given:
        enableHubAdminWrite()
        def clickFired = false
        def beforeActionsMap = ["1": "Switch On", "2": "Delay", "3": "Switch Off"]
        def afterActionsMap  = ["2": "Delay", "1": "Switch On", "3": "Switch Off"]
        def makeStatus = { Map actMap ->
            JsonOutput.toJson([
                installedApp: [id: 100],
                appSettings: [
                    [name: "actType.1", value: "switchActs"],
                    [name: "actType.2", value: "delayActs"],
                    [name: "actType.3", value: "switchActs"]
                ],
                eventSubscriptions: [[name: "evt1"]],
                scheduledJobs: [],
                appState: [:],
                actions: actMap,
                childAppCount: 0, childDeviceCount: 0
            ])
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            clickFired ? makeStatus(afterActionsMap) : makeStatus(beforeActionsMap)
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.stateAttribute == "arrowDn") clickFired = true
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, moveAction: [index: 1, direction: "down"], confirm: true])

        then: "arrowDn click fires and result reports success"
        clickFired == true
        result.success == true

        and: "note confirms the move direction"
        result.note?.contains("down")
    }

    def "moveAction arrowUp: action moves back one position using tier-1 ordering"() {
        // Before: actions in display order [1, 2, 3]. Move index 2 up.
        // After:  actions in display order [2, 1, 3]. Position shifts from 1 to 0.
        given:
        enableHubAdminWrite()
        def clickFired = false
        def beforeActionsMap = ["1": "Switch On", "2": "Delay", "3": "Switch Off"]
        def afterActionsMap  = ["2": "Delay", "1": "Switch On", "3": "Switch Off"]
        def makeStatus = { Map actMap ->
            JsonOutput.toJson([
                installedApp: [id: 100],
                appSettings: [
                    [name: "actType.1", value: "switchActs"],
                    [name: "actType.2", value: "delayActs"],
                    [name: "actType.3", value: "switchActs"]
                ],
                eventSubscriptions: [[name: "evt1"]],
                scheduledJobs: [],
                appState: [:],
                actions: actMap,
                childAppCount: 0, childDeviceCount: 0
            ])
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            clickFired ? makeStatus(afterActionsMap) : makeStatus(beforeActionsMap)
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.stateAttribute == "arrowUp") clickFired = true
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, moveAction: [index: 2, direction: "up"], confirm: true])

        then: "arrowUp click fires and result reports success"
        clickFired == true
        result.success == true

        and: "note confirms the move direction"
        result.note?.contains("up")
    }

    // ---------- state.editAct pre-flight guard (applies to removeAction AND moveAction) ----------

    def "removeAction pre-flight detects stuck state.editAct and throws immediately"() {
        // state.editAct pre-flight invariant: when state.editAct is set, RM silently no-ops delAct
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
        // Same state.editAct pre-flight guard as removeAction, applied at the moveAction site.
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
        // No-false-positive guard: when appState has no editAct entry,
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

    // Issue #178 pre-flight refusal tests — three layers that refuse the
    // call BEFORE RM is touched so the false-fail-with-post-commit race
    // can't leak through. Counterpart to the post-mutation detection in
    // check_rule_health (defense-in-depth).

    private List nestedIfThenSettings() {
        // Reproduces the live BAT-178 rule structure (well-formed):
        //   IF -> IF -> lock-delayed -> END-IF -> IF -> lock -> END-IF -> END-IF
        [
            [name: "actType.9",  value: "condActs"], [name: "actSubType.9",  value: "getIfThen"],
            [name: "actType.10", value: "condActs"], [name: "actSubType.10", value: "getIfThen"],
            [name: "actType.11", value: "lockActs"], [name: "actSubType.11", value: "getLULock"],
            [name: "actType.12", value: "condActs"], [name: "actSubType.12", value: "getEndIf"],
            [name: "actType.13", value: "condActs"], [name: "actSubType.13", value: "getIfThen"],
            [name: "actType.14", value: "lockActs"], [name: "actSubType.14", value: "getLULock"],
            [name: "actType.15", value: "condActs"], [name: "actSubType.15", value: "getEndIf"],
            [name: "actType.16", value: "condActs"], [name: "actSubType.16", value: "getEndIf"]
        ]
    }

    def "removeAction refuses to delete the inner END-IF that would unbalance a nested rule (issue #178)"() {
        // The exact #178 trigger: removeAction({index: <inner END-IF>}) on a
        // well-formed nested rule. Pre-fix: returned false-fail timeout but
        // the delete committed post-response, leaving 3 IFs / 2 END-IFs.
        // Post-fix: pre-flight refuses before any HTTP click goes out.
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Nested", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, nestedIfThenSettings()) }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 12], confirm: true])

        then:
        result.success == false
        result.error?.contains("removeAction(12) blocked")
        result.error?.contains("structural END-IF")
        result.error?.contains("RM is not touched")

        and: "no delAct button click fires"
        !posts.any { it.body?.get("stateAttribute") == "delAct" }

        and: "pre-flight refusal response attaches health so the caller can see the existing balance state"
        result.health != null
        result.restoreHint?.contains("Pre-flight refusal")
    }

    def "removeAction refuses to delete the outer IF that would unbalance a nested rule"() {
        // Symmetric to the END-IF case: removing the outer IF (action 9)
        // would leave two END-IFs dangling. Pre-flight should refuse.
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Nested", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, nestedIfThenSettings()) }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 9], confirm: true])

        then:
        result.success == false
        result.error?.contains("removeAction(9) blocked")
        result.error?.contains("structural IF")
        result.error?.contains("RM is not touched")
        !posts.any { it.body?.get("stateAttribute") == "delAct" }
    }

    def "removeAction allows deleting a leaf (lock) action — no structural risk"() {
        // No-false-positive guard: action 11 is a lockActs/getLULock leaf.
        // Deleting it doesn't touch the IF/END-IF balance, so pre-flight
        // should pass through and the normal delete path should run.
        given:
        enableHubAdminWrite()
        def delActFired = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "delAct") delActFired = true
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Nested", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (delActFired) {
                // post-click: action 11 gone
                statusJson(100, nestedIfThenSettings().findAll { !it.name?.endsWith(".11") })
            } else {
                statusJson(100, nestedIfThenSettings())
            }
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 11], confirm: true])

        then:
        result.success == true
        delActFired == true
    }

    def "removeAction allows deleting an IF when the rule is already imbalanced and the delete improves it"() {
        // Edge case: the rule is already broken (3 IFs, 2 END-IFs).
        // Removing the dangling outer IF (action 9) would FIX the imbalance.
        // Pre-flight must allow this because projected-issues <= current-issues.
        given:
        enableHubAdminWrite()
        def delActFired = false
        // Already-imbalanced rule: drop action 12 (inner END-IF) from the
        // well-formed set so 3 IFs (9, 10, 13) and only 2 END-IFs (15, 16) remain.
        def alreadyBroken = nestedIfThenSettings().findAll { !it.name?.endsWith(".12") }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "delAct") delActFired = true
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Already broken", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (delActFired) {
                statusJson(100, alreadyBroken.findAll { !it.name?.endsWith(".9") })
            } else {
                statusJson(100, alreadyBroken)
            }
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 9], confirm: true])

        then: "pre-flight allows the removal because it doesn't make things worse"
        result.success == true
        delActFired == true
    }

    def "addAction(endIf) refuses when no IF is open on the stack"() {
        // Adding a bare END-IF to a rule with no open IF would leave an
        // orphaned closer. Pre-flight should refuse before the doActPage
        // wizard even opens.
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Plain", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            // Single switch action — no open IF.
            statusJson(100, [[name: "actType.1", value: "switchActs"], [name: "actSubType.1", value: "getOnOff"]])
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, addAction: [capability: "endIf"], confirm: true])

        then:
        result.success == false
        result.error?.contains("addAction(endIf) blocked")
        result.error?.contains("END-IF) has no matching IF")
        result.error?.contains("RM is not touched")
        !posts.any { it.body?.get("stateAttribute")?.toString()?.startsWith("doAct") }

        and: "the addAction catch path also routes pre-flight refusals through the helper — restoreHint and health are attached"
        result.restoreHint?.contains("Pre-flight refusal")
        result.health != null
    }

    def "addAction(stopRepeat) refuses when no Repeat is open on the stack"() {
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Plain", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, []) }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, addAction: [capability: "stopRepeat"], confirm: true])

        then:
        result.success == false
        result.error?.contains("addAction(stopRepeat) blocked")
        result.error?.contains("End Repeat) has no matching Repeat")
        result.error?.contains("RM is not touched")
    }

    def "addAction(ifThen) is NOT refused — opener-without-closer is a normal multi-step build"() {
        // Asymmetric on purpose: adding an IF alone is fine; the caller
        // will close it in a follow-up call. We only refuse closers.
        // This test runs the spec only as far as the pre-flight — the
        // ifThen wizard then needs full doActPage stubbing that's out of
        // scope here, so we assert the pre-flight didn't fire.
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Plain", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, []) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "ifThen", expression: [conditions: [[capability: "Switch", deviceIds: [1], state: "on"]]]],
            confirm: true
        ])

        then: "the pre-flight does NOT refuse — error (if any) comes from the wizard stubs, not pre-flight"
        // The wizard call will fail on the harness's missing doActPage
        // mocks, but the error MUST NOT contain the pre-flight refusal
        // sentinel ('RM is not touched' is only emitted by the four
        // pre-flight refusal paths) and the pre-flight 'addAction(ifThen)
        // blocked' prefix.
        !(result?.error?.toString()?.contains("RM is not touched"))
        !(result?.error?.toString()?.contains("addAction(ifThen) blocked"))

        and: "the wizard's doActPage open click DID fire (proves pre-flight passed and control reached _rmAddAction's wizard flow)"
        posts.any { it.body?.get("stateAttribute")?.toString()?.startsWith("doAct") }
    }

    def "replaceActions refuses an imbalanced spec list before any clearActions click"() {
        // Caller passes a list with 2 IFs and 1 END-IF — pre-flight refuses
        // before clearActions runs, so the original rule is preserved.
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, [[name: "actType.1", value: "switchActs"]]) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            replaceActions: [
                [capability: "ifThen", expression: [conditions: [[capability: "Switch", deviceIds: [1], state: "on"]]]],
                [capability: "ifThen", expression: [conditions: [[capability: "Switch", deviceIds: [1], state: "on"]]]],
                [capability: "endIf"]
            ],
            confirm: true
        ])

        then:
        result.success == false
        result.error?.contains("replaceActions blocked")
        result.error?.contains("structurally imbalanced")
        result.error?.contains("RM is not touched")

        and: "no trashAll click — the original rule is preserved"
        !posts.any { it.body?.get("name") == "trashAll" }

        and: "pre-flight refusal response attaches health and a non-restore hint"
        result.health != null
        result.restoreHint?.contains("Pre-flight refusal")
    }

    def "replaceActions allows a balanced spec list"() {
        // Pre-flight must pass when the list is balanced. Asserts both the
        // negative (no pre-flight refusal sentinel in the error) AND the
        // positive (the trashAll button click DID fire, meaning pre-flight
        // passed and control reached clearActions).
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, [[name: "actType.1", value: "switchActs"]]) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            replaceActions: [
                [capability: "ifThen", expression: [conditions: [[capability: "Switch", deviceIds: [1], state: "on"]]]],
                [capability: "endIf"]
            ],
            confirm: true
        ])

        then: "no pre-flight refusal"
        !(result?.error?.toString()?.contains("RM is not touched"))
        !(result?.error?.toString()?.contains("replaceActions blocked"))

        and: "restoreHint is NOT the pre-flight one — proves the catch block didn't tag this as a pre-flight refusal"
        result?.restoreHint == null || !result.restoreHint.contains("Pre-flight refusal")

        and: "the run made it past pre-flight into clearActions territory (either trashAll click or cancelTrash recovery from a failed clearActions attempt — both prove pre-flight passed)"
        posts.any { it.body?.get("name") == "trashAll" || it.body?.get("name") == "cancelTrash" }
    }

    def "patches[replaceActions=...] refuses an imbalanced patch spec before any clearActions click"() {
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, []) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            patches: [[replaceActions: [
                [capability: "ifThen", expression: [conditions: [[capability: "Switch", deviceIds: [1], state: "on"]]]],
                [capability: "endIf"],
                [capability: "endIf"]
            ]]],
            confirm: true
        ])

        then:
        result.patches?.first()?.success == false
        result.patches?.first()?.error?.contains("patches[0].replaceActions blocked")
        result.patches?.first()?.error?.contains("structurally imbalanced")

        and: "no trashAll click — the original rule is preserved"
        !posts.any { it.body?.get("name") == "trashAll" }
    }

    // Coverage for the structural subtypes that are in _rmDeleteAction's
    // structuralSubTypes list but had no dedicated removal test.

    def "removeAction refuses deleting a Repeat opener that would leave its End-Repeat orphaned"() {
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        def repeatSettings = ifStructureSettings([
            [idx: 1, actType: "repeatActs", actSubType: "getRepeat"],
            [idx: 2, actType: "lockActs",   actSubType: "getLULock"],
            [idx: 3, actType: "repeatActs", actSubType: "getStopRepeat"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Repeat", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, repeatSettings) }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 1], confirm: true])

        then:
        result.success == false
        result.error?.contains("removeAction(1) blocked")
        result.error?.contains("structural Repeat")
        result.error?.contains("RM is not touched")
        !posts.any { it.body?.get("stateAttribute") == "delAct" }
    }

    def "removeAction refuses deleting an End-Repeat that would leave its Repeat unclosed"() {
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        def repeatSettings = ifStructureSettings([
            [idx: 1, actType: "repeatActs", actSubType: "getRepeat"],
            [idx: 2, actType: "lockActs",   actSubType: "getLULock"],
            [idx: 3, actType: "repeatActs", actSubType: "getStopRepeat"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Repeat", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, repeatSettings) }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 3], confirm: true])

        then:
        result.success == false
        result.error?.contains("removeAction(3) blocked")
        result.error?.contains("structural End-Repeat")
        result.error?.contains("RM is not touched")
        !posts.any { it.body?.get("stateAttribute") == "delAct" }
    }

    def "removeAction allows deleting an ELSE-IF in an IF / ELSE-IF / END-IF rule (no balance change)"() {
        given:
        enableHubAdminWrite()
        def delActFired = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "delAct") delActFired = true
            [status: 200, location: null, data: '']
        }
        def chain = ifStructureSettings([
            [idx: 1, actType: "condActs", actSubType: "getIfThen"],
            [idx: 2, actType: "lockActs", actSubType: "getLULock"],
            [idx: 3, actType: "condActs", actSubType: "getElseIf"],
            [idx: 4, actType: "lockActs", actSubType: "getLULock"],
            [idx: 5, actType: "condActs", actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Chain", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            delActFired ? statusJson(100, chain.findAll { !it.name?.endsWith(".3") }) : statusJson(100, chain)
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 3], confirm: true])

        then: "deleting an ELSE-IF doesn't change IF/END-IF balance — pre-flight allows the delete"
        result.success == true
        delActFired == true
    }

    // Coverage for elseIf/else orphan refusal in _rmAddAction (the closer-or-
    // branch-keywords path that landed alongside the structural pre-flight).

    def "addAction(elseIf) refuses when no IF is open on the stack"() {
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Plain", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "actType.1", value: "switchActs"], [name: "actSubType.1", value: "getOnOff"]])
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "elseIf", expression: [conditions: [[capability: "Switch", deviceIds: [1], state: "on"]]]],
            confirm: true
        ])

        then:
        result.success == false
        result.error?.contains("addAction(elseIf) blocked")
        result.error?.contains("ELSE-IF) is outside any IF block")
        result.error?.contains("RM is not touched")
        !posts.any { it.body?.get("stateAttribute")?.toString()?.startsWith("doAct") }
    }

    def "addAction(else) refuses when no IF is open on the stack"() {
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Plain", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, []) }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, addAction: [capability: "else"], confirm: true])

        then:
        result.success == false
        result.error?.contains("addAction(else) blocked")
        result.error?.contains("ELSE) is outside any IF block")
        result.error?.contains("RM is not touched")
    }

    // Regression guard for the case-sensitivity hole: a mis-cased structural
    // capability in replaceActions used to pass the pre-flight as "balanced"
    // (the walker treated it as a leaf), then fail at per-item dispatch after
    // clearActions had already wiped the rule. The fix dropped .toLowerCase()
    // in _rmStructuralPairForCapability so it matches dispatch's case-
    // sensitive comparison — mis-cased structural caps now project to null
    // (leaf) AND will fail downstream dispatch, so this regression test
    // expects either a pre-flight refusal (when the mis-spelling produces
    // an imbalance) or a clearActions never firing.

    def "replaceActions with mis-cased endIf is not silently accepted as balanced"() {
        // ['ifThen', 'endIF' (wrong case)] — pre-fix this passed the pre-
        // flight because the walker lowercased 'endIF' to 'endif' and saw
        // a matched IF/END-IF pair. Post-fix the walker treats 'endIF' as
        // a leaf (returns null pair), so the projected sequence is just
        // [ifThen] which is imbalanced — pre-flight refuses.
        given:
        enableHubAdminWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, [[name: "actType.1", value: "switchActs"]]) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            replaceActions: [
                [capability: "ifThen", expression: [conditions: [[capability: "Switch", deviceIds: [1], state: "on"]]]],
                [capability: "endIF"]
            ],
            confirm: true
        ])

        then: "pre-flight refuses because mis-cased endIF doesn't satisfy the pair lookup"
        result.success == false
        result.error?.contains("replaceActions blocked")
        result.error?.contains("structurally imbalanced")

        and: "no trashAll click — the original rule is preserved (the #178 damage class)"
        !posts.any { it.body?.get("name") == "trashAll" }
    }

    // Coverage for the auto-attached health field on update_native_app
    // mutation responses — the PR's tool description promises this surface
    // but no existing test pins it.

    def "update_native_app attaches health.structuralIssues field on every mutation response"() {
        given:
        enableHubAdminWrite()
        def delActFired = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "delAct") delActFired = true
            [status: 200, location: null, data: '']
        }
        // Three-action balanced rule: IF / lock / END-IF.
        def settings = ifStructureSettings([
            [idx: 1, actType: "condActs", actSubType: "getIfThen"],
            [idx: 2, actType: "lockActs", actSubType: "getLULock"],
            [idx: 3, actType: "condActs", actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Balanced", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (delActFired) {
                statusJson(100, settings.findAll { !it.name?.endsWith(".2") })
            } else {
                statusJson(100, settings)
            }
        }

        when: "remove the leaf lock action (no structural risk)"
        def result = script.toolUpdateNativeApp([appId: 100, removeAction: [index: 2], confirm: true])

        then: "the response surfaces health AND specifically the structuralIssues field"
        result.success == true
        result.health != null
        result.health.structuralIssues != null
        result.health.structuralIssues instanceof List
        // The remaining IF/END-IF pair is still balanced, so structuralIssues is empty.
        result.health.structuralIssues.isEmpty()
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

    def "walkStep click fires a /installedapp/btn POST with the requested button name + stateAttribute"() {
        // Direct unit cover for walkStep operation='click' — the dispatcher
        // routes through _rmClickAppButton which POSTs to /installedapp/btn
        // with name=<btnName>, settings[btnName]=clicked, stateAttribute (when
        // supplied), pageBreadcrumbs=["mainPage"], currentPage=<page>.
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["switchActs"]],
                [name: "moreVar", type: "button"]
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
            walkStep: [page: "selectActions", operation: "click", click: [name: "moreVar", stateAttribute: "moreVar"]],
            confirm: true
        ])

        then: "the button POST is issued with the click payload shape"
        result.success == true
        def btnPost = posts.find { it.path == "/installedapp/btn" && it.body?.name == "moreVar" }
        btnPost != null
        btnPost.body["settings[moreVar]"] == "clicked"
        btnPost.body["moreVar.type"] == "button"
        btnPost.body.stateAttribute == "moreVar"
        btnPost.body.currentPage == "selectActions"
        btnPost.body.pageBreadcrumbs == '["mainPage"]'

        and: "the result echoes the clicked button under opResult"
        result.opResult?.clicked?.name == "moreVar"
        result.opResult?.clicked?.stateAttribute == "moreVar"
    }

    def "walkStep navigate emits the two-part href marker + params_for_action_href payload"() {
        // walkStep operation='navigate' routes through _rmNavigateToPage. For
        // sub-page navigation with href params (Periodic Schedule is the
        // archetype — its href carries {n:1}), the LIVE wire pair is:
        //   _action_href_<linkName>|<page>|<idx>=clicked
        //   params_for_action_href_<linkName>|<page>|<idx>=<json-of-params>
        // Without the second marker the target sub-page renders with
        // state.<paramKey>=null and the schema appears empty.
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true, version: 7,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [],
                                         // hrefs live in section.body[] with element=href +
                                         // a page/params pair. _rmCollectWalkSchema extracts these
                                         // into the beforeSchema.hrefs list so walkStep navigate
                                         // can recover the linkName + params for the marker pair.
                                         body: [[element: "href", name: "periodic1", page: "periodic", params: [n: 1]]]]]],
                settings: [:], childApps: []
            ])
        }
        // Target page returned in the nav-response body (the only place
        // state.n is in scope).
        hubGet.register('/installedapp/configure/json/100/periodic') { params ->
            JsonOutput.toJson([
                app: [id: 100, version: 7], configPage: [name: "periodic", sections: [[input: [[name: "whichPeriod1", type: "enum"]]]]], settings: [:]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: JsonOutput.toJson([
                app: [id: 100, version: 7], configPage: [name: "periodic", sections: [[input: [[name: "whichPeriod1", type: "enum"]]]]]
            ])]
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            walkStep: [page: "selectTriggers", operation: "navigate", navigate: [targetPage: "periodic"]],
            confirm: true
        ])

        then: "the nav POST emits both halves of the href marker pair"
        result.success == true
        def navPost = posts.find { it.path == "/installedapp/update/json" && it.body?.any { k, v -> k.toString().startsWith("_action_href_") } }
        navPost != null
        def actionKey = navPost.body.keySet().find { it.toString().startsWith("_action_href_") }
        actionKey.toString().endsWith("|periodic|1")
        def paramsKey = navPost.body.keySet().find { it.toString().startsWith("params_for_action_href_") }
        paramsKey != null
        navPost.body[paramsKey].toString().contains('"n":1')
        navPost.body.currentPage == "selectTriggers"

        and: "the result reflects the navigation under opResult"
        result.opResult?.navigated?.to == "periodic"
        result.opResult?.navigated?.from == "selectTriggers"

        and: "the target sub-page schema is exposed in result.after (consumed from the nav response, not a follow-up GET)"
        // _rmNavigateToPage's response is the only place state.<paramKey>
        // is in scope; if the navigate op dropped the JSON parse and fell
        // back to a plain GET, the target schema would render empty and
        // subsequent walkStep writes would silent-reject. The whichPeriod1
        // input being visible here pins the parse + extraction path.
        result.after?.inputs?.any { it?.name == "whichPeriod1" }
    }

    def "walkStep done submits sub-page with _action_previous=Done + paramsForPage routing"() {
        // walkStep operation='done' routes through _rmSubmitSubPageDone, which
        // first round-trips the nav POST to bring state.<paramKey> into scope
        // (otherwise the sub-page's schema renders empty), then issues the
        // Done POST carrying settings[X] + per-type sidecars + paramsForPage.
        // The live periodic-page Done is what bakes the trigger description
        // ("Every Hour at :15") into the parent's row — forward-nav markers
        // alone leave the row as "?".
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // Sub-page schema with one settable field so the Done body carries a
        // real settings[X] entry (the helper requires at least one field to
        // exercise the merge path).
        hubGet.register('/installedapp/configure/json/100/periodic') { params ->
            JsonOutput.toJson([
                app: [id: 100, version: 7],
                configPage: [name: "periodic", title: "Periodic", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "whichPeriod1", type: "enum", options: ["Hourly"], value: "Hourly"]
                             ]]]],
                settings: [:]
            ])
        }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            ruleConfigJson(100, "r", [[name: "periodic1", type: "href", page: "periodic", params: [n: 1]]])
        }
        // statusJson must include the sub-page's settings so the Done body
        // can recover the live value (the merge reads from appSettings).
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "whichPeriod1", type: "enum", value: "Hourly"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            // Return the periodic schema for the nav round-trip so
            // _rmSubmitSubPageDone can read the version field.
            [status: 200, location: null, data: JsonOutput.toJson([
                app: [id: 100, version: 7],
                configPage: [name: "periodic", sections: [[input: [[name: "whichPeriod1", type: "enum", value: "Hourly"]]]]]
            ])]
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            walkStep: [
                page: "periodic",
                operation: "done",
                hrefContext: [fromPage: "selectTriggers", hrefName: "periodic1", hrefParams: [n: 1]]
            ],
            confirm: true
        ])

        then: "a /installedapp/update/json POST carries _action_previous=Done with sub-page settings + paramsForPage"
        result.success == true
        def donePost = posts.find {
            it.path == "/installedapp/update/json" && it.body?.containsKey("_action_previous")
        }
        donePost != null
        donePost.body["_action_previous"] == "Done"
        // The live sub-page settings are merged into the Done body — the
        // helper reads them out of statusJson's appSettings and echoes
        // every visible input under settings[X]. If the merge regressed,
        // the trigger row's description would render as "?" because the
        // hub treats an empty settings[whichPeriod1] as "no schedule set".
        donePost.body["settings[whichPeriod1]"] == "Hourly"
        donePost.body.containsKey("paramsForPage")
        donePost.body.paramsForPage.toString().contains('"n":1')
        // pageBreadcrumbs carries parent context so the hub renders selectTriggers
        // in the response — without it the Done can't bake the parent row.
        donePost.body.pageBreadcrumbs?.toString()?.contains("selectTriggers")

        and: "the result captures the done transition under opResult"
        result.opResult?.done?.from == "periodic"
        result.opResult?.done?.parent == "selectTriggers"
    }

    def "walkStep done on a top-level page (no hrefContext) submits Done with no paramsForPage and root-only breadcrumbs"() {
        // Coverage for the top-level branch of walkStep done. When the caller
        // does NOT supply hrefContext, _rmSubmitSubPageDone is invoked with
        // parentPage=null and hrefParams=null. That collapses the Done body
        // to: _action_previous=Done, pageBreadcrumbs=["mainPage"] (no parent
        // appended), and NO paramsForPage marker (the merge only adds it when
        // hrefParams is non-empty). The companion spec above pins the
        // sub-page branch with hrefContext; this one pins the top-level
        // branch so a regression that swapped the two would surface here
        // rather than via a BAT-only signal. Note: the wizard-Done residual
        // isCondTrig.<N>=false finalize lives on the addTrigger commit path
        // (covered at :1122), not on walkStep done.
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            JsonOutput.toJson([
                app: [id: 100, version: 7],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "tCapab1", type: "enum", options: ["Switch"], value: "Switch"]
                             ]]]],
                settings: ["tCapab1": "Switch"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "tCapab1", type: "enum", value: "Switch"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: JsonOutput.toJson([
                app: [id: 100, version: 7],
                configPage: [name: "selectTriggers", sections: [[input: [[name: "tCapab1", type: "enum", value: "Switch"]]]]]
            ])]
        }

        when: "Done on selectTriggers (no hrefContext — top-level page Done)"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            walkStep: [page: "selectTriggers", operation: "done"],
            confirm: true
        ])

        then: "the Done POST has root-only breadcrumbs and NO paramsForPage"
        result.success == true
        def donePost = posts.find {
            it.path == "/installedapp/update/json" && it.body?.containsKey("_action_previous")
        }
        donePost != null
        donePost.body["_action_previous"] == "Done"
        donePost.body.pageBreadcrumbs == '["mainPage"]'
        !donePost.body.containsKey("paramsForPage")

        and: "opResult.done carries the page name and a null parent (no hrefContext)"
        // The parent==null assertion guards against cross-contamination from
        // the sub-page branch (which sets parent=<fromPage>). A regression that
        // swapped the two branches would surface here.
        result.opResult?.done?.from == "selectTriggers"
        result.opResult?.done?.parent == null
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

    // Issue #178: removeAction / replaceActions can false-fail and post-commit, leaving
    // the rule with three IFs and two END-IFs (or any other unbalanced nesting) while
    // RM's paragraph render and label remain clean. The structural-balance walker on
    // actType.<N>/actSubType.<N> is the only signal that catches this class of damage.

    private List ifStructureSettings(List<Map> rows) {
        // rows = [[idx: N, actType: "condActs", actSubType: "getIfThen"], ...]
        def out = []
        rows.each { r ->
            out << [name: "actType.${r.idx}".toString(), value: r.actType]
            out << [name: "actSubType.${r.idx}".toString(), value: r.actSubType]
        }
        out
    }

    def "check_rule_health flags structural imbalance when an IF has no closing END-IF (issue #178)"() {
        given:
        enableReadOnly()
        // Three IFs + two END-IFs — reproduces the live state observed on the test hub
        // after removeAction(<inner-END-IF>) false-failed but committed post-response.
        def settings = ifStructureSettings([
            [idx: 9,  actType: "condActs", actSubType: "getIfThen"],
            [idx: 10, actType: "condActs", actSubType: "getIfThen"],
            [idx: 11, actType: "lockActs", actSubType: "getLULock"],
            [idx: 13, actType: "condActs", actSubType: "getIfThen"],
            [idx: 14, actType: "lockActs", actSubType: "getLULock"],
            [idx: 15, actType: "condActs", actSubType: "getEndIf"],
            [idx: 16, actType: "condActs", actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Unbalanced Rule", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues instanceof List
        result.structuralIssues.size() == 1
        result.structuralIssues[0].toString().contains("action 9")
        result.structuralIssues[0].toString().contains("never closed")
        result.issues.any { it.toString().contains("structural imbalance") }
    }

    def "check_rule_health flags an orphaned END-IF (more END-IFs than IFs)"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "condActs", actSubType: "getIfThen"],
            [idx: 2, actType: "lockActs", actSubType: "getLULock"],
            [idx: 3, actType: "condActs", actSubType: "getEndIf"],
            [idx: 4, actType: "condActs", actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Extra EndIf", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.any { it.toString().contains("orphaned closer") }
        result.structuralIssues.any { it.toString().contains("action 4") }
    }

    def "check_rule_health flags an orphaned End-Repeat closing nothing"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "lockActs", actSubType: "getLULock"],
            [idx: 2, actType: "repeatActs", actSubType: "getStopRepeat"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Orphan Stop", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.any { it.toString().contains("End Repeat") }
        result.structuralIssues.any { it.toString().contains("orphaned closer") }
    }

    def "check_rule_health flags ELSE-IF outside any IF block"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "condActs", actSubType: "getElseIf"],
            [idx: 2, actType: "lockActs", actSubType: "getLULock"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Stray ElseIf", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.any { it.toString().contains("ELSE-IF") }
        result.structuralIssues.any { it.toString().contains("outside any IF block") }
    }

    def "check_rule_health flags END-IF closing a Repeat block (mismatched closer)"() {
        given:
        enableReadOnly()
        // Repeat opens at action 1, but the closer at action 3 is END-IF (wrong kind).
        def settings = ifStructureSettings([
            [idx: 1, actType: "repeatActs", actSubType: "getRepeat"],
            [idx: 2, actType: "lockActs", actSubType: "getLULock"],
            [idx: 3, actType: "condActs", actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Mismatched", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.any { it.toString().contains("END-IF") && it.toString().contains("closes a Repeat block") }
    }

    def "check_rule_health reports ok on a balanced nested IF / IF / END-IF / IF / END-IF / END-IF"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "condActs", actSubType: "getIfThen"],
            [idx: 2, actType: "condActs", actSubType: "getIfThen"],
            [idx: 3, actType: "lockActs", actSubType: "getLULock"],
            [idx: 4, actType: "condActs", actSubType: "getEndIf"],
            [idx: 5, actType: "condActs", actSubType: "getIfThen"],
            [idx: 6, actType: "lockActs", actSubType: "getLULock"],
            [idx: 7, actType: "condActs", actSubType: "getEndIf"],
            [idx: 8, actType: "condActs", actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Balanced Nested", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    def "check_rule_health reports ok on IF / ELSE-IF / ELSE / END-IF sequence"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "condActs", actSubType: "getIfThen"],
            [idx: 2, actType: "lockActs", actSubType: "getLULock"],
            [idx: 3, actType: "condActs", actSubType: "getElseIf"],
            [idx: 4, actType: "lockActs", actSubType: "getLULock"],
            [idx: 5, actType: "condActs", actSubType: "getElse"],
            [idx: 6, actType: "lockActs", actSubType: "getLULock"],
            [idx: 7, actType: "condActs", actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "If Else Chain", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    def "check_rule_health reports ok on Repeat / End-Repeat sequence"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "repeatActs", actSubType: "getRepeat"],
            [idx: 2, actType: "lockActs", actSubType: "getLULock"],
            [idx: 3, actType: "repeatActs", actSubType: "getStopRepeat"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Repeat", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("manage_native_rules_and_apps", "check_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    // ---------- clone / export / import_native_app (appCloner trio) ----------
    // These three tools share the appCloner system app's wire format. Wire
    // format captured live via Chrome XHR sniffing on firmware 2.5.0.x:
    //
    //   GET /installedapp/sysAppApi/appCloner/app/<sourceId> -> 302
    //     Location: /apps/api/<clonerId>/app/<sourceId>?access_token=...
    //   POST /installedapp/btn  (cloneRuleButton or exportRuleButton)
    //   POST /installedapp/update/json  (form refresh after each click)
    //   POST /installedapp/update/json  (with _action_href_name|importRule|<idx>=)
    //   POST /installedapp/btn  (importNow — actual commit, clone+import only)
    //
    // Helpers shared by these tests construct minimal source/parent configs
    // and stub the cloner's GET-302 + POST responses.

    private String parentConfigJson(int parentId, List<Map> children) {
        JsonOutput.toJson([
            app: [id: parentId, label: "Rule Machine"],
            configPage: [name: "mainPage", title: "RM", install: true, error: null, sections: []],
            settings: [:],
            childApps: children
        ])
    }

    def "clone_native_app requires confirm=true"() {
        given: enableHubAdminWrite()

        when: script.toolCloneNativeApp([sourceAppId: 100])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("SAFETY CHECK FAILED")
    }

    def "clone_native_app throws when sourceAppId is missing"() {
        given: enableHubAdminWrite()

        when: script.toolCloneNativeApp([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains("sourceappid")
    }

    def "clone_native_app throws when source app config fetch returns empty"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/999') { params -> "" }

        when: script.toolCloneNativeApp([sourceAppId: 999, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("999")
        ex.message.toLowerCase().contains("not found")
    }

    def "clone_native_app drives the full appCloner wizard and returns the new appId"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Source Rule", [], 21) }
        // OAuth follow-up render. _appClonerInit now throws if this 404s — the
        // cloner state machine relies on it to seed state.cloneSource.
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context page</html>' }
        // configPage prime fetch + idx discovery target. Post-clone confirmation
        // page exposes importRule action_href at idx 0 (clone-path live behavior).
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        int parentCalls = 0
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentCalls++
            // Pre-clone snapshot: just the source. Post-commit: source + new clone.
            parentCalls <= 1
                ? parentConfigJson(21, [[id: 100, label: "Source Rule"]])
                : parentConfigJson(21, [[id: 100, label: "Source Rule"], [id: 250, label: "Source Rule clone"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            posts << [path: path, body: decodeForm(encodedBody)]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, confirm: true])

        then: "tool returns the discovered new appId"
        result.success == true
        result.sourceAppId == 100
        result.clonerAppId == 4242
        result.newAppId == 250

        and: "cloneRuleButton clicked TWICE on /installedapp/btn (state-machine race — first is silently dropped)"
        def cloneClicks = posts.findAll { it.path == "/installedapp/btn" && it.body?.name == "cloneRuleButton" }
        cloneClicks.size() == 2
        cloneClicks.every { it.body["settings[cloneRuleButton]"] == "clicked" && it.body["cloneRuleButton.type"] == "button" && it.body.id == "4242" }

        and: "importNow clicked TWICE on /installedapp/btn (same race; second commits)"
        def importNowClicks = posts.findAll { it.path == "/installedapp/btn" && it.body?.name == "importNow" }
        importNowClicks.size() == 2

        and: "page-navigation POST uses the discovered href idx (0 for clone confirmation page)"
        def navPost = posts.find { it.path == "/installedapp/update/json" && it.body?.containsKey("_action_href_name|importRule|0") }
        navPost != null
    }

    def "clone_native_app surfaces isError + error when child discovery returns null (soft-failure shape)"() {
        // Cloner fires but _appClonerDiscoverNewChild can't find the new
        // child (race, parent re-fetch lag, etc.). Pre-fix the return was
        // {success: false, newAppId: null, note: "..."} with no isError/error
        // — divergent from the rest of the file's error contract and
        // invisible to LLM callers that branch on isError.
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Source Rule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context page</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        // Parent NEVER acquires the new child — simulates discovery failure.
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentConfigJson(21, [[id: 100, label: "Source Rule"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, confirm: true])

        then: "soft-failure carries the isError + error envelope, not just success: false + note"
        result.success == false
        result.newAppId == null
        result.isError == true
        result.error instanceof String
        result.error.toLowerCase().contains("no new child appeared")
        result.note == result.error
    }

    def "clone_native_app rename writes settings[newName<sourceId>] before importNow"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Src", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        int parentCalls = 0
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentCalls++
            parentCalls <= 1
                ? parentConfigJson(21, [[id: 100, label: "Src"]])
                : parentConfigJson(21, [[id: 100, label: "Src"], [id: 500, label: "My Renamed"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            posts << [path: path, body: decodeForm(encodedBody)]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, newName: "My Renamed", confirm: true])

        then: "rename POSTed with the dynamic settings[newName<sourceId>] field"
        result.success == true
        result.newAppId == 500
        result.note?.contains("renamed to 'My Renamed'")
        def renamePost = posts.find { it.path == "/installedapp/update/json" && it.body?.containsKey("settings[newName100]") }
        renamePost != null
        renamePost.body["settings[newName100]"] == "My Renamed"
    }

    def "export_native_app pulls JSON from the cloner's form-refresh response"() {
        given:
        enableHubAdminWrite()
        def fakeJson = '{"deviceReplacements":{},"appReplacements":{"100":{"appLabel":"Source Rule"}}}'
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Source Rule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        // The post-exportRuleButton form refresh response carries the canonical
        // JSON in configPage.sections[].input[].filecontent — the cloner renders
        // it there session-keyed and only on the click-fired POST.
        def refreshResp = JsonOutput.toJson([
            configPage: [name: "main", sections: [
                [input: [[name: "ruleDownload", type: "download-text", filecontent: fakeJson]]]
            ]]
        ])
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            [status: 200, location: null, data: refreshResp]
        }

        when:
        def result = script.toolExportNativeApp([sourceAppId: 100])

        then:
        result.success == true
        result.sourceAppId == 100
        result.clonerAppId == 4242
        result.jsonContent == fakeJson
        result.contentLength == fakeJson.length()
    }

    def "export_native_app saveAs uploads to File Manager"() {
        given:
        enableHubAdminWrite()
        def fakeJson = '{"deviceReplacements":{},"appReplacements":{"100":{"appLabel":"X"}}}'
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "X", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        def refreshResp = JsonOutput.toJson([
            configPage: [name: "main", sections: [
                [input: [[name: "ruleDownload", type: "download-text", filecontent: fakeJson]]]
            ]]
        ])
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            [status: 200, location: null, data: refreshResp]
        }
        def uploaded = []
        script.metaClass.uploadHubFile = { String fn, byte[] bytes -> uploaded << [name: fn, len: bytes.length] }

        when:
        def result = script.toolExportNativeApp([sourceAppId: 100, saveAs: "x-export.json"])

        then:
        result.success == true
        result.savedAs == "x-export.json"
        uploaded.size() == 1
        uploaded[0].name == "x-export.json"
        uploaded[0].len == fakeJson.getBytes("UTF-8").length
    }

    def "import_native_app requires parentHintAppId + confirm"() {
        given: enableHubAdminWrite()

        when:
        script.toolImportNativeApp([jsonContent: '{"appReplacements":{"100":{}}}'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("SAFETY CHECK FAILED") || ex.message.toLowerCase().contains("parenthint")
    }

    def "import_native_app rejects non-JSON content"() {
        given: enableHubAdminWrite()

        when:
        script.toolImportNativeApp([jsonContent: 'not-json', parentHintAppId: 100, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains("not valid json") || ex.message.toLowerCase().contains("appreplacements")
    }

    def "import_native_app rejects JSON without appReplacements"() {
        given: enableHubAdminWrite()

        when:
        script.toolImportNativeApp([jsonContent: '{"foo":"bar"}', parentHintAppId: 100, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains("appreplacements")
    }

    def "import_native_app throws when neither jsonContent nor fromFile is provided"() {
        // Pre-issue-#204, a top-level anyOf in the inputSchema rejected the
        // no-payload call at the MCP boundary. The anyOf was removed because
        // Anthropic's input_schema validator HTTP-400s on top-level
        // anyOf/oneOf/allOf (first surfaced via Haiku 4.5), so the runtime
        // throw in toolImportNativeApp is now the sole guard for this case.
        given: enableHubAdminWrite()

        when:
        script.toolImportNativeApp([parentHintAppId: 100, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains("jsoncontent or fromfile")
    }

    def "import_native_app drives the cloner with settings[ruleUpload]= and finds the new appId"() {
        given:
        enableHubAdminWrite()
        def importJson = '{"deviceReplacements":{},"appReplacements":{"42":{"appLabel":"Source Rule","appTypeName":"Rule-5.1"}}}'
        // parentHint = an existing rule under parent 21
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "ExistingRule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        // Post-upload restore-or-import page; idx=55 matches the live-observed
        // session-scoped index on import (vs idx=0 on the clone confirmation page).
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 55) }
        int parentCalls = 0
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentCalls++
            parentCalls <= 1
                ? parentConfigJson(21, [[id: 100, label: "ExistingRule"]])
                : parentConfigJson(21, [[id: 100, label: "ExistingRule"], [id: 700, label: "Source Rule import"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            posts << [path: path, body: decodeForm(encodedBody)]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolImportNativeApp([jsonContent: importJson, parentHintAppId: 100, confirm: true])

        then: "import succeeds with newAppId discovered"
        result.success == true
        result.newAppId == 700
        result.originalSourceId == 42
        result.originalLabel == "Source Rule"

        and: "JSON staged via settings[ruleUpload] (urlencoded, NOT multipart) — exactly ONCE (a second pass is harmful per inline comment)"
        def stages = posts.findAll { it.path == "/installedapp/update/json" && it.body?.containsKey("settings[ruleUpload]") && it.body["settings[ruleUpload]"] == importJson }
        stages.size() == 1

        and: "navigate POST uses the discovered idx=55 (post-upload page) — NOT the clone-path fallback of 0"
        def navPost = posts.find { it.path == "/installedapp/update/json" && it.body?.containsKey("_action_href_name|importRule|55") }
        navPost != null
        !posts.any { it.path == "/installedapp/update/json" && it.body?.containsKey("_action_href_name|importRule|0") }

        and: "import path uses the LOCAL config URL as referrer, not the OAuth source-context URL — verified live: OAuth referrer trips the cloner's session check"
        stages[0].body.referrer != null
        !stages[0].body.referrer.contains("apps/api")

        and: "importNow click fired the actual commit"
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "importNow" }

        and: "the importRule form refresh uses the ORIGINAL source id (42) as the newName field's <sourceId>, not parentHintAppId (100). With no newName argument the field is still emitted (matches UI behaviour) but its value is empty."
        def importRulePosts = posts.findAll { it.path == "/installedapp/update/json" && it.body?.currentPage == "importRule" }
        importRulePosts.any { it.body?.containsKey("settings[newName42]") }
        importRulePosts.every { it.body?["settings[newName42]"] == "" }
        !importRulePosts.any { it.body?.containsKey("settings[newName100]") }
    }

    def "import_native_app surfaces isError + error when child discovery returns null (soft-failure shape)"() {
        // Wizard fires but _appClonerDiscoverNewChild can't diff a new child
        // under the parent. Pre-fix this returned {success: false, newAppId:
        // null, note: "..."} with no isError/error fields. LLM callers that
        // branch on isError saw nothing actionable. Mirrors the clone test
        // immediately above — same contract, same enforcement.
        given:
        enableHubAdminWrite()
        def importJson = '{"deviceReplacements":{},"appReplacements":{"42":{"appLabel":"Source Rule","appTypeName":"Rule-5.1"}}}'
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "ExistingRule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 55) }
        // Parent NEVER acquires the new child — simulates discovery failure.
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentConfigJson(21, [[id: 100, label: "ExistingRule"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolImportNativeApp([jsonContent: importJson, parentHintAppId: 100, confirm: true])

        then: "soft-failure carries the isError + error envelope, not just success: false + note"
        result.success == false
        result.newAppId == null
        result.isError == true
        result.error instanceof String
        result.error.toLowerCase().contains("no new child appeared")
        result.note == result.error
    }

    def "import_native_app preserves backslash-escapes in settings[ruleUpload] (HTTPBuilder Map encoder mangles them)"() {
        given:
        enableHubAdminWrite()
        // Canonical exports embed multi-select enum values as JSON-encoded
        // strings: `"value":"[\"Events\",...]"`. HTTPBuilder's Map auto-encoder
        // strips the leading `\\` from `\\"` sequences in form-urlencoded
        // bodies — must go through hubInternalPostFormRaw with manual
        // URL-encoding (the helper introduced in this PR).
        def importJson = '{"appReplacements":{"42":{"appLabel":"X"}},"appData":{"42":{"appSettings":[{"name":"logging","type":"enum","multiple":true,"value":"[\\"Events\\",\\"Triggers\\"]"}]}}}'
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "ExistingRule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 55) }
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentConfigJson(21, [[id: 100, label: "ExistingRule"], [id: 700, label: "X import"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        // /installedapp/update/json (the form refresh that carries settings[ruleUpload])
        // MUST go through hubInternalPostFormRaw — the Map encoder strips
        // backslashes. /btn POSTs (button clicks) carry no JSON and are fine
        // through the Map path; let those pass.
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                throw new IllegalStateException("cloner /update/json POSTs must use hubInternalPostFormRaw — the Map encoder mangles backslashes")
            }
            [status: 200, location: null, data: '{"status":"success"}']
        }
        def rawBodies = []
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            rawBodies << encodedBody
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        script.toolImportNativeApp([jsonContent: importJson, parentHintAppId: 100, confirm: true])

        then: "the staged ruleUpload value, after URL-decoding, is byte-equal to the input JSON — backslash-escapes preserved"
        def stagingBody = rawBodies.find { it.contains("settings%5BruleUpload%5D=") }
        stagingBody != null
        decodeForm(stagingBody)["settings[ruleUpload]"] == importJson
    }

    def "export_native_app collapses appCloner's over-escaped multi-select values"() {
        given:
        enableHubAdminWrite()
        // Hubitat appCloner emits `\\"` (2 backslashes + quote) where canonical
        // JSON requires `\"` — the result is malformed and won't round-trip
        // back into import. _appClonerExtractJsonFromResponse collapses the
        // `\\"` triplets back to canonical form. Without the fix, the import
        // returned by export would JSON-parse-fail on read.
        def overEscapedFilecontent = '{"appReplacements":{"100":{"appLabel":"X"}},"appData":{"100":{"appSettings":[{"name":"logging","value":"[\\\\"Events\\\\"]"}]}}}'
        def expectedCanonical       = '{"appReplacements":{"100":{"appLabel":"X"}},"appData":{"100":{"appSettings":[{"name":"logging","value":"[\\"Events\\"]"}]}}}'
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "X", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        // The post-exportRuleButton form refresh is what carries the over-escaped
        // filecontent — that's the response we need to fix in-flight.
        def refreshResp = JsonOutput.toJson([
            configPage: [name: "main", sections: [
                [input: [[name: "ruleDownload", type: "download-text", filecontent: overEscapedFilecontent]]]
            ]]
        ])
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            [status: 200, location: null, data: refreshResp]
        }

        when:
        def result = script.toolExportNativeApp([sourceAppId: 100])

        then: "returned jsonContent is canonical (single-escape) and JSON-parses round-trip"
        result.success == true
        result.jsonContent == expectedCanonical
        // Round-trip parse: must produce a real Map without throwing.
        def parsed = new groovy.json.JsonSlurper().parseText(result.jsonContent)
        parsed instanceof Map
        parsed.appData["100"].appSettings[0].value == '["Events"]'
    }

    def "import_native_app refuses parentHintAppId that has no parent (no diff target -> would silently false-fail)"() {
        given:
        enableHubAdminWrite()
        // Top-level app (parentAppId == null) — we can't diff children to spot
        // the new rule, so we'd return success:false even on a successful
        // import. Refuse up front instead.
        hubGet.register('/installedapp/configure/json/200') { params ->
            JsonOutput.toJson([
                app: [id: 200, name: "Notifier", label: "Notifier",
                      installed: true,
                      appType: [name: "Notifier", namespace: "hubitat"]],
                configPage: [name: "main", sections: []],
                settings: [:],
                childApps: []
            ])
        }

        when:
        script.toolImportNativeApp([
            jsonContent: '{"appReplacements":{"42":{"appLabel":"X"}}}',
            parentHintAppId: 200,
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("no numeric parentAppId") || ex.message.toLowerCase().contains("parent")
    }

    def "_appClonerCommitImportRule throws when action_href idx never appears (no silent fallback to 0)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Src", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        // Empty sections — the regex never finds `_action_href_name|importRule|N`.
        hubGet.register('/installedapp/configure/json/4242/main') { params -> '{"configPage":{"sections":[]}}' }
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentConfigJson(21, [[id: 100, label: "Src"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        script.toolCloneNativeApp([sourceAppId: 100, confirm: true])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("action_href not found")
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
    // but a live scenario rendering as "Broken Condition" again.
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

    def "backup-before-write is a hard gate: a backup failure aborts the call BEFORE any write/click POST is issued"() {
        // Every non-discover update_native_app path runs _rmBackupRuleSnapshot
        // before dispatching to a write helper (settings/button/addTrigger/
        // addAction/removeAction/clearActions/moveAction/walkStep/etc.).
        // If the snapshot throws (config fetch fails, uploadHubFile fails,
        // serialization fails) the dispatcher must propagate the failure
        // with NO downstream wire effect — otherwise a future hub-side
        // problem in the snapshot pipeline would silently leave callers
        // with a half-mutated rule and no restore point.
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        // Force the backup to fail at the uploadHubFile step — _rmBackupRuleSnapshot
        // re-throws as IllegalArgumentException("Cannot save backup file ...").
        script.metaClass.uploadHubFile = { String fn, byte[] b ->
            throw new RuntimeException("simulated hub File Manager failure")
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when: "a non-discover write path tries to run with a broken backup pipeline"
        script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Switch", deviceIds: [8], state: "on"],
            confirm: true
        ])

        then: "the call throws — backup is a non-recoverable gate"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("backup")

        and: "no write POST and no button-click POST escaped the gate"
        // The exact strings here are the two endpoints that mutate rule state.
        // Any post-backup wiring that fires before backup verification would
        // hit one of these.
        !posts.any { it.path == "/installedapp/update/json" }
        !posts.any { it.path == "/installedapp/btn" }
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

    // ---------- addTrigger success/partial semantic matrix ----------

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
        // Matrix case 1: all settings land, trigger bakes, no broken label.
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
        // Matrix case 2: settings land (applied non-empty) but mainPage still shows
        // "Define Triggers" -- trigger skeleton written but row didn't commit.
        // Contract: success=true (something happened), partial=true (needs repair).
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
        // Matrix case 3: settings land but health check returns hasBrokenLabel=true
        // (label contains *BROKEN*). Contract: success=true (something written),
        // partial=true (needs repair).
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
        // Matrix case (pre-existing broken state): a prior trigger on the rule is
        // already broken (health check returns brokenMarkers=["**Broken Trigger**"]).
        // The new trigger commits successfully, but the overall rule is in a known-bad
        // state -- surface as partial=true so the LLM sees it without a separate
        // check_rule_health call.
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
        // Matrix case 4: hard failure -- API error present in the selectTriggers
        // configPage.error field. err != null -> success=false regardless of applied.
        // Uses a static schema (no render shift) so applied stays empty; the
        // test verifies that err alone drives success=false.
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
        // Matrix case 5: hard failure -- applied stays empty because every write
        // produces no render-hash shift (hub ignores all writes).
        // success=!err && !applied.isEmpty() => false. Uses a no-deviceIds
        // capability (Mode) with a static schema -- no shift means every write
        // routes to skipped. The flow navigates normally but nothing accumulates
        // in applied.
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

    // ---------- addTrigger auto-updateRule parity with addAction ----------

    def "addTrigger single-trigger path auto-fires updateRule after successful commit"() {
        // Single addTrigger should fire updateRule automatically so subscriptions
        // populate without a separate tool call. Mirrors the addAction pattern.
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
        // When _rmAddTrigger returns success=false (nothing committed), the wrapper
        // must NOT fire updateRule -- the extra hub round-trip is wasteful and
        // misleading. Uses a static (non-shifting) schema so applied stays empty.
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
        // When success=true but partial=true (trigger row exists but not all
        // fields landed), the wrapper SHOULD still fire updateRule. The trigger
        // IS in the rule and subscriptions should bake from whatever committed.
        // Uses triggerNotBaked scenario (mainPage shows "Define Triggers") to
        // produce partial=true.
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
        // addTriggers[] bulk path must NOT fire updateRule per item -- that
        // would cause N reinits. The bulk wrapper fires one updateRule after all
        // triggers commit. The single-trigger auto-fire must NOT activate on the
        // bulk path (it lives only in the addTrigger wrapper). Two-trigger bulk:
        // updateRule count must be exactly 1.
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
        // conditional=true sets isCondTrig.<N>=true on the trigger row and
        // _rmBuildCondition drives the condition sub-wizard internally, but
        // _rmAddTrigger still commits and returns success=true before handing back.
        // The auto-updateRule guard (`trigResult?.success != false`) does NOT inspect
        // `conditional` -- updateRule fires for conditional triggers just as for
        // plain ones. This test pins that behavior.
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

    def "addTrigger.condition Map drives the inline condition sub-wizard (isCondTrig+condTrig+rCapab/rDev/state writes land)"() {
        // The `condition` Map shape is distinct from the `conditional` boolean
        // flag. When supplied it drives _rmBuildCondition inline:
        //   isCondTrig.<idx>=true   (opens conditional editor)
        //   condTrig.<idx>="a"      (selects "Add new condition")
        //   rCapab_<idx>=<cap>      (condition capability)
        //   rDev_<idx>=<deviceIds>  (condition devices)
        //   state_<idx>=<state>     (condition state)
        //   hasAll click            (commits the condition; the helper returns
        //                           idx as the conditionId by construction --
        //                           RM allocates sequentially so id == idx)
        // Then idx is bumped (condition wizard consumed the slot) and the
        // outer trigger writes tCapab/tDev/tstate at idx+1, finally writing
        // isCondTrig.<idx+1>=true + condTrig.<idx+1>=<conditionId> to bind.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // Progressive paragraph counter so _rmWriteSettingOnPage's render-hash
        // diff fires on every successive write (otherwise a static schema
        // makes every write route to settingsSkipped with reason=silent_rejection).
        def fetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            fetchSeq++
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 // Condition wizard slot at idx 1
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "condTrig.1", type: "enum", options: ["a", "b"]],
                                 [name: "rCapab_1", type: "enum", options: ["Switch", "Motion"]],
                                 [name: "rDev_1", type: "capability.switch", multiple: true],
                                 [name: "state_1", type: "enum", options: ["on", "off"]],
                                 // Bound trigger at idx 2 (post-condition bump)
                                 [name: "tCapab2", type: "enum", options: ["Switch"]],
                                 [name: "tDev2", type: "capability.switch", multiple: true],
                                 [name: "tstate2", type: "enum", options: ["on", "off"]],
                                 [name: "isCondTrig.2", type: "bool"],
                                 [name: "condTrig.2", type: "enum", options: ["1"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"S2"}' }

        when: "addTrigger with an explicit condition Map (Motion-active gates Switch-on)"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Switch",
                deviceIds: [8],
                state: "on",
                condition: [capability: "Motion", deviceIds: [9], state: "active"]
            ],
            confirm: true
        ])

        then: "the condition-wizard writes go through at idx 1 (the consumed slot)"
        // isCondTrig.1=true opens the conditional editor at the original idx.
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[isCondTrig.1]"]?.toString() == "true"
        }
        // condTrig.1="a" picks "Add new condition" from the dropdown.
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[condTrig.1]"]?.toString() == "a"
        }
        // rCapab_1=Motion lands the condition capability.
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[rCapab_1]"]?.toString() == "Motion"
        }
        // rDev_1 carries the condition's device ID (CSV per multiple=true contract).
        // Exact-equality wire-value check (W-N.34): bare CSV scalar "9". A substring
        // .contains("9") would also match "9999"/"19"/"99" by coincidence.
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[rDev_1]"]?.toString() == "9"
        }
        // state_1=active is the condition's compared state.
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[state_1]"]?.toString() == "active"
        }
        // hasAll click commits the condition (auto-assigns id) and advances
        // the wizard to the next trigger index.
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "hasAll" }

        and: "the outer trigger then commits at idx 2 (post-bump) and binds to the saved condition"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[tCapab2]"]?.toString() == "Switch"
        }
        // The bound trigger's device + state must also reach the schema —
        // without these the trigger row commits as "Broken Trigger" and the
        // earlier condition write is wasted work.
        // Exact-equality wire-value check (W-N.34): bare CSV scalar "8".
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[tDev2]"]?.toString() == "8"
        }
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[tstate2]"]?.toString() == "on"
        }
        // The post-tCapab finalize binds isCondTrig.2=true + condTrig.2=<conditionId>
        // so the trigger references the condition we just built.
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[isCondTrig.2]"]?.toString() == "true"
        }

        and: "the wizard write ordering is preserved (RM is order-sensitive)"
        // RM 5.1 silently commits a broken trigger if these phases interleave:
        //   - condition setup MUST land before the condition's hasAll click
        //   - the condition's hasAll click MUST land before the bumped-idx
        //     tCapab2 writes (otherwise the trigger fields target the
        //     condition's slot)
        //   - tCapab2 MUST land before the trigger's final hasAll click
        // Two hasAll clicks fire in this flow -- one to commit the condition
        // (findIndexOf = first match), one to commit the trigger (findLast).
        def condSetupIdx = posts.findIndexOf {
            it.path == "/installedapp/update/json" &&
            it.body["settings[rCapab_1]"]?.toString() == "Motion"
        }
        def condHasAllIdx = posts.findIndexOf {
            it.path == "/installedapp/btn" && it.body?.name == "hasAll"
        }
        def trigCapabIdx = posts.findIndexOf {
            it.path == "/installedapp/update/json" &&
            it.body["settings[tCapab2]"]?.toString() == "Switch"
        }
        def trigHasAllIdx = posts.findLastIndexOf {
            it.path == "/installedapp/btn" && it.body?.name == "hasAll"
        }
        condSetupIdx >= 0 && condHasAllIdx >= 0 && trigCapabIdx >= 0 && trigHasAllIdx >= 0
        condSetupIdx < condHasAllIdx
        condHasAllIdx < trigCapabIdx
        trigCapabIdx < trigHasAllIdx

        and: "the call returns success"
        result.success == true
    }

    def "addTrigger Variable capability writes xVar<N> picker and ReltDev<N> comparator (no deviceIds)"() {
        // Hub Variable triggers don't take deviceIds -- the wizard exposes
        // xVar<N> (variable picker enum) populated from hub variables, and
        // ReltDev<N> for the comparator. Verified live 2026-05-17: with no
        // typed `variable` field the request fails with a clear message,
        // pointing the caller at list_variables.
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
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "tCapab1", type: "enum", options: ["Switch", "Variable"]],
                                 [name: "xVar1", type: "enum", options: ["myVar", "otherVar"]],
                                 [name: "ReltDev1", type: "enum", options: ["=", "≠", "<", ">", "<=", ">=", "in", "*changed*"]],
                                 [name: "tstate1", type: "enum", options: ["*changed*"]],
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "addTrigger Variable with typed variable + comparator"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Variable", variable: "myVar", comparator: "*changed*"],
            confirm: true
        ])

        then: "xVar1 carries the typed variable name"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[xVar1]"]?.toString() == "myVar"
        }

        and: "ReltDev1 carries the comparator"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[ReltDev1]"]?.toString() == "*changed*"
        }

        and: "no tDev1 write -- Variable triggers don't take device IDs"
        !posts.any {
            it.path == "/installedapp/update/json" && it.body.containsKey("settings[tDev1]")
        }

        and: "trigger commits successfully"
        result.success == true
    }

    def "addTrigger Variable without `variable` field fails with a list_variables pointer"() {
        // Caller forgot the variable name. The helper should refuse with a
        // clear error pointing at list_variables instead of letting the
        // wizard silently commit a half-baked broken trigger.
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
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "tCapab1", type: "enum", options: ["Variable"]],
                                 [name: "xVar1", type: "enum", options: ["myVar"]],
                                 [name: "isCondTrig.1", type: "bool"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Variable"],
            confirm: true
        ])

        then: "success=false with a pointer at list_variables in the error message"
        result.success == false
        result.error?.toString()?.contains("variable")
        result.error?.toString()?.contains("list_variables")
    }

    def "addTrigger Variable conditional A!=B writes xVar_<N>+RelrDev_<N>+isVar_<N>+xVarR_<N> via typed condition fields"() {
        // Variable A changed ONLY IF A != B: condition sub-wizard inside
        // selectTriggers exposes RelrDev_<N> as the comparator field
        // (shared with the doActPage/STPage condition wizards; the older
        // compareCond_<N> name silently skipped on every wizard page).
        // isVar_<N>=true unlocks the right-hand variable picker xVarR_<N>.
        // All four typed fields must land for the rule to render correctly
        // in Hubitat. Verified live on firmware 2.5.0.135 (2026-05-17).
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
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 // Condition wizard at slot 1
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "condTrig.1", type: "enum", options: ["a", "b"]],
                                 [name: "rCapab_1", type: "enum", options: ["Variable", "Switch"]],
                                 [name: "xVar_1", type: "enum", options: ["A", "B"]],
                                 [name: "RelrDev_1", type: "enum", options: ["=", "≠", "<", ">", "<=", ">="]],
                                 [name: "isVar_1", type: "bool"],
                                 [name: "xVarR_1", type: "enum", options: ["A", "B"]],
                                 // Conditional trigger at slot 2 (post-condition bump)
                                 [name: "tCapab2", type: "enum", options: ["Variable"]],
                                 [name: "xVar2", type: "enum", options: ["A", "B"]],
                                 [name: "ReltDev2", type: "enum", options: ["*changed*"]],
                                 [name: "isCondTrig.2", type: "bool"],
                                 [name: "condTrig.2", type: "enum", options: ["1"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "addTrigger Variable with conditional A!=B comparison"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Variable",
                variable: "A",
                comparator: "*changed*",
                condition: [
                    capability: "Variable",
                    variable: "A",
                    comparator: "!=",
                    compareToVariable: "B"
                ]
            ],
            confirm: true
        ])

        then: "condition writes xVar_1 (source variable on the condition side) and selects 'Add new condition'"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[xVar_1]"]?.toString() == "A"
        }
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[condTrig.1]"]?.toString() == "a"
        }

        and: "RelrDev_1 carries the comparator with `!=` mapped to Unicode `≠`"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[RelrDev_1]"]?.toString() == "≠"
        }

        and: "isVar_1=true unlocks the right-hand variable picker"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[isVar_1]"]?.toString() == "true"
        }

        and: "xVarR_1 carries the compareToVariable name"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[xVarR_1]"]?.toString() == "B"
        }

        and: "compareCond_1 is NOT written -- condition wizards use RelrDev_<N> on every page"
        !posts.any {
            it.path == "/installedapp/update/json" && it.body.containsKey("settings[compareCond_1]")
        }

        and: "the outer trigger commits at idx 2 (post-condition bump) with the typed xVar2 picker and its own comparator"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[tCapab2]"]?.toString() == "Variable"
        }
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[xVar2]"]?.toString() == "A"
        }
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[ReltDev2]"]?.toString() == "*changed*"
        }

        and: "isCondTrig.2=true + condTrig.2=1 binds the trigger to the saved condition (separate writes in helper)"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[isCondTrig.2]"]?.toString() == "true"
        }
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[condTrig.2]"]?.toString() == "1"
        }

        and: "call returns success"
        result.success == true
    }

    def "addTrigger rawSettings expands the `@N` token to the trigger index"() {
        // rawSettings keys with `@N` are substituted with the auto-assigned
        // trigger index, mirroring addAction's escape hatch. Without
        // expansion the literal `@N` is written verbatim and the wizard
        // silently skips it (key not in the live schema).
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
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "tCapab1", type: "enum", options: ["Variable"]],
                                 [name: "xVar1", type: "enum", options: ["foo"]],
                                 [name: "ReltDev1", type: "enum", options: ["*changed*"]],
                                 [name: "customField1", type: "text"],
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "addTrigger with rawSettings using @N placeholder"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Variable",
                variable: "foo",
                comparator: "*changed*",
                rawSettings: ["customField@N": "extraValue"]
            ],
            confirm: true
        ])

        then: "customField1 (with @N expanded to 1) is written"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[customField1]"]?.toString() == "extraValue"
        }

        and: "the literal `customField@N` key is NOT written"
        !posts.any {
            it.path == "/installedapp/update/json" && it.body.containsKey("settings[customField@N]")
        }

        and: result.success == true
    }

    def "addTrigger Variable conditional A!=B preserves write order (rCapab_<N> -> xVar_<N>, isVar_<N> -> xVarR_<N>)"() {
        // Live RM exposes the condition fields progressively:
        // rCapab_<N>=Variable unlocks xVar_<N>, and isVar_<N>=true unlocks
        // xVarR_<N>. The test fixtures here return all fields up-front so a
        // code regression that reorders writes (e.g. xVar_<N> before
        // rCapab_<N>) wouldn't break the schema check -- but on the live
        // hub it would silently no-op. Pin the order so the order
        // dependency is captured.
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
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "condTrig.1", type: "enum", options: ["a", "b"]],
                                 [name: "rCapab_1", type: "enum", options: ["Variable"]],
                                 [name: "xVar_1", type: "enum", options: ["A", "B"]],
                                 [name: "RelrDev_1", type: "enum", options: ["=", "≠"]],
                                 [name: "isVar_1", type: "bool"],
                                 [name: "xVarR_1", type: "enum", options: ["A", "B"]],
                                 [name: "tCapab2", type: "enum", options: ["Variable"]],
                                 [name: "xVar2", type: "enum", options: ["A", "B"]],
                                 [name: "ReltDev2", type: "enum", options: ["*changed*"]],
                                 [name: "isCondTrig.2", type: "bool"],
                                 [name: "condTrig.2", type: "enum", options: ["1"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Variable",
                variable: "A",
                comparator: "*changed*",
                condition: [capability: "Variable", variable: "A", comparator: "!=", compareToVariable: "B"]
            ],
            confirm: true
        ])

        then: "rCapab_1 (condition capability) is written before xVar_1 (condition variable picker)"
        def rCapabIdx = posts.findIndexOf {
            it.path == "/installedapp/update/json" && it.body["settings[rCapab_1]"]?.toString() == "Variable"
        }
        def xVarCondIdx = posts.findIndexOf {
            it.path == "/installedapp/update/json" && it.body["settings[xVar_1]"]?.toString() == "A"
        }
        rCapabIdx >= 0 && xVarCondIdx >= 0
        rCapabIdx < xVarCondIdx

        and: "isVar_1=true is written before xVarR_1 (right-hand picker only appears after isVar_1)"
        def isVarIdx = posts.findIndexOf {
            it.path == "/installedapp/update/json" && it.body["settings[isVar_1]"]?.toString() == "true"
        }
        def xVarRIdx = posts.findIndexOf {
            it.path == "/installedapp/update/json" && it.body["settings[xVarR_1]"]?.toString() == "B"
        }
        isVarIdx >= 0 && xVarRIdx >= 0
        isVarIdx < xVarRIdx
    }

    def "addTrigger Variable condition with numeric `value` writes state_<N> and does NOT touch isVar_<N>/xVarR_<N>"() {
        // The compare-to-value path (Variable A > 50) is distinct from the
        // compare-to-variable path. When the caller passes `value` instead
        // of `compareToVariable`, the helper must write state_<N> and leave
        // isVar_<N>/xVarR_<N> alone so the wizard renders "A > 50" rather
        // than "A > <empty variable>".
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
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "condTrig.1", type: "enum", options: ["a"]],
                                 [name: "rCapab_1", type: "enum", options: ["Variable"]],
                                 [name: "xVar_1", type: "enum", options: ["A"]],
                                 [name: "RelrDev_1", type: "enum", options: ["=", ">", "<"]],
                                 [name: "state_1", type: "number"],
                                 [name: "tCapab2", type: "enum", options: ["Variable"]],
                                 [name: "xVar2", type: "enum", options: ["A"]],
                                 [name: "ReltDev2", type: "enum", options: ["*changed*"]],
                                 [name: "isCondTrig.2", type: "bool"],
                                 [name: "condTrig.2", type: "enum", options: ["1"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Variable",
                variable: "A",
                comparator: "*changed*",
                condition: [capability: "Variable", variable: "A", comparator: ">", value: 50]
            ],
            confirm: true
        ])

        then: "state_1 carries the numeric value"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[state_1]"]?.toString() == "50"
        }

        and: "isVar_1 is NOT written -- value-RHS path leaves the compare-to-variable toggle alone"
        !posts.any {
            it.path == "/installedapp/update/json" && it.body.containsKey("settings[isVar_1]")
        }

        and: "xVarR_1 is NOT written"
        !posts.any {
            it.path == "/installedapp/update/json" && it.body.containsKey("settings[xVarR_1]")
        }

        and: result.success == true
    }

    def "condition.variable is required when condition.capability='Variable'"() {
        // Symmetric guard with the trigger-side missing-variable error.
        // The condition can't be built without a hub variable name on the
        // left-hand side, so refuse early with a list_variables pointer.
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
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "condTrig.1", type: "enum", options: ["a"]],
                                 [name: "rCapab_1", type: "enum", options: ["Variable"]],
                                 [name: "xVar_1", type: "enum", options: ["A"]],
                                 [name: "tCapab2", type: "enum", options: ["Variable"]]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Variable",
                variable: "A",
                comparator: "*changed*",
                condition: [capability: "Variable", comparator: "!=", compareToVariable: "B"]
            ],
            confirm: true
        ])

        then: "success=false with an actionable error pointing at list_variables"
        result.success == false
        result.error?.toString()?.contains("condition.variable")
        result.error?.toString()?.contains("list_variables")
    }

    def "condition rawSettings expands @N to the condition index"() {
        // Symmetric escape hatch with the trigger-side @N expansion. Pins
        // _rmBuildCondition's rawSettings substitution so it doesn't get
        // dropped in a future refactor.
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
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "condTrig.1", type: "enum", options: ["a"]],
                                 [name: "rCapab_1", type: "enum", options: ["Variable"]],
                                 [name: "xVar_1", type: "enum", options: ["A"]],
                                 [name: "condExtra_1", type: "text"],
                                 [name: "tCapab2", type: "enum", options: ["Variable"]],
                                 [name: "xVar2", type: "enum", options: ["A"]],
                                 [name: "ReltDev2", type: "enum", options: ["*changed*"]],
                                 [name: "isCondTrig.2", type: "bool"],
                                 [name: "condTrig.2", type: "enum", options: ["1"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson(100, "r", true) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Variable",
                variable: "A",
                comparator: "*changed*",
                condition: [
                    capability: "Variable",
                    variable: "A",
                    rawSettings: ["condExtra_@N": "raw-value"]
                ]
            ],
            confirm: true
        ])

        then: "condExtra_1 (with @N expanded to 1) is written"
        posts.any {
            it.path == "/installedapp/update/json" &&
            it.body["settings[condExtra_1]"]?.toString() == "raw-value"
        }

        and: "the literal `condExtra_@N` key is NOT written"
        !posts.any {
            it.path == "/installedapp/update/json" && it.body.containsKey("settings[condExtra_@N]")
        }

        and: result.success == true
    }

    def "_rmNormalizeComparator maps ASCII aliases to Unicode glyphs and passes Unicode through"() {
        // Unit-level coverage of every branch in the alias table so a
        // refactor that drops one mapping (or breaks the pass-through)
        // doesn't slip past the end-to-end specs.
        expect:
        script._rmNormalizeComparator(input) == expected

        where:
        input  || expected
        "!="   || "≠"
        "<>"   || "≠"
        "=="   || "="
        "≠"    || "≠"
        "="    || "="
        ">"    || ">"
        "<"    || "<"
        ">="   || ">="
        "<="   || "<="
        "*changed*" || "*changed*"
        null   || null
    }

    def "addTrigger discover schema includes the Variable capability entry"() {
        // The Variable conditional pattern is non-obvious; the discover
        // schema is the surface the LLM caller reads to learn how to drive
        // the wizard. Pin its presence so future refactors of the schema
        // don't silently drop the entry.
        given: 'discover-mode short-circuits before any hub mutation'
        enableHubAdminWrite()

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [discover: true],
            confirm: true
        ])

        then: 'Variable is in the capability list with the typed condition fields documented'
        def caps = result?.capabilities as List
        caps != null
        def varEntry = caps.find { (it as Map)?.name == "Variable" }
        varEntry != null
        // Required field must be `variable` (matches the typed-helper contract)
        ((varEntry.requiredFields as List).collect { (it as Map).name } as List).contains("variable")
        // Caller needs to learn about compareToVariable from the discover output
        (varEntry as Map).toString().contains("compareToVariable")
    }

    // ---------- addAction success/partial semantic matrix ----------

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
        // Matrix case 1: all settings land, action bakes.
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
        // Matrix case 2: settings land (applied non-empty) but mainPage still shows
        // "Define Actions". Contract: success=true, partial=true.
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
        // Matrix case 4: hard failure -- API error present in the selectActions
        // configPage.error field. err != null -> success=false. Uses a static
        // doActPage schema so writes silently reject; err alone drives success=false.
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
        // Matrix case 5: hard failure -- applied stays empty because every write
        // produces no observable schema shift. The 'log' capability has no field
        // writes beyond actType/actSubType; a static doActPage schema with no
        // render shift causes both to route to skipped rather than applied.
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
        // On doActPage the comparator field is RelrDev_<N> ("Relr", condition-wizard
        // naming), NOT compareCond_<N> (selectTriggers-only) and NOT ReltDev_<N>
        // (trigger-row numeric comparator on selectTriggers). Using the wrong field
        // name causes compareCond_1 to silently reject and the condition to render
        // as "wickFilterLife null" (broken). RelrDev_1 must land in settingsApplied.
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

    // ---------- Custom Attribute mutual-field validation (W-N.34-custattr-symmetry) ----------
    //
    // attribute WITHOUT comparator and comparator WITHOUT attribute produce distinct,
    // differentiating error messages. Both paths must fail-loud and the error text
    // must name the missing sibling field so agents know exactly what to add.
    // Both-ways pending (orchestrator).

    def "addRequiredExpression: Custom Attribute attribute-without-comparator fails with differentiating error"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Custom Attribute"]],
                                 [name: "hasAll",   type: "button"]
                             ], paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "attribute is supplied but comparator is missing"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute",
                deviceIds: [8],
                attribute: "humidity"
                // comparator intentionally omitted
            ]]],
            confirm: true
        ])

        then: "fails with an error mentioning attribute AND the missing comparator"
        result.success == false
        result.error?.toString()?.contains("attribute='humidity'")
        result.error?.toString()?.contains("comparator was not provided")
    }

    def "addRequiredExpression: Custom Attribute comparator-without-attribute fails with differentiating error"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Custom Attribute"]],
                                 [name: "hasAll",   type: "button"]
                             ], paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "comparator is supplied but attribute is missing"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute",
                deviceIds: [8],
                comparator: "="
                // attribute intentionally omitted
            ]]],
            confirm: true
        ])

        then: "fails with an error mentioning comparator AND the missing attribute"
        result.success == false
        result.error?.toString()?.contains("comparator='='")
        result.error?.toString()?.contains("attribute was not provided")
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
        // The old code wrote value_${cIdx} for cond.value but STPage only exposes
        // state_${cIdx}. After the fix, cond.value falls through to condStateOrValue
        // and writes state_${cIdx}. RelrDev_1 and state_1 are gated on progressive
        // disclosure (rCustomAttr gates RelrDev; RelrDev gates state) -- this is the
        // same sequential write order that makes the Custom Attribute path correct on
        // the real hub.
        given:
        enableHubAdminWrite()
        def rCustomAttrWritten = false
        def relrWritten = false
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        if (fn == "rCustomAttr_1") rCustomAttrWritten = true
                        if (fn == "RelrDev_1") relrWritten = true
                    }
                }
            }
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
                                                 description: "IF wickFilterLife < 20 THEN"]]]]],
                settings: [:], childApps: []
            ])
        }
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            // Progressive disclosure: RelrDev_1 gated on rCustomAttr_1; state_1 gated on RelrDev_1.
            def inputs = [
                [name: "cond",          type: "enum",              options: ["a": "New condition"]],
                [name: "rCapab_1",      type: "enum",              options: ["Custom Attribute", "Switch"]],
                [name: "rDev_1",        type: "capability.sensor", multiple: true],
                [name: "rCustomAttr_1", type: "enum",              options: ["wickFilterLife", "battery"]],
                [name: "hasAll",        type: "button"],
                [name: "doneST",        type: "button"]
            ]
            if (rCustomAttrWritten) {
                inputs = inputs + [[name: "RelrDev_1", type: "enum",
                                    options: ["<", "<=", ">", ">=", "=", "!="]]]
            }
            if (relrWritten) {
                inputs = inputs + [[name: "state_1", type: "number"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
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

    def "addAction runCommand single literal parameter: moreParams click allocates slot P=2 (live-verified)"() {
        // Live-ops verified: RM always requires a moreParams click to allocate
        // a param slot. The RM-assigned param number P starts at 2 for the first
        // param -- cpType1 is never used. The stub models the real reveal sequence:
        //   pre-click: moreParams button present, no cpType visible
        //   post-click: cpType2.1 revealed only
        //   post cpType2.1 write: uVar2.1 + cpVal2.1 revealed (gated reveal)
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def moreParamsClicks = 0
        def writtenFields = [:]
        def moreParamsFired = false
        def cpType2Written = false

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") {
                moreParamsClicks++
                moreParamsFired = true
            }
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                    if (k.toString() == "settings[cpType2.1]") cpType2Written = true
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // doActPage incremental-reveal model (matches real RM 5.1 behaviour):
        //   before moreParams: only base runCommand fields + moreParams button
        //   after moreParams click: cpType2.1 revealed (P=2); uVar2.1/cpVal2.1 hidden until cpType is written
        //   after cpType2.1 write: uVar2.1 + cpVal2.1 appear (literal path; no further re-hide)
        //   cpType1.1 is never present -- P always starts at 2
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            if (moreParamsFired) {
                inputs = inputs + [[name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]]]
                if (cpType2Written) {
                    inputs = inputs + [
                        [name: "uVar2.1", type: "bool"],
                        [name: "cpVal2.1", type: "text"]
                    ]
                }
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
                parameters: [[type: "STRING", value: "sleep"]]
            ],
            confirm: true
        ])

        then: "exactly one moreParams click allocates the first param slot"
        moreParamsClicks == 1

        and: "param lands at P=2 slot (cpType2.1 / cpVal2.1); P=1 never touched"
        result.success == true
        writtenFields["cpType2.1"] == "string"
        writtenFields["cpVal2.1"].toString() == "sleep"
        !writtenFields.containsKey("cpType1.1")
        !writtenFields.containsKey("cpVal1.1")
    }

    def "addAction runCommand two literal parameters: two moreParams clicks, P=2 then P=3"() {
        // Each parameter requires a moreParams click; P increments by 1 per click
        // (starting at P=2 for the first param). The stub models two sequential two-stage reveals:
        //   click 1: cpType2.1 appears; cpType2.1 write reveals uVar2.1 + cpVal2.1
        //   click 2: cpType3.1 appears; cpType3.1 write reveals uVar3.1 + cpVal3.1
        // Two moreParams clicks total; cpType1.1 never appears.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def moreParamsClicks = 0
        def writtenFields = [:]
        def slot2Fired = false   // after first moreParams click
        def slot3Fired = false   // after second moreParams click
        def cpType2Written = false
        def cpType3Written = false

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") {
                moreParamsClicks++
                if (!slot2Fired) { slot2Fired = true }
                else { slot3Fired = true }
            }
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "cpType2.1") cpType2Written = true
                        if (m[0][1] == "cpType3.1") cpType3Written = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // Two-stage reveal per slot: moreParams exposes only cpType<P>; cpType<P> write
        // then exposes uVar<P> and cpVal<P>. Matches the real RM 5.1 schema sequence.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            if (slot2Fired) {
                // After first moreParams click: only cpType2.1 is revealed initially.
                // Once cpType2.1 is written, uVar2.1 and cpVal2.1 appear.
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]]
                ]
                if (cpType2Written) {
                    inputs = inputs + [
                        [name: "uVar2.1", type: "bool"],
                        [name: "cpVal2.1", type: "text"]
                    ]
                }
            }
            if (slot3Fired) {
                // After second moreParams click: only cpType3.1 revealed initially.
                inputs = inputs + [
                    [name: "cpType3.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]]
                ]
                if (cpType3Written) {
                    inputs = inputs + [
                        [name: "uVar3.1", type: "bool"],
                        [name: "cpVal3.1", type: "text"]
                    ]
                }
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
                    [type: "NUMBER", value: 5]        // numeric literal -- item-2 guard rejects string "5" for NUMBER type
                ]
            ],
            confirm: true
        ])

        then: "two moreParams clicks (one per parameter)"
        moreParamsClicks == 2

        and: "params at P=2 and P=3; cpType1 never touched"
        result.success == true
        writtenFields["cpType2.1"] == "string"
        writtenFields["cpVal2.1"].toString() == "sleep"
        writtenFields["cpType3.1"] == "number"
        writtenFields["cpVal3.1"].toString() == "5"
        !writtenFields.containsKey("cpType1.1")
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
    // Regression: addTrigger with atTime but no time field
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
        // Verifies the atTime-inference fix: the inference path writes settings[time1]="A specific time"
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
    // Regression: addRequiredExpression ghost ifThen leaves app at
    //             selectActions, not mainPage
    // -----------------------------------------------------------------------
    //
    // After the ghost ifThen sequence (_rmAddRequiredExpression Step 4b):
    //   N click (selectActions) -> condActs/getIfThen writes -> actionCancel
    //   -> nav doActPage->selectActions
    //
    // Before the fix, the sequence ended at selectActions. When the
    // browser user subsequently navigated to "Manage Conditions" (STPage href
    // on mainPage), RM saw the selectActions routing context and showed
    // "Required Fields missing or not passing validation".
    //
    // Fix: after nav doActPage->selectActions, add nav selectActions->mainPage.
    // This mirrors addTrigger's pattern (selectTriggers->mainPage nav at end)
    // and leaves the app in mainPage context for the browser's next visit.

    def "addRequiredExpression Step 4b ends with selectActions->mainPage nav"() {
        // Verifies the RE nav fix: after the doActPage->selectActions nav, a
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

        and: "selectActions->mainPage nav fires AFTER doActPage->selectActions nav"
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
    // Covers _rmWriteSettingOnPage's 4th detection mechanism (zero-context
    // validation 2026-05-02).  Wizard-consumed fields (RelrDev_N,
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
    // Covers the Mode-trigger field routing fix (zero-context
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
        // Exact-equality wire-value check (W-N.34): production writes modesX1 as
        // a JSON-encoded list ['3'] (multi=true contract). A loose .contains("3")
        // would also match "30"/"31"/"13" if firmware drift renumbered modes.
        modesXWritten.any { it.value?.toString() == '["3"]' }

        and: "tstate was never written for a Mode trigger"
        tstateWritten.isEmpty()

        and: "result indicates success and trigger was indexed"
        // toolUpdateNativeApp does not surface capability on the addTrigger path
        // (only addAction does).  Assert on the fields that ARE returned.
        result?.success == true
        result?.triggerIndex == 1
        (result?.settingsApplied as List).contains("tCapab1")
        (result?.settingsApplied as List).contains("modesX1")
        !(result?.settingsApplied as List).any { it?.toString()?.startsWith("tstate") }

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
        // Exact-equality wire-value check (W-N.34): production writes modesX1 as
        // a JSON-encoded list ['3','5'] (multi=true contract). Loose .contains("3")
        // + .contains("5") would also match "53"/"35"/"3,15" by coincidence.
        modesXWritten.any { it.value?.toString() == '["3","5"]' }
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
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [capability: "Mode", state: "NotAMode"],
            confirm: true
        ])

        then: "result carries success=false and the error message names the bad mode + valid options"
        // toolUpdateNativeApp catches IllegalArgumentException from _rmAddTrigger and
        // returns it as [success:false, error:"..."] rather than re-throwing. The error
        // message must identify the unrecognized name and list the valid alternatives.
        result?.success == false
        result?.error?.contains("NotAMode")
        result?.error?.contains("Home")
        result?.error?.contains("Away")
        result?.error?.contains("Night")
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
            // settings echoes the NORMALIZED comparator ("!=" -> "≠" via _rmNormalizeComparator).
            // settingsLanded compares against newValueStr which is the post-normalization value.
            settings: ["RelrDev_1": "≠"],
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

    // ---------- removeTrigger ----------

    def "removeTrigger happy-path: deleteCon click fires, trigger disappears on first check"() {
        given:
        enableHubAdminWrite()
        def deleteConFired = false
        def verificationFetches = 0
        def deletionConfirmed = false
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (deleteConFired && !deletionConfirmed) {
                verificationFetches++
                // trigger 1 gone on the first post-click check
                deletionConfirmed = true
                statusJson(100, [[name: "tCapab2", value: "Switch"]])
            } else if (!deleteConFired) {
                // pre-click (backup + beforeIndices): both triggers present
                statusJson(100, [[name: "tCapab1", value: "Motion"], [name: "tCapab2", value: "Switch"]])
            } else {
                // post-success health check and any subsequent fetches
                statusJson(100, [[name: "tCapab2", value: "Switch"]])
            }
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "deleteCon") deleteConFired = true
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeTrigger: [index: 1], confirm: true])

        then: "success on the first verification check -- no retries needed"
        result.success == true
        result.removedIndex == 1
        result.note?.contains("Removed trigger 1")

        and: "only 1 verification fetch: attempt 0 sees trigger gone immediately"
        verificationFetches == 1
    }

    def "removeTrigger returns success: false when triggerIdx not present in rule"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            // only trigger 2 exists; caller asks to remove index 5
            statusJson(100, [[name: "tCapab2", value: "Switch"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeTrigger: [index: 5], confirm: true])

        then: "returns success: false with a descriptive error listing existing indices"
        result.success == false
        result.error?.contains("removeTrigger.index 5 not found")
        // Exact phrase pinned (W-N.34): a loose .contains("2") would match the "2" in the
        // request's index 5 message or coincidental digit overlap. The distinctive
        // "Existing indices: 2" comes from L16198's sort().join(', ') formatter.
        result.error?.contains("Existing indices: 2")
    }

    def "removeTrigger retry path: trigger disappears on second check after race recovery"() {
        given:
        enableHubAdminWrite()
        def deleteConFired = false
        def verificationFetches = 0
        def deletionConfirmed = false
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (deleteConFired && !deletionConfirmed) {
                verificationFetches++
                if (verificationFetches == 1) {
                    // first post-click check: trigger still present (async dispatch race)
                    statusJson(100, [[name: "tCapab1", value: "Motion"], [name: "tCapab2", value: "Switch"]])
                } else {
                    // second post-click check: trigger gone (race recovered)
                    deletionConfirmed = true
                    statusJson(100, [[name: "tCapab2", value: "Switch"]])
                }
            } else if (!deleteConFired) {
                statusJson(100, [[name: "tCapab1", value: "Motion"], [name: "tCapab2", value: "Switch"]])
            } else {
                statusJson(100, [[name: "tCapab2", value: "Switch"]])
            }
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "deleteCon") deleteConFired = true
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeTrigger: [index: 1], confirm: true])

        then: "success after one retry -- race-recovery path"
        result.success == true
        result.removedIndex == 1

        and: "at least 2 verification fetches: attempt 0 (still present) + attempt 1 (gone)"
        verificationFetches >= 2
    }

    def "removeTrigger exhausts retries and returns success: false with diagnostic message"() {
        given:
        enableHubAdminWrite()
        def deleteConFired = false
        def verificationFetches = 0
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (deleteConFired) {
                verificationFetches++
                // trigger never disappears -- all 4 retry attempts see it still present
            }
            statusJson(100, [[name: "tCapab1", value: "Motion"], [name: "tCapab2", value: "Switch"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.get("stateAttribute") == "deleteCon") deleteConFired = true
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, removeTrigger: [index: 1], confirm: true])

        then: "returns success: false after retry budget exhausted"
        result.success == false
        result.error?.contains("waited 10s")
        result.error?.contains("get_app_config")
        result.error?.contains("restore_item_backup")

        and: "retry loop fired at least 4 times before exhausting"
        verificationFetches >= 4
    }

    // ---------- modifyTrigger ----------

    def "modifyTrigger happy-path: editCond click fires, tstate written, hasAll commits"() {
        given:
        enableHubAdminWrite()
        def posts = []
        // selectTriggers schema exposes tstate1 after editCond opens the wizard
        def selectTriggersSchema = [
            [name: "tstate1", type: "enum", options: ["on", "off"]]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // selectTriggers configPage response includes settings.tstate1="on" so the
        // _rmWriteSettingOnPage post-write verification (settingsLanded mechanism)
        // classifies the write as 'applied' rather than 'silent_rejection'. Without
        // this echo, all four detection mechanisms (schemaShifted / valueLanded /
        // renderShifted / settingsLanded) return false and applied stays empty,
        // making _rmModifyTrigger's success = !applied.isEmpty() = false.
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            groovy.json.JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "Triggers", install: true, error: null,
                             sections: [[title: "", input: selectTriggersSchema]]],
                settings: [tstate1: "on"],  // echo back the written value -- settingsLanded fires
                childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "tCapab1", value: "Switch"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            modifyTrigger: [index: 1, mods: [state: "on"]],
            confirm: true
        ])

        then: "success"
        result.success == true
        result.modifiedIndex == 1
        result.note?.contains("Modified trigger 1")

        and: "editCond button click fired with correct stateAttribute"
        posts.any { it.path == "/installedapp/btn" && it.body?.get("stateAttribute") == "editCond" && it.body?.name == "1" }

        and: "tstate1 write was attempted"
        posts.any { it.path == "/installedapp/update/json" && it.body?.containsKey("settings[tstate1]") }

        and: "hasAll commit fired"
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "hasAll" }
    }

    def "modifyTrigger returns success: false when triggerIdx not present"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            // only trigger 2 exists
            statusJson(100, [[name: "tCapab2", value: "Motion"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            modifyTrigger: [index: 7, mods: [state: "active"]],
            confirm: true
        ])

        then: "returns success: false with descriptive error listing existing indices"
        result.success == false
        result.error?.contains("modifyTrigger.index 7 not found")
        // Exact phrase pinned (W-N.34): the distinctive "Existing indices: 2" comes
        // from L16277's sort().join(', ') formatter; a loose .contains("2") would also
        // match coincidental digit overlap (e.g. modifyTrigger.index 7 -> two-digit hits).
        result.error?.contains("Existing indices: 2")
    }

    def "modifyTrigger returns success: false on unsupported mods fields (capability or deviceIds) with workaround hint"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "tCapab1", value: "Switch"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            modifyTrigger: [index: 1, mods: [state: "on", capability: "Motion"]],
            confirm: true
        ])

        then: "returns success: false with workaround guidance pointing to removeTrigger + addTrigger"
        result.success == false
        result.error?.contains("modifyTrigger currently only supports changing the trigger's state field")
        result.error?.contains("removeTrigger + addTrigger")
    }

    def "modifyTrigger throws when mods is empty (no state field)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "tCapab1", value: "Switch"]])
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            modifyTrigger: [index: 1, mods: [:]],
            confirm: true
        ])

        then: "returns success: false because mods lacks the required 'state' key"
        result.success == false
        result.error?.contains("must include 'state'")
    }

    def "modifyTrigger returns success: false when trigger has no state field in schema (Time/Periodic trigger)"() {
        given:
        enableHubAdminWrite()
        // selectTriggers schema does NOT include tstate1 -- simulating a Time or
        // Periodic trigger whose wizard page only exposes scheduling fields.
        def selectTriggersSchema = [
            [name: "tCapab1", type: "enum", options: ["Time"]]
        ]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            ruleConfigJson(100, "r", selectTriggersSchema)
        }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, [[name: "tCapab1", value: "Time"]])
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
            modifyTrigger: [index: 1, mods: [state: "08:00"]],
            confirm: true
        ])

        then: "returns success: false with explanation that Time triggers have no state field"
        result.success == false
        result.error?.contains("does not expose a 'state' field")

        and: "hasAll was NOT clicked -- wizard was not committed after the skipped write"
        !posts.any { it.path == "/installedapp/btn" && it.body?.name == "hasAll" }
    }

    // ============================================================
    // Dispatch-envelope coverage (#187 / #121)
    // ------------------------------------------------------------
    // This spec has ~135 direct-call features; per the migration plan
    // (see commit message + #187) we take SELECTIVE representative
    // coverage rather than full one-for-one parity. The coverage
    // matrix: one happy path per native_app tool (create / update /
    // delete / clone / export / import / check_rule_health), plus
    // representative IAE (-32602) and runtime-exception (isError)
    // envelope shapes. The full per-tool internals are covered by
    // the direct-call features above; this block guards the
    // production envelope (handleMcpRequest -> handleToolsCall ->
    // executeTool) for both useGateways=true and useGateways=false.
    // ============================================================

    // ---------- create_native_app dispatch ----------

    @spock.lang.Unroll
    def "create_native_app via dispatch returns -32602 envelope when confirm is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('create_native_app', [name: "BAT-RM-demo"])

        then:
        response.error.code == -32602
        response.error.message.contains("SAFETY CHECK FAILED")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "create_native_app via dispatch returns -32602 envelope when name is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('create_native_app', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains("name is required")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "create_native_app via dispatch discovers RM parent, creates child, returns new appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        hubGet.register('/hub2/appsList') { params -> appsListJson(21) }
        hubGet.register('/installedapp/configure/json/974') { params -> ruleConfigJson(974, "", [[name: "origLabel", type: "text"]]) }
        hubGet.register('/installedapp/statusJson/974') { params -> statusJson(974) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/installedapp/configure/974", data: ""]
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def response = mcpDriver.callTool('create_native_app', [name: "BAT-RM-demo", confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.appId == 974
        inner.appType == "rule_machine"
        inner.name == "BAT-RM-demo"
        inner.parentAppId == 21
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "updateRule" }

        where:
        useGateways << [true, false]
    }

    // ---------- update_native_app dispatch ----------

    @spock.lang.Unroll
    def "update_native_app via dispatch returns -32602 envelope when confirm is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('update_native_app', [appId: 100, settings: [a: 1]])

        then:
        response.error.code == -32602
        response.error.message.contains("SAFETY CHECK FAILED")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "update_native_app via dispatch emits 3-field capability contract for multi-device inputs (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('update_native_app', [appId: 100, settings: [tDev0: [8, 9]], confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        def updatePost = posts.find { it.path == "/installedapp/update/json" }
        updatePost.body["settings[tDev0]"] == "8,9"
        updatePost.body["tDev0.type"] == "capability.switch"
        updatePost.body["tDev0.multiple"] == "true"
        inner.backup?.backupKey?.startsWith("rm-rule_100_")

        where:
        useGateways << [true, false]
    }

    // ---------- delete_native_app dispatch ----------

    @spock.lang.Unroll
    def "delete_native_app via dispatch force-deletes with snapshot when force=true (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('delete_native_app', [appId: 200, force: true, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.mode == "forcedelete"
        inner.backup?.backupKey?.startsWith("rm-rule_200_")
        rawCalls.any { it == "/installedapp/forcedelete/200/quiet" }

        where:
        useGateways << [true, false]
    }

    // ---------- clone_native_app dispatch ----------

    @spock.lang.Unroll
    def "clone_native_app via dispatch returns -32602 envelope when sourceAppId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('clone_native_app', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains("sourceappid")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "clone_native_app via dispatch drives the full appCloner wizard and returns the new appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Source Rule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context page</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        int parentCalls = 0
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentCalls++
            parentCalls <= 1
                ? parentConfigJson(21, [[id: 100, label: "Source Rule"]])
                : parentConfigJson(21, [[id: 100, label: "Source Rule"], [id: 250, label: "Source Rule clone"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def response = mcpDriver.callTool('clone_native_app', [sourceAppId: 100, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceAppId == 100
        inner.clonerAppId == 4242
        inner.newAppId == 250

        where:
        useGateways << [true, false]
    }

    // ---------- export_native_app dispatch ----------

    @spock.lang.Unroll
    def "export_native_app via dispatch pulls JSON from the cloner's form-refresh response (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        def fakeJson = '{"deviceReplacements":{},"appReplacements":{"100":{"appLabel":"Source Rule"}}}'
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Source Rule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        def refreshResp = JsonOutput.toJson([
            configPage: [name: "main", sections: [
                [input: [[name: "ruleDownload", type: "download-text", filecontent: fakeJson]]]
            ]]
        ])
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            [status: 200, location: null, data: refreshResp]
        }

        when:
        def response = mcpDriver.callTool('export_native_app', [sourceAppId: 100])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceAppId == 100
        inner.clonerAppId == 4242
        inner.jsonContent == fakeJson
        inner.contentLength == fakeJson.length()

        where:
        useGateways << [true, false]
    }

    // ---------- import_native_app dispatch ----------

    @spock.lang.Unroll
    def "import_native_app via dispatch returns -32602 envelope on non-JSON content (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('import_native_app', [jsonContent: 'not-json', parentHintAppId: 100, confirm: true])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains("not valid json") ||
            response.error.message.toLowerCase().contains("appreplacements")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "import_native_app via dispatch returns -32602 envelope on JSON without appReplacements (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('import_native_app', [jsonContent: '{"foo":"bar"}', parentHintAppId: 100, confirm: true])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains("appreplacements")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "import_native_app via dispatch drives the cloner with settings[ruleUpload] and finds the new appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        def importJson = '{"deviceReplacements":{},"appReplacements":{"42":{"appLabel":"Source Rule","appTypeName":"Rule-5.1"}}}'
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "ExistingRule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 55) }
        int parentCalls = 0
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentCalls++
            parentCalls <= 1
                ? parentConfigJson(21, [[id: 100, label: "ExistingRule"]])
                : parentConfigJson(21, [[id: 100, label: "ExistingRule"], [id: 700, label: "Source Rule import"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            posts << [path: path, body: decodeForm(encodedBody)]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def response = mcpDriver.callTool('import_native_app', [jsonContent: importJson, parentHintAppId: 100, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.newAppId == 700
        inner.originalSourceId == 42
        inner.originalLabel == "Source Rule"

        where:
        useGateways << [true, false]
    }

    // ---------- check_rule_health dispatch ----------

    @spock.lang.Unroll
    def "check_rule_health via dispatch surfaces ok=true on a clean rule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Healthy Rule", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def response = mcpDriver.callTool('check_rule_health', [appId: 100])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.ok == true
        inner.label == "Healthy Rule"
        inner.brokenMarkers == [] || inner.brokenMarkers?.isEmpty()
        inner.multipleFlagPoison == [] || inner.multipleFlagPoison?.isEmpty()

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "check_rule_health via dispatch flags BROKEN marker in label as ok=false (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Some Rule *BROKEN*", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def response = mcpDriver.callTool('check_rule_health', [appId: 100])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.ok == false
        inner.issues != null
        inner.issues.any { it.toString().toLowerCase().contains("broken") }

        where:
        useGateways << [true, false]
    }

    // ---------- addAction capability completeness ----------

    // Helper: minimal doActPage JSON for modeActs subtypes.
    // Exposes actType/actSubType fields plus any extra inputs supplied by the test.
    // seqProvider is a closure called once per fetch; its return value is embedded
    // in paragraphs[] so the render-hash shifts on every GET, preventing
    // _rmWriteSettingOnPage from classifying every write as a silent_rejection.
    // Callers pass { ++localFetchSeq } where localFetchSeq is a def int in given:.
    //
    // Base doActPage page for modeActs/getSetVariable specs. Contains the stable fields
    // (actType, actSubType, actionDone) plus caller-supplied extraInputs. Specs that
    // model the schema-gated source-variable reveal (xVar3.<N> appears only after
    // numOp.<N>=variable is written) gate the xVar3 entry in their own stub logic
    // before delegating here.
    private String modeActsDoActPageJson(int ruleId, List extraInputs = [], Closure seqProvider = { 0 }) {
        def seq = seqProvider()
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "doActPage", title: "T", install: false, error: null,
                         sections: [[title: "", input: [
                             [name: "actType.1", type: "enum",
                              options: ["modeActs": "Set Mode / Variable / Hub Action"]],
                             [name: "actSubType.1", type: "enum",
                              options: ["getSetMode": "Set Mode", "getSetVariable": "Set Variable"]],
                             [name: "actionDone", type: "button"]
                         ] + extraInputs, paragraphs: ["seq ${seq}".toString()]]]],
            settings: [:], childApps: []
        ])
    }

    // setVariable capability (constant form)

    def "addAction setVariable constant form writes modeActs/getSetVariable fields"() {
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def writtenFields = [:]

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }
        // Pre-write validation checks the variable exists against the hub variable list; stub supplies it.
        script.metaClass.getAllGlobalVars = { -> ["counter": [name: "counter", type: "Number", value: 0], "temp": [name: "temp", type: "Number", value: 0]] }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable / Hub Action"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            modeActsDoActPageJson(100, [
                [name: "xVarV.1", type: "enum", options: ["counter": "counter", "temp": "temp"]],
                [name: "numOp.1", type: "enum", options: ["number": "Number", "variable": "From variable"]],
                [name: "valNumber.1", type: "number"],
                [name: "xVar.1", type: "enum", options: ["counter": "counter"]]
            ], { ++fetchSeq })
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "counter", value: 42],
            confirm: true
        ])

        then: "actType/actSubType set to the variable path"
        writtenFields["actType.1"] == "modeActs"
        writtenFields["actSubType.1"] == "getSetVariable"

        and: "variable name, operation mode, and constant value written"
        writtenFields["xVarV.1"] == "counter"
        writtenFields["numOp.1"] == "number"
        writtenFields["valNumber.1"].toString() == "42"

        and: "overall result is success; settingsApplied includes the variable-path key"
        result.success == true
        result.settingsApplied?.contains("xVarV.1")
    }

    def "addAction 'variable' alias accepted same as 'setVariable'"() {
        // capability='variable' is an alias for 'setVariable'. Both must route
        // to the same modeActs/getSetVariable code path.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def actSubTypeWritten = null

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                def m = body?.find { k, v -> k.toString() == "settings[actSubType.1]" }
                if (m) actSubTypeWritten = m.value
            }
            [status: 200, location: null, data: '']
        }
        // Pre-write validation checks the variable exists against the hub variable list; stub supplies it.
        script.metaClass.getAllGlobalVars = { -> ["myVar": [name: "myVar", type: "Number", value: 0]] }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            modeActsDoActPageJson(100, [
                [name: "xVarV.1", type: "enum", options: ["myVar": "myVar"]],
                [name: "numOp.1", type: "enum", options: ["number": "Number"]],
                [name: "valNumber.1", type: "number"]
            ], { ++fetchSeq })
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "variable", variable: "myVar", value: 10],
            confirm: true
        ])

        then: "alias routes to the same getSetVariable subtype"
        actSubTypeWritten == "getSetVariable"
        result.success == true
    }

    def "addAction setVariable sourceVariable form uses numOp=variable and discovers xVar3 via schema reveal"() {
        // RM 5.1 live-verified wire: the source-variable field is xVar3.<N>, not xVar.<N>.
        // RM only reveals xVar3.<N> AFTER numOp.<N>="variable" (the full word) is written --
        // it is schema-gated. Writing numOp=var (short form) leaves xVar3 hidden and the
        // action silently bakes without a source variable.
        //
        // This stub gates xVar3.1 on whether numOp.1=variable was already written:
        // if code regresses to numOp=var or tries to write xVar.1 directly without the
        // reveal step, the post-write re-introspect finds no xVar<digits>.1 field and
        // must throw (fail-loud guard), which surfaces as result.success==false.
        given:
        enableHubAdminWrite()
        def writtenFields = [:]
        def fetchSeq = 0

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }
        // Pre-write validation: both variables exist on the hub.
        script.metaClass.getAllGlobalVars = { -> ["dest": [name: "dest", type: "Number", value: 0], "source": [name: "source", type: "Number", value: 0]] }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        // doActPage stub: xVar3.1 (the source-var enum) is gated on numOp.1=variable.
        // When numOp.1 has not yet been written as "variable", only the base fields appear.
        // After the numOp=variable write, re-introspect reveals xVar3.1.
        // This non-circular gate mirrors the actual RM 5.1 behavior verified live.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def seq = ++fetchSeq
            def numOpWritten = writtenFields["numOp.1"] == "variable"
            def extraInputs = [
                [name: "xVarV.1", type: "enum", options: ["dest": "dest"]],
                [name: "numOp.1", type: "enum", options: ["number": "Number", "variable": "From variable"]],
                [name: "valNumber.1", type: "number"]
            ]
            if (numOpWritten) {
                // numOp=variable written: RM reveals xVar3.1 (source-variable enum).
                extraInputs << [name: "xVar3.1", type: "enum", options: ["dest": "dest", "source": "source"]]
            }
            modeActsDoActPageJson(100, extraInputs, { seq })
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "dest", sourceVariable: "source"],
            confirm: true
        ])

        then: "numOp uses the full word 'variable' (not the short 'var' form rejected by RM 5.1 live)"
        writtenFields["numOp.1"] == "variable"

        and: "source variable lands in the schema-revealed xVar3.1 field, not the non-existent xVar.1"
        writtenFields["xVar3.1"] == "source"
        !writtenFields.containsKey("xVar.1")

        and: "constant value field is not written for the copy-from-variable path"
        !writtenFields.containsKey("valNumber.1")

        and: "action bakes cleanly -- no settings skipped, no partial"
        result.success == true
        result.settingsApplied?.contains("xVar3.1")
        result.settingsSkipped == null || result.settingsSkipped.isEmpty()
        result.partial != true
    }

    def "addAction setVariable rejects missing variable field"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", value: 99],
            confirm: true
        ])

        then: "validation error surfaces in result.error (toolUpdateNativeApp catches IAE from _rmAddAction)"
        result.success == false
        result.error?.contains("requires 'variable'")
    }

    def "addAction setVariable rejects missing value and sourceVariable"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "counter"],
            confirm: true
        ])

        then: "validation error surfaces in result.error (toolUpdateNativeApp catches IAE from _rmAddAction)"
        result.success == false
        result.error?.contains("requires 'value' (numeric constant) or 'sourceVariable'")
    }

    def "addAction setVariable rejects when both value and sourceVariable are provided"() {
        // Providing both is ambiguous; enforce mutual exclusion.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "counter", value: 99, sourceVariable: "other"],
            confirm: true
        ])

        then: "mutual-exclusion error surfaces in result.error (toolUpdateNativeApp catches IAE from _rmAddAction)"
        result.success == false
        result.error?.contains("provide 'value' OR 'sourceVariable', not both")
    }

    def "addAction mode with modeName and empty location.modes fails fast with empty available list"() {
        // When location.modes returns [] (edge case), the error must still surface and
        // include a clear 'Available modes:' message -- production code is already
        // correct (location?.modes ?: []); this spec pins that behavior.
        given:
        enableHubAdminWrite()
        sharedLocation.modes = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "mode", modeName: "AnyMode"],
            confirm: true
        ])

        then: "validation error surfaces in result.error; rejected name + not found + Available modes all present"
        result.success == false
        result.error?.contains("AnyMode")
        result.error?.contains("modeName 'AnyMode' not found")
        result.error?.contains("Available modes")
    }

    // runCommand variable parameter: correct moreParams/P-discovery/xVar sequence

    def "addAction runCommand variable parameter: moreParams+P-discovery writes uVar+xVar (not cpVar)"() {
        // Live-verified wire sequence for a hub-variable-sourced runCommand parameter:
        //   moreParams click -> cpType<P>.N revealed (P=2 for first param, RM-assigned)
        //   cpType<P>.N write -> uVar<P>.N + cpVal<P>.N revealed
        //   uVar<P>.N = "true" -> xVar<P>.N (enum of var names) revealed; cpVal<P>.N hidden
        //   xVar<P>.N = varName written
        // Stub models the full three-stage reveal so the P-discovery and uVar logic
        // are exercised; a green run proves the orchestration matches real RM 5.1.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def writtenFields = [:]
        def moreParamsFired = false
        def cpType2Written = false
        def uVar2Written = false
        // Tracks field names present in the schema at the fetch that happens right
        // after cpType2 is written but BEFORE uVar2.1 is written (stage 1-2).
        // xVar2.1 must be absent from that fetch -- it is only gated by uVar2.1=true.
        def fieldNamesAtPreUVarFetch = null as List

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") moreParamsFired = true
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "cpType2.1") cpType2Written = true
                        if (m[0][1] == "uVar2.1")   uVar2Written = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // Three-stage reveal:
        //   stage 0: base fields + moreParams (no cpType yet)
        //   stage 1 (after moreParams): cpType2.1 + uVar2.1 + cpVal2.1 appear
        //   stage 2 (after cpType2.1 write): same as stage 1 (cpType write doesn't hide fields)
        //   stage 3 (after uVar2.1=true write): xVar2.1 (enum) appears; cpVal2.1 hidden
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            if (moreParamsFired && !uVar2Written) {
                // Stage 1-2: cpType + uVar + cpVal visible; xVar2.1 is NOT yet present.
                // Capture field names here to assert xVar2.1 is absent before the uVar write (gating is production-observable).
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "uVar2.1", type: "bool"],
                    [name: "cpVal2.1", type: "text"]
                ]
                if (cpType2Written && fieldNamesAtPreUVarFetch == null) {
                    // This fetch is the re-introspect AFTER cpType2.1 write but BEFORE uVar2.1 write.
                    fieldNamesAtPreUVarFetch = inputs.collect { it.name?.toString() }
                }
            } else if (uVar2Written) {
                // Stage 3: uVar=true hides cpVal, reveals xVar (enum of hub variable names)
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "uVar2.1", type: "bool"],
                    [name: "xVar2.1", type: "enum",
                     options: ["myVar": "myVar", "counter": "counter", "temp": "temp"]]
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
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "setLevel",
                deviceIds: [42],
                parameters: [[type: "NUMBER", variable: "myVar"]]
            ],
            confirm: true
        ])

        then: "param binds via uVar2.1=true + xVar2.1=varName at the RM-assigned slot P=2"
        result.success == true
        writtenFields["cpType2.1"] == "number"
        writtenFields["uVar2.1"] == "true"
        writtenFields["xVar2.1"] == "myVar"

        and: "cpVal and any cpVar fields are absent"
        !writtenFields.containsKey("cpVal2.1")
        !writtenFields.any { k, v -> k.toString().startsWith("cpVar") }

        and: "xVar2.1 was absent from the schema at the pre-uVar fetch (gating is production-observable)"
        fieldNamesAtPreUVarFetch != null
        !fieldNamesAtPreUVarFetch.contains("xVar2.1")
    }

    def "addAction runCommand literal parameter: moreParams+P-discovery writes cpVal at P=2"() {
        // Literal value path: moreParams click -> cpType2.1 revealed -> write cpType + cpVal.
        // uVar is not set; xVar is not written. cpType1.1 never appears.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def writtenFields = [:]
        def moreParamsFired = false

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") moreParamsFired = true
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // Stage 0: no cpType; Stage 1: cpType2.1 + uVar2.1 + cpVal2.1 revealed after moreParams.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            if (moreParamsFired) {
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "uVar2.1", type: "bool"],
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
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "setLevel",
                deviceIds: [42],
                parameters: [[type: "NUMBER", value: 75]]
            ],
            confirm: true
        ])

        then: "cpType2.1 and cpVal2.1 written; cpType1.1, uVar, xVar, cpVar all absent"
        result.success == true
        writtenFields["cpType2.1"] == "number"
        writtenFields["cpVal2.1"].toString() == "75"
        !writtenFields.containsKey("cpType1.1")
        !writtenFields.containsKey("uVar2.1")
        !writtenFields.any { k, v -> k.toString().startsWith("cpVar") }
    }

    def "addAction runCommand legacy bare-scalar parameter writes to cpVal<P>.<N>"() {
        // Legacy scalar path: parameters: [75] (bare Integer, not a Map).
        // The pre-validation loop skips non-Map entries; __runCommandExtraParams treats them
        // as type="string" and pValue=p, then follows the literal write path.
        // Both-ways pending (orchestrator): mutate the `if (!(p instanceof Map)) return` early-return
        // to a throw and confirm this spec goes RED on the scalar contract.
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def writtenFields = [:]
        def moreParamsFired = false

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") moreParamsFired = true
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // Stage 0: no cpType; Stage 1: cpType2.1 + uVar2.1 + cpVal2.1 revealed after moreParams.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            if (moreParamsFired) {
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "uVar2.1", type: "bool"],
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
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "setLevel",
                deviceIds: [42],
                parameters: [75]   // bare Integer scalar, not a Map
            ],
            confirm: true
        ])

        then: "scalar treated as literal: cpType2.1='string' and cpVal2.1='75' written; uVar absent"
        result.success == true
        moreParamsFired == true
        writtenFields["cpType2.1"] == "string"
        writtenFields["cpVal2.1"].toString() == "75"
        !writtenFields.containsKey("uVar2.1")
    }

    def "addAction runCommand variable parameter: xVar enum validation rejects unknown variable name"() {
        // If the requested variable name is not in the xVar<P>.N enum options,
        // the tool must fail with a clear error rather than silently dropping the write.
        given:
        enableHubAdminWrite()
        def moreParamsFired = false
        def uVar2Written = false

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") moreParamsFired = true
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    if (k.toString() == "settings[uVar2.1]") uVar2Written = true
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // xVar2.1 enum contains only "knownVar" -- "unknownVar" is absent.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            if (moreParamsFired && !uVar2Written) {
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "uVar2.1", type: "bool"],
                    [name: "cpVal2.1", type: "text"]
                ]
            } else if (uVar2Written) {
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "uVar2.1", type: "bool"],
                    [name: "xVar2.1", type: "enum",
                     options: ["knownVar": "knownVar"]]  // unknownVar is NOT in here
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "setLevel",
                deviceIds: [42],
                parameters: [[type: "NUMBER", variable: "unknownVar"]]
            ],
            confirm: true
        ])

        then: "validation failure surfaces in result.error naming the unknown variable and the enum constraint"
        result.success == false
        result.error?.contains("unknownVar")
        result.error?.contains("is not in the hub variable enum")
    }

    def "addAction runCommand variable parameter: xVar field not revealed after uVar=true fails loud"() {
        // When uVar<P>.N=true is written but the hub does not reveal xVar<P>.N in the
        // subsequent schema fetch (firmware gap, unsupported command, etc.), the tool
        // must throw rather than silently falling through to a rejected write.
        given:
        enableHubAdminWrite()
        def moreParamsFired = false
        def uVar2Written = false

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") moreParamsFired = true
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    if (k.toString() == "settings[uVar2.1]") uVar2Written = true
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // After uVar2.1=true is written the hub does NOT reveal xVar2.1 (simulates
        // firmware version that does not support variable-sourced runCommand params).
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            if (moreParamsFired) {
                // cpType2.1+uVar2.1+cpVal2.1 revealed after moreParams click.
                // Regardless of whether uVar2.1 has been written, xVar2.1 is NEVER revealed.
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "uVar2.1", type: "bool"],
                    [name: "cpVal2.1", type: "text"]
                    // xVar2.1 intentionally absent even after uVar2.1 write
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "setLevel",
                deviceIds: [42],
                parameters: [[type: "NUMBER", variable: "myVar"]]
            ],
            confirm: true
        ])

        then: "fail loud with an actionable error -- silent success + broken rule is not acceptable"
        result.success == false
        result.error?.contains("xVar field not revealed")
    }

    def "addAction runCommand variable parameter: moreParams click that reveals no new cpType field marks param as skipped (partial=true)"() {
        // When the moreParams click reveals no new cpType<P> field, the param is recorded
        // in skipped (not silently dropped), driving partial=true and surfacing the failure
        // to the caller instead of returning success with a lost param.
        // The stub uses a shifting paragraph sequence so actType/actSubType writes
        // land in applied (render-hash shifts per GET), pinning "action initiated, param
        // skipped" distinctly from "action never started."
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def moreParamsFired = false

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") moreParamsFired = true
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // doActPage NEVER reveals a cpType<P>.N field, even after the moreParams click.
        // Shifting paragraphs ensure each GET produces a new render-hash so writes
        // are classified as landed (not silent_rejection) and appear in settingsApplied.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                                 [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                                 [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                                 [name: "devices.1", type: "capability.switch", multiple: true],
                                 [name: "cCmd.1", type: "text"],
                                 [name: "moreParams", type: "button"],
                                 [name: "actionDone", type: "button"]
                                 // cpType<P>.N intentionally never appears
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "noParamCmd",
                deviceIds: [42],
                parameters: [[type: "NUMBER", value: 5]]
            ],
            confirm: true
        ])

        then: "action row initiated (actType written) but param skipped; partial=true, not success=false"
        result.success == true
        result.partial == true
        result.settingsApplied?.contains("actType.1")
        // The reason-coded check is the load-bearing discriminator here: partial=true alone is
        // incidentally satisfiable by an unrelated not_in_schema skip in this stub, so only
        // the reason='moreParams_no_reveal' entry is the genuine regression pin.
        result.settingsSkipped?.any { it?.key?.toString()?.startsWith("param") && it?.reason == "moreParams_no_reveal" }
    }

    def "addAction runCommand mixed literal+variable parameters: each slot takes the correct path"() {
        // Exercises the two-param mixed case: first param is a literal, second is variable-sourced.
        // Consequence-gated stub: xVar3.1 (variable slot) appears only AFTER uVar3.1=true is written,
        // mirroring the production reveal sequence so the guard is non-circular (if the code fails to
        // write uVar3.1 the xVar3.1 enum never opens and xVar3.1 cannot be written).
        //
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def fetchSeq = 0
        def writtenFields = [:]
        def moreParamsFired = 0
        def uVar3Written = false

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") moreParamsFired++
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "uVar3.1") uVar3Written = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // Three-stage doActPage reveal:
        //   base: no cpType fields
        //   after 1st moreParams: cpType2.1 + uVar2.1 + cpVal2.1 (literal slot P=2)
        //   after 2nd moreParams: adds cpType3.1 + uVar3.1 + cpVal3.1 (variable slot P=3)
        //   after uVar3.1=true:   xVar3.1 (enum) appears; cpVal3.1 hidden
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            def base = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            def slot2 = [
                [name: "cpType2.1", type: "enum", options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                [name: "uVar2.1", type: "bool"],
                [name: "cpVal2.1", type: "text"]
            ]
            def slot3Pre = [
                [name: "cpType3.1", type: "enum", options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                [name: "uVar3.1", type: "bool"],
                [name: "cpVal3.1", type: "text"]
            ]
            def slot3Post = [
                [name: "cpType3.1", type: "enum", options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                [name: "uVar3.1", type: "bool"],
                [name: "xVar3.1", type: "enum", options: ["myVar": "myVar", "counter": "counter"]]
                // cpVal3.1 deliberately absent after uVar3.1=true (schema-gated)
            ]
            def inputs = base
            if (moreParamsFired >= 1) inputs = inputs + slot2
            if (moreParamsFired >= 2) inputs = inputs + (uVar3Written ? slot3Post : slot3Pre)
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "mixedCmd",
                deviceIds: [42],
                parameters: [
                    [type: "NUMBER", value: 75],           // literal -> slot P=2
                    [type: "NUMBER", variable: "myVar"]    // variable -> slot P=3
                ]
            ],
            confirm: true
        ])

        then: "literal slot P=2 uses cpVal2.1; variable slot P=3 uses uVar3.1+xVar3.1 (not cpVal)"
        result.success == true
        writtenFields["cpType2.1"] == "number"
        writtenFields["cpVal2.1"].toString() == "75"
        writtenFields["cpType3.1"] == "number"
        writtenFields["uVar3.1"] == "true"
        writtenFields["xVar3.1"] == "myVar"

        and: "cpType1.1 never written; variable slot has no cpVal; no cpVar fields"
        !writtenFields.containsKey("cpType1.1")
        !writtenFields.containsKey("cpVal3.1")
        !writtenFields.any { k, v -> k.toString().startsWith("cpVar") }

        and: "two moreParams clicks fired (one per parameter)"
        moreParamsFired == 2
    }

    def "addAction runCommand variable parameter: null xVar options fail loud (not silent write)"() {
        // CODE fix: when xVar<P>.N is revealed but its options are null or non-enumerable,
        // the tool must throw rather than writing an unvalidated variable name.
        given:
        enableHubAdminWrite()
        def moreParamsFired = false
        def uVar2Written = false

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body?.name == "moreParams") moreParamsFired = true
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    if (k.toString() == "settings[uVar2.1]") uVar2Written = true
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        // xVar2.1 is revealed after uVar2.1=true but its options are null (hub bug/fw gap).
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                [name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]],
                [name: "devices.1", type: "capability.switch", multiple: true],
                [name: "cCmd.1", type: "text"],
                [name: "moreParams", type: "button"],
                [name: "actionDone", type: "button"]
            ]
            if (moreParamsFired && !uVar2Written) {
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "uVar2.1", type: "bool"],
                    [name: "cpVal2.1", type: "text"]
                ]
            } else if (uVar2Written) {
                // xVar2.1 revealed but with null options (simulates hub returning no enum list)
                inputs = inputs + [
                    [name: "cpType2.1", type: "enum",
                     options: ["string": "String", "number": "Number", "decimal": "Decimal"]],
                    [name: "uVar2.1", type: "bool"],
                    [name: "xVar2.1", type: "enum", options: null]  // null options
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "runCommand",
                command: "setLevel",
                deviceIds: [42],
                parameters: [[type: "NUMBER", variable: "myVar"]]
            ],
            confirm: true
        ])

        then: "fail loud -- writing an unvalidated variable name produces a silently-broken action"
        result.success == false
        result.error?.contains("xVar2.1")
        result.error?.contains("no enumerable options")
    }

    def "addAction setVariable rejects unknown target variable"() {
        // The hub silently rejects an unknown variable name written to xVarV -- validate
        // upfront via getAllGlobalVars so the caller gets a clear error instead of partial=true
        // with no explanation.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        // Hub has "knownVar" but NOT "unknownTarget"
        script.metaClass.getAllGlobalVars = { -> ["knownVar": [name: "knownVar", type: "Number", value: 0]] }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "unknownTarget", value: 42],
            confirm: true
        ])

        then: "validation error names the unknown variable and lists available variables"
        result.success == false
        result.error?.contains("unknownTarget")
        result.error?.contains("variable 'unknownTarget' not found")
        result.error?.contains("knownVar")
    }

    def "addAction setVariable rejects unknown sourceVariable"() {
        // The hub also silently rejects an unknown variable name in xVar (sourceVariable).
        // Both target and source must be validated against the hub variable enum.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        // Hub has "target" but NOT "ghostSource"
        script.metaClass.getAllGlobalVars = { -> ["target": [name: "target", type: "Number", value: 0]] }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "target", sourceVariable: "ghostSource"],
            confirm: true
        ])

        then: "validation error names the unknown sourceVariable and lists available variables"
        result.success == false
        result.error?.contains("sourceVariable 'ghostSource' not found")
    }

    def "addAction setVariable fails loud when hub has no variables (getAllGlobalVars returns empty map)"() {
        // When the hub variable API returns an empty map (no variables defined on the hub),
        // every variable name is invalid. The code must distinguish this from an API failure
        // (null sentinel) and fail loud with a clear message rather than skipping validation.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        // Empty map: API returned successfully but the hub has no hub variables defined.
        script.metaClass.getAllGlobalVars = { -> [:] }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "anything", value: 1],
            confirm: true
        ])

        then: "fails loud -- empty-hub case is not the same as an API failure"
        result.success == false
        result.error?.contains("variable 'anything' not found")
        result.error?.contains("(none -- hub has no variables defined)")
    }

    def "addAction setVariable skips variable-name validation when getAllGlobalVars throws (graceful degradation)"() {
        // When the hub variable API is unavailable (throws), validation is skipped rather than
        // failing the call -- the caller's intent is applied and the hub enforces correctness.
        // The warn log distinguishes the skip from a normal validation pass.
        given:
        enableHubAdminWrite()
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        // API unavailable -- throws instead of returning a map.
        script.metaClass.getAllGlobalVars = { -> throw new RuntimeException("API unavailable") }

        def fetchSeq = 0
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def seq = ++fetchSeq
            ruleConfigJson(100, "r", [
                [name: "actSubType.1", type: "enum", options: ["getSetVariable": "Set Variable"]],
                [name: "xVarV.1", type: "enum", options: ["myVar": "myVar"], value: ""],
                [name: "numOp.1", type: "enum", options: ["number": "Constant", "var": "Variable"], value: ""],
                [name: "valNumber.1", type: "number", value: "", paragraphs: ["seq ${seq}".toString()]]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "unknownButNotValidated", value: 7],
            confirm: true
        ])

        then: "call succeeds because validation was skipped (API threw), and a warn is emitted"
        result.success == true
        mcpLogCalls.any { it.level == "warn" && it.component == "rm-native" && it.msg?.contains("getAllGlobalVars()") }
    }

    def "addAction setVariable sourceVariable: xVar3 not revealed after numOp=variable fails loud"() {
        // When numOp=variable is written but it does not land (silent_rejection from a static
        // schema stub), the item-5 precise-attribution guard fires: it detects numOp in skipped
        // and throws the numOp-failure error BEFORE attempting the schema re-introspect.
        // This is more actionable than the old reveal-miss message, which would wrongly blame
        // firmware when the real cause is the numOp write itself.
        // The stub uses a constant seqProvider ({ 1 }) so before/after hashes are identical --
        // numOp.1 routes to skipped(silent_rejection), triggering the precise-attribution path.
        // Both-ways pending (orchestrator): stub re-routes numOp.1 to applied to prove the
        // old reveal-miss path is still reachable when numOp lands correctly.
        given:
        enableHubAdminWrite()
        def writtenFields = [:]

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }
        script.metaClass.getAllGlobalVars = { -> ["dst": [name: "dst", type: "Number", value: 0], "src": [name: "src", type: "Number", value: 0]] }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        // xVar3.1 absent; static seqProvider means numOp.1 routes to skipped(silent_rejection).
        // The item-5 check fires on numOp.1 in skipped before the reveal-miss path is reached.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            modeActsDoActPageJson(100, [
                [name: "xVarV.1", type: "enum", options: ["dst": "dst"]],
                [name: "numOp.1", type: "enum", options: ["number": "Number", "variable": "From variable"]],
                [name: "valNumber.1", type: "number"]
                // xVar3.1 intentionally absent; static stub causes numOp.1 to go to skipped
            ], { 1 })
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "dst", sourceVariable: "src"],
            confirm: true
        ])

        then: "numOp write failure surfaces as the precise-attribution error, not the reveal-miss message"
        result.success == false
        result.error?.contains("numOp.1")
        result.error?.contains("did not land")
        !result.error?.contains("source-variable field was not revealed after writing numOp=variable")
    }

    def "addAction setVariable sourceVariable: revealed xVar3 with null options fails loud"() {
        // When xVar3.<N> is revealed after numOp=variable but its options are null or non-enumerable,
        // the tool must throw rather than writing an unvalidated variable name -- an unvalidated
        // write would produce a silently-broken action with no source variable persisted.
        given:
        enableHubAdminWrite()
        def writtenFields = [:]

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }
        script.metaClass.getAllGlobalVars = { -> ["dst": [name: "dst", type: "Number", value: 0], "src": [name: "src", type: "Number", value: 0]] }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        // xVar3.1 is revealed after numOp=variable write but with null options (hub bug/fw gap).
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def numOpWritten = writtenFields["numOp.1"] == "variable"
            def extraInputs = [
                [name: "xVarV.1", type: "enum", options: ["dst": "dst"]],
                [name: "numOp.1", type: "enum", options: ["number": "Number", "variable": "From variable"]],
                [name: "valNumber.1", type: "number"]
            ]
            if (numOpWritten) {
                extraInputs << [name: "xVar3.1", type: "enum", options: null]  // revealed but options null
            }
            modeActsDoActPageJson(100, extraInputs, { 1 })
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "dst", sourceVariable: "src"],
            confirm: true
        ])

        then: "fail loud -- writing an unvalidated source variable name produces a silently-broken action"
        result.success == false
        result.error?.contains("xVar3.1")
        result.error?.contains("no enumerable options")
    }

    def "addAction setVariable sourceVariable: source variable not in revealed xVar3 enum fails loud"() {
        // If the requested sourceVariable name is not in the enum options of the revealed
        // xVar3.<N> field, the tool must fail with a clear error rather than silently dropping
        // the write or writing an unrecognised value.
        given:
        enableHubAdminWrite()
        def writtenFields = [:]

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }
        // getAllGlobalVars allows "ghostSrc" so pre-write validation passes.
        // The hub's xVar3 enum (live schema) does NOT include "ghostSrc" -- the enum is
        // the authoritative list; a mismatch here means the variable was deleted between
        // the getAllGlobalVars call and the schema re-introspect, or the hub restricts scope.
        script.metaClass.getAllGlobalVars = { -> ["dst": [name: "dst", type: "Number", value: 0], "ghostSrc": [name: "ghostSrc", type: "Number", value: 0]] }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        // xVar3.1 revealed after numOp=variable write but enum contains only "dst", not "ghostSrc".
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def numOpWritten = writtenFields["numOp.1"] == "variable"
            def extraInputs = [
                [name: "xVarV.1", type: "enum", options: ["dst": "dst"]],
                [name: "numOp.1", type: "enum", options: ["number": "Number", "variable": "From variable"]],
                [name: "valNumber.1", type: "number"]
            ]
            if (numOpWritten) {
                extraInputs << [name: "xVar3.1", type: "enum", options: ["dst": "dst"]]  // ghostSrc absent
            }
            modeActsDoActPageJson(100, extraInputs, { 1 })
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "dst", sourceVariable: "ghostSrc"],
            confirm: true
        ])

        then: "validation failure names the unknown source variable and the enum constraint"
        result.success == false
        result.error?.contains("ghostSrc")
        result.error?.contains("is not in the revealed enum")
    }

    // setVariable value type narrowing

    @spock.lang.Unroll
    def "addAction setVariable rejects non-numeric value: #label"() {
        // valNumber.<N> is a numeric RM field; writing a String, Boolean, or date string
        // produces a broken action that RM silently accepts but renders incorrectly.
        // Reject early so the caller gets a clear message rather than a broken rule.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "counter", value: badValue],
            confirm: true
        ])

        then: "non-numeric value rejected before any hub write"
        result.success == false
        result.error?.contains("numeric constant")

        where:
        label            | badValue
        "string"         | "hello"
        "boolean true"   | true
        "boolean false"  | false
        "datetime string"| "2026-05-19T10:00"
        "decimal string" | "3.14"
    }

    def "addAction setVariable with numeric value zero is accepted (truthiness guard regression)"() {
        // value=0 is a valid numeric constant; a truthy-check would wrongly reject it.
        // The numeric-type guard must use instanceof Number, not a truthiness test.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def writtenFields = [:]
        script.metaClass.getAllGlobalVars = { -> ["counter": [name: "counter", type: "Number", value: 0]] }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }
        def fetchSeq = 0
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def seq = ++fetchSeq
            ruleConfigJson(100, "r", [
                [name: "actSubType.1", type: "enum", options: ["getSetVariable": "Set Variable"]],
                [name: "xVarV.1", type: "enum", options: ["counter": "counter"], value: ""],
                [name: "numOp.1", type: "enum", options: ["number": "Constant", "variable": "Variable"], value: ""],
                [name: "valNumber.1", type: "number", value: "", paragraphs: ["seq ${seq}".toString()]]
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "counter", value: 0],
            confirm: true
        ])

        then: "value=0 is accepted and written (not falsely rejected by a truthiness check)"
        result.success == true
        writtenFields.containsKey("valNumber.1")
        writtenFields["valNumber.1"].toString() == "0"
    }

    def "addAction setVariable API-unavailable adds sentinel to skipped (partial=true)"() {
        // When getAllGlobalVars() throws, variable-name validation is skipped and a
        // sentinel entry is pushed to skipped so partial=true surfaces to the caller.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.getAllGlobalVars = { -> throw new RuntimeException("API unavailable") }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        def fetchSeq = 0
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def seq = ++fetchSeq
            ruleConfigJson(100, "r", [
                [name: "actSubType.1", type: "enum", options: ["getSetVariable": "Set Variable"]],
                [name: "xVarV.1", type: "enum", options: ["myVar": "myVar"], value: ""],
                [name: "numOp.1", type: "enum", options: ["number": "Constant", "variable": "Variable"], value: ""],
                [name: "valNumber.1", type: "number", value: "", paragraphs: ["seq ${seq}".toString()]]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "unvalidated", value: 7],
            confirm: true
        ])

        then: "call proceeds (API-unavailable is graceful degradation) but partial=true and sentinel present"
        result.success == true
        result.partial == true
        // Load-bearing discriminator: the reason-coded .any{} check below is the sole assertion
        // that fails when the sentinel push is removed from the catch block. result.partial==true
        // is incidentally satisfiable by unrelated stub-level skipped entries in this fixture
        // (actType.1 not_in_schema fires independently of the sentinel), so a refactor that
        // weakens this to a bare partial==true check would silently hollow the regression guard.
        // Keep the reason-coded form.
        result.settingsSkipped?.any { it?.key == "variable-validation" && it?.reason == "api_unavailable" }
    }

    // runCommand parameter validation: mutex, neither, type-mismatch guards

    @spock.lang.Unroll
    def "addAction runCommand rejects parameter slot #slot with both value and variable"() {
        // Providing both value and variable in a single parameter Map is ambiguous;
        // the pre-validation loop must reject it before any hub writes are attempted.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { req -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { req ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { req -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "runCommand", command: "setLevel", deviceIds: [],
                        parameters: params],
            confirm: true
        ])

        then: "mutex violation rejected before any hub write"
        result.success == false
        result.error?.contains("provide 'value' OR 'variable', not both")
        result.error?.contains("slot ${slot}")

        where:
        slot | params
        1    | [[type: "number", value: 75, variable: "foo"]]
        2    | [[type: "number", value: 10], [type: "string", value: "x", variable: "bar"]]
    }

    def "addAction runCommand rejects Map parameter with neither value nor variable"() {
        // A Map entry with no value and no variable writes only cpType<P>, leaving RM to
        // bake a half-formed action with a type but no actual parameter content.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "runCommand", command: "setLevel", deviceIds: [],
                        parameters: [[type: "number"]]],  // no value, no variable
            confirm: true
        ])

        then: "rejected before any hub write with a message naming the slot"
        result.success == false
        result.error?.contains("slot 1")
        result.error?.contains("'value'")
        result.error?.contains("'variable'")
    }

    @spock.lang.Unroll
    def "addAction runCommand rejects non-numeric value for type '#pType'"() {
        // cpVal<P>.<N> is a numeric field when cpType is 'number' or 'decimal'; writing
        // a string produces a silently-broken action. Reject early with a clear message.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Run Custom Action"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "runCommand", command: "cmd", deviceIds: [],
                        parameters: [[type: pType, value: badValue]]],
            confirm: true
        ])

        then: "non-numeric value rejected before any hub write"
        result.success == false
        result.error?.contains("numeric")
        result.error?.contains("slot 1")

        where:
        pType     | badValue
        "number"  | "oops"
        "decimal" | "3.14"    // string, not a Number instance
        "NUMBER"  | "25"      // uppercase type alias + string value
    }

    def "addAction setVariable sourceVariable: numOp write failure causes precise error not reveal-miss blame"() {
        // When numOp.<N> is not in the doActPage schema (not_in_schema skip), the subsequent
        // source-variable reveal cannot happen. The error must name the numOp write failure,
        // not the reveal-miss -- which would wrongly suggest RM/firmware is the cause.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def writtenFields = [:]
        script.metaClass.getAllGlobalVars = { -> ["dst": [name: "dst", type: "Number", value: 0], "src": [name: "src", type: "Number", value: 0]] }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        // doActPage schema intentionally omits numOp.1 -- the field is never revealed,
        // so _rmWriteSettingOnPage routes it to skipped(reason=not_in_schema).
        // The production-observable consequence: the check before the schema re-fetch
        // fires first and throws a numOp-specific error, not the reveal-miss error.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            modeActsDoActPageJson(100, [
                [name: "xVarV.1", type: "enum", options: ["dst": "dst"]],
                // numOp.1 intentionally absent from schema
                [name: "valNumber.1", type: "number"]
            ], { 1 })
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "dst", sourceVariable: "src"],
            confirm: true
        ])

        then: "error blames numOp write failure, not the reveal-miss (which has a different message)"
        result.success == false
        result.error?.contains("numOp.1")
        result.error?.contains("did not land")
        !result.error?.contains("source-variable field was not revealed")
    }

    def "addAction setVariable sourceVariable: multiple xVar<digits> matches causes loud failure not silent first-pick"() {
        // When the schema contains more than one field matching xVar<digits>.<N> at the same
        // action slot (unexpected but possible in future firmware), the tool must fail loudly
        // rather than silently picking the first match and producing a confusing write.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def writtenFields = [:]
        script.metaClass.getAllGlobalVars = { -> ["dst": [name: "dst", type: "Number", value: 0], "src": [name: "src", type: "Number", value: 0]] }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode / Variable"]]])
        }
        // Two xVar<digits>.1 fields present simultaneously (ambiguous schema).
        // The production-observable consequence: findAll returns size>1, causing the error
        // rather than .find returning the first and silently writing it.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def numOpWritten = writtenFields["numOp.1"] == "variable"
            def extra = [
                [name: "xVarV.1", type: "enum", options: ["dst": "dst"]],
                [name: "numOp.1", type: "enum", options: ["number": "Number", "variable": "From variable"]],
                [name: "valNumber.1", type: "number"]
            ]
            if (numOpWritten) {
                extra << [name: "xVar3.1", type: "enum", options: ["src": "src"]]
                extra << [name: "xVar4.1", type: "enum", options: ["src": "src"]]  // second match -- ambiguous
            }
            modeActsDoActPageJson(100, extra, { 1 })
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "setVariable", variable: "dst", sourceVariable: "src"],
            confirm: true
        ])

        then: "ambiguous schema causes loud failure naming both candidates"
        result.success == false
        result.error?.contains("2 candidate")
        result.error?.contains("xVar3.1")
        result.error?.contains("xVar4.1")
    }

    // mode action modeName resolution

    def "addAction mode with modeName resolves to mode ID before writing"() {
        // Writing the name literal to mode.<N> produces 'Mode: null' in RM render.
        // The fix resolves modeName to an integer ID via location.modes first.
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "3", name: "Night"], [id: "1", name: "Home"]]
        def fetchSeq = 0
        def writtenFields = [:]

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            modeActsDoActPageJson(100, [
                [name: "mode.1", type: "enum", options: ["1": "Home", "3": "Night"]]
            ], { ++fetchSeq })
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "mode", modeName: "Night"],
            confirm: true
        ])

        then: "mode.1 receives the integer ID '3', not the string 'Night'"
        writtenFields["mode.1"].toString() == "3"
        result.success == true
    }

    def "addAction mode with modeId bypasses name resolution and writes ID directly"() {
        // Existing modeId path must not be broken by the modeName-resolution fix.
        given:
        enableHubAdminWrite()
        // location.modes left empty -- modeId path must not consult it
        sharedLocation.modes = []
        def fetchSeq = 0
        def writtenFields = [:]

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            modeActsDoActPageJson(100, [
                [name: "mode.1", type: "enum", options: ["5": "Day"]]
            ], { ++fetchSeq })
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "mode", modeId: 5],
            confirm: true
        ])

        then: "mode.1 receives the supplied modeId directly"
        writtenFields["mode.1"].toString() == "5"
        result.success == true
    }

    def "addAction mode with unknown modeName fails fast with available modes list"() {
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Home"], [id: "2", name: "Away"]]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "mode", modeName: "NoSuchMode"],
            confirm: true
        ])

        then: "validation error surfaces in result.error (toolUpdateNativeApp catches IAE from _rmAddAction)"
        result.success == false
        result.error?.contains("NoSuchMode")
        result.error?.contains("Home")
        result.error?.contains("Away")
    }

    def "addAction mode with modeName is case-insensitive"() {
        // Resolve 'night' (lowercase) -> ID '3' for mode named 'Night'.
        // Day(id=1) is listed FIRST so a first-entry-bias bug would return '1', not '3';
        // the assertion then fails, making this a discriminating regression guard.
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "3", name: "Night"]]
        def fetchSeq = 0
        def writtenFields = [:]

        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }

        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["modeActs": "Set Mode"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            modeActsDoActPageJson(100, [
                [name: "mode.1", type: "enum", options: ["1": "Day", "3": "Night"]]
            ], { ++fetchSeq })
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [capability: "mode", modeName: "night"],
            confirm: true
        ])

        then: "case-insensitive match for 'night' resolves to Night's ID '3', not Day's ID '1'"
        writtenFields["mode.1"].toString() == "3"
        result.success == true
    }

    // ---------- per-capability reveal sequences ----------
    //
    // Each spec models the REAL RM 5.1 schema progression for its capability:
    // the stub only reveals the next field AFTER the production write that
    // gates it is observed in the POST body (consequence-gated stubs). This
    // catches the root-cause bug (writing field B before re-fetching to check
    // A committed) rather than just asserting the final POST shape.
    //
    // Regression guards flagged below are "both-ways pending (orchestrator)":
    // the orchestrator must revert each guarded code path and confirm the spec
    // goes RED for the right reason before the guard is considered non-vacuous.

    // ---- Mode capability ----

    def "addRequiredExpression Mode condition: discovers modes picker via regex, writes resolved IDs"() {
        // Regression guard: Mode was Broken Condition because the old walker wrote
        // rCapab only and then wrote state_<N> -- but RM's Mode path reveals a
        // modes<N> picker (not state_<N>) after rCapab='Mode'.
        // The fix discovers the picker name from the live schema and writes IDs.
        // Consequence-gated: modes1 appears in the schema only after the stub
        // sees rCapab_1='Mode' in the POST body. Firmware field name: modes<cIdx>.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "3", name: "Night"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip the wizard-Done submit: Hubitat sends empty placeholders for all
            // wizard-consumed fields on Done, which would overwrite the real values.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1") rCapabWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF Mode is Night"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            // After rCapab_1='Mode' lands, modes1 appears (firmware field: modes<cIdx>). Before that, only the base fields.
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Mode", "Switch", "Motion"]],
                [name: "hasAll", type: "button"],
                [name: "doneST", type: "button"]
            ]
            if (rCapabWritten) {
                // modes1 revealed only after Mode is committed (firmware field: modes<cIdx>).
                inputs = inputs + [[name: "modes1", type: "enum",
                    options: ["1": "Day", "3": "Night"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
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

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Mode", state: "Night"]]],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "modes1 was written with the resolved ID for 'Night' (consequence-gated: appeared only after rCapab_1 landed)"
        // 'Night' resolves to ID '3'; walker serializes as a JSON-string on the wire.
        // Firmware field name is modes<cIdx> (e.g. modes1) -- NOT modes0_1 or modesX1.
        writtenFields["modes1"] == '["3"]'

        and: "rCapab_1 was set to the canonical 'Mode' value"
        writtenFields["rCapab_1"] == "Mode"

        and: "state_<N> was NOT written for Mode -- Mode uses the picker, not state_N"
        !writtenFields.containsKey("state_1")
    }

    def "addRequiredExpression Mode condition by modeIds: IDs written directly without name resolution"() {
        // Alternate Mode spec: pass modeIds directly to skip name->ID resolution.
        // Fixture uses ID "99" which is NOT present in location.modes -- proves the modeIds
        // path bypasses _rmResolveModeIds entirely (no name lookup, no KeyError on "99").
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        // ID "99" intentionally absent from modes list -- proves the bypass path doesn't consult it.
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "3", name: "Night"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1") rCapabWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF Mode is 99"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                [name: "hasAll", type: "button"],
                [name: "doneST", type: "button"]
            ]
            if (rCapabWritten) {
                // Picker includes "99" so the write is accepted by the stub.
                inputs = inputs + [[name: "modes1", type: "enum", options: ["1": "Day", "3": "Night", "99": "Hidden"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
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

        when:
        // modeIds: ["99"] -- ID 99 is not in location.modes (proves name resolution is bypassed)
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Mode", modeIds: ["99"]]]],
            confirm: true
        ])

        then: "success with the provided ID written directly (serialized as JSON-string on the wire)"
        result.success == true
        writtenFields["modes1"] == '["99"]'

        and: "state_1 was NOT written for Mode-by-modeIds (picker write, not state_N) -- parity with doActPage sibling"
        !writtenFields.containsKey("state_1")
    }

    def "addRequiredExpression Mode condition: fail-loud when modes picker not revealed"() {
        // When rCapab='Mode' does NOT cause the modes picker to appear (firmware
        // gap or schema version mismatch), the walker must cancel and throw rather
        // than silently writing nothing. Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "3", name: "Night"]]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // STPage never reveals modes1 -- static schema simulates firmware gap.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond", type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                                 [name: "hasAll", type: "button"],
                                 [name: "doneST", type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Mode", state: "Night"]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("expected modes") &&
            result.error?.toString()?.contains("picker after rCapab='Mode'")
    }

    def "addRequiredExpression Mode condition: fail-loud when neither state nor modeIds is provided"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "3", name: "Night"]]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond", type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Mode"]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        // Pin the distinctive phrase from the production message (L20011) -- not a loose 5-char substring.
        result.error?.toString()?.contains("'state' (mode name) or 'modeIds'")
    }

    // ---- Between two times capability ----

    def "addRequiredExpression Between two times: clock start + sunrise end -- reveal sequence produces all four fields"() {
        // Regression guard: 'Between two times' was Broken Condition because the
        // old walker used wrong field names (startType_N, startTime_N, stopType_N,
        // stopOffset_N). Firmware uses: starting<N> (type enum), startingA<N> (clock time
        // ISO datetime), ending<N> (end-type enum), endSunriseOffset<N> (offset minutes).
        // Type wire values: 'A specific time' (not 'clock'), 'Sunrise', 'Sunset'.
        // Clock time wire format: ISO datetime '2000-01-01THH:mm:00.000+HHMM' with hub-local TZ offset.
        // sharedLocation defaults to UTC so the fixture produces '+0000' for the assertion.
        // Consequence-gated: each field appears only after the preceding write lands.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def startingWritten = false
        def startingAWritten = false
        def endingWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fieldName = m[0][1]
                        writtenFields[fieldName] = v
                        if (fieldName == "rCapab_1")  rCapabWritten = true
                        if (fieldName == "starting1") startingWritten = true
                        if (fieldName == "startingA1") startingAWritten = true
                        if (fieldName == "ending1")   endingWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF Between 22:00 and sunrise"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Between two times", "Switch"]],
                [name: "hasAll", type: "button"],
                [name: "doneST", type: "button"]
            ]
            // Firmware field names: starting<cIdx>, startingA<cIdx>, ending<cIdx>, endSunriseOffset<cIdx>
            if (rCapabWritten) {
                inputs = inputs + [[name: "starting1", type: "enum",
                    options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            }
            if (startingWritten) {
                inputs = inputs + [[name: "startingA1", type: "time"]]
            }
            if (startingAWritten) {
                inputs = inputs + [[name: "ending1", type: "enum",
                    options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            }
            if (endingWritten) {
                inputs = inputs + [[name: "endSunriseOffset1", type: "number"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
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

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "22:00"],
                end: [type: "sunrise", offset: 30]
            ]]],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "starting1 written with the wire value for clock (consequence-gated: appeared only after rCapab)"
        // Firmware enum value for clock is 'A specific time' (not 'clock')
        writtenFields["starting1"] == "A specific time"

        and: "startingA1 written with ISO datetime with hub-local TZ offset (consequence-gated: appeared only after starting1)"
        // sharedLocation.timeZone defaults to UTC -> offset=0 -> '+0000'. On a UTC-5 hub this
        // would be '-0500', keeping the wall-clock time correct in the hub's local display.
        writtenFields["startingA1"] == "2000-01-01T22:00:00.000+0000"

        and: "ending1 written with Sunrise (consequence-gated: appeared only after startingA1)"
        writtenFields["ending1"] == "Sunrise"

        and: "endSunriseOffset1 written with 30 (consequence-gated: appeared only after ending1)"
        writtenFields["endSunriseOffset1"].toString() == "30"
    }

    def "addRequiredExpression Between two times: fail-loud when start-type selector not revealed"() {
        // Confirms the walker throws rather than silently skipping when the
        // expected reveal does not materialise. Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // STPage never reveals starting1 (simulates firmware that doesn't support this path).
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond", type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "22:00"],
                end: [type: "sunrise", offset: 30]
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("start-type selector")
    }

    def "addRequiredExpression Between two times: fail-loud with 'offset' field hint when sunrise start value not revealed"() {
        // W-spec-clock-aware-error-sunrise (start side): when type='sunrise' and the
        // start-value reveal fails, the error must mention "'offset' field" not "'time' field"
        // or the wrong field name "'offset' field (startingA<N>)". Verifies the
        // startFieldHint fix (non-clock types say "'offset' field (firmware-assigned)").
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        // Track rCapab write so the STPage stub can reveal starting1 progressively.
        def rCapabWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage") {
                if (body.toString().contains("rCapab_1")) rCapabWritten = true
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // After rCapab write: starting1 appears but startSunriseOffset1 never appears.
        // This simulates the "start-value reveal failed" path for sunrise type.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "starting1", type: "enum",
                    options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: [rCapabWritten ? "post-cap" : "pre-cap"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "start.type='sunrise' but the offset field (startSunriseOffset<N>) never appears"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "sunrise", offset: 30],
                end: [type: "clock", time: "07:00"]
            ]]],
            confirm: true
        ])

        then: "fail-loud: success=false mentioning 'offset' field (firmware-assigned) (not 'time' field or 'startingA<N>')"
        result.success == false
        result.error?.toString()?.contains("'offset' field")
        // Pinned to the symmetric 'firmware-assigned' phrasing (W-N.34-endFieldHint-symmetric).
        // Start and end sides emit the same hint for sunrise/sunset because the live field
        // name is firmware-version-dependent (startingA<N> vs startSunriseOffset<N>).
        result.error?.toString()?.contains("'offset' field (firmware-assigned)")
        !result.error?.toString()?.contains("'time' field")
        !result.error?.toString()?.contains("startingA")
    }

    def "addRequiredExpression Between two times: fail-loud with 'offset' field hint when sunset end value not revealed"() {
        // W-spec-clock-aware-error-sunrise (end side): when type='sunset' and the
        // end-value reveal fails, the error must mention "'offset' field".
        // Mirrors the start-side spec above. Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def startingWritten = false
        def endingWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage") {
                def bodyStr = body.toString()
                if (bodyStr.contains('"rCapab_1"') || bodyStr.contains('[rCapab_1]')) startingWritten = true
                if (bodyStr.contains('"starting1"') || bodyStr.contains('[starting1]')) endingWritten = true
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                [name: "hasAll",   type: "button"]
            ]
            if (startingWritten) {
                inputs = inputs + [
                    [name: "starting1",  type: "enum", options: ["A specific time": "A specific time"]],
                    [name: "startingA1", type: "text"]
                ]
            }
            if (endingWritten) {
                // ending1 appears after start fields, but endSunriseOffset1 never appears.
                inputs = inputs + [[name: "ending1", type: "enum",
                    options: ["A specific time": "A specific time", "Sunset": "Sunset"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["${inputs.size()}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "end.type='sunset' but endSunriseOffset<N> never appears"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "08:00"],
                end: [type: "sunset", offset: 0]
            ]]],
            confirm: true
        ])

        then: "fail-loud: success=false mentioning 'offset' field (firmware-assigned) (not 'time' field or endingA<N>)"
        result.success == false
        result.error?.toString()?.contains("'offset' field")
        // End and start sides emit the SAME 'firmware-assigned' hint for the
        // sunrise/sunset branch (W-N.34-endFieldHint-symmetric). A regression that
        // names a single candidate ('endSunriseOffset<N>' OR 'endingA<N>') would
        // mislead callers when firmware uses the other variant.
        result.error?.toString()?.contains("'offset' field (firmware-assigned)")
        !result.error?.toString()?.contains("'time' field")
        !result.error?.toString()?.contains("endingA<N>")
        !result.error?.toString()?.contains("endSunriseOffset<N>")
    }

    // ---- Variable comparison capability ----

    def "addRequiredExpression Variable: discovers variable picker, validates name, writes comparator chain"() {
        // Variable comparison in RE: the comparator/value fields are gated on the
        // variable-picker write; the walker must re-fetch between writes or the
        // comparator/value silently drop. The picker name is firmware-assigned and
        // discovered by regex; names are validated against the revealed enum.
        // Consequence-gated: lVar_1 appears only after rCapab_1='Variable' lands.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def varPickerWritten = false
        def relrWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "rCapab_1") rCapabWritten = true
                        if (fn == "lVar_1") varPickerWritten = true
                        if (fn == "RelrDev_1") relrWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF myVar = 42"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Variable", "Switch"]],
                [name: "hasAll", type: "button"],
                [name: "doneST", type: "button"]
            ]
            if (rCapabWritten) {
                // lVar_1 is the variable-name picker revealed after Variable is chosen.
                inputs = inputs + [[name: "lVar_1", type: "enum",
                    options: ["myVar": "myVar", "otherVar": "otherVar"]]]
            }
            if (varPickerWritten) {
                inputs = inputs + [[name: "RelrDev_1", type: "enum",
                    options: ["=", "!=", "<", ">"]]]
            }
            if (relrWritten) {
                inputs = inputs + [[name: "state_1", type: "number"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
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

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable",
                variable: "myVar",
                comparator: "=",
                value: 42
            ]]],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "lVar_1 written with the variable name (consequence-gated: appeared only after Variable chosen)"
        writtenFields["lVar_1"] == "myVar"

        and: "RelrDev_1 written with normalized comparator (consequence-gated: appeared only after lVar_1 was written)"
        writtenFields["RelrDev_1"] == "="

        and: "state_1 written with the value (consequence-gated: appeared only after RelrDev_1 was written)"
        writtenFields["state_1"].toString() == "42"
    }

    def "addRequiredExpression Variable: fail-loud when variable picker not revealed"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // Static schema: no variable picker ever appears.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond", type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Variable"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable", variable: "myVar", comparator: "=", value: 1
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("variable-name picker not revealed")
    }

    def "addRequiredExpression Variable: fail-loud when variable name not in revealed enum"() {
        // Validates the name against the live schema enum; unknown name must throw.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                def m = body.find { k, v -> k.toString() == "settings[rCapab_1]" }
                if (m) rCapabWritten = true
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Variable"]],
                [name: "hasAll", type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "lVar_1", type: "enum",
                    options: ["existingVar": "existingVar"]]]  // 'unknownVar' is NOT here
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable", variable: "unknownVar", comparator: "=", value: 1
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false naming the unknown variable and available options"
        result.success == false
        result.error?.toString()?.contains("unknownVar")
        result.error?.toString()?.contains("existingVar")
    }

    // ---- Custom Attribute capability ----

    def "addRequiredExpression Custom Attribute: writes RelrDev_N and state_N through the walker"() {
        // Validates the walker correctly writes BOTH RelrDev_<N> and state_<N> for a
        // Custom Attribute condition with explicit comparator + value. The stub's
        // consequence-gated reveal (RelrDev_<N> appears only after rCustomAttr_<N>
        // is observed in a POST) exercises the walker's post-rCustomAttr re-fetch
        // path, but cannot model the real-hub async-render race that the silent-drop
        // bug actually exhibits -- that requires distinguishing "rCustomAttr_<N> and
        // RelrDev_<N> in one POST body" from "two sequential POSTs", a distinction
        // outside this stub's wire-format model. Live-hub validation via the wizard
        // probe matrix remains the primary signal for the silent-drop class of bugs.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCustomAttrWritten = false
        def relrWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fieldName = m[0][1]
                        writtenFields[fieldName] = v
                        if (fieldName == "rCustomAttr_1") rCustomAttrWritten = true
                        if (fieldName == "RelrDev_1") relrWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF humidity < 40"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Custom Attribute", "Switch"]],
                [name: "rDev_1", type: "capability.sensor", multiple: true],
                [name: "rCustomAttr_1", type: "enum", options: ["humidity", "battery"]],
                [name: "hasAll", type: "button"],
                [name: "doneST", type: "button"]
            ]
            // RelrDev_1 is only revealed AFTER rCustomAttr_1 commits. Without the
            // re-fetch fix, the walker would write RelrDev_1 before seeing it here
            // and the write would silently be rejected.
            if (rCustomAttrWritten) {
                inputs = inputs + [
                    [name: "RelrDev_1", type: "enum", options: ["<", "<=", ">", ">=", "=", "!="]],
                ]
            }
            if (relrWritten) {
                inputs = inputs + [
                    [name: "state_1", type: "number"]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
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
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"HumiditySensor"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute",
                deviceIds: [8],
                attribute: "humidity",
                comparator: "<",
                value: 40
            ]]],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "RelrDev_1 was written with normalized comparator (consequence-gated: appeared only after rCustomAttr_1)"
        writtenFields["RelrDev_1"] == "<"

        and: "state_1 was written with the value (consequence-gated: appeared only after RelrDev_1)"
        writtenFields["state_1"].toString() == "40"
    }

    def "addRequiredExpression Custom Attribute: fail-loud when RelrDev not revealed after rCustomAttr"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // RelrDev_1 never appears -- simulates firmware that doesn't reveal it.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond", type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Custom Attribute"]],
                                 [name: "rDev_1", type: "capability.sensor", multiple: true],
                                 [name: "rCustomAttr_1", type: "enum", options: ["humidity"]],
                                 [name: "hasAll", type: "button"]
                                 // RelrDev_1 intentionally absent
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute", deviceIds: [8],
                attribute: "humidity", comparator: "<", value: 40
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("RelrDev_<N> (comparator) not revealed after rCustomAttr_<N>")
    }

    // ---- Between two times: additional negative pins (W2) ----

    def "addRequiredExpression Between two times: fail-loud when start.time missing for clock type"() {
        // start.type='clock' requires start.time; the walker validates before any hub writes.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                                 [name: "hasAll",   type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "start.time is absent for clock type"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock"],     // no time
                end:   [type: "sunrise", offset: 30]
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("start.'time'")
    }

    def "addRequiredExpression Between two times: fail-loud when end.offset missing for sunrise type"() {
        // end.type='sunrise' requires end.offset; validated before hub writes.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                                 [name: "hasAll",   type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "end.offset is absent for sunrise type"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "22:00"],
                end:   [type: "sunrise"]    // no offset
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("end.'offset'")
    }

    def "addRequiredExpression Between two times: non-UTC TZ -- anchor-date offset used, not now() offset"() {
        // Proves getOffset(anchorMs) is exercised: Eastern time (EST = UTC-5 in January).
        // If getOffset(now()) were used instead, the offset would differ in summer (EDT = -0400).
        // Anchor date 2000-01-01 is always winter in Eastern TZ, so offset is always -0500.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.timeZone = TimeZone.getTimeZone("US/Eastern")  // EST = -0500 for Jan anchor
        def rCapabWritten = false
        def startingWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")  rCapabWritten = true
                        if (m[0][1] == "starting1") startingWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF Between 22:00 and 07:00"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        def stFetchSeq = 0
        def startingAWritten = false
        def endingWritten = false
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                [name: "hasAll", type: "button"], [name: "doneST", type: "button"]
            ]
            if (rCapabWritten)   inputs = inputs + [[name: "starting1", type: "enum",
                options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            if (startingWritten) inputs = inputs + [[name: "startingA1", type: "time"]]
            if (startingAWritten) inputs = inputs + [[name: "ending1", type: "enum",
                options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            if (endingWritten)   inputs = inputs + [[name: "endingA1", type: "time"]]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        // Track startingA and ending writes via POST interception after stub setup
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")   rCapabWritten = true
                        if (m[0][1] == "starting1")  startingWritten = true
                        if (m[0][1] == "startingA1") startingAWritten = true
                        if (m[0][1] == "ending1")    endingWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "22:00"],
                end:   [type: "clock", time: "07:00"]
            ]]],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "startingA1 carries the EST (-0500) offset for the January anchor date"
        // EST offset for 2000-01-01: US/Eastern in January is always -0500 (no DST).
        // Regression to UTC +0000 would produce '2000-01-01T22:00:00.000+0000' instead.
        writtenFields["startingA1"] == "2000-01-01T22:00:00.000-0500"

        and: "endingA1 carries the EST (-0500) offset"
        writtenFields["endingA1"] == "2000-01-01T07:00:00.000-0500"
    }

    def "addRequiredExpression Between two times: start sunrise + end clock -- start field not clock, end clock revealed"() {
        // Exercises the start-sunrise branch (no startingA) and end-clock (endingA) branches.
        // Critical: if firmware uses a different field for start-sunrise offset (e.g.
        // startSunriseOffset<N>), the existing startingA regex /startingA\d+/ would miss it.
        // This spec documents what the stub encodes; live firmware shape verified separately.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def startingWritten = false
        def endingWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")  rCapabWritten = true
                        if (m[0][1] == "starting1") startingWritten = true
                        if (m[0][1] == "ending1")   endingWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF Between sunrise+30m and 07:00"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        def stFetchSeq = 0
        def startingAWritten = false
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                [name: "hasAll", type: "button"], [name: "doneST", type: "button"]
            ]
            if (rCapabWritten)   inputs = inputs + [[name: "starting1", type: "enum",
                options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            // Sunrise branch: startingA1 still appears but represents the offset (number field, not time).
            // Stub: pick startingA1 as the sunrise-offset field name (firmware may use a different name;
            // see note in spec comment above -- this is the stub's choice, matching /startingA\d+/).
            if (startingWritten) inputs = inputs + [[name: "startingA1", type: "number"]]
            if (startingAWritten) inputs = inputs + [[name: "ending1", type: "enum",
                options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            if (endingWritten)   inputs = inputs + [[name: "endingA1", type: "time"]]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")   rCapabWritten = true
                        if (m[0][1] == "starting1")  startingWritten = true
                        if (m[0][1] == "startingA1") startingAWritten = true
                        if (m[0][1] == "ending1")    endingWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [[name: "actionCancel", type: "button"]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "sunrise", offset: 30],
                end:   [type: "clock", time: "07:00"]
            ]]],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "starting1 written with Sunrise wire value"
        writtenFields["starting1"] == "Sunrise"

        and: "startingA1 written with the raw sunrise offset (not converted to ISO datetime)"
        // Sunrise branch writes the offset minutes directly; no toIsoTime conversion.
        writtenFields["startingA1"].toString() == "30"

        and: "ending1 written with 'A specific time' wire value"
        writtenFields["ending1"] == "A specific time"

        and: "endingA1 written with UTC ISO datetime (UTC fixture, so +0000)"
        writtenFields["endingA1"] == "2000-01-01T07:00:00.000+0000"
    }

    def "addRequiredExpression Between two times: requires start and end Maps"() {
        // Validation before any hub write: start/end must both be Maps.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "start is not a Map"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: "notAMap",
                end: [type: "clock", time: "07:00"]
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("'start' and 'end' Maps")
    }

    def "addRequiredExpression Between two times: fail-loud when start.type is invalid"() {
        // start.type must be 'clock', 'sunrise', or 'sunset'.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "badvalue"],
                end: [type: "clock", time: "07:00"]
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false"
        result.success == false
        result.error?.toString()?.contains("start.type must be")
    }

    def "addRequiredExpression Between two times: fail-loud when end.type is invalid"() {
        // end.type must be 'clock', 'sunrise', or 'sunset'.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "22:00"],
                end: [type: "badvalue"]
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false"
        result.success == false
        result.error?.toString()?.contains("end.type must be")
    }

    // ---- Variable: additional negative pins (W2) ----

    def "addRequiredExpression Variable: fail-loud when RelrDev not revealed after picker write"() {
        // The comparator field (RelrDev_<N>) must be gated on the variable-picker write.
        // When it never appears, the walker must cancel and throw.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                if (body.find { k, v -> k.toString() == "settings[rCapab_1]" }) rCapabWritten = true
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // lVar_1 appears (picker exists), but RelrDev_1 never appears even after lVar_1 write.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Variable"]],
                [name: "hasAll", type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "lVar_1", type: "enum", options: ["myVar": "myVar"]]]
            }
            // RelrDev_1 never added -- simulates firmware that doesn't reveal it here.
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable", variable: "myVar", comparator: "=", value: 1
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("RelrDev_<N> (comparator) not revealed after variable name write")
    }

    def "addRequiredExpression Variable: fail-loud when state field not revealed after RelrDev write"() {
        // The value field (state_<N>) must be gated on the RelrDev_<N> write.
        // When it never appears, the walker must cancel and throw.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def varPickerWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        if (fn == "rCapab_1") rCapabWritten = true
                        if (fn == "lVar_1")   varPickerWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // lVar_1 and RelrDev_1 reveal normally, but state_1 never appears.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Variable"]],
                [name: "hasAll", type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "lVar_1", type: "enum", options: ["myVar": "myVar"]]]
            }
            if (varPickerWritten) {
                inputs = inputs + [[name: "RelrDev_1", type: "enum", options: ["=", "!="]]]
            }
            // state_1 never added.
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable", variable: "myVar", comparator: "=", value: 1
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("state_<N> (value field) not revealed after RelrDev write")
    }

    // ---- Custom Attribute: additional negative pin (W2) ----

    def "addRequiredExpression Custom Attribute: fail-loud when state not revealed after RelrDev write"() {
        // The value field (state_<N>) must be gated on RelrDev_<N> write.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCustomAttrWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m && m[0][1] == "rCustomAttr_1") rCustomAttrWritten = true
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // RelrDev_1 appears after rCustomAttr_1, but state_1 never appears.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",          type: "enum",              options: ["a": "New condition"]],
                [name: "rCapab_1",      type: "enum",              options: ["Custom Attribute"]],
                [name: "rDev_1",        type: "capability.sensor", multiple: true],
                [name: "rCustomAttr_1", type: "enum",              options: ["humidity"]],
                [name: "hasAll",        type: "button"]
            ]
            if (rCustomAttrWritten) {
                inputs = inputs + [[name: "RelrDev_1", type: "enum", options: ["<", "<=", ">", ">="]]]
            }
            // state_1 intentionally never added.
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute", deviceIds: [8],
                attribute: "humidity", comparator: "<", value: 40
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("state_<N> (value) not revealed after RelrDev write")
    }

    // ---- compareToDevice capability (B3) ----

    def "addRequiredExpression compareToDevice: RHS-type selector revealed -- RelrDev and rhsType written"() {
        // Device-relative comparison: after RelrDev write reveals the RHS-type selector,
        // the walker chooses the 'another device' option and writes it. The secondary
        // reference-device/attribute/offset fields are discovered via empty-trigger
        // _rmRevealStep calls (no write to trigger their appearance), so they are only
        // written if they appear as newly-visible fields at that point. This spec asserts
        // the core discovery path (RelrDev -> rhsType reveal -> 'another device' write).
        // Consequence-gated: rhsType_1 appears only after RelrDev_1 is written.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def relrWritten = false
        def rhsTypeWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "RelrDev_1")  relrWritten = true
                        if (fn == "rhsType_1")  rhsTypeWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF Temperature > Sensor2"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            // Base fields always present; rhsType_1 revealed after RelrDev_1 write;
            // refDev_1 and refAttr_1 revealed after rhsType_1 write; offset_1 always
            // present once device-relative path is active.
            def inputs = [
                [name: "cond",       type: "enum",                options: ["a": "New condition"]],
                [name: "rCapab_1",   type: "enum",                options: ["Temperature", "Humidity"]],
                [name: "rDev_1",     type: "capability.sensor",   multiple: true],
                [name: "RelrDev_1",  type: "enum",                options: [">", ">=", "<", "<=", "="]],
                [name: "hasAll",     type: "button"],
                [name: "doneST",     type: "button"]
            ]
            if (relrWritten) {
                inputs = inputs + [[name: "rhsType_1", type: "enum",
                    options: ["literal": "A literal value", "device": "Another device's attribute"]]]
            }
            if (rhsTypeWritten) {
                inputs = inputs + [
                    [name: "refDev_1",  type: "capability.sensor", multiple: false],
                    [name: "refAttr_1", type: "enum", options: ["temperature", "humidity"]],
                    [name: "offset_1",  type: "number"]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                compareToDevice: [deviceId: 99, attribute: "temperature", offset: -2]
            ]]],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "RelrDev_1 written with comparator (consequence-gated: rhsType appears only after RelrDev)"
        writtenFields["RelrDev_1"] == ">"

        and: "rhsType_1 written with the 'another device' option key"
        writtenFields["rhsType_1"] == "device"

        and: "no partial/degradation sentinel -- RHS-type selector path succeeded"
        result.partial != true
    }

    def "addRequiredExpression compareToDevice: fail-loud when RelrDev not visible after base writes"() {
        // The walker does a direct fetch after rCapab/rDev writes and fails loud if
        // RelrDev_<N> is absent. Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // RelrDev_1 intentionally absent from static schema.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum",              options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum",              options: ["Temperature"]],
                                 [name: "rDev_1",   type: "capability.sensor", multiple: true],
                                 [name: "hasAll",   type: "button"]
                                 // RelrDev_1 intentionally absent
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                compareToDevice: [deviceId: 99, attribute: "temperature"]
            ]]],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("RelrDev_<N> not visible after rCapab/rDev")
    }

    def "addRequiredExpression compareToDevice: firmware fallback -- RHS-type not revealed, partial=true sentinel"() {
        // When the RHS-type selector does not appear after RelrDev write (older firmware),
        // the walker falls back to writing literal state_<N> and pushes a sentinel to
        // skipped so the caller can detect the degraded write. result.partial must be true.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def relrWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "RelrDev_1") relrWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF Temperature > 20"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // RelrDev_1 always present (base write succeeds); rhsType_1 never appears (old firmware).
            def inputs = [
                [name: "cond",      type: "enum",                options: ["a": "New condition"]],
                [name: "rCapab_1",  type: "enum",                options: ["Temperature"]],
                [name: "rDev_1",    type: "capability.sensor",   multiple: true],
                [name: "RelrDev_1", type: "enum",                options: [">", "<", "="]],
                [name: "state_1",   type: "number"],
                [name: "hasAll",    type: "button"],
                [name: "doneST",    type: "button"]
            ]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                // state is provided so the fallback can write something; fallbackApplied=true.
                state: 70,
                compareToDevice: [deviceId: 99, attribute: "temperature"]
            ]]],
            confirm: true
        ])

        then: "result is successful (expression committed)"
        result.success == true

        and: "partial=true signals that the compareToDevice was not fully written (fallback path)"
        result.partial == true

        and: "settingsSkipped contains the compareToDevice sentinel with rhs_type_not_revealed reason"
        def sentinel = (result.settingsSkipped as List)?.find { it instanceof Map && it.key == "compareToDevice" }
        sentinel != null
        sentinel.reason == "rhs_type_not_revealed"

        and: "fallbackApplied=true because state was provided and written as literal state_<N> fallback"
        // Production: fallbackApplied = (cond.state != null || cond.value != null).
        // Both-ways: if cond.state is removed, this assertion flips to false (vacuous guard test).
        sentinel.fallbackApplied == true
    }

    def "addRequiredExpression compareToDevice: fallbackApplied=false when no state or value provided"() {
        // When rhsType is not revealed AND no state/value was given, the sentinel
        // must report fallbackApplied=false (condition will be incomplete).
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // Static schema: RelrDev_1 is present, rhsType never appears.
        def relrPresent = true
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",      type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",  type: "enum", options: ["Temperature"]],
                                 [name: "rDev_1",    type: "capability.sensor", multiple: true],
                                 [name: "RelrDev_1", type: "enum", options: [">", "<"]],
                                 [name: "hasAll",    type: "button"]
                                 // rhsType intentionally never added
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"T2"}' }

        when: "compareToDevice with no state or value"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                compareToDevice: [deviceId: 9, attribute: "temperature"]
                // no state, no value
            ]]],
            confirm: true
        ])

        then: "partial result with compareToDevice sentinel"
        result.partial == true
        def s = (result.settingsSkipped as List)?.find { it instanceof Map && it.key == "compareToDevice" }
        s != null
        s.reason == "rhs_type_not_revealed"

        and: "fallbackApplied=false because no state/value was provided to write as fallback"
        s.fallbackApplied == false
    }

    def "addRequiredExpression Variable: fail-loud when 'variable' field missing"() {
        // Variable condition validation: 'variable' is required.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "rCapab_1", type: "enum", options: ["Variable"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "variable field is absent"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable",
                comparator: "=",
                value: 42
            ]]],
            confirm: true
        ])

        then: "fail-loud -- variable name required"
        result.success == false
        result.error?.toString()?.contains("requires 'variable'")
    }

    def "addRequiredExpression Variable: fail-loud when 'comparator' field missing"() {
        // Variable condition validation: 'comparator' is required.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "rCapab_1", type: "enum", options: ["Variable"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "comparator field is absent"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable",
                variable: "myVar"
            ]]],
            confirm: true
        ])

        then: "fail-loud -- comparator required"
        result.success == false
        result.error?.toString()?.contains("requires 'comparator'")
    }

    def "addRequiredExpression Custom Attribute: fail-loud when attribute set but comparator missing"() {
        // Custom Attribute validation: both attribute AND comparator required when one is present.
        // This covers the first throw at L20246: attribute!=null && comparator==null.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "rCapab_1", type: "enum", options: ["Custom Attribute"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S"}' }

        when: "attribute present but comparator absent"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute",
                deviceIds: [8],
                attribute: "humidity"
                // no comparator
            ]]],
            confirm: true
        ])

        then: "fail-loud -- comparator required alongside attribute"
        result.success == false
        result.error?.toString()?.contains("requires both 'attribute'")
        result.error?.toString()?.contains("'comparator'")
    }

    def "addRequiredExpression Custom Attribute: fail-loud when comparator set but attribute missing"() {
        // The second Custom Attribute validation: comparator!=null && attribute==null.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "rCapab_1", type: "enum", options: ["Custom Attribute"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "comparator present but attribute absent"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute",
                comparator: "="
                // no attribute
            ]]],
            confirm: true
        ])

        then: "fail-loud -- attribute required alongside comparator"
        result.success == false
        result.error?.toString()?.contains("requires both 'attribute'")
        result.error?.toString()?.contains("'comparator'")
    }

    def "addRequiredExpression compareToDevice: fail-loud when deviceId missing"() {
        // compareToDevice.deviceId is required.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "rCapab_1", type: "enum", options: ["Temperature"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                compareToDevice: [attribute: "temperature"]  // no deviceId
            ]]],
            confirm: true
        ])

        then: "fail-loud -- deviceId required"
        result.success == false
        result.error?.toString()?.contains("requires 'deviceId'")
    }

    def "addRequiredExpression compareToDevice: fail-loud when attribute missing"() {
        // compareToDevice.attribute is required.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "rCapab_1", type: "enum", options: ["Temperature"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["s1"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                compareToDevice: [deviceId: 9]  // no attribute
            ]]],
            confirm: true
        ])

        then: "fail-loud -- attribute required"
        result.success == false
        result.error?.toString()?.contains("requires 'attribute'")
    }

    def "addRequiredExpression Mode: multi-mode by name list -- both names resolved, both IDs written"() {
        // Exercises state: ['Day', 'Night'] (List form of state).
        // Both names are resolved via location.modes, both IDs written to modes<N>.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "3", name: "Night"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1") rCapabWritten = true
                    }
                }
            }
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
                                         body: [[element: "paragraph", description: "IF Mode in [Day, Night]"]]]]],
                settings: [useST: "true"], childApps: []
            ])
        }
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            stFetchSeq++
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                [name: "hasAll",   type: "button"], [name: "doneST", type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "modes1", type: "enum",
                    options: ["1": "Day", "3": "Night"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [[name: "actionCancel", type: "button"]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Mode",
                state: ["Day", "Night"]  // List form of state
            ]]],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "modes1 written with both resolved IDs (Day=1, Night=3)"
        // List form resolves both names; serialized as JSON-string list on the wire.
        // JsonOutput.toJson produces compact form without spaces (["1","3"]).
        writtenFields["modes1"] == '["1","3"]'
    }

    // ---------- addAction ifThen: walker parity with addRequiredExpression ----------
    //
    // The expressionSubtypes branch in _rmAddAction (getIfThen / getElseIf /
    // getWhile / getWaitRule) now delegates to _rmWalkConditionReveal with
    // page="doActPage", the same shared helper as addRequiredExpression (STPage).
    // These guards verify the per-capability reveal sequences fire correctly
    // on doActPage, covering the same bug classes the STPage guards cover:
    //
    //   Mode         -- modes picker revealed after rCapab='Mode'
    //   Variable     -- variable picker -> RelrDev -> state_N chain
    //   Custom Attr  -- rCustomAttr -> RelrDev -> state_N chain
    //   Between two times -- startType -> startVal -> stopType -> stopVal chain
    //   compareToDevice   -- rhsType reveal -> refDev/refAttr/offset
    //   fail-loud paths   -- same cancelCapab+throw contract as STPage callers
    //
    // Both-ways pending (orchestrator) for every guard below.

    // --- Shared helpers for addAction ifThen tests ---

    // Minimal selectActions schema for addAction tests: exposes actType picker with condActs.
    private String actSelectActionsJson(int ruleId) {
        ruleConfigJson(ruleId, "r", [[name: "actType.1", type: "enum",
            options: ["condActs": "Conditional Actions"]]])
    }

    // Minimal mainPage that shows a baked IF paragraph so post-commit check passes.
    private String actMainPageBakedJson(int ruleId, String para = "IF ... THEN") {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                         sections: [[title: "", input: [],
                                     paragraphs: [para]]]],
            settings: [:], childApps: []
        ])
    }

    def "addAction ifThen: Custom Attribute condition routes RelrDev_N and state_N through the walker"() {
        // Validates the walker correctly writes BOTH RelrDev_<N> and state_<N> for a
        // Custom Attribute condition with explicit comparator + value on doActPage.
        // The stub's consequence-gated reveal (RelrDev_<N> appears only after
        // rCustomAttr_<N> is observed in a POST) exercises the walker's
        // post-rCustomAttr re-fetch path, but cannot model the real-hub async-render
        // race that the silent-drop bug actually exhibits -- that requires
        // distinguishing "rCustomAttr_<N> and RelrDev_<N> in one POST body" from
        // "two sequential POSTs", a distinction outside this stub's wire-format
        // model. Live-hub validation via the wizard probe matrix remains the
        // primary signal for the silent-drop class of bugs.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCustomAttrWritten = false
        def relrWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // addAction does not go through _rmSubmitSubPageDone, so no _action_previous=Done;
            // all writes on doActPage are direct _rmWriteSettingOnPage calls.
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fieldName = m[0][1]
                        writtenFields[fieldName] = v
                        if (fieldName == "rCustomAttr_1") rCustomAttrWritten = true
                        if (fieldName == "RelrDev_1") relrWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        def doActFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            doActFetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Custom Attribute", "Switch"]],
                [name: "rDev_1", type: "capability.sensor", multiple: true],
                [name: "rCustomAttr_1", type: "enum", options: ["humidity", "battery"]],
                [name: "hasAll", type: "button"]
            ]
            // RelrDev_1 only appears AFTER rCustomAttr_1 commits (consequence gate).
            if (rCustomAttrWritten) {
                inputs = inputs + [[name: "RelrDev_1", type: "enum", options: ["<", "<=", ">", ">=", "=", "!="]]]
            }
            if (relrWritten) {
                inputs = inputs + [[name: "state_1", type: "number"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${doActFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "IF humidity < 40 THEN")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"HumiditySensor"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Custom Attribute",
                    deviceIds: [8],
                    attribute: "humidity",
                    comparator: "<",
                    value: 40
                ]]]
            ],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "RelrDev_1 was written with normalized comparator (consequence-gated: appeared only after rCustomAttr_1)"
        writtenFields["RelrDev_1"] == "<"

        and: "state_1 was written with the value (consequence-gated: appeared only after RelrDev_1)"
        writtenFields["state_1"].toString() == "40"

        and: "rCustomAttr_1 was written with the attribute name"
        writtenFields["rCustomAttr_1"] == "humidity"
    }

    def "addAction ifThen: Mode condition discovers modes picker via regex, writes resolved IDs"() {
        // Mode on doActPage: rCapab='Mode' causes modes<N> picker to appear.
        // The walker discovers the picker name from the live schema (not hardcoded) and
        // writes the resolved mode IDs. Without the walker, no modes picker was written
        // (the old code wrote rCapab and then tried state_N which does not exist for Mode).
        // Consequence-gated: modes1 appears only after rCapab_1='Mode' POST lands.
        // Firmware field name: modes<cIdx> (e.g. modes1). Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "3", name: "Night"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "rCapab_1") rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        def doActFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            doActFetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                [name: "hasAll", type: "button"]
            ]
            // modes1 only appears AFTER rCapab_1='Mode' commits (firmware field: modes<cIdx>).
            if (rCapabWritten) {
                inputs = inputs + [[name: "modes1", type: "enum", options: ["1": "Day", "3": "Night"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${doActFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "IF Mode is Night THEN")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[capability: "Mode", state: "Night"]]]
            ],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "modes1 was written with the resolved ID for 'Night' (consequence-gated)"
        // Firmware field name is modes<cIdx> (e.g. modes1) -- NOT modes0_1 or modesX1.
        writtenFields["modes1"] == '["3"]'

        and: "rCapab_1 was set to 'Mode'"
        writtenFields["rCapab_1"] == "Mode"

        and: "state_1 was NOT written for Mode (Mode uses the picker, not state_N)"
        !writtenFields.containsKey("state_1")
    }

    def "addAction ifThen: Variable condition discovers picker, validates name, writes comparator chain"() {
        // Variable on doActPage: rCapab='Variable' reveals variable-name picker, which
        // reveals RelrDev_<N>, which reveals state_<N>. The walker must re-fetch between
        // each write or the subsequent fields silently drop (same invariant as STPage).
        // Consequence-gated: lVar_1 only after rCapab_1='Variable'; RelrDev_1 only
        // after lVar_1; state_1 only after RelrDev_1.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def varPickerWritten = false
        def relrWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "rCapab_1") rCapabWritten = true
                        if (fn == "lVar_1") varPickerWritten = true
                        if (fn == "RelrDev_1") relrWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        def doActFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            doActFetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Variable", "Switch"]],
                [name: "hasAll", type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "lVar_1", type: "enum",
                    options: ["myVar": "myVar", "otherVar": "otherVar"]]]
            }
            if (varPickerWritten) {
                inputs = inputs + [[name: "RelrDev_1", type: "enum", options: ["=", "!=", "<", ">"]]]
            }
            if (relrWritten) {
                inputs = inputs + [[name: "state_1", type: "number"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${doActFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "IF myVar = 42 THEN")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Variable",
                    variable: "myVar",
                    comparator: "=",
                    value: 42
                ]]]
            ],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "lVar_1 written with variable name (consequence-gated: appeared only after Variable chosen)"
        writtenFields["lVar_1"] == "myVar"

        and: "RelrDev_1 written with comparator (consequence-gated: appeared only after lVar_1 written)"
        writtenFields["RelrDev_1"] == "="

        and: "state_1 written with value (consequence-gated: appeared only after RelrDev_1 written)"
        writtenFields["state_1"].toString() == "42"
    }

    def "addAction ifThen: Between two times reveals start/end chain"() {
        // Between two times on doActPage: rCapab reveal chain uses firmware field names
        // starting<N> (type enum), startingA<N> (ISO datetime with hub-local TZ offset),
        // ending<N> (end-type), endSunriseOffset<N> (offset minutes).
        // Type wire values: 'A specific time', 'Sunrise', 'Sunset'.
        // Clock time: ISO datetime '2000-01-01THH:mm:00.000+HHMM' with hub-local TZ offset.
        // sharedLocation defaults to UTC so the fixture produces '+0000' for the assertion.
        // Consequence-gated: each field only appears after the preceding one is committed.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def startingWritten = false
        def startingAWritten = false
        def endingWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "rCapab_1")   rCapabWritten = true
                        if (fn == "starting1")  startingWritten = true
                        if (fn == "startingA1") startingAWritten = true
                        if (fn == "ending1")    endingWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        def doActFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            doActFetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Between two times", "Switch"]],
                [name: "hasAll", type: "button"]
            ]
            // Firmware field names: starting<cIdx>, startingA<cIdx>, ending<cIdx>, endSunriseOffset<cIdx>
            if (rCapabWritten) {
                inputs = inputs + [[name: "starting1", type: "enum",
                    options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            }
            if (startingWritten) {
                inputs = inputs + [[name: "startingA1", type: "time"]]
            }
            if (startingAWritten) {
                inputs = inputs + [[name: "ending1", type: "enum",
                    options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            }
            if (endingWritten) {
                inputs = inputs + [[name: "endSunriseOffset1", type: "number"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${doActFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "IF between 22:00 and sunrise THEN")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Between two times",
                    start: [type: "clock", time: "22:00"],
                    end: [type: "sunrise", offset: 30]
                ]]]
            ],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "starting1 written with wire value for clock (consequence-gated: appeared only after rCapab)"
        // Firmware enum value for clock is 'A specific time' (not 'clock')
        writtenFields["starting1"] == "A specific time"

        and: "startingA1 written with ISO datetime with hub-local TZ offset (consequence-gated: appeared only after starting1)"
        // sharedLocation.timeZone defaults to UTC -> offset=0 -> '+0000'. On a UTC-5 hub this
        // would be '-0500', keeping the wall-clock time correct in the hub's local display.
        writtenFields["startingA1"] == "2000-01-01T22:00:00.000+0000"

        and: "ending1 written with Sunrise (consequence-gated: appeared only after startingA1)"
        writtenFields["ending1"] == "Sunrise"

        and: "endSunriseOffset1 written with 30 (consequence-gated: appeared only after ending1)"
        writtenFields["endSunriseOffset1"]?.toString() == "30"
    }

    def "addAction ifThen: compareToDevice writes rhsType/refDev/refAttr"() {
        // Device-relative comparison on doActPage: after rCapab+rDev+RelrDev writes,
        // the RHS-type selector appears and must be set to the 'another device' option,
        // which then reveals the reference device and attribute pickers.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def relrWritten = false
        def rhsTypeWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "RelrDev_1") relrWritten = true
                        if (fn == "stateType_1") rhsTypeWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        def doActFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            doActFetchSeq++
            def inputs = [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Temperature", "Switch"]],
                [name: "rDev_1", type: "capability.temperatureMeasurement", multiple: true],
                [name: "RelrDev_1", type: "enum", options: [">", "<", "=", ">=", "<="]],
                [name: "hasAll", type: "button"]
            ]
            if (relrWritten) {
                inputs = inputs + [[name: "stateType_1", type: "enum",
                    options: ["literal": "Literal value", "another device": "Another device"]]]
            }
            if (rhsTypeWritten) {
                // Stub picks rDev2_1 / rCustomAttr2_1 as the alternative names from production's
                // /rDev2_\d+|refDev_\d+|compareDevId_\d+/ and /rCustomAttr2_\d+|refAttr_\d+|compareAttr_\d+/ regexes.
                // Live firmware emit shape unverified independently; assertions pin to the stub's choice.
                inputs = inputs + [
                    [name: "rDev2_1", type: "capability.temperatureMeasurement", multiple: false],
                    [name: "rCustomAttr2_1", type: "enum", options: ["temperature"]]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${doActFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "IF Sensor1.temperature > Sensor2.temperature THEN")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"Sensor1"}' }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"Sensor2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Temperature",
                    deviceIds: [8],
                    comparator: ">",
                    compareToDevice: [deviceId: 9, attribute: "temperature"]
                ]]]
            ],
            confirm: true
        ])

        then: "result is successful"
        result.success == true

        and: "RelrDev_1 written with comparator"
        writtenFields["RelrDev_1"] == ">"

        and: "stateType_1 set to the 'another device' option key (exact match)"
        writtenFields["stateType_1"] == "another device"

        and: "reference device written with device ID 9 (scalar enum on the wire)"
        // Stub exposes rDev2_1; pin to exact key name (stub controls which alternative is in schema).
        writtenFields["rDev2_1"]?.toString() == "9"

        and: "reference attribute written with 'temperature'"
        // Stub exposes rCustomAttr2_1; pin to exact key name.
        writtenFields["rCustomAttr2_1"] == "temperature"
    }

    def "addAction ifThen: fail-loud when modes picker not revealed on doActPage"() {
        // When rCapab='Mode' does not cause modes picker to appear, the walker must
        // cancel and throw so the caller gets a diagnostic rather than a silent no-op.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "3", name: "Night"]]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // Static schema -- modes picker never appears regardless of what's written.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond", type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[capability: "Mode", state: "Night"]]]
            ],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("expected modes") &&
            result.error?.toString()?.contains("picker after rCapab='Mode'")
    }

    def "addAction ifThen: fail-loud when variable name not in revealed enum on doActPage"() {
        // Unknown variable name must throw with a message naming the bad variable
        // and the available options -- same contract as the STPage Variable path.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                def m = body.find { k, v -> k.toString() == "settings[rCapab_1]" }
                if (m) rCapabWritten = true
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond", type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Variable"]],
                [name: "hasAll", type: "button"]
            ]
            if (rCapabWritten) {
                // 'unknownVar' is NOT in this list
                inputs = inputs + [[name: "lVar_1", type: "enum", options: ["existingVar": "existingVar"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Variable", variable: "unknownVar", comparator: "=", value: 1
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud: success=false, error names the unknown variable and available options"
        result.success == false
        result.error?.toString()?.contains("unknownVar")
        result.error?.toString()?.contains("existingVar")
    }

    def "addAction ifThen: fail-loud when Custom Attribute RelrDev not revealed on doActPage"() {
        // When rCustomAttr_N is written but RelrDev_N never appears, the walker must
        // cancel and throw with a diagnostic -- same contract as the STPage Custom
        // Attribute path. The static stub simulates firmware that doesn't reveal RelrDev.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // RelrDev_1 is intentionally absent -- simulates firmware gap.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond", type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Custom Attribute"]],
                                 [name: "rDev_1", type: "capability.sensor", multiple: true],
                                 [name: "rCustomAttr_1", type: "enum", options: ["humidity"]],
                                 [name: "hasAll", type: "button"]
                                 // RelrDev_1 intentionally absent
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Custom Attribute", deviceIds: [8],
                    attribute: "humidity", comparator: "<", value: 40
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud: success=false with diagnostic referencing RelrDev not revealed after rCustomAttr"
        result.success == false
        result.error?.toString()?.contains("RelrDev_<N> (comparator) not revealed after rCustomAttr_<N>")
    }

    def "addAction ifThen: fail-loud surfaces wizardStuck when cancelCapab cleanup also fails"() {
        // When writing a condition fails AND the cancelCapab cleanup also throws,
        // the outer catch must decode the [wizardStuck] marker and set wizardStuck=true
        // in the response with a restoreHint pointing at doActPage cancelCapab.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "3", name: "Night"]]
        def cancelCapabCalled = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // _rmClickAppButton sends to /installedapp/btn; body contains settings[cancelCapab]=clicked
            if (path == "/installedapp/btn" && body["settings[cancelCapab]"] == "clicked") {
                cancelCapabCalled = true
                throw new RuntimeException("cancelCapab POST failed: hub timeout")
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // Static schema -- modes picker never appears, triggering the fail-loud path;
        // cancelCapab click then throws to exercise the wizardStuck encoding.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond", type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[capability: "Mode", state: "Night"]]]
            ],
            confirm: true
        ])

        then: "fail-loud surfaces wizardStuck and points at cancelCapab on doActPage"
        result.success == false
        result.wizardStuck == true
        result.restoreHint?.contains("cancelCapab")
        result.restoreHint?.contains("doActPage")
        result.error?.contains("wizardStuck")
    }

    def "addAction ifThen: Mode condition by modeIds -- IDs written directly, no name resolution"() {
        // Parity with addRequiredExpression Mode-by-modeIds spec: when modeIds is
        // provided instead of state, the walker writes the IDs directly without
        // attempting name->ID resolution via location.modes.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "3", name: "Night"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "rCapab_1") rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Mode", "Switch"]],
                [name: "hasAll",       type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "modes1", type: "enum", options: ["1": "Day", "3": "Night"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "IF Mode is Day THEN")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[capability: "Mode", modeIds: ["1"]]]]
            ],
            confirm: true
        ])

        then: "success with the provided ID written directly (serialized as JSON-string on the wire)"
        result.success == true
        // Firmware field name is modes<cIdx> (e.g. modes1) -- NOT modes0_1 or modesX1.
        writtenFields["modes1"] == '["1"]'

        and: "state_1 was NOT written for Mode-by-modeIds (picker write, not state_N)"
        !writtenFields.containsKey("state_1")
    }

    def "addAction ifThen: compareToDevice firmware fallback -- RHS-type not revealed, partial=true sentinel"() {
        // Parity with addRequiredExpression firmware-fallback spec: when the RHS-type
        // selector does not appear after RelrDev write, the walker falls back to writing
        // literal state_<N> and pushes a sentinel to skipped. result.partial must be true.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def actTypeWritten  = false
        def actSubTypeWritten = false
        def rCapabWritten   = false
        def relrWritten     = false
        def writtenFields   = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "actType.1")    actTypeWritten = true
                        if (fn == "actSubType.1") actSubTypeWritten = true
                        if (fn == "rCapab_1")     rCapabWritten = true
                        if (fn == "RelrDev_1")    relrWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // doActPage: schema grows progressively so each pre-condition write looks accepted.
        // RelrDev_1 and state_1 appear after rCapab is written; stateType_1 / rhsType_1
        // intentionally never appear (old firmware -- no device-relative RHS-type toggle).
        def doActFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            doActFetchSeq++
            def inputs = [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Temperature"]],
                [name: "hasAll",       type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",    type: "capability.sensor", multiple: true],
                    [name: "RelrDev_1", type: "enum", options: [">", "<", "="]],
                    [name: "state_1",   type: "number"]
                    // stateType_1 / rhsType_1 intentionally absent (firmware fallback)
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs,
                                         paragraphs: ["seq ${doActFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "IF Temperature > 20 THEN")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Temperature",
                    deviceIds: [8],
                    comparator: ">",
                    compareToDevice: [deviceId: 99, attribute: "temperature"]
                ]]]
            ],
            confirm: true
        ])

        then: "result is successful (action committed)"
        result.success == true

        and: "partial=true signals that the compareToDevice was not fully written (fallback path)"
        result.partial == true

        and: "settingsSkipped contains the compareToDevice sentinel with rhs_type_not_revealed reason"
        def sentinel = (result.settingsSkipped as List)?.find { it instanceof Map && it.key == "compareToDevice" }
        sentinel != null
        sentinel.reason == "rhs_type_not_revealed"

        and: "fallbackApplied=false because no state/value was provided to write as literal fallback"
        // Parity with addRequiredExpression fallbackApplied spec: production uses
        // (cond.state != null || cond.value != null); this input supplies neither.
        sentinel.fallbackApplied == false
    }

    def "addAction ifThen: fail-loud when compareToDevice RelrDev not visible after base writes"() {
        // Parity with addRequiredExpression compareToDevice fail-loud spec: when RelrDev_<N>
        // is absent from the schema after rCapab/rDev writes, the walker must fail loud.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // RelrDev_1 intentionally absent -- simulates firmware gap.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond",         type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",     type: "enum", options: ["Temperature"]],
                                 [name: "rDev_1",       type: "capability.sensor", multiple: true],
                                 [name: "hasAll",       type: "button"]
                                 // RelrDev_1 intentionally absent
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Temperature",
                    deviceIds: [8],
                    comparator: ">",
                    compareToDevice: [deviceId: 99, attribute: "temperature"]
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud surfaces as success:false with the diagnostic in result.error"
        result.success == false
        result.error?.toString()?.contains("RelrDev_<N> not visible after rCapab/rDev")
    }

    // ---------- W-N.35 doActPage fail-loud parity ----------
    //
    // These specs mirror the addRequiredExpression fail-loud guards on the addAction
    // (doActPage) path. Both paths call the same _rmWalkConditionReveal helper; the
    // same bug class (field name drift, reveal sequence broken) can surface on either.

    def "addAction ifThen: fail-loud when Mode condition supplies neither state nor modeIds"() {
        // Parity with addRequiredExpression Mode "neither provided" spec.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "3", name: "Night"]]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond",         type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",     type: "enum", options: ["Mode", "Switch"]],
                                 [name: "hasAll",       type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[capability: "Mode"]]]   // neither state nor modeIds
            ],
            confirm: true
        ])

        then: "fail-loud: success=false, error pins the distinctive phrase from production"
        result.success == false
        result.error?.toString()?.contains("'state' (mode name) or 'modeIds'")
    }

    def "addAction ifThen: fail-loud when Between two times start.time missing for clock type"() {
        // When start.type='clock' and no time is provided, the IAE must fire before
        // the wizard walk begins. Parity with addRequiredExpression validation path.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond",         type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",     type: "enum", options: ["Between two times"]],
                                 [name: "hasAll",       type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Between two times",
                    start: [type: "clock"],   // no time field
                    end: [type: "clock", time: "07:00"]
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud before or during wizard walk: success=false with diagnostic"
        result.success == false
        result.error?.toString()?.contains("start.'time' (HH:mm)")

    }

    def "addAction ifThen: fail-loud when Between two times end.time missing for clock type"() {
        // When end.type='clock' and no time is provided, the IAE must fire.
        // Parity with addRequiredExpression validation path.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond",         type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",     type: "enum", options: ["Between two times"]],
                                 [name: "hasAll",       type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Between two times",
                    start: [type: "clock", time: "22:00"],
                    end: [type: "clock"]    // no time field
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud before or during wizard walk: success=false with diagnostic"
        result.success == false
        result.error?.toString()?.contains("end.'time' (HH:mm)")

    }

    def "addAction ifThen: fail-loud when Between two times starting<N> not revealed after rCapab write"() {
        // Static doActPage schema never reveals starting1 -- verifies fail-loud on the
        // start-type-reveal step. Parity with the addRequiredExpression static-schema guard.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // Static schema -- starting1 never appears regardless of rCapab write.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond",         type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",     type: "enum", options: ["Between two times"]],
                                 [name: "hasAll",       type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Between two times",
                    start: [type: "clock", time: "22:00"],
                    end: [type: "clock", time: "07:00"]
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud: success=false with message referencing starting<N> not revealed"
        result.success == false
        // Pin the full production phrase: loose "starting" could match other contexts (W-N.34-loose-starting).
        result.error?.toString()?.contains("start-type selector (starting<N>) not revealed")
    }

    // When location.timeZone is null on a doActPage Between-two-times condition the
    // toIsoTime closure must cancel the in-flight wizard before throwing. Without that
    // cancel, the wizard stays half-open and the next write inherits it. The fix point
    // is _rmWalkConditionReveal's toIsoTime closure (Round 12 B1).
    // Both-ways pending (orchestrator).
    def "addAction ifThen: fail-loud when Between two times location.timeZone is null on doActPage"() {
        given:
        enableHubAdminWrite()
        sharedLocation.timeZone = null   // simulate unconfigured hub timezone
        def cancelCapabClicks = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body["settings[cancelCapab]"] == "clicked") {
                cancelCapabClicks++
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond",         type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",     type: "enum", options: ["Between two times"]],
                                 [name: "hasAll",       type: "button"],
                                 [name: "cancelCapab",  type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Between two times",
                    start: [type: "clock", time: "22:00"],
                    end: [type: "clock", time: "07:00"]
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud with timezone hint and cancelled wizard (wizardStuck false)"
        result.success == false
        result.error?.toString()?.contains("location.timeZone is null")
        result.error?.toString()?.contains("Settings > Location and Modes")
        // The walker cancelled the in-flight wizard before throwing; cleanup succeeded
        // so the dispatcher must NOT flag wizardStuck (the cleanup-failed contract).
        result.wizardStuck != true
        cancelCapabClicks >= 1
    }

    // The cancelledByWalker guard in _rmAddRequiredExpression's outer catch prevents
    // the outer catch from issuing a redundant second cancelCapab click after the
    // walker has already cancelled. Without the guard the second click fails (nothing
    // to cancel) and flips wizardStuck to true erroneously. The fix point is the
    // !cancelledByWalker conditional at L20800 (Round 11) -- this spec pins it
    // (Round 12 B2).
    // Both-ways pending (orchestrator).
    def "addRequiredExpression Mode: walker cancel succeeds -- outer catch does not issue a second cancelCapab"() {
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "3", name: "Night"]]
        def cancelCapabClicks = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body["settings[cancelCapab]"] == "clicked") {
                cancelCapabClicks++
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // Static STPage schema: modes<N> never reveals after rCapab='Mode'.
        // The walker enters its 'modesReveal.input == null' branch, calls
        // cancelInFlightCondition() (sets cancelledByWalker=true), then throws.
        // The outer catch must observe cancelledByWalker and skip the redundant cancel.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "Required Expression", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",        type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",    type: "enum", options: ["Mode"]],
                                 [name: "hasAll",      type: "button"],
                                 [name: "cancelCapab", type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Mode", state: "Night"]]],
            confirm: true
        ])

        then: "walker cancelled once; outer catch did not re-cancel; wizardStuck stays false"
        result.success == false
        result.error?.toString()?.contains("expected modes<N> picker")
        result.wizardStuck != true
        // The walker issued exactly one cancelCapab. A regression that drops the
        // cancelledByWalker guard would produce 2 clicks (walker + outer catch),
        // the second of which would still succeed in this stub but in production
        // throws because there is nothing to cancel.
        cancelCapabClicks == 1
    }

    def "addAction ifThen: fail-loud when Variable lVar picker not revealed on doActPage"() {
        // When rCapab='Variable' does not cause the variable-name picker (lVar_<N>) to
        // appear, the walker must cancel and throw. Parity with addRequiredExpression guard.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // lVar_1 intentionally absent (firmware gap or wrong rCapab value written).
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                                 [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                 [name: "cond",         type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",     type: "enum", options: ["Variable"]],
                                 [name: "hasAll",       type: "button"]
                                 // lVar_1 intentionally absent
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Variable", variable: "myVar", comparator: "=", value: 1
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud: success=false with message referencing variable-name picker not revealed"
        result.success == false
        result.error?.toString()?.contains("variable-name picker not revealed")
    }

    def "addAction ifThen: fail-loud when Variable RelrDev not revealed after picker write on doActPage"() {
        // Variable comparator field (RelrDev_<N>) must be revealed after the picker write.
        // When it never appears, the walker must cancel and throw.
        // Parity with addRequiredExpression Variable RelrDev fail-loud spec.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                def m = body.find { k, v -> k.toString() == "settings[rCapab_1]" }
                if (m) rCapabWritten = true
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // lVar_1 appears after rCapab write, but RelrDev_1 never appears (firmware gap).
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Variable"]],
                [name: "hasAll",       type: "button"]
            ]
            if (rCapabWritten) {
                // Stub picks lVar_1 as the canonical name (live firmware may emit lVar_<N>
                // or globalVarName_<N>; asserting lVar_1 pins the stub's choice).
                inputs = inputs + [[name: "lVar_1", type: "enum", options: ["myVar": "myVar"]]]
                // RelrDev_1 intentionally absent after variable-picker write (simulates firmware gap)
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Variable", variable: "myVar", comparator: "=", value: 1
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud: success=false with message referencing RelrDev_<N> not revealed"
        result.success == false
        result.error?.toString()?.contains("RelrDev_<N> (comparator) not revealed after variable name write")
    }

    def "addAction ifThen: fail-loud when Variable state_N not revealed after RelrDev write on doActPage"() {
        // The value field (state_<N>) must be revealed after the RelrDev write.
        // When it never appears, the walker must cancel and throw.
        // Parity with addRequiredExpression Variable state_N fail-loud spec.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def lVarWritten = false    // gate for RelrDev reveal: appears after picker write
        def relrDevWritten = false // gate for state reveal: intentionally never flipped
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    if (k.toString() == "settings[rCapab_1]") rCapabWritten = true
                    if (k.toString() == "settings[lVar_1]")   lVarWritten = true
                    // relrDevWritten intentionally never set: RelrDev_1 appears but state_1 never does
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // lVar_1 appears after rCapab; RelrDev_1 appears after lVar write; state_1 never appears.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Variable"]],
                [name: "hasAll",       type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "lVar_1",    type: "enum",   options: ["myVar": "myVar"]]]
            }
            if (lVarWritten) {
                // RelrDev_1 appears after lVar write (comparator picker revealed by variable selection)
                inputs = inputs + [[name: "RelrDev_1", type: "enum",   options: ["=", "<", ">"]]]
                // state_1 intentionally absent after RelrDev write (firmware gap -- simulates old firmware)
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Variable", variable: "myVar", comparator: "=", value: 1
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud: success=false with message referencing state_<N> not revealed"
        result.success == false
        result.error?.toString()?.contains("state_<N> (value field) not revealed after RelrDev write")
    }

    def "addAction ifThen: fail-loud when Custom Attribute state_N not revealed after RelrDev write on doActPage"() {
        // The value field (state_<N>) for Custom Attribute must be revealed after RelrDev write.
        // When it never appears, the walker must cancel and throw.
        // Parity with addRequiredExpression Custom Attribute state_N fail-loud spec.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def rCustomAttrWritten = false  // gate for RelrDev reveal: appears after rCustomAttr write
        // relrDevWritten not needed: state_1 intentionally absent regardless of RelrDev
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    if (k.toString() == "settings[rCapab_1]")       rCapabWritten = true
                    if (k.toString() == "settings[rCustomAttr_1]")  rCustomAttrWritten = true
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        // rDev_1 + rCustomAttr_1 appear after rCapab; RelrDev_1 appears after rCustomAttr write;
        // state_1 intentionally absent after RelrDev write (firmware gap).
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Custom Attribute"]],
                [name: "hasAll",       type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",        type: "capability.sensor", multiple: true],
                    [name: "rCustomAttr_1", type: "enum", options: ["humidity"]]
                ]
            }
            if (rCustomAttrWritten) {
                // RelrDev_1 appears after rCustomAttr_1 write (attribute triggers comparator reveal)
                inputs = inputs + [[name: "RelrDev_1", type: "enum", options: ["<", ">", "="]]]
                // state_1 intentionally absent after RelrDev write (firmware gap)
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"Sensor"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Custom Attribute", deviceIds: [8],
                    attribute: "humidity", comparator: "<", value: 40
                ]]]
            ],
            confirm: true
        ])

        then: "fail-loud: success=false with message referencing state_<N> not revealed"
        result.success == false
        result.error?.toString()?.contains("state_<N> (value) not revealed after RelrDev write")
    }

    // ---------- W-STPage-wizardStuck-parity ----------
    //
    // Mirrors addAction wizardStuck spec on the STPage (addRequiredExpression) side.
    // When the condition walk fails AND cancelCapab cleanup also throws, addRequiredExpression
    // must surface wizardStuck=true in the response -- same contract as addAction.

    def "addRequiredExpression: fail-loud surfaces wizardStuck when cancelCapab cleanup also fails on STPage"() {
        // When writing a condition fails AND the cancelCapab cleanup also throws on STPage,
        // the outer catch must set wizardStuck=true in the response.
        // Parity with addAction ifThen wizardStuck spec.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "3", name: "Night"]]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // cancelCapab click goes to /installedapp/btn
            if (path == "/installedapp/btn" && body["settings[cancelCapab]"] == "clicked") {
                throw new RuntimeException("cancelCapab POST failed: hub timeout")
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // Static schema -- modes picker never appears, triggering the fail-loud path;
        // cancelCapab click then throws to exercise the wizardStuck encoding.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                                 [name: "hasAll",   type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Mode", state: "Night"]]],
            confirm: true
        ])

        then: "fail-loud surfaces wizardStuck=true pointing at cancelCapab on STPage"
        result.success == false
        result.wizardStuck == true
        result.restoreHint?.contains("cancelCapab")
        result.restoreHint?.contains("STPage")
        result.error?.contains("wizardStuck")
    }

    // ---------- W-addRE-propagation ----------
    //
    // Verifies that when _rmWalkConditionReveal throws from inside addRequiredExpression,
    // the outer toolUpdateNativeApp propagates the inner failure fields (success=false,
    // error, etc.) rather than wrapping them in a generic envelope or re-throwing bare.

    def "addRequiredExpression: outer toolUpdateNativeApp propagates inner failure fields on STPage walk error"() {
        // When the walker fails (e.g. modes picker not revealed), the outer call must
        // surface success=false and error in the returned map, not an unhandled exception.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "3", name: "Night"]]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // Static schema -- modes picker never appears, causing a walk failure.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                                 [name: "hasAll",   type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Mode", state: "Night"]]],
            confirm: true
        ])

        then: "outer call returns a proper result map with success=false and a non-null error field"
        result instanceof Map
        result.success == false
        result.error != null
        // Must be a result map, not a re-thrown exception escaping the boundary.
    }

    // ---------- W-repairHints-count ----------
    //
    // repairHints plural: "2 conditions" when two conditions both hit the degraded write path.

    def "addRequiredExpression: repairHints says '2 conditions' when two compareToDevice conditions both degrade"() {
        // When two conditions each hit the compareToDevice firmware-fallback path (sentinel pushed),
        // the repairHints string should say '2 conditions' not '1 condition' or '2 condition(s)'.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // STPage always exposes all fields for both conditions; stateType_<N> / rhsType_<N>
        // intentionally absent to simulate old firmware, triggering the fallback path for both.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Temperature"]],
                                 [name: "rDev_1",   type: "capability.sensor", multiple: true],
                                 [name: "RelrDev_1", type: "enum", options: [">", "<", "="]],
                                 [name: "state_1",  type: "number"],
                                 // stateType_1 absent (old firmware -- fallback path)
                                 [name: "rCapab_2", type: "enum", options: ["Temperature"]],
                                 [name: "rDev_2",   type: "capability.sensor", multiple: true],
                                 [name: "RelrDev_2", type: "enum", options: [">", "<", "="]],
                                 [name: "state_2",  type: "number"],
                                 // stateType_2 absent (old firmware -- fallback path)
                                 [name: "hasAll",   type: "button"]
                             ], paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["IF Temp > 20 THEN"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/9')  { params -> '{"id":"9","name":"T2"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"Ref"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [
                operator: "AND",
                conditions: [
                    [capability: "Temperature", deviceIds: [8], comparator: ">",
                     compareToDevice: [deviceId: 99, attribute: "temperature"]],
                    [capability: "Temperature", deviceIds: [9], comparator: "<",
                     compareToDevice: [deviceId: 99, attribute: "temperature"]]
                ]
            ],
            confirm: true
        ])

        then: "result is success=true with partial=true (both conditions degraded)"
        result.success == true
        result.partial == true

        and: "settingsSkipped contains two compareToDevice sentinels"
        def skipped = result.settingsSkipped as List
        skipped?.findAll { it instanceof Map && it.key == "compareToDevice" }?.size() == 2

        and: "repairHints uses plural 'conditions' (not singular '1 condition' or the awkward '2 condition(s)')"
        // The stub cannot simulate field-persistence verification (all writes appear as
        // silent_rejections in the static schema), so deg > 2 in practice. The intent is
        // to verify the plural branch fires (deg > 1), not the exact count.
        def hint = result.repairHints?.toString() ?: ""
        hint.contains("conditions")   // plural (not "1 condition")
        !hint.contains("1 condition ")
        !hint.contains("condition(s)")
    }

    // ---------- W-not-true (cond.not == true for each capability) ----------
    //
    // When cond.not == true, the walker writes not<N>=true to the hub after the
    // main capability sequence completes. These five specs verify that field lands.

    def "addRequiredExpression Mode: cond.not=true writes not<N> to STPage"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "3", name: "Night"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1") rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Mode", "Switch"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "modes1", type: "enum", options: ["1": "Day", "3": "Night"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["IF NOT Mode is Day THEN"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Mode", state: "Day", not: true]]],
            confirm: true
        ])

        then: "success and not1=true was written to the hub"
        result.success == true
        // Production writes the boolean true (not the string "true") to the hub wire.
        // Production serializes boolean true via toString() before posting to the hub.
        // The stub captures the serialized string form; assert string "true" not boolean.
        writtenFields["not1"]?.toString() == "true"
    }

    def "addRequiredExpression Between two times: cond.not=true writes not<N> to STPage"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def startingWritten = false
        def startingAWritten = false
        def endingWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")    rCapabWritten = true
                        if (m[0][1] == "starting1")   startingWritten = true
                        if (m[0][1] == "startingA1")  startingAWritten = true
                        if (m[0][1] == "ending1")     endingWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "starting1", type: "enum",
                                    options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            }
            if (startingWritten) {
                inputs = inputs + [[name: "startingA1", type: "text"]]
            }
            if (startingAWritten) {
                inputs = inputs + [[name: "ending1", type: "enum",
                                    options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            }
            if (endingWritten) {
                inputs = inputs + [[name: "endingA1", type: "text"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["NOT Between 22:00 and 07:00"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "22:00"],
                end:   [type: "clock", time: "07:00"],
                not: true
            ]]],
            confirm: true
        ])

        then: "success and not1=true was written to the hub"
        result.success == true
        // Production serializes boolean true via toString() before posting to the hub.
        // The stub captures the serialized string form; assert string "true" not boolean.
        writtenFields["not1"]?.toString() == "true"
    }

    def "addRequiredExpression Variable: cond.not=true writes not<N> to STPage"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def lVarWritten = false
        def relrDevWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")  rCapabWritten = true
                        if (m[0][1] == "lVar_1")    lVarWritten = true
                        if (m[0][1] == "RelrDev_1") relrDevWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // Stub picks lVar_1 as the variable picker name; see also lVar_1 note
            // in the RelrDev fail-loud spec above.
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Variable"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "lVar_1", type: "enum", options: ["myVar": "myVar"]]]
            }
            if (lVarWritten) {
                inputs = inputs + [[name: "RelrDev_1", type: "enum", options: ["=", "<", ">"]]]
            }
            if (relrDevWritten) {
                inputs = inputs + [[name: "state_1", type: "number"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["NOT Variable myVar = 5"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable", variable: "myVar", comparator: "=", value: 5, not: true
            ]]],
            confirm: true
        ])

        then: "success and not1=true was written to the hub"
        result.success == true
        // Production serializes boolean true via toString() before posting to the hub.
        // The stub captures the serialized string form; assert string "true" not boolean.
        writtenFields["not1"]?.toString() == "true"
    }

    def "addRequiredExpression Custom Attribute: cond.not=true writes not<N> to STPage"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def rCustomAttrWritten = false  // gates RelrDev+state reveal
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")       rCapabWritten = true
                        if (m[0][1] == "rCustomAttr_1")  rCustomAttrWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Custom Attribute"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",        type: "capability.sensor", multiple: true],
                    [name: "rCustomAttr_1", type: "enum",              options: ["humidity"]]
                ]
            }
            if (rCustomAttrWritten) {
                // RelrDev_1 and state_1 appear after rCustomAttr_1 is written (attribute gates comparator)
                inputs = inputs + [
                    [name: "RelrDev_1", type: "enum",   options: ["<", ">", "="]],
                    [name: "state_1",   type: "number"]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["NOT humidity < 40"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"Sensor"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute", deviceIds: [8],
                attribute: "humidity", comparator: "<", value: 40, not: true
            ]]],
            confirm: true
        ])

        then: "success and not1=true was written to the hub"
        result.success == true
        // Production serializes boolean true via toString() before posting to the hub.
        // The stub captures the serialized string form; assert string "true" not boolean.
        writtenFields["not1"]?.toString() == "true"
    }

    def "addRequiredExpression compareToDevice: cond.not=true writes not<N> to STPage"() {
        // When stateType/rhsType ARE revealed (non-fallback path) and not=true,
        // the walker must write not1=true after the full sequence.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def relrWritten = false
        def rhsTypeWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "rCapab_1")     rCapabWritten = true
                        if (fn == "RelrDev_1")    relrWritten = true
                        if (fn =~ /stateType_1|rhsType_1/) rhsTypeWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Temperature"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",    type: "capability.sensor", multiple: true],
                    [name: "RelrDev_1", type: "enum",              options: [">", "<", "="]],
                    [name: "state_1",   type: "number"]
                ]
            }
            if (relrWritten) {
                // rhsType_1 appears; includes "another device" option for compareToDevice path
                inputs = inputs + [[name: "rhsType_1", type: "enum",
                                    options: ["fixed": "Fixed value", "another device": "Another device value"]]]
            }
            if (rhsTypeWritten) {
                // rDev2_1 and rCustomAttr2_1 appear after selecting "another device"
                inputs = inputs + [
                    [name: "rDev2_1",        type: "capability.sensor", multiple: false],
                    [name: "rCustomAttr2_1", type: "enum",              options: ["temperature": "temperature"]]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["NOT Temp > [device]"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature", deviceIds: [8], comparator: ">",
                compareToDevice: [deviceId: 99, attribute: "temperature"], not: true
            ]]],
            confirm: true
        ])

        then: "success and not1=true was written to the hub"
        result.success == true
        // Production serializes boolean true via toString() before posting to the hub.
        // The stub captures the serialized string form; assert string "true" not boolean.
        writtenFields["not1"]?.toString() == "true"
    }

    // ---------- W-rawSettings (post-walker merge for each capability) ----------
    //
    // rawSettings lets callers inject extra fields that the walker doesn't map.
    // Each spec writes one extra field via rawSettings and asserts it lands alongside
    // the normal capability write sequence.

    def "addRequiredExpression Mode: rawSettings fields written alongside modes picker"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1") rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Mode"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "modes1", type: "enum", options: ["1": "Day"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["IF Mode is Day THEN"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Mode", state: "Day",
                rawSettings: ["customField_1": "extraValue"]
            ]]],
            confirm: true
        ])

        then: "success and the rawSettings field was written alongside the normal sequence"
        result.success == true
        writtenFields["customField_1"] == "extraValue"
        writtenFields["modes1"] == '["1"]'   // main write still happened
    }

    def "addRequiredExpression Variable: rawSettings fields written alongside comparator chain"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def lVarWritten = false
        def relrDevWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")  rCapabWritten = true
                        if (m[0][1] == "lVar_1")    lVarWritten = true
                        if (m[0][1] == "RelrDev_1") relrDevWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Variable"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "lVar_1", type: "enum", options: ["myVar": "myVar"]]]
            }
            if (lVarWritten) {
                inputs = inputs + [[name: "RelrDev_1", type: "enum", options: ["=", "<", ">"]]]
            }
            if (relrDevWritten) {
                inputs = inputs + [[name: "state_1", type: "number"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["IF myVar = 5 THEN"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable", variable: "myVar", comparator: "=", value: 5,
                rawSettings: ["extraVarField_1": "bonus"]
            ]]],
            confirm: true
        ])

        then: "success and the rawSettings field was written"
        result.success == true
        writtenFields["extraVarField_1"] == "bonus"
        writtenFields["lVar_1"] == "myVar"   // main write still happened
    }

    def "addRequiredExpression Custom Attribute: rawSettings fields written alongside comparator chain"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def rCustomAttrWritten = false  // gates RelrDev+state reveal: appears after rCustomAttr_1 write
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")       rCapabWritten = true
                        if (m[0][1] == "rCustomAttr_1")  rCustomAttrWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Custom Attribute"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",        type: "capability.sensor", multiple: true],
                    [name: "rCustomAttr_1", type: "enum",              options: ["humidity"]]
                ]
            }
            if (rCustomAttrWritten) {
                // RelrDev_1 and state_1 appear after rCustomAttr_1 write (attribute triggers comparator reveal)
                inputs = inputs + [
                    [name: "RelrDev_1", type: "enum",   options: ["<", ">", "="]],
                    [name: "state_1",   type: "number"]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["IF humidity < 40 THEN"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"Sensor"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute", deviceIds: [8],
                attribute: "humidity", comparator: "<", value: 40,
                rawSettings: ["xtraAttrField_1": "customVal"]
            ]]],
            confirm: true
        ])

        then: "success and the rawSettings field was written"
        result.success == true
        writtenFields["xtraAttrField_1"] == "customVal"
        writtenFields["rCustomAttr_1"] == "humidity"   // main write still happened
    }

    def "addRequiredExpression Between two times: rawSettings fields written alongside clock sequence"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def startingWritten = false
        def startingAWritten = false
        def endingWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")   rCapabWritten = true
                        if (m[0][1] == "starting1")  startingWritten = true
                        if (m[0][1] == "startingA1") startingAWritten = true
                        if (m[0][1] == "ending1")    endingWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "starting1", type: "enum",
                                    options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            }
            if (startingWritten) {
                inputs = inputs + [[name: "startingA1", type: "text"]]
            }
            if (startingAWritten) {
                inputs = inputs + [[name: "ending1", type: "enum",
                                    options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]]
            }
            if (endingWritten) {
                inputs = inputs + [[name: "endingA1", type: "text"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Between 22:00 and 07:00"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "22:00"],
                end:   [type: "clock", time: "07:00"],
                rawSettings: ["betweenExtra_1": "timezone_note"]
            ]]],
            confirm: true
        ])

        then: "success and the rawSettings field was written"
        result.success == true
        writtenFields["betweenExtra_1"] == "timezone_note"
        writtenFields["starting1"] == "A specific time"   // main write still happened
    }

    def "addRequiredExpression compareToDevice: rawSettings fields written alongside device-compare sequence"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def relrWritten = false
        def rhsTypeWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        def fn = m[0][1]
                        writtenFields[fn] = v
                        if (fn == "rCapab_1")                  rCapabWritten = true
                        if (fn == "RelrDev_1")                 relrWritten = true
                        if (fn =~ /stateType_1|rhsType_1/)     rhsTypeWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Temperature"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",    type: "capability.sensor", multiple: true],
                    [name: "RelrDev_1", type: "enum",              options: [">", "<", "="]],
                    [name: "state_1",   type: "number"]
                ]
            }
            if (relrWritten) {
                inputs = inputs + [[name: "rhsType_1", type: "enum",
                                    options: ["fixed": "Fixed value", "another device": "Another device value"]]]
            }
            if (rhsTypeWritten) {
                inputs = inputs + [
                    [name: "rDev2_1",        type: "capability.sensor", multiple: false],
                    [name: "rCustomAttr2_1", type: "enum",              options: ["temperature": "temperature"]]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Temp > [device]"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature", deviceIds: [8], comparator: ">",
                compareToDevice: [deviceId: 99, attribute: "temperature"],
                rawSettings: ["compareExtra_1": "annotationField"]
            ]]],
            confirm: true
        ])

        then: "success and the rawSettings field was written alongside the device-compare sequence"
        result.success == true
        writtenFields["compareExtra_1"] == "annotationField"
        writtenFields["RelrDev_1"] == ">"   // main write still happened
    }

    // ---------- W-elseIf/repeatWhile/waitExpression smoke specs ----------
    //
    // _rmWalkConditionReveal is called from four expression subtypes: getIfThen,
    // getElseIf, getWhile, getWaitRule. These three smoke specs verify the walker
    // fires (success=true) for the non-ifThen subtypes using a simple Mode condition.

    def "addAction elseIf: Mode condition writes modes picker -- walker fires on elseIf subtype"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1") rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // selectActions exposes actType with condActs; elseIf subtype comes from actSubType=getElseIf.
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["condActs": "Conditional Actions"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getElseIf": "ELSE IF Expression"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Mode"]],
                [name: "hasAll",       type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "modes1", type: "enum", options: ["1": "Day"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "ELSE IF Mode is Day THEN")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "elseIf",
                expression: [conditions: [[capability: "Mode", state: "Day"]]]
            ],
            confirm: true
        ])

        then: "success and modes1 was written (walker fired on elseIf subtype)"
        result.success == true
        writtenFields["modes1"] == '["1"]'
        // actSubType pin: confirms the elseIf wire value was written to the hub (W-N.34-subtype-routing).
        writtenFields["actSubType.1"] == "getElseIf"

    }

    def "addAction repeatWhile: Mode condition writes modes picker -- walker fires on repeatWhile subtype"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1") rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["condActs": "Conditional Actions"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getWhile": "REPEAT While Expression is TRUE"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Mode"]],
                [name: "hasAll",       type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "modes1", type: "enum", options: ["1": "Day"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "REPEAT WHILE Mode is Day")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "repeatWhile",
                expression: [conditions: [[capability: "Mode", state: "Day"]]]
            ],
            confirm: true
        ])

        then: "success and modes1 was written (walker fired on repeatWhile subtype)"
        result.success == true
        writtenFields["modes1"] == '["1"]'
        // actSubType pin: confirms the repeatWhile wire value was written to the hub (W-N.34-subtype-routing).
        writtenFields["actSubType.1"] == "getWhile"

    }

    def "addAction waitExpression: Mode condition writes modes picker -- walker fires on waitExpression subtype"() {
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "1", name: "Day"]]
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1") rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["condActs": "Conditional Actions"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getWaitRule": "WAIT for Expression"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Mode"]],
                [name: "hasAll",       type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "modes1", type: "enum", options: ["1": "Day"]]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "WAIT FOR Mode is Day")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "waitExpression",
                expression: [conditions: [[capability: "Mode", state: "Day"]]]
            ],
            confirm: true
        ])

        then: "success and modes1 was written (walker fired on waitExpression subtype)"
        result.success == true
        writtenFields["modes1"] == '["1"]'
        // actSubType pin: confirms the waitExpression wire value was written to the hub (W-N.34-subtype-routing).
        writtenFields["actSubType.1"] == "getWaitRule"

    }

    // ---------- singular deviceId normalization ----------
    //
    // Agents sometimes pass deviceId: N (singular int) instead of deviceIds: [N]
    // (array). The dispatcher normalizes singular -> array before the walker fires.
    // The three specs below verify: STPage path, doActPage path, and precedence
    // (explicit deviceIds beats singular deviceId when both are provided).

    def "addRequiredExpression: singular deviceId normalized to deviceIds array before STPage walk"() {
        // Both-ways pending (orchestrator).
        // When the caller passes deviceId: 73 instead of deviceIds: [73], the
        // dispatcher normalizes it to [73] so rDev_1 is written correctly.
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def rCustomAttrWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")      rCapabWritten = true
                        if (m[0][1] == "rCustomAttr_1") rCustomAttrWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Custom Attribute"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",        type: "capability.sensor", multiple: true],
                    [name: "rCustomAttr_1", type: "enum", options: ["humidity"]]
                ]
            }
            if (rCustomAttrWritten) {
                inputs = inputs + [
                    [name: "RelrDev_1", type: "enum",   options: ["<", ">", "="]],
                    [name: "state_1",   type: "number"]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["IF humidity < 40 THEN"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/73') { params -> '{"id":"73","name":"Sensor"}' }

        when: "caller passes singular deviceId: 73 instead of deviceIds: [73]"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Custom Attribute",
                deviceId: 73,              // singular -- should be normalized
                attribute: "humidity", comparator: "<", value: 40
            ]]],
            confirm: true
        ])

        then: "result succeeds and rDev_1 was written with the normalized device ID"
        result.success == true
        // _rmBuildSettingsBody serializes a single-element List for a capability.* field
        // by CSV-joining the toString'd entries; for [73] that produces the bare string
        // "73" on the wire (no JSON-array wrapping). Exact equality so a wire value like
        // "7300" or "73,99" cannot produce a false-positive match (W-N.34-short-substring).
        writtenFields["rDev_1"] == "73"
    }

    def "addAction ifThen: singular deviceId normalized to deviceIds array before doActPage walk"() {
        // Both-ways pending (orchestrator).
        // Same normalization as STPage path but exercised via addAction ifThen expression.
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def rCustomAttrWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "doActPage") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1")      rCapabWritten = true
                        if (m[0][1] == "rCustomAttr_1") rCustomAttrWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum",
                options: ["condActs": "Conditional Actions"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            def inputs = [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Custom Attribute"]],
                [name: "hasAll",       type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",        type: "capability.sensor", multiple: true],
                    [name: "rCustomAttr_1", type: "enum", options: ["humidity"]]
                ]
            }
            if (rCustomAttrWritten) {
                inputs = inputs + [
                    [name: "RelrDev_1", type: "enum",   options: ["<", ">", "="]],
                    [name: "state_1",   type: "number"]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            actMainPageBakedJson(100, "IF humidity < 40 THEN")
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/73') { params -> '{"id":"73","name":"Sensor"}' }

        when: "caller passes singular deviceId: 73 instead of deviceIds: [73]"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Custom Attribute",
                    deviceId: 73,          // singular -- should be normalized
                    attribute: "humidity", comparator: "<", value: 40
                ]]]
            ],
            confirm: true
        ])

        then: "result succeeds and rDev_1 was written with the normalized device ID"
        result.success == true
        // Exact-equality wire-value check: capability.* single-device serializes as the
        // bare CSV scalar (e.g. "73"), not the JSON array form. A substring match on "73"
        // or '"73"' would risk false positives on "7300" / "73,99" (W-N.34-short-substring).
        writtenFields["rDev_1"] == "73"
    }

    // _rmAddAction recursive subExpression normalization spec — limited form.
    //
    // L16953-16967 defines a recursive normExprCondList closure that walks into
    // subExpression.conditions[] and rewrites singular deviceId -> deviceIds: [N].
    // However the SIBLING pre-validator at L16968-16972 is FLAT: it iterates the
    // outer conditions only and never recurses. So a singular deviceId nested in
    // a subExpression gets normalized but NOT pre-validated. (The downstream
    // wizard write at L18085+ does not yet emit cond=b open-paren writes for
    // addAction subExpressions, so the rule would fail at the write stage anyway.)
    //
    // This spec pins the limited contract that ACTUALLY exists today: the
    // recursive normalization MUST run without crashing the dispatcher, and the
    // failure mode must be the write-stage Unstubbed/missing-page error rather
    // than a Groovy null-pointer crash inside the closure. When the addAction
    // path gains real subExpression-write support (cond=b open-paren walk), a
    // companion spec should be added asserting the recursive validation also
    // throws on non-existent ids; that companion belongs in the same PR as the
    // write-side feature work.
    // Both-ways pending (orchestrator).
    def "addAction ifThen: recursive normExprCondList walks subExpression without crashing the dispatcher"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            actSelectActionsJson(100)
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        // Device 73 exists so flat pre-validation passes (the outer condition has
        // null deviceIds because only subExpression is set, so the flat validator
        // early-returns regardless).
        hubGet.register('/device/fullJson/73') { params -> '{"id":"73","name":"MotionSensor"}' }

        when: "addAction ifThen with subExpression carrying singular deviceId"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    subExpression: [conditions: [[
                        capability: "Motion",
                        deviceId: 73,
                        state: "active"
                    ]]]
                ]]]
            ],
            confirm: true
        ])

        then: "dispatcher reaches the wizard write stage cleanly (no closure crash from recursive call)"
        // The reachable failure mode is the missing doActPage stub for the write
        // phase (or any other site-specific Unstubbed message), NOT a Groovy
        // MissingMethodException / NullPointerException inside normExprCondList.
        // If the recursive closure had a structural bug (e.g. mishandled null
        // entries / non-Map sub-entries), it would crash BEFORE reaching the
        // write stage and the error would mention the closure's call site.
        result.success == false
        !result.error?.toString()?.contains("MissingMethodException")
        !result.error?.toString()?.contains("NullPointerException")
        !result.error?.toString()?.contains("normExprCondList")
    }

    // _rmValidateDeviceIdsExist must run AFTER normalization for addTrigger.condition.
    // A singular non-existent deviceId in trigger.condition gets normalized to
    // deviceIds: [9999], then validated, then the validator must throw with the
    // distinctive 'does not exist on the hub' phrase. Without the order, the
    // un-normalized singular deviceId would bypass validation entirely.
    // Both-ways pending (orchestrator).
    def "addTrigger: singular non-existent deviceId in trigger.condition fails the existence check"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "trig",      type: "enum", options: ["c": "Conditional trigger"]],
                                 [name: "tCapab1",   type: "enum", options: ["Motion"]],
                                 [name: "hasTrig",   type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        // The outer Switch trigger references device 8 (registered, passes the existence
        // check). The gating condition Map carries 9999, lookup returns empty body
        // (length 0). _rmValidateDeviceIdsExist treats empty body as exists=false and
        // throws the distinctive 'does not exist on the hub' phrase. (An unstubbed
        // path raises a non-404 error which the validator treats as "transient".)
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"Switch"}' }
        hubGet.register('/device/fullJson/9999') { params -> '' }

        when: "addTrigger.condition with singular non-existent deviceId"
        // The outer Switch trigger references an existing device; the gating
        // condition Map carries the singular non-existent deviceId that must
        // be normalized then caught by _rmValidateDeviceIdsExist on the
        // trigger.condition path.
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Switch",
                deviceIds: [8],
                state: "on",
                condition: [
                    capability: "Motion",
                    deviceId: 9999,    // singular non-existent inside the condition Map
                    state: "active"
                ]
            ],
            confirm: true
        ])

        then: "fail-loud with the existence-check phrase, not a silent rule write"
        result.success == false
        // Pin the distinctive 12-char phrase from _rmValidateDeviceIdsExist; loose ".contains('9999')"
        // would match the unrelated 'rule-id 9999' or numeric overlap (W-N.34).
        result.error?.toString()?.contains("does not exist on the hub")
        result.error?.toString()?.contains("9999")
    }

    def "addRequiredExpression: explicit deviceIds wins over singular deviceId when both provided"() {
        // Both-ways pending (orchestrator).
        // Precedence: if both deviceId: 5 and deviceIds: [99] are given, deviceIds wins.
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "rCapab_1") rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Motion"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",   type: "capability.sensor", multiple: true],
                    [name: "RelrDev_1", type: "enum", options: ["active", "inactive"]],
                    [name: "state_1",  type: "enum", options: ["active", "inactive"]]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["IF Motion is active THEN"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        // Only device 99 (the deviceIds winner) registered -- device 5 (singular deviceId) is NOT
        // registered, so if the dispatcher uses deviceId: 5 instead of deviceIds: [99],
        // the pre-validation would use device 5 which would fail the hub lookup.
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"MotionSensor"}' }

        when: "caller passes BOTH deviceId: 5 AND deviceIds: [99]; deviceIds must win"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Motion",
                deviceId: 5,              // singular -- MUST be ignored
                deviceIds: [99],          // array -- MUST win
                state: "active"
            ]]],
            confirm: true
        ])

        then: "result succeeds and rDev_1 is exactly '99' (the singular deviceId 5 was ignored)"
        result.success == true
        // Exact-equality wire-value check: when deviceIds: [99] wins, the wire value is
        // the bare CSV scalar "99". The singular deviceId: 5 must not leak into either
        // the wire value or the pre-validation path (no hub stub registered for /5).
        writtenFields["rDev_1"] == "99"
    }

    // ---------- W-spec-norm-before-validation: normalization runs before _rmValidateDeviceIdsExist ----------
    //
    // When caller passes singular deviceId: <nonexistent>, the normalization converts it
    // to deviceIds: [<nonexistent>], and then _rmValidateDeviceIdsExist catches the bad ID.
    // If normalization didn't run, deviceIds would be null and the validator would skip.
    // Both-ways pending (orchestrator).

    def "addRequiredExpression: singular deviceId for nonexistent device triggers pre-validation throw"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        // Device 9999 is NOT registered -- lookup returns empty body (length 0),
        // which the validator treats as "device does not exist" (exists=false).
        hubGet.register('/device/fullJson/9999') { params -> '' }

        when: "singular deviceId: 9999 for a device that does not exist"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Switch",
                deviceId: 9999      // singular nonexistent device -- normalized then caught
            ]]],
            confirm: true
        ])

        then: "pre-validation throws (not a silent pass-through): success=false mentioning the device ID"
        result.success == false
        // Pin the distinctive 12-char phrase from _rmValidateDeviceIdsExist; a loose
        // .contains("9999") || .contains("not found") OR-chain would tolerate a
        // generic "9999" leak elsewhere (W-N.34-OR-chain).
        result.error?.toString()?.contains("does not exist on the hub")
        result.error?.toString()?.contains("9999")
    }

    // ---------- B2: subExpression recursive deviceId normalization ----------
    //
    // When a condition has a subExpression, the deviceId-normalization closure
    // must recurse into subExpression.conditions[] so that singular deviceId
    // entries inside nested parens are also normalized.
    // Both-ways pending (orchestrator).

    def "addRequiredExpression: singular deviceId inside nested subExpression.conditions is normalized"() {
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] =~ /rCapab_\d+/) rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition", "b": "( sub-expression"]],
                [name: "oper",     type: "enum", options: ["AND": "AND", "OR": "OR"]],
                [name: "rCapab_1", type: "enum", options: ["Motion"]],
                [name: "hasAll",   type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",   type: "capability.sensor", multiple: true],
                    [name: "state_1",  type: "enum", options: ["active", "inactive"]]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Motion active"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/73') { params -> '{"id":"73","name":"MotionSensor"}' }

        when: "singular deviceId inside nested subExpression.conditions"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                subExpression: [conditions: [[
                    capability: "Motion",
                    deviceId: 73,      // singular inside sub-expression -- must be normalized
                    state: "active"
                ]]]
            ]]],
            confirm: true
        ])

        then: "result succeeds and rDev_1 contains device 73"
        result.success == true
        // Exact-equality wire-value check (same rationale as F1/F2 above); the recursive
        // subExpression normalization must produce the same wire value as a top-level
        // singular deviceId would.
        writtenFields["rDev_1"] == "73"
    }

    // ---------- W-N.35-revealedAny-fallback: static-schema always-visible spec ----------
    //
    // _rmRevealStep falls back to revealedAny when the field is already visible before the
    // trigger write (no new appearance detected). This path fires on hubs where the schema
    // is static (all fields always visible regardless of prior writes). A simple Mode
    // condition with a static STPage schema verifies the fallback keeps success=true.
    // Both-ways pending (orchestrator).

    def "addRequiredExpression Mode: static-schema (all fields always visible) succeeds via revealedAny fallback"() {
        given:
        enableHubAdminWrite()
        sharedLocation.modes = [[id: "2", name: "Night"]]
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // Static: modes2 is ALWAYS visible (no progressive reveal needed).
        // _rmRevealStep sees modes2 in both pre and post -- revealedNew is null but
        // revealedAny returns modes2, so the fallback path keeps the walker alive.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",   type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Mode"]],
                                 [name: "modes1", type: "enum", options: ["2": "Night"]],
                                 [name: "hasAll", type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Mode is Night"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Mode", state: "Night"]]],
            confirm: true
        ])

        then: "success even though modes1 was already visible before rCapab write (revealedAny fallback fired)"
        result.success == true
        // Exact-equality wire-value check (W-N.34): bare JSON array '["2"]'. A substring
        // .contains("2") would also match "20"/"21"/"32" by coincidence.
        writtenFields["modes1"] == '["2"]'
    }

    // ---------- B3: location.timeZone null guard ----------
    //
    // When location.timeZone is null, the Between two times walker must fail-loud
    // before touching the wizard (throw with message naming the Settings path).
    // Both-ways pending (orchestrator).

    def "addRequiredExpression Between two times: fail-loud when location.timeZone is null"() {
        given:
        enableHubAdminWrite()
        sharedLocation.timeZone = null   // simulate unconfigured hub timezone
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                                 [name: "hasAll",   type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "22:00"],
                end: [type: "clock", time: "07:00"]
            ]]],
            confirm: true
        ])

        then: "fail-loud mentioning location.timeZone is null and the Settings path"
        result.success == false
        result.error?.toString()?.contains("location.timeZone is null")
        result.error?.toString()?.contains("Settings > Location and Modes")
    }

    // ---------- W-N.34-seasonal-tz: non-UTC timezone ISO datetime ----------
    //
    // The Between two times clock-time conversion must produce the correct TZ offset
    // for non-UTC hubs. Verify with Australia/Sydney (+11 or +10 depending on DST;
    // using a fixed-offset timezone to keep the test deterministic).
    // Both-ways pending (orchestrator).

    def "addRequiredExpression Between two times: clock time ISO datetime uses hub timezone offset (non-UTC zone)"() {
        given:
        enableHubAdminWrite()
        // Use a fixed-offset zone (UTC+10) to avoid DST ambiguity in the assertion.
        sharedLocation.timeZone = TimeZone.getTimeZone("GMT+10")
        def startingWritten = false
        def endingWritten = false
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) {
                        writtenFields[m[0][1]] = v
                        if (m[0][1] == "starting1") startingWritten = true
                        if (m[0][1] == "ending1")   endingWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Between two times"]],
                [name: "hasAll",   type: "button"]
            ]
            // Progressive reveal mirrors the production walker's expected chain:
            // rCapab -> starting<N> -> startingA<N> + ending<N> -> endSunriseOffset<N>
            // (the end branch picks endSunriseOffset for sunrise/sunset, endingA for clock).
            if (startingWritten) {
                inputs = inputs + [
                    [name: "startingA1", type: "text"],
                    [name: "ending1",    type: "enum", options: ["A specific time": "A specific time", "Sunrise": "Sunrise"]],
                ]
            } else {
                inputs = inputs + [
                    [name: "starting1", type: "enum", options: ["A specific time": "A specific time", "Sunrise": "Sunrise", "Sunset": "Sunset"]]
                ]
            }
            if (endingWritten) {
                inputs = inputs + [[name: "endSunriseOffset1", type: "number"]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${inputs.size()}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Between 22:00 and sunrise"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                start: [type: "clock", time: "22:00"],
                end: [type: "sunrise", offset: 0]
            ]]],
            confirm: true
        ])

        then: "startingA1 carries +1000 offset (GMT+10), not +0000"
        result.success == true
        // The ISO datetime must encode the hub's TZ offset (+10:00 = +1000).
        writtenFields["startingA1"]?.toString()?.contains("+1000")
        // Wall-clock time must be preserved -- 22:00 in local time.
        writtenFields["startingA1"]?.toString()?.contains("T22:00:00")
    }

    // ---------- W-spec-repairHints-singular: single-degraded repairHints says "1 condition" ----------
    //
    // When exactly one condition degrades (compareToDevice fallback), the repairHints
    // string must say "1 condition" (not "1 conditions" or "1 condition(s)").
    // Both-ways pending (orchestrator).

    def "addRequiredExpression: repairHints says '1 condition' (singular) for exactly one degraded condition"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // Static schema -- RelrDev_1 (RHS type selector) never appears so compareToDevice degrades.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Temperature"]],
                                 [name: "rDev_1",   type: "capability.sensor", multiple: true],
                                 [name: "RelrDev_1", type: "enum", options: ["<", ">", "="]],
                                 [name: "state_1",  type: "number"],
                                 [name: "hasAll",   type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["Temperature > 72"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"TempSensor"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"RefSensor"}' }

        when: "one condition with compareToDevice (static schema, RHS-type toggle never appears)"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                compareToDevice: [deviceId: 99, attribute: "temperature"]
            ]]],
            confirm: true
        ])

        then: "partial=true with repairHints saying '1 condition' not '1 conditions'"
        result.partial == true
        def hint = result.repairHints?.toString() ?: ""
        hint.contains("1 condition ")      // singular space-terminated
        !hint.contains("1 conditions")     // plural must NOT appear
        !hint.contains("condition(s)")     // parenthetical form must NOT appear
    }

    // ---------- W-multi-entry-condition: single condition producing multiple skipped entries
    //
    // Regression guard for the unique-condIdx discrimination logic at L21006-21007.
    // The buggy entry-count code would inflate "1 condition with 3 skipped fields" to
    // "3 conditions"; the fixed unique-count logic must report "1 condition". The
    // existing singular-degradation spec at L15069 produces exactly 1 skipped entry,
    // so it cannot distinguish entry-count from unique-count. This spec forces 3
    // skipped entries that all carry condIdx=0 (via rawSettings keys absent from
    // the static schema) and pins the singular wording.
    // Both-ways pending (orchestrator).

    def "addRequiredExpression: repairHints says '1 condition' when one condition produces multiple skipped entries"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // Schema has the basic Switch chain but lacks three fields that the spec writes
        // via rawSettings (notSchemaA, notSchemaB, notSchemaC). _rmWriteSettingOnPage
        // records each as a separate skipped entry with reason='not_in_schema'.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",      type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",  type: "enum", options: ["Switch"]],
                                 [name: "rDev_1",    type: "capability.switch", multiple: true],
                                 [name: "RelrDev_1", type: "enum", options: ["="]],
                                 [name: "state_1",   type: "enum", options: ["on", "off"]],
                                 [name: "hasAll",    type: "button"]
                             ], paragraphs: ["static"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"Sw1"}' }

        when: "one condition where every write is silent-rejected (static-schema stub yields no progressive disclosure)"
        // Static-schema stubs make _rmWriteSettingOnPage flag each write as
        // reason='silent_rejection' (the schema didn't grow / shrink after the
        // write -- characteristic of a static fixture). The hint's count must
        // reflect distinct condIdx values, not entry count.
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Switch",
                deviceIds: [8],
                comparator: "=",
                state: "on",
                rawSettings: [notSchemaA: 1, notSchemaB: 2, notSchemaC: 3]
            ]]],
            confirm: true
        ])

        then: "partial=true with repairHints saying '1 condition' (unique-condIdx count), NOT '7+ conditions' (entry-count)"
        result.partial == true
        def skip = result.settingsSkipped as List
        // Multiple skipped entries all carry condIdx=0 (production stamps every
        // walker-side skipped entry with the in-flight condition index). The
        // unique-count discrimination must collapse them to "1 condition".
        def walkerEntries = skip.findAll { it instanceof Map && it.condIdx == 0 }
        walkerEntries.size() >= 3   // proves multiple skipped entries share condIdx=0
        def hint = result.repairHints?.toString() ?: ""
        hint.contains("1 condition ")    // singular -- unique-count discrimination
        // Entry-count would have produced "${walkerEntries.size()} conditions"
        // (e.g. "5 conditions"); pin it cannot leak.
        !hint.contains("${walkerEntries.size()} conditions")
        !hint.contains("condition(s)")
    }

    // ---------- W-spec-addRE-note-text: count-aware Required Expression note ----------
    //
    // L21617 emits "Required Expression added with N condition(s); updateRule fired."
    // with a count-aware ternary on condition/conditions. Pin both forms so a regression
    // that drops the discrimination is caught.
    // Both-ways pending (orchestrator).

    def "addRequiredExpression note says '1 condition' singular for one condition"() {
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage") {
                body.each { k, v ->
                    def mm = k.toString() =~ /^settings\[(.+)\]$/
                    if (mm && mm[0][1] == "rCapab_1") rCapabWritten = true
                }
            }
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
                             sections: [[title: "", input: [], paragraphs: ["Motion active"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1", type: "enum", options: ["Motion"]],
                [name: "hasAll",   type: "button"], [name: "doneST", type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [
                    [name: "rDev_1",  type: "capability.sensor", multiple: true],
                    [name: "state_1", type: "enum", options: ["active", "inactive"]]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"Motion"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[capability: "Motion", deviceIds: [8], state: "active"]]],
            confirm: true
        ])

        then: "note uses 'with 1 condition;' singular"
        result.success == true
        result.note?.toString()?.contains("Required Expression added with 1 condition;")
        !result.note?.toString()?.contains("with 1 conditions")
    }

    def "addRequiredExpression note says '2 conditions' plural for two conditions"() {
        given:
        enableHubAdminWrite()
        def rCapabWrittenForSlot = [1: false, 2: false]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage") {
                body.each { k, v ->
                    def mm = k.toString() =~ /^settings\[rCapab_(\d+)\]$/
                    if (mm) {
                        def s = (mm[0][1] as Integer)
                        rCapabWrittenForSlot[s] = true
                    }
                }
            }
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
                             sections: [[title: "", input: [], paragraphs: ["Motion active AND Switch on"]]]],
                settings: [:], childApps: []
            ])
        }
        // STPage exposes 2 sibling Motion slots; the walker advances cond=a -> walks
        // slot 1 -> writes oper -> cond=a -> walks slot 2. Same capability on both
        // slots keeps the state-domain stub simple and exercises the count-aware
        // 'with 2 conditions;' branch without colliding on slot-specific state enums.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            def inputs = [
                [name: "cond",     type: "enum", options: ["a": "New condition"]],
                [name: "oper",     type: "enum", options: ["AND": "AND", "OR": "OR"]],
                [name: "rCapab_1", type: "enum", options: ["Motion"]],
                [name: "hasAll",   type: "button"], [name: "doneST", type: "button"]
            ]
            if (rCapabWrittenForSlot[1]) {
                inputs = inputs + [
                    [name: "rDev_1",  type: "capability.sensor", multiple: true],
                    [name: "state_1", type: "enum", options: ["active", "inactive"]],
                    [name: "rCapab_2", type: "enum", options: ["Motion"]]
                ]
            }
            if (rCapabWrittenForSlot[2]) {
                inputs = inputs + [
                    [name: "rDev_2",  type: "capability.sensor", multiple: true],
                    [name: "state_2", type: "enum", options: ["active", "inactive"]]
                ]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq ${rCapabWrittenForSlot[1]}-${rCapabWrittenForSlot[2]}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"MotionA"}' }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"MotionB"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [
                conditions: [
                    [capability: "Motion", deviceIds: [8], state: "active"],
                    [capability: "Motion", deviceIds: [9], state: "inactive"]
                ],
                operator: "AND"
            ],
            confirm: true
        ])

        then: "note uses 'with 2 conditions;' plural"
        result.success == true
        result.note?.toString()?.contains("Required Expression added with 2 conditions;")
        !result.note?.toString()?.contains("with 2 condition;")
    }

    // ---------- W-spec-subscriptionSettle: count-aware helper output (precursor) ----------
    //
    // The subscription-settle WARN message at L21824 uses two count-aware phrasings
    // for "trigger is" / "triggers are". The helper _rmCheckSubscriptionSettle
    // determines the count via tDev<N> setting introspection. Pin both forms via the
    // helper output: triggerCount=1 (singular path) and triggerCount=2 (plural).
    // Both-ways pending (orchestrator).
    def "subscriptionSettle WARN message: singular 'trigger is' when triggerCount=1"() {
        // Drive the count-aware trigVerb ternary at L21834 via a real statusJson
        // stub returning one tDev entry + zero subscriptions. _rmCheckSubscriptionSettle
        // reports unsettled=true twice (initial + post-retry), so the WARN string fires.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "updateRule", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "updateRule", type: "button"]])
        }
        // statusJson direct (helper's subs:0 yields (1..0).collect = 2 entries, not 0).
        // Force eventSubscriptions: [] so _rmCheckSubscriptionSettle returns unsettled=true.
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100],
                appSettings: [[name: "tDev1", deviceIdsForDeviceList: [8]]],
                eventSubscriptions: [],
                scheduledJobs: [], appState: [:], childAppCount: 0, childDeviceCount: 0
            ])
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, button: "updateRule", confirm: true])

        then: "WARN string says 'trigger is' singular (count-aware, not the stringified-word ternary)"
        result.subscriptionSettle?.contains("rule has 1 trigger but")
        result.subscriptionSettle?.contains("The trigger is likely incomplete")
        !result.subscriptionSettle?.contains("The triggers are likely incomplete")
    }

    def "subscriptionSettle WARN message: plural 'triggers are' when triggerCount=2"() {
        // Plural-side regression pin for the trigVerb ternary at L21834.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "updateRule", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "updateRule", type: "button"]])
        }
        // statusJson direct (same rationale as singular spec above).
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100],
                appSettings: [
                    [name: "tDev1", deviceIdsForDeviceList: [8]],
                    [name: "tDev2", deviceIdsForDeviceList: [9]]
                ],
                eventSubscriptions: [],
                scheduledJobs: [], appState: [:], childAppCount: 0, childDeviceCount: 0
            ])
        }

        when:
        def result = script.toolUpdateNativeApp([appId: 100, button: "updateRule", confirm: true])

        then: "WARN string says 'triggers are' plural"
        result.subscriptionSettle?.contains("rule has 2 triggers but")
        result.subscriptionSettle?.contains("The triggers are likely incomplete")
        !result.subscriptionSettle?.contains("The trigger is likely incomplete")
    }

    // ---------- W-spec-addTrigger-deviceId-silent: addTrigger.condition singular deviceId ----------
    //
    // _rmBuildCondition now normalizes singular deviceId -> deviceIds for trigger.condition.
    // Both-ways pending (orchestrator).

    def "addTrigger: singular deviceId in trigger.condition is normalized before wizard write"() {
        given:
        enableHubAdminWrite()
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "selectTriggers") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m) writtenFields[m[0][1]] = v
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        // The conditional-trigger path consumes idx=1 for the inline condition wizard
        // (_rmBuildCondition writes rCapab_1/rDev_1/state_1) then bumps to idx=2 for the
        // actual trigger (tCapab2/tDev2/tstate2/isCondTrig.2/condTrig.2). Both slots must
        // be visible in the static stub or _rmAddTrigger errors on the missing tCapab2.
        // Mirrors the working "addTrigger.condition Map drives the inline condition
        // sub-wizard" spec's stub layout.
        def stFetchSeq = 0
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            stFetchSeq++
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectTriggers", title: "Triggers", install: false, error: null,
                             sections: [[title: "", input: [
                                 // Condition wizard slot at idx 1 (consumed by _rmBuildCondition)
                                 [name: "isCondTrig.1", type: "bool"],
                                 [name: "condTrig.1", type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1",   type: "enum", options: ["Motion", "Switch"]],
                                 [name: "rDev_1",     type: "capability.sensor", multiple: true],
                                 [name: "state_1",    type: "enum", options: ["active", "inactive"]],
                                 // Bound trigger at idx 2 (post-condition bump)
                                 [name: "tCapab2",    type: "enum", options: ["Schedule", "Switch", "Mode"]],
                                 [name: "tDev2",      type: "capability.sensor", multiple: true],
                                 [name: "tstate2",    type: "enum", options: ["on", "off"]],
                                 [name: "isCondTrig.2", type: "bool"],
                                 [name: "condTrig.2", type: "enum", options: ["1"]],
                                 [name: "hasAll",     type: "button"]
                             ], paragraphs: ["seq ${stFetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [], paragraphs: ["When Switch changes"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/7')  { params -> '{"id":"7","name":"Switch"}' }
        hubGet.register('/device/fullJson/73') { params -> '{"id":"73","name":"MotionSensor"}' }

        when: "trigger with conditional whose condition uses singular deviceId: 73"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addTrigger: [
                capability: "Switch",
                deviceIds: [7],
                conditional: true,
                condition: [
                    capability: "Motion",
                    deviceId: 73,     // singular -- should be normalized to rDev_1=[73]
                    state: "active"
                ]
            ],
            confirm: true
        ])

        then: "rDev_1 was written (condition device was normalized)"
        result.success == true
        // Exact-equality wire-value check: _rmBuildSettingsBody serializes a
        // single-element capability.* list as the bare CSV scalar "73" (no JSON-array
        // wrapping). The trigger.condition.deviceId singular form must produce the
        // same wire value as the deviceIds: [73] array form.
        writtenFields["rDev_1"] == "73"
    }

    // ---------- runtime-exception envelope (isError) coverage ----------

    @spock.lang.Unroll
    def "clone_native_app via dispatch returns -32602 when source config fetch returns empty (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        hubGet.register('/installedapp/configure/json/999') { params -> "" }

        when:
        def response = mcpDriver.callTool('clone_native_app', [sourceAppId: 999, confirm: true])

        then: "empty config fetch surfaces as -32602 IAE (matches the direct-call test's IllegalArgumentException)"
        response.error.code == -32602
        response.error.message.contains("999")
        response.error.message.toLowerCase().contains("not found")

        where:
        useGateways << [true, false]
    }

    // =========================================================================
    // R5 maintainer-review fixes -- pinning the round-5 critical / important
    // findings on the rm-reveal-walker PR. Each spec targets ONE
    // specific behavioural change introduced in the corresponding fix.
    // Both-ways pending (orchestrator).
    // =========================================================================

    // ---------- Finding 1: compareToDevice silent writes on null reveal ----------

    def "addRequiredExpression compareToDevice: refDev reveal returning null fails loud with cancel"() {
        // After rhsType_1 selects 'another device', the rDev2/refDev/compareDevId picker
        // must reveal. When it does not (firmware variant whose field names miss the
        // regex unions), the walker MUST cancel the wizard + throw rather than silently
        // commit a compareToDevice condition with no reference device.
        // Production fix: was `if (refDevReveal.input)` (silent no-op); now mandatory.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // RelrDev_1 + rhsType_1 reveal; but refDev/rDev2/compareDevId NEVER appear.
            // No field name in this fixture matches /rDev2_\d+|refDev_\d+|compareDevId_\d+/.
            def inputs = [
                [name: "cond",       type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",   type: "enum", options: ["Temperature"]],
                [name: "rDev_1",     type: "capability.sensor", multiple: true],
                [name: "RelrDev_1",  type: "enum", options: [">", "<", "="]],
                [name: "rhsType_1",  type: "enum", options: ["aValue": "a value", "aDevice": "another device"]],
                [name: "hasAll",     type: "button"],
                [name: "cancelCapab", type: "button"]
            ]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                compareToDevice: [deviceId: 99, attribute: "temperature"]
            ]]],
            confirm: true
        ])

        then: "fail-loud -- reference-device picker did not appear"
        result.success == false
        // Load-bearing discriminator: the error message must name the reference-device
        // picker missing AFTER the rhsType write succeeded. A vacuous regression that
        // silently writes a partial condition would return success:true, partial:false.
        result.error?.toString()?.contains("reference-device picker not revealed")
    }

    def "addRequiredExpression compareToDevice: refAttr reveal returning null fails loud with cancel"() {
        // Same shape as the refDev fail-loud spec, but exercises the refAttr reveal
        // branch one step further down the wizard.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // refDev_1 appears (matches /refDev_\d+/) but refAttr/rCustomAttr2/compareAttr never does.
            def inputs = [
                [name: "cond",       type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",   type: "enum", options: ["Temperature"]],
                [name: "rDev_1",     type: "capability.sensor", multiple: true],
                [name: "RelrDev_1",  type: "enum", options: [">", "<", "="]],
                [name: "rhsType_1",  type: "enum", options: ["aValue": "a value", "aDevice": "another device"]],
                [name: "refDev_1",   type: "capability.sensor", multiple: true],
                [name: "hasAll",     type: "button"],
                [name: "cancelCapab", type: "button"]
            ]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                compareToDevice: [deviceId: 99, attribute: "temperature"]
            ]]],
            confirm: true
        ])

        then: "fail-loud -- reference-attribute picker did not appear"
        result.success == false
        result.error?.toString()?.contains("reference-attribute picker not revealed")
    }

    def "addRequiredExpression compareToDevice: offset reveal returning null degrades with sentinel"() {
        // Optional offset field is missing-OK in the spec; when its picker does not
        // appear the walker logs warn + pushes a sentinel with reason='offset_field_not_revealed'.
        // Does NOT fail the call (offset is genuinely optional per the spec shape).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // refDev_1 + refAttr_1 reveal; offset_<N>/devOffset_<N> never does.
            def inputs = [
                [name: "cond",       type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",   type: "enum", options: ["Temperature"]],
                [name: "rDev_1",     type: "capability.sensor", multiple: true],
                [name: "RelrDev_1",  type: "enum", options: [">", "<", "="]],
                [name: "rhsType_1",  type: "enum", options: ["aValue": "a value", "aDevice": "another device"]],
                [name: "refDev_1",   type: "capability.sensor", multiple: true],
                [name: "refAttr_1",  type: "enum", options: ["temperature"]],
                [name: "hasAll",     type: "button"]
            ]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> '{"id":"99","name":"T2"}' }

        when: "offset is requested but the field is not in the schema"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Temperature",
                deviceIds: [8],
                comparator: ">",
                compareToDevice: [deviceId: 99, attribute: "temperature", offset: -2]
            ]]],
            confirm: true
        ])

        then: "non-fatal degradation -- sentinel pushed but call proceeds"
        // Load-bearing discriminator: a regression that drops the sentinel push (silent
        // no-op) would still satisfy result.success == true but would NOT carry the
        // offset_field_not_revealed entry. The reason-coded find below is the only
        // assertion that fails when the production sentinel push is removed.
        def offsetSentinel = (result.settingsSkipped as List)?.find {
            it instanceof Map && it.key == "compareToDevice" && it.reason == "offset_field_not_revealed"
        }
        offsetSentinel != null
        offsetSentinel.offset == -2
    }

    // ---------- Finding 2: trailing updateRule failure surfaces via dedicated response slots ----------

    def "addRequiredExpression: trailing updateRule failure surfaces updateRuleFailed + expressionNotLive"() {
        // _rmCheckRuleHealth does not detect an updateRule click rejection. The dispatcher
        // must populate updateRuleFailed=true + expressionNotLive=true + a repairHint
        // entry when the trailing click throws, so callers can diagnose without log-grep.
        // Fixture mirrors the Step 4b ghost-ifThen spec at L905 (which proves the full RE
        // commit path reaches its trailing updateRule); the only delta is the
        // updateRule-click injection that throws.
        given:
        enableHubAdminWrite()
        def updateRuleAttempted = false
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // _rmClickAppButton posts to /installedapp/btn with body["settings[<name>]"]="clicked"
            // -- canonical discriminator pattern used by sibling specs (e.g. cancelCapab wizardStuck
            // specs at L12527, L13021, L13086). NOT _action_button -- that key is never set.
            if (path == "/installedapp/btn" && body["settings[updateRule]"] == "clicked") {
                updateRuleAttempted = true
                throw new RuntimeException("simulated updateRule rejection (firmware refused the click)")
            }
            [status: 200, location: null, data: '']
        }
        // configure/json/<id> -- the bare-id config fetch.
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        // mainPage -- post-commit bake-check looks for the baked RE paragraph here, so
        // _rmCheckRuleHealth + the RE result reach the trailing updateRule.
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                             sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                         body: [[element: "paragraph", description: "IF S1 is on"]]]]],
                settings: [:], childApps: []
            ])
        }
        // STPage -- the full RE wizard's input schema must include cond/rCapab/rDev/state/hasAll/doneST.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            fetchSeq++
            stPageCondSchemaJson(100, fetchSeq)
        }
        // selectActions -- N button for Step 4b ghost ifThen click.
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "N", type: "button"]])
        }
        // doActPage -- Step 4b ghost ifThen sequence: actType.1=condActs, actSubType.1=getIfThen, actionCancel click.
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "actionCancel", type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Switch", deviceIds: [8], state: "on"
            ]]],
            confirm: true
        ])

        then: "updateRule click was attempted -- precondition"
        // If this fails, the trailing updateRule path is not being reached (the call
        // probably threw earlier in the RE commit). Earlier-throw regressions would
        // show up as result.success == false WITHOUT updateRuleFailed being set.
        updateRuleAttempted == true

        and: "envelope carries dedicated failure slots, not just a warn log"
        // Load-bearing discriminators: the three flag keys are the only way callers can
        // detect this failure mode without parsing log output. A regression that reverts
        // the propagation (warn-log-only) would leave updateRuleFailed=null and
        // expressionNotLive=null.
        result.updateRuleFailed == true
        result.expressionNotLive == true
        result.updateRuleError?.toString()?.contains("simulated updateRule rejection")

        and: "success flips false and partial flips true so callers do not treat the response as fully baked"
        result.success == false
        result.partial == true

        and: "repairHints names the recovery action"
        result.repairHints?.any { it?.toString()?.contains("updateRule") }
    }

    // ---------- Finding 4: doActPage walker-cancel symmetry with STPage ----------

    def "addAction ifThen: walker-thrown exception triggers exactly one cancelCapab via outer-catch fallback"() {
        // When _rmFetchConfigJson throws from inside _rmRevealStep (the post-trigger
        // re-fetch), the walker never invokes its cancel closure -- the exception comes
        // from inside the reveal step's plumbing, not from a guarded validation path.
        // The outer catch in _rmAddAction must observe that actCancelledByWalker is still
        // false and issue cancelCapab itself. Mode capability exercises this path because
        // its walker branch uses revealStep with a write-trigger closure (rCapab='Mode'
        // reveals modes<N>); when the post-fetch in revealStep throws, the walker has no
        // try/catch around it.
        given:
        enableHubAdminWrite()
        def cancelCapabClicks = 0
        // doActFetchesAfterRCapab counts doActPage fetches that happen AFTER rCapab_1 has
        // been POSTed. The throw fires on the SECOND such fetch -- the sequence around
        // the walker's revealStep call for rCapab_1 is:
        //   (1) revealStep pre-fetch              -- before POST, NOT counted
        //   (2) _rmWriteSettingOnPage pre-fetch   -- before POST, NOT counted
        //   POST settings[rCapab_1]=Mode          -- flips rCapabWritten=true
        //   (3) _rmWriteSettingOnPage verify-fetch -- counted=1; this fetch is wrapped in
        //       _rmWriteSettingOnPage's own try/catch so a throw here would be SWALLOWED
        //       (just sets verifyFetchErr=msg and continues). Letting this one return
        //       successfully.
        //   (4) revealStep post-trigger fetch     -- counted=2; this fetch is UNGUARDED in
        //       _rmRevealStep/_rmWalkConditionReveal so a throw propagates to the per-cond
        //       catch in _rmAddAction -- the exact path we are pinning the outer-catch
        //       fallback against.
        def rCapabWritten = false
        def doActFetchesAfterRCapab = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn" && body["settings[cancelCapab]"] == "clicked") {
                cancelCapabClicks++
            }
            if (path == "/installedapp/update/json" && body["settings[rCapab_1]"] != null) {
                rCapabWritten = true
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]]
            ])
        }
        def doActThrowArmed = false
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            if (rCapabWritten) {
                doActFetchesAfterRCapab++
                // Throw on the SECOND post-rCapab fetch -- the revealStep post-trigger
                // fetch. Skipping fetch 1 (the verify-fetch inside _rmWriteSettingOnPage,
                // which would swallow the exception in its own try/catch and let the
                // walker continue normally).
                if (doActFetchesAfterRCapab == 2 && !doActThrowArmed) {
                    doActThrowArmed = true
                    throw new RuntimeException("simulated mid-walk fetch error (revealStep post-trigger fetch)")
                }
            }
            ruleConfigJson(100, "r", [
                [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
                [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                [name: "cond",         type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",     type: "enum", options: ["Mode"]],
                [name: "hasAll",       type: "button"],
                [name: "cancelCapab",  type: "button"]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        sharedLocation.modes = [[id: "3", name: "Night"]]

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[capability: "Mode", state: "Night"]]]
            ],
            confirm: true
        ])

        then: "addAction surfaced the failure -- precondition"
        result.success == false

        and: "the throw injection actually fired -- pinning that we exercised the right path"
        // Without this check, a refactor that changes the fetch order could cause the throw
        // to never fire and the test would still pass with cancelCapabClicks == 1 from a
        // different code path (or fail to mean what we think it means).
        doActThrowArmed == true

        and: "outer-catch fallback issued exactly one cancelCapab click"
        // Load-bearing discriminator: TWO regression classes are pinned with the exact
        // count form.
        //   (a) cancelCapabClicks == 0: outer-catch fallback removed -> walker-thrown
        //       exception never got a cancel because the walker itself never fired
        //       cancelInFlightActCond (the throw came from inside _rmFetchConfigJson).
        //   (b) cancelCapabClicks == 2: actCancelledByWalker flag broken -> outer catch
        //       fires cancel AFTER the walker already did, the second click fails on an
        //       empty wizard, sets actWizardCleanupFailed=true, surfaces a false wizardStuck.
        // Sibling STPage spec ("walker cancelled once; outer catch did not re-cancel")
        // uses cancelCapabClicks == 1 for the same symmetry reason.
        cancelCapabClicks == 1
    }

    // ---------- Finding 5: Variable picker validation -- empty varOpts pushes sentinel ----------

    def "addRequiredExpression Variable: empty option list pushes api_unavailable sentinel + partial"() {
        // When the schema's variable-picker option list is empty (e.g. firmware lazily
        // populates the enum, or probe-timing race), the walker MUST signal degradation
        // via the variable-validation sentinel rather than silently accept the name.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // lVar_1 reveal succeeds, but options:[] -- walker cannot validate the name.
            def inputs = [
                [name: "cond",      type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",  type: "enum", options: ["Variable"]],
                [name: "lVar_1",    type: "enum", options: [:]],  // empty -- the regression target
                [name: "RelrDev_1", type: "enum", options: ["=", "!="]],
                [name: "state_1",   type: "text"],
                [name: "hasAll",    type: "button"],
                [name: "doneST",    type: "button"]
            ]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable", variable: "myVar", comparator: "=", value: 42
            ]]],
            confirm: true
        ])

        then: "write proceeds (graceful degradation) but flagged partial"
        result.partial == true

        and: "sentinel names the variable-validation failure path"
        // Load-bearing discriminator: the reason-coded find is the only assertion that
        // fails when the production sentinel push is removed. Plain partial==true might
        // be satisfied by unrelated skipped entries in this fixture.
        def sentinel = (result.settingsSkipped as List)?.find {
            it instanceof Map && it.key == "variable-validation" && it.reason == "api_unavailable"
        }
        sentinel != null
        sentinel.varName == "myVar"
    }

    // ---------- Finding 6: Variable condition missing RHS fails loud ----------

    def "addRequiredExpression Variable: comparator '=' without state or value fails loud"() {
        // When the comparator requires RHS but neither state nor value is supplied, the
        // walker MUST cancel + throw rather than letting RM render against the field
        // default (0). Mirrors the _rmBuildCondition (selectTriggers) fail-loud at L18557.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // Reveal succeeds through state_1 -- the test isolates the RHS-missing condition.
            def inputs = [
                [name: "cond",      type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",  type: "enum", options: ["Variable"]],
                [name: "lVar_1",    type: "enum", options: ["myVar": "myVar"]],
                [name: "RelrDev_1", type: "enum", options: ["=", "!="]],
                [name: "state_1",   type: "text"],
                [name: "hasAll",    type: "button"],
                [name: "cancelCapab", type: "button"]
            ]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "comparator '=' supplied but neither state nor value"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable", variable: "myVar", comparator: "="
                // No state, no value -- the regression target.
            ]]],
            confirm: true
        ])

        then: "fail-loud -- comparator requires RHS"
        result.success == false
        // Load-bearing discriminator: error message must name the missing-RHS contract
        // and the field-default-(0) failure mode the walker is protecting against.
        result.error?.toString()?.contains("requires an RHS value")
    }

    def "addRequiredExpression Variable: state-change comparator '*changed*' WITHOUT state or value succeeds"() {
        // Negative pin for the comparatorIsRhsOptional exemption in the walker (Variable
        // condition fail-loud guard). State-change comparators legitimately omit RHS;
        // a regression that drops the exemption would throw on every Variable-changed
        // condition and break the entire "fire when myVar changes" idiom.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // state_1 present in the schema (the walker still reveals it -- the spec only
            // pins the no-write path when condStateOrValue is null and the comparator is
            // change-shaped). hasAll closes the wizard.
            def inputs = [
                [name: "cond",      type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",  type: "enum", options: ["Variable"]],
                [name: "lVar_1",    type: "enum", options: ["myVar": "myVar"]],
                [name: "RelrDev_1", type: "enum", options: ["*changed*", "=", "!="]],
                [name: "state_1",   type: "text"],
                [name: "hasAll",    type: "button"],
                [name: "doneST",    type: "button"]
            ]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "Variable + comparator '*changed*' supplied, no state, no value"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Variable", variable: "myVar", comparator: "*changed*"
                // Intentionally NO state, NO value -- state-change is the RHS-optional shape.
            ]]],
            confirm: true
        ])

        then: "call succeeded -- comparatorIsRhsOptional exemption let the no-RHS path through"
        // Load-bearing discriminators: any regression that drops the exemption would
        // flip BOTH of these (success=false + error containing 'requires an RHS value').
        // The negative-pin pair is what catches a refactor that mistakenly tightens the
        // RHS-required check to all comparators.
        result.success != false
        result.error == null
    }

    // ---------- Finding 7: subExpression on doActPage rejected with targeted error ----------

    def "addAction ifThen: nested subExpression in expression.conditions rejected with targeted error"() {
        // The normalize pre-pass recurses through subExpression (justified for the shared
        // helper shape), but the doActPage walker only handles flat conditions. Reject at
        // the pre-pass with a clear message rather than letting the walker emit a generic
        // 'capability is required' error.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [
                [name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]]
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"S1"}' }
        hubGet.register('/device/fullJson/9')  { params -> '{"id":"9","name":"S2"}' }

        when: "expression.conditions contains a nested subExpression entry"
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    subExpression: [conditions: [
                        [capability: "Switch", deviceIds: [8], state: "on"],
                        [capability: "Switch", deviceIds: [9], state: "off"]
                    ], operator: "AND"]
                ]]]
            ],
            confirm: true
        ])

        then: "rejected with the targeted message rather than the generic 'capability is required'"
        result.success == false
        // Load-bearing discriminator: a regression that drops the pre-pass reject and
        // lets the walker handle the shape would surface a different error string.
        result.error?.toString()?.contains("nested subExpression")
        result.error?.toString()?.contains("not yet supported")
    }

    // ---------- Finding 9: _rmRevealStep reveal-fallback sentinel ----------

    def "addRequiredExpression: walker pushes reveal_fallback_to_existing_field sentinel when only revealedAny matches"() {
        // Static-schema firmware exposes always-visible fields; the walker's revealStep
        // wrapper pushes an informational sentinel so callers can detect that a same-named
        // leftover field was matched rather than a freshly-revealed one. Does NOT set
        // partial=true on its own.
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // Static schema -- every field present from the first fetch. The Mode-capability
            // reveal sees modes<N> already-present (matches revealedAny but not revealedNew)
            // because the field exists before the rCapab trigger writes.
            def inputs = [
                [name: "cond",       type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",   type: "enum", options: ["Mode"]],
                [name: "modes1",     type: "enum", options: ["1": "Day", "3": "Night"], multiple: true],
                [name: "hasAll",     type: "button"],
                [name: "doneST",     type: "button"]
            ]
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        sharedLocation.modes = [[id: "3", name: "Night"]]

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Mode", state: "Night"
            ]]],
            confirm: true
        ])

        then: "call succeeded (static-schema operation is legitimate)"
        result.success != false

        and: "informational sentinel surfaces the reveal-fallback path"
        // Load-bearing discriminator: a regression that drops the wrapper or stops pushing
        // the sentinel would leave settingsSkipped without this specific entry. The
        // reason-coded find is the only assertion that distinguishes pre-fix vs post-fix.
        def fallbackSentinel = (result.settingsSkipped as List)?.find {
            it instanceof Map && it.reason == "reveal_fallback_to_existing_field"
        }
        fallbackSentinel != null
    }

    def "addRequiredExpression: progressive-disclosure reveal does NOT push reveal_fallback sentinel"() {
        // Negative pin for the reveal_fallback_to_existing_field sentinel: when the schema
        // legitimately advances after the trigger write (revealedNew matches), the wrapper
        // MUST NOT push the sentinel. A regression that flips the wrapper to push on every
        // fallback path (even when revealedNew matched) would emit false positives on every
        // normal progressive-disclosure write -- the entire walker would mark every
        // condition partial=true.
        // Both-ways pending (orchestrator).
        given:
        enableHubAdminWrite()
        def rCapabWritten = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            // Skip wizard-Done submit: Hubitat sends empty placeholders on Done.
            if (path == "/installedapp/update/json" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def m = k.toString() =~ /^settings\[(.+)\]$/
                    if (m && m[0][1] == "rCapab_1") {
                        rCapabWritten = true
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // Progressive disclosure: modes1 ONLY appears after the rCapab='Mode' write
            // commits. Pre-trigger snapshot has no modes<N>; post-trigger snapshot does.
            // revealedNew matches modes1 -> fallbackToExisting=false -> NO sentinel push.
            def inputs = [
                [name: "cond",      type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",  type: "enum", options: ["Mode"]],
                [name: "hasAll",    type: "button"],
                [name: "doneST",    type: "button"]
            ]
            if (rCapabWritten) {
                inputs = inputs + [[name: "modes1", type: "enum",
                    options: ["1": "Day", "3": "Night"], multiple: true]]
            }
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: inputs, paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        sharedLocation.modes = [[id: "3", name: "Night"]]

        when:
        def result = script.toolUpdateNativeApp([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Mode", state: "Night"
            ]]],
            confirm: true
        ])

        then: "call succeeded"
        result.success != false

        and: "NO reveal_fallback sentinel in settingsSkipped -- progressive disclosure is the normal path"
        // Load-bearing discriminator: pairs with the positive-pin spec above ("walker
        // pushes reveal_fallback_to_existing_field sentinel when only revealedAny
        // matches"). Together the pair pins the wrapper to fire ONLY on the static-schema
        // / leftover-field case, not on legitimate progressive disclosure.
        def fallbackSentinel = (result.settingsSkipped as List)?.find {
            it instanceof Map && it.reason == "reveal_fallback_to_existing_field"
        }
        fallbackSentinel == null
    }

    // ---------- Finding 3: tools/list inline schema description -- detected not tampered ----------

    def "tools/list update_native_app description aligns Tamper + CO2 with detected/clear vocabulary"() {
        // The inline schema description served via tools/list pointed at 'tampered' for
        // Tamper (the capability actually emits 'detected') and 'not detected' for CO2
        // (the capability actually emits 'clear'). Both calls would have failed against
        // the live walker's option validation; the description was misleading agents.
        when:
        def tools = script.getAllToolDefinitions()
        def updateNativeApp = tools.find { it.name == "update_native_app" }
        // The relevant text is inside the addRequiredExpression / addAction / addTrigger
        // condition state-name note (rendered into the description). Concatenate all
        // description-bearing fields so we catch the note wherever it lives.
        def schemaText = updateNativeApp?.inputSchema?.toString() ?: ""
        def fullText = (updateNativeApp?.description ?: "") + " " + schemaText

        then: "tools/list no longer surfaces the wrong vocabulary"
        // Load-bearing discriminators: must NOT contain 'tampered' (the wrong word for
        // TamperAlert) and must NOT contain 'not detected' (the wrong word for CO2).
        // Production fix touched the inline description; without it agents copying the
        // example would write a state never accepted by the walker.
        !fullText.contains("'tampered'")
        !fullText.contains("'not detected'")

        and: "the correct values appear in the relevant context"
        fullText.contains("'detected'")
        fullText.contains("'clear'")
    }
}
