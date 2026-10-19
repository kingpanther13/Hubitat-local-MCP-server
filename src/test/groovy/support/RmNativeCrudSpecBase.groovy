package support

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Unroll

// Fixtures and helpers shared by the native Rule Machine CRUD specs.
abstract class RmNativeCrudSpecBase extends ToolSpecBase {

    // Shared location stub for tests that exercise location.modes (Mode trigger)
    // or location.hub (future). AppExecutor.getLocation() is wired in setupSpec
    // below. Tests that need specific modes assign sharedLocation.modes in their
    // given: block; cleanup() resets to the empty default so tests stay isolated.
    @Shared protected TestLocation sharedLocation = new TestLocation()

    // Per-test RMUtils stub for the rule-target existence specs. Installed in the
    // given: block of the specs that need it, uninstalled in cleanup() so the
    // shared class metaclass mutation cannot leak into unrelated tests.
    protected RMUtilsMock rmUtils

    def setupSpec() {
        // Wire location reads to the shared TestLocation stub so code paths that
        // call location.modes (addTrigger Mode, hub_list_modes, toolSetMode) get
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
        // Undo any RMUtils metaclass mutation from a rule-target existence spec.
        rmUtils?.uninstall()
        rmUtils = null
    }

    protected void enableWrite() {
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    // Single source of truth for POST-interceptor key matching. Returns the inner
    // field name of a "settings[<name>]" form key, or null if k is not such a key.
    // Single-evaluation matches()/group(1) -- NOT the `def m = k =~ /.../; m[0][1]`
    // idiom, which re-coerces a STATEFUL java.util.regex.Matcher on every read and
    // exhausts it under Groovy 3.0 (the second `m[0][1]` on the same matcher returns
    // null because the previous read advanced past the match). Routing every
    // interceptor through this helper makes that double-coercion shape unable to recur.
    protected static String _settingKeyOf(Object k) {
        def matcher = (k?.toString() ?: "") =~ /^settings\[(.+)\]$/
        return matcher.matches() ? matcher.group(1) : null
    }

    // Minimal RM rule config JSON. Schema inputs drive _rmBuildSettingsBody's
    // 3-field group logic, so tests shape the sections/inputs as needed.
    protected String ruleConfigJson(int ruleId, String label = "BAT-RM-test", List inputs = [], Integer parentAppId = null, Map settings = [:]) {
        def app = [id: ruleId, name: "Rule-5.1", label: label, trueLabel: label, installed: true,
                   appType: [name: "Rule-5.1", namespace: "hubitat"]]
        if (parentAppId != null) app.parentAppId = parentAppId
        JsonOutput.toJson([
            app: app,
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null, sections: [
                [title: "", input: inputs]
            ]],
            settings: settings,
            childApps: []
        ])
    }

    protected String statusJson(int ruleId, List appSettings = [], int subs = 1) {
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

    // Live app-id tree used by the rule-target existence specs below: a Rule Machine parent
    // (id 21) with one child rule (id 555). _collectLiveApps walks data.id at every depth,
    // so the reachable set is {21, 555}. The parent id 21 is deliberately present-but-not-a-rule
    // so the "non-rule app id is rejected" spec can target it: it exists in the app tree yet is
    // absent from the RMUtils rule list, which is what the existence guard resolves against.
    protected String appsListWithRule(int ruleId = 555) {
        appsListWithRules([ruleId])
    }

    // Same tree for a multi-rule set: the RM parent (id 21) with one child node per rule id.
    protected String appsListWithRules(List ruleIds) {
        JsonOutput.toJson([apps: [
            [data: [id: 21, name: "Rule Machine", type: "Rule Machine"],
             children: ruleIds.collect { [data: [id: it, name: "Target Rule", type: "Rule-5.1"], children: []] }]
        ]])
    }

    // Seed RMUtils so the rule id 555 is the ONLY existing RM rule; the app tree above adds the
    // non-rule parent id 21. The existence-safe set the guard resolves is therefore {555}.
    protected void installRuleTargetStubs() {
        rmUtils = new RMUtilsMock()
        rmUtils.stubRuleList5 = [[555: 'Target Rule']]
        rmUtils.install()
    }

    // Serves a committed rule and RM's action editor. The doAct click is recorded like every other
    // button; a settings POST updates committedSettings the way RM's updateSetting does, so the
    // readback sees it; ruleBuilderJson serves ctl.order. editorInputs is the doActPage schema RM
    // renders for the row. ctl.applyWrites=false models a write RM silently drops (ctl.dropKeys, one
    // key it drops); ctl.dupOnDone models an editor that re-registers the index on actionDone.
    protected Map wireModifyEditorTransport(int ruleId, List order, Map committedSettings, List editorInputs, Closure postHook = null) {
        Map ctl = [order: new ArrayList(order), clicks: [], writes: [], fetchSeq: 0, applyWrites: true, dropKeys: [], dupOnDone: false]
        hubGet.register("/installedapp/configure/json/${ruleId}".toString()) { params -> ruleConfigJson(ruleId, "r", [], null, committedSettings) }
        hubGet.register("/installedapp/configure/json/${ruleId}/selectActions".toString()) { params -> ruleConfigJson(ruleId, "r", [], null, committedSettings) }
        hubGet.register("/installedapp/configure/json/${ruleId}/mainPage".toString()) { params -> ruleConfigJson(ruleId, "r", [], null, committedSettings) }
        hubGet.register("/installedapp/configure/json/${ruleId}/doActPage".toString()) { params ->
            ctl.fetchSeq = (ctl.fetchSeq as int) + 1
            JsonOutput.toJson([
                app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: editorInputs, paragraphs: ["seq ${ctl.fetchSeq}".toString()]]]],
                settings: committedSettings, childApps: []
            ])
        }
        hubGet.register("/installedapp/statusJson/${ruleId}".toString()) { params ->
            JsonOutput.toJson([installedApp: [id: ruleId],
                               appSettings: committedSettings.collect { k, v -> [name: k.toString(), value: v] + (v instanceof List ? [deviceIdsForDeviceList: (v as List).collect { it?.toString() }] : [:]) },
                               eventSubscriptions: [[name: "evt1"]], scheduledJobs: [], appState: [], childAppCount: 0, childDeviceCount: 0])
        }
        hubGet.register("/app/ruleBuilderJson/${ruleId}".toString()) { params ->
            JsonOutput.toJson([broken: false, actionList: (ctl.order as List).collect { it.toString() }])
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (postHook != null) postHook.call(path, body)
            if (path == "/installedapp/btn") {
                (ctl.clicks as List) << [name: body?.name?.toString(), stateAttribute: body?.stateAttribute?.toString()]
                if (body?.name?.toString() == "actionDone" && ctl.dupOnDone) {
                    def edited = (ctl.clicks as List).find { it.stateAttribute == "doActN" }?.name?.toString()?.tokenize(".")?.get(0)
                    if (edited) ctl.order = (ctl.order as List) + edited.toInteger()
                }
            }
            if (path == "/installedapp/update/json") {
                body?.each { k, v ->
                    def key = _settingKeyOf(k)
                    if (key != null) {
                        (ctl.writes as List) << [key: key, value: v]
                        // A device picker stays a list, as the hub's statusJson serves it (deviceIdsForDeviceList).
                        boolean devicePicker = editorInputs.any { it.name?.toString() == key && it.type?.toString()?.startsWith("capability.") }
                        if (ctl.applyWrites && !(key in (ctl.dropKeys as List))) committedSettings[key] = (devicePicker && !(v instanceof List))
                            ? v.toString().tokenize(",").collect { it.trim() }.findAll { it } : v
                    }
                }
            }
            [status: 200, location: null, data: '']
        }
        return ctl
    }

    // RM's editor on an existing row: the row's own fields and actionDone (its "Cancel editing
    // Action" too); the type pickers and actionCancel appear only for a new row.
    protected List runRuleEditor(int idx) {
        [[name: "runRuleType.${idx}".toString(), type: "enum", options: ["Rule Machine": "Rule Machine"]],
         [name: "ruleAct.${idx}".toString(), type: "enum", multiple: true, options: ["200": "A", "300": "B"]],
         [name: "actionDone", type: "button"]]
    }

    protected String selectTriggersSchemaJson(int ruleId, int seqNum) {
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

    protected String mainPageJson(int ruleId, String label = "r", boolean triggerBaked = true) {
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

    protected String doActPageSchemaJson(int ruleId, int seqNum) {
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

    // doActPage schema JSON carrying an arbitrary input set, with an
    // incrementing paragraph so every _rmWriteSettingOnPage routes to
    // `applied` (renderShifted=true) -- lets a wire test assert the POSTs
    // without simulating each field's individual reveal.
    protected String doActPageWith(int ruleId, int seqNum, List inputs) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "doActPage", title: "T", install: false, error: null,
                         sections: [[title: "", input: inputs, paragraphs: ["seq ${seqNum}".toString()]]]],
            settings: [:], childApps: []
        ])
    }

    protected String bakedMainPage(int ruleId) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                         sections: [[title: "", input: [], paragraphs: ["action row baked"]]]],
            settings: [:], childApps: []
        ])
    }

    protected String runCommandDoActPageJson(int ruleId, int seqNum, Map runCapOptions = ["Switch": "Switch", "SwitchLevel": "Dimmer"], List cmds = ["setLevel", "on", "off"]) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "doActPage", title: "T", install: false, error: null,
                         sections: [[title: "", input: [
                             [name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]],
                             [name: "actSubType.1", type: "enum", options: ["getDefinedAction": "Run Custom Action"]],
                             [name: "myCapab.1", type: "enum", options: runCapOptions],
                             [name: "devices.1", type: "capability.switch", multiple: true],
                             [name: "cCmd.1", type: "enum", options: cmds.collectEntries { [(it): it] }],
                             [name: "moreParams", type: "button"],
                             [name: "actionDone", type: "button"],
                             [name: "actionCancel", type: "button"]
                         ], paragraphs: ["seq ${seqNum}".toString()]]]],
            settings: [:], childApps: []
        ])
    }

    protected String bakedMainPageJson(int ruleId, String paragraph) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                         sections: [[title: "", input: [], paragraphs: [paragraph]]]],
            settings: [:], childApps: []
        ])
    }

    protected List zwavePollEditor(int idx) {
        [[name: "ssZ.${idx}".toString(), type: "bool"], [name: "ssSD.${idx}".toString(), type: "bool"],
         [name: "ssZPoll.${idx}".toString(), type: "capability.switch", multiple: true], [name: "actionDone", type: "button"]]
    }

    protected List runCommandEditor(int idx) {
        [[name: "myCapab.${idx}".toString(), type: "enum", options: ["Switch": "Switch", "SwitchLevel": "Dimmer"]],
         [name: "devices.${idx}".toString(), type: "capability.switch", multiple: true],
         [name: "cCmd.${idx}".toString(), type: "enum", options: ["on": "on", "off": "off"]],
         [name: "actionDone", type: "button"]]
    }

    // selectTriggers serves `inputs`; writes are recorded (and land) without a hub round trip.
    protected Map wireTriggerWizard(List inputs) {
        def posts = []
        def writes = []
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params -> ruleConfigJson(100, "r", inputs) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"T2"}' }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        script.metaClass._rmWriteSettingOnPage = { Integer appId, String pageName, String key, Object value, List applied, String typeHint = null, List skipped = null, Map cache = null ->
            writes << [key: key, value: value]; applied << key
        }
        return [posts: posts, writes: writes]
    }

    protected static List temperatureTriggerInputs(boolean withReferencePicker) {
        def inputs = [
            [name: "tCapab1", type: "enum", options: ["Temperature"]],
            [name: "tDev1", type: "capability.temperatureMeasurement", multiple: true],
            [name: "ReltDev1", type: "enum", options: ["<", ">", "="]],
            [name: "istDev1", type: "bool"],
            [name: "tstate1", type: "decimal"],
            [name: "isCondTrig.1", type: "bool"],
            [name: "hasAll", type: "button"]
        ]
        if (withReferencePicker) inputs << [name: "reltDevice1", type: "capability.temperatureMeasurement"]
        return inputs
    }

    // An unbaked switch action (mainPage keeps "Define Actions"). rowSurvives: the row stays in the
    // settings after the editor's cancel, and its delete fails, so the rollback cannot be confirmed.
    protected List wireUnbakedSwitchAction(boolean rowSurvives) {
        def fetchSeq = 0
        def posts = []
        boolean cancelled = false
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            if (path == "/installedapp/btn" && body?.name == "actionCancel") cancelled = true
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["switchActs": "Switches"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> fetchSeq++; doActPageSchemaJson(100, fetchSeq) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPageJson(100, "Define Actions") }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, (rowSurvives && cancelled) ? [[name: "actType.1", value: "switchActs"], [name: "actSubType.1", value: "getOnOffSwitch"]] : [])
        }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        if (rowSurvives) script.metaClass._rmDeleteAction = { Integer appId, Integer idx, boolean skip = false -> throw new IllegalStateException("delAct refused") }
        return posts
    }

    protected Map wireRunRuleDoneFails() {
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        wireModifyEditorTransport(100, [1], ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]], runRuleEditor(1)) { String path, Map body ->
            if (path == "/installedapp/btn" && body?.name?.toString() == "actionDone") throw new IllegalStateException("btn 500")
        }
    }

}
