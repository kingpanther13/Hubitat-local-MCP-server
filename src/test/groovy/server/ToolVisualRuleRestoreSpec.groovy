package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Spec for the Visual Rules Builder arm of the unified rule backup/restore pipeline:
 *
 *   _rmBackupRuleSnapshot    -- VRB-aware capture: vrbFormat ("classic"|"graph"),
 *                              vrbRulePaused, vrbDefinition (classic) / vrbRuleJson (graph);
 *                              configure/json-throws fallback (Vue children may not serve it);
 *                              husk snapshot when the rule endpoints answer not-a-rule.
 *   _rmRestoreFromBackup     -- routes appType=="visual_rule" snapshots to
 *                              _vrbRestoreFromSnapshot before any registry/createchild logic.
 *   _vrbRestoreFromSnapshot  -- in-place vs recreate, format-mismatch contracts,
 *                              snapshot pause-state replay, read-back verification.
 *
 * Mocking (docs/testing.md cheat sheet): hubInternalGet routes through hubGet.register;
 * hubInternalGetRaw / hubInternalPostJson / uploadHubFile / downloadHubFile are purely
 * dynamic main-file methods, stubbed per-test on script.metaClass.
 */
class ToolVisualRuleRestoreSpec extends ToolSpecBase {

    // The per-version child-create routes the VRB parent (id 700) exposes.
    private static final String CREATE_1_0 = '/installedapp/createchild/hubitat/Visual Rule Builder 1.0/parent/700'
    private static final String CREATE_2_0 = '/installedapp/createchild/hubitat/Visual Rule Builder 2.0/parent/700'

    private static final String GRAPH_NOT_FOUND = '{"success":false,"message":"Rule builder instance not found"}'

    // Fresh per feature (Spock builds a new spec instance per feature method).
    List rawPaths = []
    List posts = []
    List uploads = []

    private void enableWrite() {
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        stateMap.lastBackupTimestamp = 1234567890000L  // == the fixed harness now(), so "within 24h"
    }

    private static String json(Object o) { JsonOutput.toJson(o) }

    private static Map classicDefinition() {
        [whenNodes: [[result: true, deviceIds: [59], switches: [59], switchEvent: 'Turns off',
                      index: 0, triggerType: 'switch', type: 'when']],
         thenNodes: [[actionType: 'turnOff', deviceIds: [122], switches: [122], index: 0, type: 'then']],
         elseNodes: []]
    }

    private static Map graphDefinition() {
        [version: 1,
         nodes: [[id: 'n1', kind: 'trigger', type: 'switch', config: [switches: [59], switchEvent: 'Turns off']],
                 [id: 'trigger-merge', kind: 'merge', type: 'triggerMerge', config: [:]],
                 [id: 'decision', kind: 'decision', type: 'all', config: [conditions: []]],
                 [id: 'n2', kind: 'action', type: 'turnOff', config: [switches: [59]]]],
         edges: [[from: 'n1', to: 'trigger-merge', port: 'next'],
                 [from: 'trigger-merge', to: 'decision', port: 'next'],
                 [from: 'decision', to: 'n2', port: 'true']]]
    }

    /** configure/json body for a VRB child -- appType.name reverse-maps to "visual_rule" in
     *  _appTypeRegistry. The hub reports the child type VERSIONED ("Visual Rule Builder 1.0" /
     *  "... 2.0"), never the bare family name the registry stores, so that is the default here. */
    private static String vrbChildConfigJson(int appId, String label, String typeName = "Visual Rule Builder 1.0") {
        JsonOutput.toJson([
            app: [id: appId, name: typeName, label: label, trueLabel: label, installed: true,
                  appType: [name: typeName, namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "", install: true, error: null, sections: []],
            settings: [:],
            childApps: []
        ])
    }

    private static String vrbStatusJson(int appId) {
        JsonOutput.toJson([installedApp: [id: appId], appSettings: [], eventSubscriptions: [],
                           scheduledJobs: [], appState: [:], childAppCount: 0, childDeviceCount: 0])
    }

    /** Base visual_rule snapshot shape as _rmBackupRuleSnapshot writes it; extend per test. */
    private static Map vrbSnapshot(int id, Map extra = [:]) {
        [schemaVersion: 1, ruleId: id, appId: id, appType: 'visual_rule', reason: 'pre-delete',
         timestamp: 1000, timestampIso: '2026-01-01T00:00:00Z', configJson: null, statusJson: [:]] + extra
    }

    private void stubUploads() {
        def captured = uploads
        script.metaClass.uploadHubFile = { String fn, byte[] b -> captured << [fileName: fn, bytes: b] }
    }

    private void stubDownload(byte[] bytes) {
        script.metaClass.downloadHubFile = { String fn -> bytes }
    }

    private Map parsedUpload(int idx = 0) {
        new JsonSlurper().parseText(new String(uploads[idx].bytes as byte[], 'UTF-8')) as Map
    }

    /** hubInternalGetRaw stub: records every path (create page AND forcedelete), serves the builder HTML. */
    private void stubRawPage(String html) {
        def paths = rawPaths
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            paths << path
            [status: 200, location: null, data: html]
        }
    }

    /** The VRB parent node the per-version child-create routes hang off. */
    private void registerVrbParent() {
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[key: 700, data: [id: 700, name: 'Visual Rules Builder',
                                                        type: 'Visual Rules Builder', disabled: false],
                                       children: []]]])
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

    /** hubInternalPostJson stub: records {path, body}; responder's return is the parsed-Map response. */
    private void stubPostJson(Closure responder = null) {
        def captured = posts
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            captured << [path: path, body: jsonBody]
            responder ? responder.call(path, jsonBody) : null
        }
    }

    // ==================== _rmBackupRuleSnapshot: VRB capture ====================

    // The registry holds the bare family name; the hub reports the child type with a version
    // suffix. An exact-only reverse map classified every real Visual Rule as rule_machine, so
    // the snapshot skipped the definition capture and a restore recreated it as an RM rule.
    def "backup capture maps a VERSIONED Visual Rule type name to visual_rule"() {
        given:
        stubUploads()
        hubGet.register('/installedapp/configure/json/711') { params ->
            vrbChildConfigJson(711, 'Versioned', typeName) }
        hubGet.register('/installedapp/statusJson/711') { params -> vrbStatusJson(711) }
        hubGet.register('/app/ruleBuilder20Json/711') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/711') { params ->
            json(classicDefinition() + [name: 'Versioned', rulePaused: false, promptHistory: []])
        }

        when:
        script._rmBackupRuleSnapshot(711, 'pre-delete')

        then: 'the definition is captured, which only happens on the visual_rule branch'
        def snap = parsedUpload()
        snap.appType == 'visual_rule'
        snap.vrbFormat == 'classic'
        snap.vrbDefinition != null

        where:
        typeName << ['Visual Rule Builder', 'Visual Rule Builder 1.0', 'Visual Rule Builder 2.0']
    }

    def "backup capture does NOT map the parent app or an unrelated suffix to visual_rule"() {
        given:
        stubUploads()
        hubGet.register('/installedapp/configure/json/712') { params ->
            vrbChildConfigJson(712, 'Impostor', typeName) }
        hubGet.register('/installedapp/statusJson/712') { params -> vrbStatusJson(712) }
        hubGet.register('/app/ruleBuilder20Json/712') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/712') { params -> GRAPH_NOT_FOUND }

        when:
        script._rmBackupRuleSnapshot(712, 'pre-delete')

        then: 'a plural parent name and a non-version suffix both stay rule_machine'
        parsedUpload().appType == 'rule_machine'

        where:
        typeName << ['Visual Rules Builder', 'Visual Rule Builder Deluxe']
    }

    def "backup capture (classic VRB child): snapshot carries appType, vrbFormat, vrbDefinition and vrbRulePaused"() {
        given: 'configure/json answers as a Visual Rule Builder child; rule endpoints speak classic'
        stubUploads()
        hubGet.register('/installedapp/configure/json/701') { params -> vrbChildConfigJson(701, 'Hall light') }
        hubGet.register('/installedapp/statusJson/701') { params -> vrbStatusJson(701) }
        hubGet.register('/app/ruleBuilder20Json/701') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/701') { params ->
            json(classicDefinition() + [name: 'Hall light', rulePaused: true, promptHistory: []])
        }

        when:
        def result = script._rmBackupRuleSnapshot(701, 'pre-delete')

        then: 'one snapshot file written and the manifest entry recorded under the rm-rule key'
        uploads.size() == 1
        result.backupKey.toString().startsWith('rm-rule_701_')
        uploads[0].fileName == result.fileName
        def manifestEntry = atomicStateMap.itemBackupManifest.find { it.key.toString().startsWith('rm-rule_701_') }
        manifestEntry != null
        manifestEntry.value.type == 'rm-rule'
        manifestEntry.value.appLabel == 'Hall light'

        and: 'the snapshot JSON carries the full VRB capture'
        def snap = parsedUpload()
        snap.appType == 'visual_rule'
        snap.vrbFormat == 'classic'
        snap.vrbRulePaused == true
        snap.appLabel == 'Hall light'
        snap.configJson != null

        and: 'the classic definition round-tripped node-for-node'
        snap.vrbDefinition.whenNodes[0].triggerType == 'switch'
        snap.vrbDefinition.whenNodes[0].switchEvent == 'Turns off'
        snap.vrbDefinition.thenNodes[0].actionType == 'turnOff'
        snap.vrbDefinition.elseNodes == []
        !snap.containsKey('vrbRuleJson')
    }

    def "backup capture (graph VRB child): vrbRuleJson keeps the raw double-encoded string"() {
        given: 'configure/json is unavailable (live VRB shape) but the graph endpoint answers'
        stubUploads()
        def ruleJsonStr = json(graphDefinition())
        hubGet.register('/installedapp/configure/json/702') { params -> throw new RuntimeException('Vue child -- no classic config page') }
        hubGet.register('/installedapp/statusJson/702') { params -> throw new RuntimeException('no statusJson either') }
        hubGet.register('/app/ruleBuilder20Json/702') { params ->
            json([name: 'Graph rule', rulePaused: false, ruleJson: ruleJsonStr, validationErrors: []])
        }

        when:
        def result = script._rmBackupRuleSnapshot(702, 'pre-delete')

        then:
        result.backupKey.toString().startsWith('rm-rule_702_')
        def snap = parsedUpload()
        snap.appType == 'visual_rule'
        snap.vrbFormat == 'graph'
        snap.vrbRulePaused == false
        snap.vrbRuleJson == ruleJsonStr
        !snap.containsKey('vrbDefinition')

        and: 'config absent, label from the VRB rule name, status failure recorded not fatal'
        snap.configJson == null
        snap.appLabel == 'Graph rule'
        snap.statusJson?.error?.contains('no statusJson')
    }

    def "backup fallback: configure/json throws but the id speaks classic VRB -- snapshot still written with configJson null"() {
        given:
        stubUploads()
        hubGet.register('/installedapp/configure/json/703') { params -> throw new RuntimeException('404 from configure/json') }
        hubGet.register('/installedapp/statusJson/703') { params -> throw new RuntimeException('404 from statusJson') }
        hubGet.register('/app/ruleBuilder20Json/703') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/703') { params ->
            json(classicDefinition() + [name: 'Door alert', rulePaused: false, promptHistory: []])
        }

        when:
        def result = script._rmBackupRuleSnapshot(703, 'pre-update')

        then: 'no throw; the VRB-flavored snapshot is the backup'
        noExceptionThrown()
        result.backupKey.toString().startsWith('rm-rule_703_')
        def snap = parsedUpload()
        snap.appType == 'visual_rule'
        snap.configJson == null
        snap.appLabel == 'Door alert'
        snap.vrbFormat == 'classic'
        snap.vrbRulePaused == false
        snap.vrbDefinition.whenNodes[0].triggerType == 'switch'
    }

    def "backup aborts with IllegalArgumentException when configure/json throws AND no VRB serialization answers"() {
        given:
        stubUploads()
        hubGet.register('/installedapp/configure/json/704') { params -> throw new RuntimeException('404 from configure/json') }
        hubGet.register('/app/ruleBuilder20Json/704') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/704') { params -> '{}' }

        when:
        script._rmBackupRuleSnapshot(704, 'pre-delete')

        then: 'backup-before-write stays a hard gate -- nothing written'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('No rule/app with id')
        uploads.isEmpty()
    }

    def "backup husk: VRB-typed app whose rule endpoints answer not-a-rule snapshots WITHOUT vrb fields (no throw)"() {
        given: 'configure/json says Visual Rule Builder but both rule endpoints deny the id'
        stubUploads()
        hubGet.register('/installedapp/configure/json/705') { params -> vrbChildConfigJson(705, 'Husk rule') }
        hubGet.register('/installedapp/statusJson/705') { params -> vrbStatusJson(705) }
        hubGet.register('/app/ruleBuilder20Json/705') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/705') { params -> '{}' }

        when:
        def result = script._rmBackupRuleSnapshot(705, 'pre-delete')

        then:
        noExceptionThrown()
        result.backupKey.toString().startsWith('rm-rule_705_')
        uploads.size() == 1

        and: 'the snapshot is a husk: typed visual_rule but with no captured definition fields'
        def snap = parsedUpload()
        snap.appType == 'visual_rule'
        snap.appLabel == 'Husk rule'
        !snap.containsKey('vrbFormat')
        !snap.containsKey('vrbDefinition')
        !snap.containsKey('vrbRuleJson')
        !snap.containsKey('vrbRulePaused')
    }

    // ==================== restore: recreate golden path ====================

    /** Deleted classic VRB rule 500; the classic snapshot recreates a 1.0 child at id 510; read-back echoes the save. */
    private void seedRecreateFixture() {
        def snapshot = vrbSnapshot(500, [appLabel: 'Hall light', vrbFormat: 'classic',
                                         vrbRulePaused: false, vrbDefinition: classicDefinition()])
        def bytes = json(snapshot).getBytes('UTF-8')
        atomicStateMap.itemBackupManifest = [
            'rm-rule_500_t': [type: 'rm-rule', id: 500, ruleId: 500, fileName: 'mcp-rm-backup-500-t.json',
                              reason: 'pre-delete', appLabel: 'Hall light', timestamp: 1000, sourceLength: bytes.length]
        ]
        stubDownload(bytes)
        hubGet.register('/installedapp/configure/json/500') { params -> throw new RuntimeException('410 -- rule gone') }
        hubGet.register('/app/ruleBuilder20Json/500') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/500') { params -> '{}' }
        registerVrbParent()
        stubCreateChild(510)
        def savedState = [:]
        stubPostJson { path, body -> savedState.putAll(new JsonSlurper().parseText(body) as Map); null }
        hubGet.register('/app/ruleBuilder20Json/510') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/510') { params -> json(savedState) }
    }

    def "hub_restore_backup recreates a deleted Visual Rule with a fresh id and replays the snapshot definition"() {
        given:
        enableWrite()
        seedRecreateFixture()

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'rm-rule_500_t', confirm: true])

        then: 'routed through _vrbRestoreFromSnapshot, recreated and verified'
        result.success == true
        result.type == 'visual-rule'
        result.recreated == true
        result.ruleId == 510
        result.originalRuleId == 500
        result.verified == true
        result.format == 'classic'
        result.version == '1.0'
        result.name == 'Hall light'
        result.rulePaused == false
        result.backupFile == 'mcp-rm-backup-500-t.json'
        result.note.contains('recreated with new id 510')

        and: 'a Visual Rule Builder 1.0 child was created and the save POSTed to the NEW id'
        rawPaths == [CREATE_1_0]
        posts.size() == 1
        posts[0].path == '/app/ruleBuilderJson/510'

        and: 'the POST body carried the snapshot name + nodes + pause state'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.name == 'Hall light'
        body.rulePaused == false
        body.whenNodes[0].switchEvent == 'Turns off'
        body.thenNodes[0].actionType == 'turnOff'
        body.elseNodes == []
    }

    def "backup capture prefers the rule's own name over a pause-decorated installed-app label"() {
        given: 'a PAUSED rule: configure/json label carries the hub decoration, the VRB name is clean'
        stubUploads()
        hubGet.register('/installedapp/configure/json/702') { params -> vrbChildConfigJson(702, 'Hall light (Paused)') }
        hubGet.register('/installedapp/statusJson/702') { params -> vrbStatusJson(702) }
        hubGet.register('/app/ruleBuilder20Json/702') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/702') { params ->
            json(classicDefinition() + [name: 'Hall light', rulePaused: true, promptHistory: []])
        }

        when:
        script._rmBackupRuleSnapshot(702, 'pre-delete')

        then: 'the snapshot appLabel is the UNDECORATED rule name (live-verified hub behavior)'
        parsedUpload().appLabel == 'Hall light'
        parsedUpload().vrbRulePaused == true
    }

    def "restore strips a trailing pause decoration from a decorated-label snapshot"() {
        given: 'a paused-rule snapshot whose appLabel carries the hub decoration'
        enableWrite()
        def snapshot = vrbSnapshot(500, [appLabel: 'Hall light (Paused)', vrbFormat: 'classic',
                                         vrbRulePaused: true, vrbDefinition: classicDefinition()])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/app/ruleBuilder20Json/500') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/500') { params -> '{}' }
        registerVrbParent()
        stubCreateChild(510)
        def savedState = [:]
        stubPostJson { path, body -> savedState.putAll(new JsonSlurper().parseText(body) as Map); null }
        hubGet.register('/app/ruleBuilder20Json/510') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/510') { params -> json(savedState) }

        when:
        def result = script._rmRestoreFromBackup([fileName: 'mcp-rm-backup-500-t.json'])

        then: 'the replayed name has the decoration stripped; the pause state still restores'
        result.success == true
        result.name == 'Hall light'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.name == 'Hall light'
        body.rulePaused == true
    }

    @Unroll
    def "hub_restore_backup via dispatch recreates a deleted Visual Rule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        seedRecreateFixture()

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'rm-rule_500_t', confirm: true])

        then:
        response.error == null
        response.id == mcpDriver.lastSentId
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.type == 'visual-rule'
        inner.recreated == true
        inner.ruleId == 510
        inner.originalRuleId == 500
        inner.verified == true

        where:
        useGateways << [true, false]
    }

    // ==================== restore: in-place ====================

    def "restore in-place: original id still answers classic -- save POSTs to the ORIGINAL id and the snapshot pause state wins"() {
        given: 'rule 600 still exists, renamed and paused since the snapshot was taken'
        def snapshot = vrbSnapshot(600, [appLabel: 'Hall light', vrbFormat: 'classic',
                                         vrbRulePaused: false, vrbDefinition: classicDefinition()])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/installedapp/configure/json/600') { params -> throw new RuntimeException('Vue child -- no classic config page') }
        def state600 = [name: 'Hall light (drifted)', rulePaused: true, promptHistory: []] + classicDefinition()
        hubGet.register('/app/ruleBuilder20Json/600') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/600') { params -> json(state600) }
        stubRawPage('<html>should never be fetched</html>')
        stubPostJson { path, body -> state600.putAll(new JsonSlurper().parseText(body) as Map); null }

        when:
        def result = script._rmRestoreFromBackup([type: 'rm-rule', fileName: 'mcp-rm-backup-600-t.json'])

        then: 'in-place: no create page, no forcedelete, the save targets the original id'
        rawPaths.isEmpty()
        posts.size() == 1
        posts[0].path == '/app/ruleBuilderJson/600'
        result.success == true
        result.type == 'visual-rule'
        result.recreated == false
        result.ruleId == 600
        result.originalRuleId == 600
        result.verified == true
        result.note.contains('restored in place')

        and: 'the SNAPSHOT pause state rides the save body -- live drift (paused=true) does not survive'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.rulePaused == false
        body.name == 'Hall light'
        result.rulePaused == false
    }

    // ==================== restore: error contracts ====================

    def "restore of a husk snapshot (no vrb fields) fails with a no-captured-definition error and points at hub_set_visual_rule"() {
        given: 'a visual_rule snapshot written when the rule endpoints were unreadable'
        def snapshot = vrbSnapshot(620, [appLabel: 'Husk rule'])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/installedapp/configure/json/620') { params -> throw new RuntimeException('410 -- rule gone') }
        stubRawPage('<html>should never be fetched</html>')
        stubPostJson()

        when:
        def result = script._rmRestoreFromBackup([type: 'rm-rule', fileName: 'mcp-rm-backup-620-t.json'])

        then:
        result.success == false
        result.originalRuleId == 620
        result.error.contains('no captured rule definition')
        result.note.contains('hub_set_visual_rule')

        and: 'nothing was created or written'
        rawPaths.isEmpty()
        posts.isEmpty()
    }

    def "restore format mismatch in-place: graph snapshot against a live classic rule fails without saving"() {
        given:
        def snapshot = vrbSnapshot(630, [appLabel: 'G', vrbFormat: 'graph',
                                         vrbRulePaused: false, vrbRuleJson: json(graphDefinition())])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/installedapp/configure/json/630') { params -> throw new RuntimeException('Vue child') }
        hubGet.register('/app/ruleBuilder20Json/630') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/630') { params ->
            json(classicDefinition() + [name: 'Live classic', rulePaused: false, promptHistory: []])
        }
        stubRawPage('<html>should never be fetched</html>')
        stubPostJson()

        when:
        def result = script._rmRestoreFromBackup([type: 'rm-rule', fileName: 'mcp-rm-backup-630-t.json'])

        then:
        result.success == false
        result.originalRuleId == 630
        result.error.contains('classic-format')
        result.error.contains('graph-format')
        result.note.contains('hub_delete_visual_rule')
        posts.isEmpty()
        rawPaths.isEmpty()
    }

    def "restore recreate on a hub that can only create 2.0 children TRANSLATES a classic snapshot"() {
        given: 'rule 640 is gone; no versioned child type, and the legacy route yields a GRAPH child at 999'
        def snapshot = vrbSnapshot(640, [appLabel: 'Old classic', vrbFormat: 'classic',
                                         vrbRulePaused: false, vrbDefinition: classicDefinition()])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/installedapp/configure/json/640') { params -> throw new RuntimeException('410 -- rule gone') }
        hubGet.register('/app/ruleBuilder20Json/640') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/640') { params -> '{}' }
        registerVrbParent()
        stubLegacyCreateOnly('<html><script>window.HubitatRuleBuilder20AppId = 999;</script></html>')
        def state999 = [name: null, rulePaused: false, ruleJson: null]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state999.name = b.name
            state999.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: []]
        }
        hubGet.register('/app/ruleBuilderPause/999/false') { params -> '{"success":true}' }
        hubGet.register('/app/ruleBuilder20Json/999') { params ->
            json([name: state999.name, rulePaused: state999.rulePaused, ruleJson: state999.ruleJson, validationErrors: []])
        }

        when:
        def result = script._rmRestoreFromBackup([type: 'rm-rule', fileName: 'mcp-rm-backup-640-t.json'])

        then: 'the snapshot was replayed onto the new GRAPH child, translated on the way'
        result.success == true
        result.originalRuleId == 640
        result.ruleId == 999
        result.recreated == true
        result.format == 'graph'
        result.version == '2.0'
        result.translatedFrom == 'classic'
        result.verified == true

        and: 'the classic whenNode became a trigger and the thenNode the true branch'
        posts.size() == 1
        posts[0].path == '/app/ruleBuilder20Json/999'
        def graph = new JsonSlurper().parseText(new JsonSlurper().parseText(posts[0].body as String).ruleJson as String)
        graph.version == 1
        graph.nodes.findAll { it.kind == 'trigger' }*.type == ['switch']
        graph.nodes.find { it.kind == 'decision' }.type == 'all'
        graph.nodes.findAll { it.kind == 'action' }*.type == ['turnOff']
        graph.edges.find { it.port == 'true' }.to == graph.nodes.find { it.kind == 'action' }.id

        and: 'no shell was force-deleted -- the create was used, not abandoned'
        rawPaths == [CREATE_1_0, '/app/createVisualRuleBuilderRule']
    }

    def "restore recreate whose activation threw on the hub reports activated=false and keeps the hub's activationError"() {
        given: 'rule 644 is gone; the 2.0 child stores the graph but activation raises'
        def graph = graphDefinition()
        def snapshot = vrbSnapshot(644, [appLabel: 'Graph rule', vrbFormat: 'graph',
                                         vrbRulePaused: false, vrbRuleJson: json(graph)])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/installedapp/configure/json/644') { params -> throw new RuntimeException('410 -- rule gone') }
        hubGet.register('/app/ruleBuilder20Json/644') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/644') { params -> '{}' }
        registerVrbParent()
        stubCreateChild(998)
        def state998 = [name: null, rulePaused: false, ruleJson: null]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state998.name = b.name
            state998.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, storedSuccessfully: true, activatedSuccessfully: false,
             activationError: 'scheduler unavailable', validationErrors: []]
        }
        hubGet.register('/app/ruleBuilderPause/998/false') { params -> '{"success":true}' }
        hubGet.register('/app/ruleBuilder20Json/998') { params ->
            json([name: state998.name, rulePaused: state998.rulePaused, ruleJson: state998.ruleJson, validationErrors: [], runtimeGraph: null])
        }

        when:
        def result = script._rmRestoreFromBackup([type: 'rm-rule', fileName: 'mcp-rm-backup-644-t.json'])

        then: 'the replay verified, but the caller is told the automation is NOT running and why'
        result.success == true
        result.verified == true
        result.ruleId == 998
        result.activated == false
        result.activationError == 'scheduler unavailable'
        result.note.contains('recreated with new id 998')
        result.note.contains('Stored but NOT activated')
        result.note.contains('scheduler unavailable')
    }

    def "restore recreate of a GRAPH snapshot asks for a 2.0 child and replays it untranslated"() {
        given: 'rule 643 is gone; the versioned route yields a graph child at 997'
        def graph = graphDefinition()
        def snapshot = vrbSnapshot(643, [appLabel: 'Graph rule', vrbFormat: 'graph',
                                         vrbRulePaused: false, vrbRuleJson: json(graph)])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/installedapp/configure/json/643') { params -> throw new RuntimeException('410 -- rule gone') }
        hubGet.register('/app/ruleBuilder20Json/643') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/643') { params -> '{}' }
        registerVrbParent()
        stubCreateChild(997)
        def state997 = [name: null, rulePaused: false, ruleJson: null]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state997.name = b.name
            state997.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: []]
        }
        hubGet.register('/app/ruleBuilderPause/997/false') { params -> '{"success":true}' }
        hubGet.register('/app/ruleBuilder20Json/997') { params ->
            json([name: state997.name, rulePaused: state997.rulePaused, ruleJson: state997.ruleJson, validationErrors: []])
        }

        when:
        def result = script._rmRestoreFromBackup([type: 'rm-rule', fileName: 'mcp-rm-backup-643-t.json'])

        then: 'the 2.0 route was taken and the stored graph replayed node-for-node'
        rawPaths == [CREATE_2_0]
        result.success == true
        result.ruleId == 997
        result.recreated == true
        result.format == 'graph'
        result.version == '2.0'
        !result.containsKey('translatedFrom')
        posts[0].path == '/app/ruleBuilder20Json/997'
        new JsonSlurper().parseText(new JsonSlurper().parseText(posts[0].body as String).ruleJson as String).nodes*.id == graph.nodes*.id
    }

    def "restore in-place onto a rule that has become 2.0 translates the classic snapshot"() {
        given: 'app 642 still exists but now speaks graph; the snapshot is classic'
        def snapshot = vrbSnapshot(642, [appLabel: 'Hall light', vrbFormat: 'classic',
                                         vrbRulePaused: false, vrbDefinition: classicDefinition()])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/installedapp/configure/json/642') { params -> throw new RuntimeException('Vue child') }
        def state642 = [name: 'Drifted', rulePaused: false, ruleJson: json(graphDefinition())]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state642.name = b.name
            state642.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: []]
        }
        hubGet.register('/app/ruleBuilderPause/642/false') { params -> '{"success":true}' }
        hubGet.register('/app/ruleBuilder20Json/642') { params ->
            json([name: state642.name, rulePaused: state642.rulePaused, ruleJson: state642.ruleJson, validationErrors: []])
        }
        stubRawPage('<html>should never be fetched</html>')

        when:
        def result = script._rmRestoreFromBackup([type: 'rm-rule', fileName: 'mcp-rm-backup-642-t.json'])

        then: 'in-place, translated, and no child app was created'
        result.success == true
        result.recreated == false
        result.ruleId == 642
        result.format == 'graph'
        !result.containsKey('version')   // nothing was created, so no builder version was chosen
        result.translatedFrom == 'classic'
        result.name == 'Hall light'
        rawPaths.isEmpty()
        posts[0].path == '/app/ruleBuilder20Json/642'
    }

    def "restore recreate format mismatch: a hub that can only create 1.0 children refuses a GRAPH snapshot and cleans up the shell"() {
        given: 'rule 641 is gone; no versioned child type, and the legacy route yields a CLASSIC child at 998'
        def snapshot = vrbSnapshot(641, [appLabel: 'New graph', vrbFormat: 'graph',
                                         vrbRulePaused: false, vrbRuleJson: json(graphDefinition())])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/installedapp/configure/json/641') { params -> throw new RuntimeException('410 -- rule gone') }
        hubGet.register('/app/ruleBuilder20Json/641') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/641') { params -> '{}' }
        registerVrbParent()
        stubLegacyCreateOnly('<html><script>window.HubitatRuleBuilderAppId = 998;</script></html>')
        hubGet.register('/installedapp/json/998') { params -> '' }  // post-cleanup existence probe: gone
        stubPostJson()

        when:
        def result = script._rmRestoreFromBackup([type: 'rm-rule', fileName: 'mcp-rm-backup-641-t.json'])

        then: '2.0 structure has no 1.0 expression, so this direction still refuses'
        result.success == false
        result.originalRuleId == 641
        result.hubNativeFormat == 'classic'
        result.error.contains('classic-format')
        result.error.contains('graph-format')
        result.note.contains('cleaned up')

        and: 'the orphan shell created during the attempt was force-deleted; no save ever fired'
        rawPaths == [CREATE_2_0, '/app/createVisualRuleBuilderRule', '/installedapp/forcedelete/998/quiet']
        posts.isEmpty()
    }

    def "restore of a graph snapshot whose vrbRuleJson is unparseable fails with a structured error"() {
        given:
        def snapshot = vrbSnapshot(650, [appLabel: 'Broken graph', vrbFormat: 'graph',
                                         vrbRulePaused: false, vrbRuleJson: 'not json {{{'])
        stubDownload(json(snapshot).getBytes('UTF-8'))
        hubGet.register('/installedapp/configure/json/650') { params -> throw new RuntimeException('410 -- rule gone') }
        stubRawPage('<html>should never be fetched</html>')
        stubPostJson()

        when:
        def result = script._rmRestoreFromBackup([type: 'rm-rule', fileName: 'mcp-rm-backup-650-t.json'])

        then:
        result.success == false
        result.originalRuleId == 650
        result.error.contains('not parseable JSON')
        result.note.contains('hub_set_visual_rule')
        rawPaths.isEmpty()
        posts.isEmpty()
    }
}
