package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Unroll
import support.RmNativeCrudSpecBase

/**
 * Native Rule Machine CRUD behaviour derived from Rule Machine's source tables: the action catalog,
 * the trigger and condition tables, modifyAction, the walkStep checks and the outcome flags.
 * Fixtures and helpers come from RmNativeCrudSpecBase, shared with ToolRmNativeCrudSpec.
 */
class ToolRmNativeCrudSourceSpec extends RmNativeCrudSpecBase {

    private static final Map RUN_CAP_PICKER = ["Switch": "Switch", "SwitchLevel": "Dimmer", "SpeechSynthesis": "Speech device",
                                               "WindowShade": "Window Shade", "FanControl": "Fan Control"]

    def "_discoverParentAppId installs a built-in with no sysApp alias by its listed type id (Notifications)"() {
        given: "sysApp creates nothing; the app list offers Notifications as system app type 24"
        boolean created = false
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([
                apps: created ? [[data: [id: 91, name: "Notifications", type: "Notifications", user: false, installed: true, hidden: false], children: []]] : [],
                systemAppTypes: [[id: 24, name: "Notifications", allowNewInstances: true, deprecated: false]]
            ])
        }
        def rawCalls = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            rawCalls << path
            if (path == "/installedapp/create/24") created = true
            [status: 200, location: null, data: path.startsWith("/installedapp/sysApp/") ? "<html>stub</html>" : ""]
        }

        when:
        def id = script._discoverParentAppId("notifier")

        then:
        rawCalls.any { it.startsWith("/installedapp/sysApp/") }
        rawCalls.contains("/installedapp/create/24")
        id == 91
    }

    def "backupBeforeRuleEdits=false takes no pre-edit baseline, while a delete still snapshots"() {
        given:
        enableWrite()
        settingsMap.backupBeforeRuleEdits = false
        hubGet.register('/installedapp/configure/json/504') { params -> ruleConfigJson(504, "nobackup") }
        hubGet.register('/installedapp/statusJson/504') { params -> statusJson(504) }
        def uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << fn }

        when:
        def edit = script._rmBackupBeforeEdit(504, "pre-addAction")
        def deletion = script._rmBackupRuleSnapshot(504, "pre-forcedelete")

        then: "no edit baseline, one delete snapshot, and the hints say why there is nothing to restore"
        edit.skipped && !edit.backupKey
        deletion.backupKey
        uploads.size() == 1
        script._rmPreflightRestoreHint(null, null).contains("pre-edit backups are off")
        script._rmBackupHint(null).contains("no snapshot to restore")
        script._rmRollbackIfNotCommittedHint(null).contains("repair the rule in place")
        script._rmBackupHint([backupKey: "rm-rule_504_x"]).contains("backupKey='rm-rule_504_x'")
    }

    def "addRequiredExpression sub-page writes use pageBreadcrumbs=[] (not [\"mainPage\"]) on STPage"() {
        given:
        enableWrite()
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
            script.toolSetRule([
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

    def "addTrigger state-change token on a device-state/numeric capability '#cap' is steered to comparator"() {
        // The live tCapab picker accepts many device-state/numeric caps the curated discover schema
        // omits (Energy, Water, Voltage, Smoke, ...); those resolve to family=null. A POSITIVE
        // device-state/numeric family scope would leave them UNGUARDED, committing a broken trigger;
        // the deny-list guards every non-exempt family, including null. Reverting
        // _rmStateChangeGuardApplies to `family in ["device-state","numeric"]` turns these rows RED
        // (family null -> not guarded -> no exception; execution falls through to the unstubbed hub).
        when: "an unlisted-capability trigger passes a state-change token with no comparator"
        def spec = [capability: cap, deviceIds: [8]]
        spec[field] = "increased"
        script._rmAddTrigger(100, spec)

        then: "refused up front, steering to comparator"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(offending)
        ex.message.contains("is a change, not a state")
        ex.message.contains("comparator")
        ex.message.contains("RM is not touched")

        where:
        cap       | field   | offending
        "Energy meter" | "value" | "value:'increased'"
        "Water sensor" | "state" | "state:'increased'"
        "Illuminance"  | "value" | "value:'increased'"
    }

    def "modifyAction rejects an index that has no committed action before any hub write"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]], runRuleEditor(1))

        when:
        script._rmModifyAction(100, 3, [ruleIds: [200]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("modifyAction.index 3 not found")
        ex.message.contains("RM is not touched")
        (ma.clicks as List).isEmpty() && (ma.writes as List).isEmpty()
    }

    def "modifyAction refuses an expression-bearing action and steers to removeAction + addAction"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "condActs", "actSubType.1": "getIfThen"], runRuleEditor(1))

        when:
        script._rmModifyAction(100, 1, [expression: [conditions: []]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("cannot be edited in place")
        ex.message.contains("removeAction + addAction")
        ex.message.contains("RM is not touched")
        (ma.clicks as List).isEmpty()
    }

    def "modifyAction refuses a field the action does not have, naming the editable ones"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]], runRuleEditor(1))

        when:
        script._rmModifyAction(100, 1, [ruleIds: [200], state: "on"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'state' is not a field that can be edited in place")
        ex.message.contains("Editable: ruleIds")
        ex.message.contains("RM is not touched")
        (ma.clicks as List).isEmpty()
    }

    def "modifyAction refuses a nonexistent target rule before opening the editor"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]], runRuleEditor(1))
        installRuleTargetStubs()
        hubGet.register('/hub2/appsList') { params -> appsListWithRule(555) }

        when:
        script._rmModifyAction(100, 1, [ruleIds: [999]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("999")
        (ma.clicks as List).isEmpty()
    }

    def "modifyAction retargets a runRule action in place: same index, same order, readback verified, no updateRule"() {
        given: "a middle action of three is edited"
        def settings = ["actType.2": "rulesActs", "actSubType.2": "getRuleActions", "ruleAct.2": ["200"]]
        def ma = wireModifyEditorTransport(100, [1, 2, 3], settings, runRuleEditor(2))

        when:
        def result = script._rmModifyAction(100, 2, [ruleIds: [300]])

        then: "RM's row editor was opened on action 2 and closed with actionDone"
        (ma.clicks as List)[0] == [name: "2.0.false", stateAttribute: "doActN"]
        (ma.clicks as List).any { it.name == "actionDone" }
        !(ma.clicks as List).any { it.name == "updateRule" }

        and: "only the target was written, at the committed index"
        (ma.writes as List).find { it.key == "ruleAct.2" } != null
        !(ma.writes as List).any { it.key?.toString()?.startsWith("actType") }

        and: "the readback verified it and the order is untouched"
        result.success == true
        result.partial == false
        result.modifiedIndex == 2
        result.capability == "runRule"
        result.actionOrder == [1, 2, 3]
        !result.containsKey("error")
        !result.containsKey("readbackMismatches")
    }

    def "modifyAction flips a switch action's variant through its fixed setting and changes its devices"() {
        given:
        def settings = ["actType.1": "switchActs", "actSubType.1": "getOnOffSwitch", "onOffSwitch.1": ["8"], "onOff.1": "true"]
        def editor = [[name: "actType.1", type: "enum", options: ["switchActs": "Switches"]],
                      [name: "actSubType.1", type: "enum", options: ["getOnOffSwitch": "Turn switches on/off"]],
                      [name: "onOffSwitch.1", type: "capability.switch", multiple: true],
                      [name: "onOff.1", type: "bool"], [name: "actionDone", type: "button"]]
        def ma = wireModifyEditorTransport(100, [1], settings, editor)
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"S2"}' }

        when:
        def result = script._rmModifyAction(100, 1, [action: "off", deviceIds: [9]])

        then:
        (ma.writes as List).find { it.key == "onOff.1" }?.value?.toString() == "false"
        (ma.writes as List).find { it.key == "onOffSwitch.1" } != null
        result.success == true
        result.action == "off"
    }

    @Unroll
    def "modifyAction writes the inverted RM boolean for #cap (#mods -> #key=#stored)"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], settings, editor)

        when:
        def result = script._rmModifyAction(100, 1, mods)

        then:
        (ma.writes as List).find { it.key == key }?.value?.toString() == stored
        result.success == true

        where:
        cap              | settings                                                                                              | editor | mods            | key      | stored
        "privateBoolean" | ["actType.1": "rulesActs", "actSubType.1": "getSetPrivateBoolean", "privateT.1": ["*"], "pvTF.1": "true"] | [[name: "actSubType.1", type: "enum", options: ["getSetPrivateBoolean": "x"]], [name: "pvTF.1", type: "bool"], [name: "privateT.1", type: "enum", multiple: true, options: ["*": "this"]]] | [value: true]   | "pvTF.1" | "false"
        "pauseRule"      | ["actType.1": "rulesActs", "actSubType.1": "getPauseResumeRules", "pauseRule.1": ["200"], "pR.1": "false"] | [[name: "actSubType.1", type: "enum", options: ["getPauseResumeRules": "x"]], [name: "pR.1", type: "bool"], [name: "pauseRule.1", type: "enum", multiple: true, options: ["200": "A"]]] | [action: "resume"] | "pR.1"   | "true"
    }

    def "modifyAction reports a value RM dropped as unverified instead of success"() {
        given:
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]]
        def ma = wireModifyEditorTransport(100, [1], settings, runRuleEditor(1))
        ma.applyWrites = false

        when:
        def result = script._rmModifyAction(100, 1, [ruleIds: [300]])

        then:
        result.success == false
        result.partial == true
        result.readbackMismatches == ["ruleAct.1"]
        result.error.contains("did not read back as written")
    }

    def "modifyAction reports an action list that gained a duplicate index after the edit"() {
        given:
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]]
        def ma = wireModifyEditorTransport(100, [1, 2], settings, runRuleEditor(1))
        ma.dupOnDone = true

        when:
        def result = script._rmModifyAction(100, 1, [ruleIds: [300]])

        then:
        result.success == false
        result.duplicateIndices == [1]
        result.error.contains("more than once")
    }

    def "modifyAction clicks nothing when RM's editor did not open on the row, since that editor's cancel would delete its row"() {
        given: "doActPage renders no sub-type picker for the row"
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]]
        def ma = wireModifyEditorTransport(100, [1], settings, [[name: "actionDone", type: "button"]])

        when:
        script._rmModifyAction(100, 1, [ruleIds: [300]])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("did not open its editor on action 1")
        ex.message.contains("restore the pre-edit backup")
        (ma.clicks as List).size() == 1
        !(ma.clicks as List).any { it.name in ["actionCancel", "actionDone"] }
        (ma.writes as List).isEmpty()
    }

    def "modifyAction via toolSetRule: in-place success envelope with the outcome flags and no null keys"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"], "actType.2": "rulesActs"]
        def ma = wireModifyEditorTransport(100, [1, 2], settings, runRuleEditor(1))

        when:
        def result = script.toolSetRule([appId: 100, modifyAction: [index: 1, mods: [ruleIds: [300]]], confirm: true])

        then:
        result.success == true
        result.backup.reason == "pre-modifyAction"
        result.note.contains("edited in place")
        result.modifiedIndex == 1
        result.health != null
        result.applied == true
        result.activated == true
        result.verified == true
        !result.containsKey("error")
        !result.containsKey("restoreHint")
        !(ma.clicks as List).any { it.name == "updateRule" }
    }

    def "modifyAction via toolSetRule refuses before the backup and says so"() {
        given:
        enableWrite()
        def uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << fn }
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]]
        wireModifyEditorTransport(100, [1], settings, runRuleEditor(1))

        when:
        def result = script.toolSetRule([appId: 100, modifyAction: [index: 1, mods: [state: "on"]], confirm: true])

        then:
        result.success == false
        result.error.contains("'state' is not a field that can be edited in place")
        result.restoreHint.contains("not touched")
        result.backup == null
        uploads.isEmpty()
        result.applied == false
    }

    def "modifyAction via toolSetRule: a clean readback on a rule the health check reports broken is partial, not success"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]]
        def ma = wireModifyEditorTransport(100, [1], settings, runRuleEditor(1))
        hubGet.register('/app/ruleBuilderJson/100') { params ->
            JsonOutput.toJson([broken: true, actionList: (ma.order as List).collect { it.toString() }])
        }

        when:
        def result = script.toolSetRule([appId: 100, modifyAction: [index: 1, mods: [ruleIds: [300]]], confirm: true])

        then: "the readback verified, but the health verdict decides the envelope"
        !result.containsKey("readbackMismatches")
        result.health.ok == false
        result.success == false
        result.partial == true
        result.error.contains("the health check reports the rule broken")
        result.restoreHint != null
        result.applied == true
        result.verified == false
    }

    def "modifyAction clicks the edit button selectActions renders for a Simple Conditional Action row"() {
        given: "action 1 is bound to condition 2, and its row button carries that index"
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]]
        def ma = wireModifyEditorTransport(100, [1], settings, runRuleEditor(1))
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> selectActionsPage(100, ["1.2.false"]) }

        when:
        def result = script._rmModifyAction(100, 1, [ruleIds: [300]])

        then:
        (ma.clicks as List)[0] == [name: "1.2.false", stateAttribute: "doActN"]
        result.success == true
    }

    def "modifyAction takes a row's condition index from the committed state.actions when selectActions does not render the row"() {
        given:
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]]
        def ma = wireModifyEditorTransport(100, [1, 2], settings, runRuleEditor(1))
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> selectActionsPage(100, ["2.0.false"]) }
        committedStatus(settings, [[name: "actions", value: ["1": [cond: 3], "2": [:]]]])

        when:
        script._rmModifyAction(100, 1, [ruleIds: [300]])

        then:
        (ma.clicks as List)[0] == [name: "1.3.false", stateAttribute: "doActN"]
    }

    def "modifyAction via toolSetRule on a rule with an open action editor returns the refusal envelope before the backup"() {
        given:
        enableWrite()
        def uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << fn }
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]]
        def ma = wireModifyEditorTransport(100, [1], settings, runRuleEditor(1))
        committedStatus(settings, [[name: "editAct", value: 2]])

        when:
        def result = script.toolSetRule([appId: 100, modifyAction: [index: 1, mods: [ruleIds: [300]]], confirm: true])

        then: "a structured envelope, not a thrown tool error"
        result.success == false
        result.error.contains("state.editAct=2")
        result.restoreHint.contains("not touched")
        result.backup == null
        result.applied == false
        uploads.isEmpty()
        (ma.clicks as List).isEmpty()
    }

    def "modifyAction via toolSetRule reads the committed rule once before opening RM's editor"() {
        given:
        enableWrite()
        settingsMap.backupBeforeRuleEdits = false
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]]
        Integer statusReadsAtClick = null
        wireModifyEditorTransport(100, [1], settings, runRuleEditor(1)) { String path, Map body ->
            if (path == "/installedapp/btn" && body?.stateAttribute?.toString() == "doActN" && statusReadsAtClick == null) {
                statusReadsAtClick = hubGet.calls.count { it.path == "/installedapp/statusJson/100" } as Integer
            }
        }

        when:
        def result = script.toolSetRule([appId: 100, modifyAction: [index: 1, mods: [ruleIds: [300]]], confirm: true])

        then: "the pre-flight plan is the one the edit runs"
        result.success == true
        statusReadsAtClick == 1
    }

    def "trigger and condition discover come from RM's capability tables (#kind #cap)"() {
        when:
        def entry = script._rmCondDiscover(kind, cap).capability

        then:
        entry.name == canonical
        entry.family == family
        (entry.requiredFields as List)*.name == required

        where:
        kind        | cap                  | canonical             | family           | required
        "trigger"   | "switch"             | "Switch"              | "device-state"   | ["deviceIds", "state"]
        "trigger"   | "Temperature"        | "Temperature"         | "numeric"        | ["deviceIds", "comparator"]
        "condition" | "Between two times"  | "Between two times"   | "time"           | ["start", "end"]
        "trigger"   | "Button"             | "Button"              | "button"         | ["deviceIds", "state"]
        "condition" | "Variable"           | "Variable"            | "variable"       | ["variable"]
        "trigger"   | "Water sensor"       | "Water sensor"        | "device-state"   | ["deviceIds", "state"]
        "trigger"   | "Button LED"         | "Button LED"          | "button-led"     | ["deviceIds", "state"]
    }

    def "discover refuses a capability RM does not have for that kind"() {
        when:
        script._rmCondDiscover("condition", "Periodic Schedule")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("not a Rule Machine condition capability")
    }

    def "Button LED and Rule paused triggers follow RM's state pickers"() {
        when:
        def led = script._rmCondDiscover("trigger", "Button LED").capability
        def paused = script._rmCondDiscover("trigger", "Rule paused").capability

        then: "Button LED is an LED state on/off with a button number, no and-stays"
        (led.requiredFields as List).find { it.name == "state" }.values == ["on", "off"]
        ((led.optionalFields as List)*.name).containsAll(["buttonNumber", "comparator"])
        !((led.optionalFields as List)*.name).contains("andStays")

        and: "Rule paused has no *changed* option"
        !((paused.optionalFields as List)*.name).contains("comparator")
    }

    def "a *changed* trigger is refused where RM's state picker has no change option"() {
        when:
        script._rmCheckCondSpec([capability: "Rule paused", comparator: "*changed*"], "trigger", "addTrigger")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Rule Machine has no comparator for a Rule paused trigger;")

        when:
        script._rmCheckCondSpec([capability: "Button LED", deviceIds: [1], comparator: "*changed*"], "trigger", "addTrigger")

        then:
        noExceptionThrown()
    }

    def "a trigger with no and-stays duration in RM gets no andStays field"() {
        expect:
        !((script._rmCondDiscover("trigger", "Mode").capability.optionalFields as List)*.name).contains("andStays")
        ((script._rmCondDiscover("trigger", "Motion").capability.optionalFields as List)*.name).contains("andStays")
    }

    def "replaceActions reads a mis-cased endIF as Rule Machine's endIf, so the IF block is balanced"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: ''] }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, [[name: "actType.1", value: "switchActs"]]) }
        def specs = [
            [capability: "ifThen", expression: [conditions: [[capability: "Switch", deviceIds: [1], state: "on"]]]],
            [capability: "endIF"]
        ]

        when:
        def result = script.toolSetRule([appId: 100, replaceActions: specs, confirm: true])

        then:
        !(result.error?.toString()?.contains("structurally imbalanced"))
        script._rmCanonicalActionCapability([capability: "endIF"]) == "endIf"
    }

    def "addTrigger checks the state against RM's live state picker (#state)"() {
        given:
        enableWrite()
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
        def result = script.toolSetRule([appId: 100, addTrigger: [capability: "Switch", deviceIds: [8], state: state], confirm: true])

        then:
        result.success == accepted
        if (accepted) {
            assert posts.any { (it.body as Map)["settings[tstate1]"] == "on" }
        } else {
            assert result.error?.contains("addTrigger.state 'purple' is not a state Rule Machine offers for Switch: on, off")
            assert !posts.any { (it.body as Map).containsKey("settings[tstate1]") }
        }

        where:
        state    | accepted
        "ON"     | true
        "purple" | false
    }

    def "_rmReadPickerOptionLabels maps the hub's single-entry enum options to their labels"() {
        expect:
        script._rmReadPickerOptionLabels([options: [["Switch": "Switch"], ["SwitchLevel": "Dimmer"]]]) == ["Switch": "Switch", "SwitchLevel": "Dimmer"]
    }

    def "_rmUnrepresentedComparatorHint is the single canonical wording -- names the comparator, attribute, workaround + concrete example"() {
        // The trigger, action and Required Expression result paths all call this one helper.
        when:
        def hint = script._rmUnrepresentedComparatorHint([reason: "comparator_not_representable_for_enum_attribute", attribute: "switch", value: "*changed*"])

        then: "names the requested comparator and the offending attribute"
        hint.contains("'*changed*'")
        hint.contains("'switch'")

        and: "states the limitation and the device-capability workaround WITH a concrete example"
        hint.contains("has no slot")
        hint.contains("own capability")
        hint.contains("capability:'Switch'")

        and: "ASCII-only"
        hint.every { (it as char) < 128 }
    }

    def "_rmCanonicalActionCapability matches the catalog spelling regardless of case and writes it back"() {
        given:
        def spec = [capability: capIn]

        expect:
        script._rmCanonicalActionCapability(spec) == capOut
        spec.capability == capOut

        where:
        capIn        | capOut
        "Switch"     | "switch"
        "IFTHEN"     | "ifThen"
        "runCommand" | "runCommand"
        "teleport"   | "teleport"
    }

    def "a condition never takes a change comparator on a Custom Attribute or attribute comparison (#surface)"() {
        // Rule Machine's condition pickers drop the change comparators (getStrRelational with isCond,
        // getRelational with six), so the refusal comes before any write on every condition surface.
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> posts << path; [status: 200, location: null, data: ''] }
        def cond = [capability: "Custom Attribute", deviceIds: [8], attribute: "switch", comparator: "*changed*"]

        when:
        def result = script.toolSetRule([appId: 100, confirm: true] + args.call(cond))

        then:
        result.success == false
        result.backup == null
        result.error?.toString()?.contains("comparator '*changed*' is not one Rule Machine offers for a Custom Attribute condition")
        !posts.any { it in ["/installedapp/update/json", "/installedapp/btn"] }

        where:
        surface                 | args
        "Required Expression"   | { c -> [addRequiredExpression: [conditions: [c]]] }
        "ifThen action"         | { c -> [addAction: [capability: "ifThen", expression: [conditions: [c]]]] }
        "trigger condition"     | { c -> [addTrigger: [capability: "Switch", deviceIds: [8], state: "on", condition: c]] }
    }

    def "_rmUnrepresentedComparatorHint for a comparator RM does not offer names RM's own fields"() {
        when:
        def hint = script._rmUnrepresentedComparatorHint([reason: "comparator_not_offered", capability: "Motion", value: ">",
                                                          available: ["rDev_3", "state_3", "not3"]])

        then:
        hint.contains("'>' is not offered for a Motion condition")
        hint.contains("rDev_3, state_3, not3")
        hint.contains("not:true")
    }

    def "discover kind:'condition' Variable carries the variable and compareToVariable fields"() {
        when:
        def entry = script.toolSetRule([addTrigger: [discover: [kind: "condition", capability: "Variable"]]]).capability

        then:
        (entry.requiredFields as List)*.name == ["variable"]
        (entry.optionalFields as List)*.name.contains("compareToVariable")
    }

    def "addRequiredExpression Between two times with a comparator is refused (Rule Machine has none there)"() {
        // C-1 regression guard: Between two times has null trigger family, so the deny-list
        // change-comparator guard would otherwise catch a stray comparator:*changed* and
        // mis-report a valid condition surface as invalid. BTT takes no comparator; it is exempt
        // and flows to its start/end handler (the stray comparator is ignored). Both-ways:
        // removing the BTT exemption makes the guard fire, flipping success:false with the
        // not-valid-as-a-condition error and no starting1 write.
        given:
        enableWrite()
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
                    def key = _settingKeyOf(k)
                    if (key != null) {
                        def fieldName = key
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
        def result = script.toolSetRule([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Between two times",
                comparator: "*changed*",
                start: [type: "clock", time: "22:00"],
                end: [type: "sunrise", offset: 30]
            ]]],
            confirm: true
        ])

        then: "Rule Machine has no comparator for Between two times, so it is refused before any write"
        result.success == false
        result.backup == null
        result.error?.toString()?.contains("Rule Machine has no comparator for a Between two times condition")
    }

    def "hub_set_rule reference aligns Tamper with detected/clear vocabulary (discover)"() {
        // Tamper emits 'detected'/'clear'; 'tampered' / 'not detected' would fail the walker's option
        // check. The state vocabulary comes from discover; neither the inline description nor the
        // guide may surface the wrong forms. CO2 is numeric ppm, so it is not pinned here.
        when:
        def tools = script.getAllToolDefinitions()
        def updateNativeApp = tools.find { it.name == "hub_set_rule" }
        def schemaText = updateNativeApp?.inputSchema?.toString() ?: ""
        def fullText = (updateNativeApp?.description ?: "") + " " + schemaText
        def guide = (script.toolGetToolGuide("set_rule_reference")?.content ?: "").toString()

        then: "neither the inline description nor the guide surfaces the wrong vocabulary"
        !fullText.contains("'tampered'")
        !fullText.contains("'not detected'")
        !guide.contains("'tampered'")
        !guide.contains("'not detected'")

        and: "discover carries Rule Machine's default state for those capabilities"
        script._rmCondDiscover("condition", "Tamper alert").capability.requiredFields.find { it.name == "state" }.get("default") == "detected"
        script._rmCondDiscover("trigger", "Smoke detector").capability.requiredFields.find { it.name == "state" }.get("default") == "detected"
    }

    @spock.lang.Unroll
    def "addRequiredExpression #cap with comparator + NO state + NO value is refused by the condition check, naming the capability"() {
        // The shared condition check refuses a comparator on a device-state condition (RM offers
        // none; pass state) before any write; the refusal names the capability. CO2 is numeric and
        // keeps its comparator.
        given:
        enableWrite()
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
            def inputs = [
                [name: "cond",      type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",  type: "enum", options: [cap]],
                [name: "rDev_1",    type: "capability.sensor", multiple: true],
                [name: "RelrDev_1", type: "enum", options: [">"]],
                [name: "state_1",   type: "enum", options: validStates],
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
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"S1"}' }

        when: "caller passes comparator with no state, no value"
        def result = script.toolSetRule([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: cap, deviceIds: [8], comparator: ">"
                // Intentionally NO state, NO value -- the regression target.
            ]]],
            confirm: true
        ])

        then: "the condition check refuses, naming the capability"
        // RM offers no comparator for these device-state conditions, so the refusal names the capability.
        result.success == false
        result.error?.toString()?.contains(cap)
        result.error?.toString()?.contains("has no comparator for a")

        where:
        // Carbon dioxide sensor is absent: RM treats it as numeric (comparator, value).
        cap                            | validStates
        "Water sensor"                 | ["wet", "dry"]
        "Smoke detector"               | ["detected", "clear"]
        "Carbon monoxide detector"     | ["detected", "clear"]
        "Tamper alert"                 | ["detected", "clear"]
        "Acceleration"                 | ["active", "inactive"]
    }

    @spock.lang.Unroll
    def "addRequiredExpression #cap with state: '#validState' is not refused"() {
        // With a valid state the condition check does not refuse and the walker writes state_<N>;
        // the same capabilities as the refusal spec above.
        given:
        enableWrite()
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage") {
                body.each { k, v ->
                    def key = _settingKeyOf(k)
                    if (key != null) writtenFields[key] = v
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
                [name: "cond",      type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",  type: "enum", options: [cap]],
                [name: "rDev_1",    type: "capability.sensor", multiple: true],
                [name: "state_1",   type: "enum", options: validStates],
                [name: "hasAll",    type: "button"]
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
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"Sensor1"}' }

        when: "caller passes the valid state value (no comparator)"
        def result = script.toolSetRule([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: cap, deviceIds: [8], state: validState
            ]]],
            confirm: true
        ])

        then: "no refusal; the walker proceeded"
        !(result.error?.toString()?.contains("has no comparator for a"))

        where:
        cap                            | validState | validStates
        "Water sensor"                 | "wet"      | ["wet", "dry"]
        "Smoke detector"               | "detected" | ["detected", "clear"]
        "Carbon monoxide detector"     | "detected" | ["detected", "clear"]
        "Tamper alert"                 | "detected" | ["detected", "clear"]
        "Acceleration"                 | "active"   | ["active", "inactive"]
    }

    def "addRequiredExpression Carbon dioxide sensor with comparator + value: no refusal (numeric ppm path)"() {
        // Carbon dioxide sensor is numeric in Rule Machine's tables, so a comparator with a value is accepted.
        given:
        enableWrite()
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json" && body?.currentPage == "STPage" && body["_action_previous"] != "Done") {
                body.each { k, v ->
                    def key = _settingKeyOf(k)
                    if (key != null) writtenFields[key] = v
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
            // CO2 numeric-comparator path: capability + device + RelrDev_<N> (comparator)
            // + state_<N> (numeric value -- RM 5.1 uses the same field name for both
            // numeric value and discrete state).
            def inputs = [
                [name: "cond",      type: "enum", options: ["a": "New condition"]],
                [name: "rCapab_1",  type: "enum", options: ["Carbon dioxide sensor"]],
                [name: "rDev_1",    type: "capability.sensor", multiple: true],
                [name: "RelrDev_1", type: "enum", options: [">", "<", "=", ">=", "<=", "!="]],
                [name: "state_1",   type: "text"],
                [name: "hasAll",    type: "button"]
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
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"CO2-Sensor"}' }

        when: "caller passes comparator + numeric value (the valid CO2 shape)"
        def result = script.toolSetRule([
            appId: 100,
            addRequiredExpression: [conditions: [[
                capability: "Carbon dioxide sensor", deviceIds: [8], comparator: ">", value: 800
            ]]],
            confirm: true
        ])

        then: "no device-state refusal on the numeric CO2 path"
        // The numeric CO2 path must not get the device-state refusal.
        !(result.error?.toString()?.contains("has no comparator for a"))

        and: "call did not hard-fail (positive pin: paired with the device-state refusal negation above)"
        // Load-bearing positive discriminator (W10): without this, a regression
        // that returns success=false for an unrelated reason would silently
        // satisfy the negation above. result.success != false catches both
        // explicit `success: false` and the case where the dispatcher errored
        // through `_rmBuildUpdateErrorResponse` (which never sets `success`).
        result.success != false

        and: "rDev_1 wire-write captured the CO2 device (positive contract pin: W10)"
        // The walk must reach the rDev_1 write (bare CSV "8") for the numeric path.
        writtenFields["rDev_1"] == "8"
    }

    def "addAction ifThen with a device-state capability + comparator + NO state is refused (cross-page invariant)"() {
        // The device-state refusal runs in the shared condition check, on doActPage (ifThen) as on STPage.
        given:
        enableWrite()
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
                                  options: ["Water sensor"]],
                                 [name: "rDev_1", type: "capability.sensor", multiple: true],
                                 [name: "RelrDev_1", type: "enum", options: [">"]],
                                 [name: "state_1", type: "enum", options: ["wet", "dry"]],
                                 [name: "hasAll", type: "button"],
                                 [name: "cancelCapab", type: "button"]
                             ], paragraphs: ["seq"]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8')  { params -> '{"id":"8","name":"Leak1"}' }

        when: "caller passes addAction(ifThen) with a device-state capability + comparator + no state"
        def result = script.toolSetRule([
            appId: 100,
            addAction: [
                capability: "ifThen",
                expression: [conditions: [[
                    capability: "Water sensor", deviceIds: [8], comparator: ">"
                    // Intentionally NO state, NO value -- the regression target.
                ]]]
            ],
            confirm: true
        ])

        then: "the condition check refuses on doActPage too, naming the capability"
        // Load-bearing discriminator: the device-state refusal must fire on doActPage as on
        // STPage; a check narrowed to STPage would let this through with no refusal text.
        result.success == false
        result.error?.toString()?.contains("Water sensor")
        result.error?.toString()?.contains("has no comparator for a")
    }

    def "patches modifyAction edits in place and the batch finalises once"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def settings = ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"], "actType.2": "rulesActs"]
        def ma = wireModifyEditorTransport(100, [1, 2], settings, runRuleEditor(1))

        when:
        def result = script.toolSetRule([appId: 100, patches: [[modifyAction: [index: 1, mods: [ruleIds: [300]]]]], confirm: true])

        then:
        result.patches.size() == 1
        result.patches[0].op == "modifyAction"
        result.patches[0].success == true
        result.patches[0].modifiedIndex == 1
        ma.order == [1, 2]
        result.success == true

        and: "the batch fires the one updateRule"
        (ma.clicks as List).count { it.name == "updateRule" } == 1
    }

    def "a bulk stop reads wizardStuck from the item's flag, not its error text"() {
        when:
        def out = script._rmBulkStoppedResult(100, [backupKey: "k"], "addActions[0]",
            [success: false, error: "x [wizardStuck -- text only]"], [health: [ok: true]])

        then:
        !out.containsKey("wizardStuck")
    }

    def "_appTypeRegistry registers the scene and thermostat child types the e2e fixtures bootstrap, each committing with its page's Update button, and the enum advertises every registry key"() {
        when:
        def reg = script._appTypeRegistry()
        def def_ = script.getAllToolDefinitions().find { it.name == 'hub_set_native_app' }

        then:
        reg.scene == [namespace: "hubitat", appName: "Scene-1.2", parentTypeName: "Groups and Scenes", labelInput: "name", commitButton: "update"]
        reg.thermostat_scheduler == [namespace: "hubitat", appName: "Thermostat Scheduler 2.0", parentTypeName: "Thermostat Schedulers", commitButton: "update"]
        reg.thermostat_controller == [namespace: "hubitat", appName: "Thermostat Controller 2.0", parentTypeName: "Thermostat Controllers", commitButton: "update"]
        ["Scene-1.2", "Thermostat Scheduler 2.0", "Thermostat Controller 2.0"].every { script._resolveCommitButton(it) == "update" }

        and: "the served enum is the registry minus the types the classic create path rejects"
        (def_.inputSchema.properties.appType.enum as Set) == (reg.keySet() - ["visual_rule", "button_rule"]) as Set
    }

    def "addAction switch #action with onlyIfChanged writes optSwitch AFTER the device picker"() {
        // Both on and off honor onlyOn -- the optSwitch reveal/write path is identical for the
        // two directions (the `on` branch is a separate case from the tested `off` one).
        given:
        enableWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]; [status: 200, location: null, data: '']
        }
        def seq = 0
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["switchActs": "Switches"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            seq++; doActPageWith(100, seq, [[name: "actType.1", type: "enum"], [name: "actSubType.1", type: "enum"],
                [name: "onOffSwitch.1", type: "capability.switch", multiple: true], [name: "onOff.1", type: "bool"],
                [name: "optSwitch.1", type: "bool"], [name: "actionDone", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPage(100) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        script.toolSetRule([appId: 100, addAction: [capability: "switch", action: action, deviceIds: [8], onlyIfChanged: true], confirm: true])

        then: "optSwitch.1 is written, and only after onOffSwitch.1 (its reveal gate)"
        def devIdx = posts.findIndexOf { it.body.containsKey("settings[onOffSwitch.1]") }
        def optIdx = posts.findIndexOf { it.body.containsKey("settings[optSwitch.1]") }
        devIdx >= 0
        optIdx >= 0
        optIdx > devIdx

        where:
        action << ["off", "on"]
    }

    def "addAction switch toggle with onlyIfChanged:true writes no optSwitch (non-on/off negative pin)"() {
        // onlyOn is meaningful only for the on/off subtypes -- toggle uses getToggleSwitch,
        // which has no optSwitch field. The write lives INSIDE the on/off case branches; a
        // refactor hoisting `if (onlyOn == true) fields[optSwitch.@N]=true` into shared code
        // would silently write an invalid field on toggle. The doActPage schema below DOES
        // expose optSwitch.1, so a wrongly-written value would land and be captured here --
        // the pin goes RED if optSwitch is ever written on toggle.
        given:
        enableWrite()
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]; [status: 200, location: null, data: '']
        }
        def seq = 0
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["switchActs": "Switches"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            seq++; doActPageWith(100, seq, [[name: "actType.1", type: "enum"], [name: "actSubType.1", type: "enum"],
                [name: "toggleSwitch.1", type: "capability.switch", multiple: true],
                [name: "optSwitch.1", type: "bool"], [name: "actionDone", type: "button"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPage(100) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        script.toolSetRule([appId: 100, addAction: [capability: "switch", action: "toggle", deviceIds: [8], onlyIfChanged: true], confirm: true])

        then: "toggleSwitch.1 lands but optSwitch.1 is never written"
        posts.any { it.body.containsKey("settings[toggleSwitch.1]") }
        !posts.any { it.body.containsKey("settings[optSwitch.1]") }
    }

    def "Custom Attribute discover lists *contains* and a condition carries the not negation"() {
        when:
        def entry = script._rmCondDiscover("condition", "Custom Attribute").capability

        then:
        def cmp = (entry.optionalFields as List).find { (it as Map).name == "comparator" }
        ((cmp as Map).values as List).contains("*contains*")
        !((cmp as Map).values as List).contains("*changed*")
        (entry.optionalFields as List)*.name.contains("not")
    }

    def "Variable discover lists Rule Machine's String-variable comparators"() {
        when:
        def entry = script._rmCondDiscover("trigger", "Variable").capability

        then: "a String variable's picker offers = ≠ *contains* *changed* *is empty*"
        def cmp = (entry.optionalFields as List).find { (it as Map).name == "comparator" }
        ((cmp as Map).values as List).containsAll(["*contains*", "*is empty*", "*changed*"])
    }

    def "addAction switch discover lists onlyIfChanged in optionalFields with the sub-type and an example"() {
        when:
        def result = script.toolSetRule([addAction: [discover: true]])

        then:
        def caps = result?.capabilities as List
        def sw = caps.find { (it as Map)?.name == "switch" }
        sw != null
        ((sw.optionalFields as List)*.name).contains("onlyIfChanged")
        sw.actSubType.on == "getOnOffSwitch"
        sw.actType == "switchActs"
        sw.example.capability == "switch"
        sw.example.action == "on"
        sw.example.deviceIds instanceof List
    }

    def "walkStep on a non-RM wizard reads health from the page it is on, never the app's default page"() {
        given: "an app whose default page clears its pending wizard setting when rendered (HPM's prefOptions)"
        enableWrite()
        def stored = [:]
        def defaultRenders = 0
        hubGet.register('/installedapp/configure/json/100') { params ->
            defaultRenders++
            stored.remove("pkgInstall")
            ruleConfigJson(100, "pkg", [])
        }
        hubGet.register('/installedapp/configure/json/100/prefPkgInstallUrl') { params ->
            JsonOutput.toJson([app: [id: 100, name: "pkg", label: "pkg", trueLabel: "pkg", installed: true, appType: [name: "Package Manager", namespace: "x"]],
                               configPage: [name: "prefPkgInstallUrl", title: "", install: false, error: null,
                                            sections: [[title: "", input: [[name: "pkgInstall", type: "text", required: true]]]]],
                               settings: stored.clone(), childApps: []])
        }
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJson(100, stored.collect { k, v -> [name: k, type: "text", value: v] })
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            def key = body.keySet().collect { _settingKeyOf(it) }.find { it != null }
            if (key != null) stored[key] = body["settings[${key}]".toString()]
            [status: 200, location: null, data: '']
        }

        when:
        def rendersBefore = defaultRenders
        def result = script.toolSetNativeApp([appId: 100, confirm: true,
            walkStep: [page: "prefPkgInstallUrl", operation: "write", write: [pkgInstall: "https://example.test/packageManifest.json"]]])

        then: "the write echoes, and the trailing health probe did not render the default page"
        result.valueEcho.match == true
        defaultRenders == rendersBefore
        stored.pkgInstall == "https://example.test/packageManifest.json"

        and: "the app's kind came from the page the backup read; the compiled state is read once, by the health probe"
        hubGet.calls.count { it.path == '/app/ruleBuilderJson/100' } == 1
    }

    def "walkStep on an RM rule through hub_set_native_app snapshots the rule's main page"() {
        given: "the wizard page's envelope names the app type, as every page of an app does"
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["switchActs"]]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/app/ruleBuilderJson/100') { params -> JsonOutput.toJson([broken: false, actionList: []]) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def seen = script.toolSetNativeApp([appId: 100, confirm: true, walkStep: [page: "selectActions", operation: "introspect"]])

        then: "the snapshot read the rule's main page after the wizard page named the app's type, with no compiled-state read before it"
        seen.success == true
        def paths = hubGet.calls*.path
        paths.indexOf('/installedapp/configure/json/100/selectActions') < paths.indexOf('/installedapp/configure/json/100')
        paths.indexOf('/app/ruleBuilderJson/100') > paths.indexOf('/installedapp/configure/json/100')
    }

    def "walkStep shows a wizard page's nextPage and navigating to it presses the page's Next"() {
        given:
        enableWrite()
        def wizardPage = { String name, String next ->
            JsonOutput.toJson([app: [id: 100, name: "pkg", label: "pkg", trueLabel: "pkg", installed: true, version: 7, appType: [name: "Package Manager", namespace: "x"]],
                               configPage: [name: name, title: "T", install: false, error: null, nextPage: next,
                                            sections: [[title: "", input: [[name: "pkgInstall", type: "text"]]]]],
                               settings: [pkgInstall: "https://example.test/m.json"], childApps: []])
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "pkg", []) }
        hubGet.register('/installedapp/configure/json/100/prefPkgInstallUrl') { params -> wizardPage("prefPkgInstallUrl", "prefInstallChoices") }
        hubGet.register('/installedapp/configure/json/100/prefInstallChoices') { params -> wizardPage("prefInstallChoices", null) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: wizardPage("prefInstallChoices", null)]
        }

        when:
        def seen = script.toolSetNativeApp([appId: 100, confirm: true, walkStep: [page: "prefPkgInstallUrl", operation: "introspect"]])
        def moved = script.toolSetNativeApp([appId: 100, confirm: true,
            walkStep: [page: "prefPkgInstallUrl", operation: "navigate", navigate: [targetPage: "prefInstallChoices"]]])

        then:
        seen.before.nextPage == "prefInstallChoices"
        def nav = posts.find { it.path == "/installedapp/update/json" && it.body.containsKey("_action_next") }
        nav != null
        nav.body.nextPage == "prefInstallChoices"
        !nav.body.keySet().any { it.toString().startsWith("_action_href_") }
        moved.opResult.navigated.to == "prefInstallChoices"
    }

    @Unroll
    def "runCommand capabilityFilter '#raw' resolves against RM's picker to the key RM stores (#expected)"() {
        expect:
        script._rmResolveRunCommandCapability(raw, RUN_CAP_PICKER) == expected

        where:
        raw             | expected
        null            | "Switch"
        ""              | "Switch"
        "Switch"        | "Switch"
        "switch"        | "Switch"
        "SwitchLevel"   | "SwitchLevel"
        "switch level"  | "SwitchLevel"
        "Dimmer"        | "SwitchLevel"
        "dimmer"        | "SwitchLevel"
        "Speech device" | "SpeechSynthesis"
        "Window Shade"  | "WindowShade"
        "Fan Control"   | "FanControl"
    }

    @Unroll
    def "runCommand capabilityFilter '#raw' is refused with the list RM's picker shows"() {
        when:
        script._rmResolveRunCommandCapability(raw, RUN_CAP_PICKER)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("capabilityFilter '${raw}' is not a capability Rule Machine's Run Custom Action offers")
        e.message.contains("RM offers: FanControl, SpeechSynthesis, Switch, SwitchLevel, WindowShade")
        !e.message.contains("rolled back")

        where:
        raw << ["Initialize", "ColorControl", "AudioVolume", "Color Temperature"]
    }

    @Unroll
    def "picker option labels are read from a #shape enum"() {
        expect:
        script._rmReadPickerOptionLabels([options: options]) == expected

        where:
        shape          | options                                                        | expected
        "Map"          | ["Switch": "Switch", "SwitchLevel": "Dimmer"]                  | ["Switch": "Switch", "SwitchLevel": "Dimmer"]
        "value/text"   | [[value: "Switch", text: "Switch"], [value: "Lock", text: "Lock"]] | ["Switch": "Switch", "Lock": "Lock"]
        "bare string"  | ["on", "off"]                                                  | ["on": "on", "off": "off"]
        "empty"        | null                                                           | [:]
    }

    def "addAction runCommand with a capabilityFilter RM's picker does not offer is refused and the row rolled back"() {
        given:
        enableWrite()
        def fetchSeq = 0
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> fetchSeq++; runCommandDoActPageJson(100, fetchSeq) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPageJson(100, "Define Actions") }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolSetRule([
            appId: 100,
            addAction: [capability: "runCommand", command: "initialize", capabilityFilter: "Initialize", deviceIds: [42]],
            confirm: true
        ])

        then:
        result.success == false
        result.error?.contains("capabilityFilter 'Initialize' is not a capability Rule Machine's Run Custom Action offers")
        result.error?.contains("RM offers: Switch, SwitchLevel")

        and: "the unknown value was never stored and the editor was cancelled"
        !posts.any { it.path == "/installedapp/update/json" && it.body?.containsKey("settings[myCapab.1]") }
        !posts.any { it.path == "/installedapp/update/json" && it.body?.containsKey("settings[devices.1]") }
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "actionCancel" }
    }

    def "addAction runCommand writes the capability key RM stores for a picker label and the command RM lists"() {
        given:
        enableWrite()
        def fetchSeq = 0
        def writtenFields = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v -> def key = _settingKeyOf(k); if (key != null) writtenFields[key] = v }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> fetchSeq++; runCommandDoActPageJson(100, fetchSeq) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPageJson(100, "setLevel() on Device1") }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolSetRule([
            appId: 100,
            addAction: [capability: "runCommand", command: "SETLEVEL", capabilityFilter: "Dimmer", deviceIds: [42]],
            confirm: true
        ])

        then:
        result.success == true
        result.rolledBack == false
        result.note?.contains("added and baked")
        writtenFields["myCapab.1"] == "SwitchLevel"
        writtenFields["devices.1"] == "42"

        and: "the command is written in RM's own casing"
        writtenFields["cCmd.1"] == "setLevel"
    }

    def "addAction runCommand refuses a command RM does not list for the devices and rolls the opened row back"() {
        given:
        enableWrite()
        def fetchSeq = 0
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["modeActs": "Run Custom Action"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> fetchSeq++; runCommandDoActPageJson(100, fetchSeq) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPageJson(100, "Define Actions") }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/42') { params -> '{"id":"42","name":"Device1"}' }

        when:
        def result = script.toolSetRule([
            appId: 100,
            addAction: [capability: "runCommand", command: "fakeCmd", deviceIds: [42]],
            confirm: true
        ])

        then:
        result.success == false
        result.error?.contains("'fakeCmd' is not a command of the selected device(s)")
        result.error?.contains("RM offers: off, on, setLevel")
        result.error?.contains("rolled back")

        and: "the command was never written and the editor was cancelled"
        !posts.any { it.path == "/installedapp/update/json" && it.body?.containsKey("settings[cCmd.1]") }
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "actionCancel" }
    }

    def "addAction switch onlyIfChanged writes optSwitch after the device picker"() {
        given:
        enableWrite()
        def fetchSeq = 0
        def writes = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/update/json") {
                body?.each { k, v -> def key = _settingKeyOf(k); if (key != null) writes << [key: key, value: v] }
            }
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["switchActs": "Switches"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetchSeq++
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", title: "T", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "actType.1", type: "enum", options: ["switchActs": "Switches"]],
                                 [name: "actSubType.1", type: "enum", options: ["getOnOffSwitch": "Turn switches on/off"]],
                                 [name: "onOffSwitch.1", type: "capability.switch", multiple: true],
                                 [name: "onOff.1", type: "bool"],
                                 [name: "optSwitch.1", type: "bool"],
                                 [name: "actionDone", type: "button"]
                             ], paragraphs: ["seq ${fetchSeq}".toString()]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPageJson(100, "On: S1 (Command only switches that are off)") }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolSetRule([
            appId: 100,
            addAction: [capability: "switch", action: "on", deviceIds: [8], onlyIfChanged: true],
            confirm: true
        ])

        then:
        result.success == true
        writes.find { it.key == "optSwitch.1" }?.value?.toString() == "true"
        writes.findIndexOf { it.key == "optSwitch.1" } > writes.findIndexOf { it.key == "onOffSwitch.1" }
    }

    def "addAction switch refuses the old onlyOn name before any hub write"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolSetRule([
            appId: 100,
            addAction: [capability: "switch", action: "off", deviceIds: [8], onlyOn: true],
            confirm: true
        ])

        then:
        result.success == false
        result.error?.contains("'onlyOn' is now 'onlyIfChanged'")
        posts.isEmpty()
    }

    def "addAction that RM does not bake is rolled back and reported as not added"() {
        given:
        enableWrite()
        def fetchSeq = 0
        def posts = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["switchActs": "Switches"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> fetchSeq++; doActPageSchemaJson(100, fetchSeq) }
        // mainPage still shows the placeholder: RM did not bake the row.
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPageJson(100, "Define Actions") }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when:
        def result = script.toolSetRule([
            appId: 100,
            addAction: [capability: "switch", action: "on", deviceIds: [8]],
            confirm: true
        ])

        then: "the add is reported as failed, not as committed"
        result.success == false
        result.rolledBack == true
        result.note?.contains("Action not added")
        !result.note?.contains("committed")
        result.repairHints?.any { it.toString().contains("Action 1 was not added") && it.toString().contains("The row was removed") }

        and: "the editor was cancelled"
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "actionCancel" }
    }

    def "discover narrows to one capability"() {
        when:
        def one = script._rmAddAction(100, [discover: [capability: "runCommand"]])
        def onAction = script._rmAddAction(100, [discover: [capability: "Switch", action: "on"]])
        def all = script._rmAddAction(100, [discover: true])

        then:
        one.capability.name == "runCommand"
        one.discriminator == "capability"
        !one.containsKey("capabilities")

        and: "a selector with an action echoes it"
        onAction.action == "on"
        onAction.capability.name == "switch"

        and: "no selector returns the full schema"
        (all.capabilities as List).any { (it as Map).name == "switch" }
    }

    @Unroll
    def "discover refuses an unknown #what"() {
        when:
        script._rmAddAction(100, [discover: selector])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(needle)

        where:
        what         | selector                                  | needle
        "capability" | [capability: "teleport"]                  | "'teleport' is not an addAction capability"
        "action"     | [capability: "switch", action: "blink"]   | "'blink' is not an action of 'switch'"
    }

    def "_rmAddRefusalResult carries the flags the throw recorded, never the message's wording"() {
        expect: "rolledBack only from the flag, and not when the row is still open"
        script._rmAddRefusalResult(script._rmFail(new IllegalArgumentException("x."), [rolledBack: true])).rolledBack == true
        !script._rmAddRefusalResult(script._rmFail(new IllegalStateException("x."), [rolledBack: true, wizardStuck: true])).containsKey("rolledBack")
        script._rmAddRefusalResult(script._rmFail(new IllegalStateException("x."), [wizardStuck: true, fieldsWritten: true])).subMap(["wizardStuck", "fieldsWritten"]) == [wizardStuck: true, fieldsWritten: true]
        !script._rmAddRefusalResult(new IllegalArgumentException("x. The opened action row was rolled back. [wizardStuck -- row still there]")).containsKey("rolledBack")
        !script._rmAddRefusalResult(new IllegalArgumentException("x. RM is not touched.")).containsKey("rolledBack")

        and: "a rewrap keeps the inner flags, and the envelope reads them off the cause"
        script._rmOutcomeFlags(script._rmFail(new IllegalStateException("outer", script._rmFail(new IllegalStateException("inner"), [fieldsWritten: true])), script._rmOutcomeFlags(script._rmFail(new IllegalStateException("inner2"), [fieldsWritten: true])) + [wizardStuck: true])) == [fieldsWritten: true, wizardStuck: true]
        script._rmBuildUpdateErrorResponse(100, "plain text", [backupKey: "k"], "doActPage", script._rmFail(new IllegalStateException("plain text"), [wizardStuck: true, fieldsWritten: true])).subMap(["wizardStuck", "fieldsWritten"]) == [wizardStuck: true, fieldsWritten: true]
        script._rmBuildUpdateErrorResponse(100, "x [wizardStuck -- text only]", [backupKey: "k"]).wizardStuck == false
    }

    @spock.lang.Unroll
    def "_rmStampOutcome: #label"() {
        when:
        def r = script._rmStampOutcome(new LinkedHashMap(envelope))

        then:
        r.applied == applied
        r.activated == activated
        r.verified == verified

        where:
        label                                              | envelope                                        | applied | activated | verified
        "a clean write"                                    | [success: true]                                 | true    | true      | true
        "a rollback is not applied"                        | [success: false, rolledBack: true]              | false   | false     | false
        "an editor left open leaves applied unknown"       | [success: false, wizardStuck: true]             | null    | false     | false
        "fields written before a failure: unknown"         | [success: false, fieldsWritten: true]           | null    | false     | false
        "a partial row with fields written: unknown"       | [success: false, partial: true, fieldsWritten: true] | null | false | false
        "a partial row with an open editor: unknown"       | [success: false, partial: true, wizardStuck: true]   | null | false | false
        "success outranks fields written"                  | [success: true, partial: true, fieldsWritten: true]  | true | true  | true
        "a partial row otherwise is applied"               | [success: false, partial: true]                      | true | true  | false
        "a rejected update is applied, not activated"      | [success: true, updateRuleFailed: true]         | true    | false     | false
        "subscriptions not live: not activated"            | [success: true, subscriptionsNotLive: true]     | true    | false     | false
        "a failed readback is activated, not verified"     | [success: true, verificationFetchFailed: true]  | true    | true      | false
        "a broken health is not verified"                  | [success: true, health: [ok: false]]            | true    | true      | false
        "a bulk time-budget pause is applied, not activated"    | [success: true, partial: false, status: "in_progress", actionsCommitted: 1, addActionsRemaining: [[capability: "log"]]] | true | false | false
        "a patches time-budget pause is applied, not activated" | [success: true, partial: false, status: "in_progress", patchesRemaining: [[addAction: [capability: "log"]]]]            | true | false | false
    }

    def "the catalog refuses a value outside a field's enum before any write"() {
        when:
        script._rmMapActionSpec(100, [deviceIds: [12], speed: "turbo"], "fan", "setSpeed")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'speed' must be one of low, medium-low")
    }

    def "a device action without deviceIds is refused by the catalog mapper"() {
        when:
        script._rmMapActionSpec(100, [:], "switch", "on")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("switch.on requires 'deviceIds'")
    }

    def "a device without the capability Rule Machine's picker lists for the action is refused"() {
        given:
        def listReads = 0
        def fullJsonReads = [:]
        hubGet.register('/hub2/vrb/devices') { params ->
            listReads++
            JsonOutput.toJson([[id: 26, label: "Temp", capabilities: ["TemperatureMeasurement"]], [id: 8, label: "Sw", capabilities: ["Switch", "Refresh"]]])
        }
        [8, 9].each { id ->
            hubGet.register("/device/fullJson/${id}".toString()) { params -> fullJsonReads.put(id, (fullJsonReads.get(id) ?: 0) + 1); JsonOutput.toJson([id: id]) }
        }

        when:
        script._rmMapActionSpec(100, [deviceIds: [26]], "switch", "on")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("device 26 (Temp) does not have the Switch capability")

        when:
        def mapped = script._rmMapActionSpec(100, [deviceIds: [8]], "switch", "on")

        then: "the device list was read once for the execution"
        mapped.actSubType == "getOnOffSwitch"
        listReads == 1

        when: "the existence check runs for a listed device, and twice for an unlisted one"
        script._rmValidateDeviceIdsExist("addAction.deviceIds", [8])
        script._rmValidateDeviceIdsExist("addAction.deviceIds", [9])
        script._rmValidateDeviceIdsExist("addTrigger.deviceIds", [9])

        then: "the list answers for the listed device; the unlisted one is read once"
        fullJsonReads.get(8) == null
        fullJsonReads.get(9) == 1
    }

    def "an unreadable device list skips the capability check instead of refusing"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> throw new IllegalStateException("down") }

        expect:
        script._rmMapActionSpec(100, [deviceIds: [26]], "switch", "on").actSubType == "getOnOffSwitch"
    }

    def "zwavePoll is described by the catalog: start/stop and the target are RM's two booleans"() {
        expect:
        script._rmMapActionSpec(100, [target: "dimmers"], "zwavePoll", "stop").fields == ["ssSD.@N": false, "ssZ.@N": false]
        script._rmMapActionSpec(100, [:], "zwavePoll", "start").fields == ["ssSD.@N": true, "ssZ.@N": true]
        script._rmCatalogEntryForSubType("getStartStopZPoll", { String k -> [ssZ: "false"][k] }).action == "stop"
        script._rmCatalogEntryForSubType("getStartStopZPoll", { String k -> [ssZ: "true"][k] }).action == "start"
    }

    def "every catalog-described action maps a canonical example to the sub-type the catalog names"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> "[]" }
        Map catalog = script._rmActionCatalog()

        expect:
        catalog.every { String cap, Map entry ->
            Map variants = (entry.actions instanceof Map) ? (entry.actions as Map) : [(null): entry]
            variants.every { act, Map ae ->
                if (entry.custom == true || ae.custom == true) return true
                def slots = ((entry.slots ?: []) as List) + (ae.is(entry) ? [] : ((ae.slots ?: []) as List))
                def example = [:]
                def exampleFor = { Map f -> f.name == "deviceIds" ? [1] : (f.values instanceof List ? (f.values as List)[0]
                        : (f.type == "Integer" ? 1 : (f.type == "Boolean" ? true : "x"))) }
                slots.findAll { Map f -> f.name && f.required }.each { Map f -> example[f.name] = exampleFor(f) }
                // A one-of requirement (entry or action level) is met with its first field.
                (((entry.requires ?: []) as List) + ((ae.is(entry) ? [] : (ae.requires ?: [])) as List)).each { r ->
                    def n = (r instanceof List) ? (r as List)[0] : r
                    def f = slots.find { Map s -> s.name == n }
                    if (f && !example.containsKey(n)) example[n] = exampleFor(f)
                }
                def mapped = script._rmMapActionSpec(100, example, cap, act?.toString())
                mapped.actType == entry.actType && mapped.actSubType == (ae.sub ?: entry.sub) &&
                    slots.findAll { Map f -> f.required && f.key }.every { Map f -> mapped.fields.containsKey(f.key) }
            }
        }
    }

    def "the action catalog keeps its shape: slot flags, distinct variants and machine-readable requires"() {
        given:
        Map catalog = script._rmActionCatalog()

        expect:
        catalog.every { String cap, Map entry ->
            Map variants = (entry.actions instanceof Map) ? (entry.actions as Map) : [(null): entry]
            def slots = ((entry.slots ?: []) as List) + variants.values().collectMany { Map ae -> ae.is(entry) ? [] : ((ae.slots ?: []) as List) }
            boolean slotsOk = slots.every { Map f ->
                (f.containsKey("fixed") ? (f.key && !f.name) : (f.name != null)) &&
                (!f.whenTrue || f.type == "Boolean") &&
                (f.trueWhen == null || (f.values instanceof List && (f.values as List).contains(f.trueWhen))) &&
                (f.values == null || f.values instanceof List) &&
                (f.dflt == null || !(f.values instanceof List) || (f.values as List).contains(f.dflt)) &&
                (f.direction == null || f.values == ["raise", "lower"])
            }
            boolean requiresOk = variants.values().every { Map ae ->
                def names = ((((entry.slots ?: []) as List) + (ae.is(entry) ? [] : ((ae.slots ?: []) as List)) + ((entry.common ?: []) as List))*.name).findAll { it } as Set
                ae.requires == null || (ae.requires instanceof List && (ae.requires as List).every { r ->
                    (r instanceof String && r in names) || (r instanceof List && (r as List).every { it in names })
                })
            }
            boolean distinct = variants.findAll { k, Map ae -> k != null }.groupBy { k, Map ae -> ae.sub ?: entry.sub }.every { sub, Map group ->
                group.size() == 1 || group.values().collect { Map ae -> ((ae.slots ?: []) as List).findAll { it.containsKey("fixed") }.collectEntries { [(it.key): it.fixed] } }.unique().size() == group.size()
            }
            slotsOk && requiresOk && distinct
        }
    }

    def "every sub-type key of the device-capability map is a catalog sub-type or one the catalog does not model"() {
        given: "RM sub-types with a device picker that no catalog entry maps"
        def rmOnly = ["getBlindTilt", "getOCGarage", "getOCValve", "getSetMusicPlayer", "getTone"]
        def subs = script._rmActionCatalog().collectMany { String cap, Map entry ->
            [entry.sub] + ((entry.actions instanceof Map) ? (entry.actions as Map).values()*.sub : [])
        }.findAll { it } as Set

        expect:
        script._rmSubActCapab().keySet().every { it in subs || it in rmOnly }
    }

    def "modifyAction flips zwavePoll stop to start and the target through RM's booleans"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "deviceActs", "actSubType.1": "getStartStopZPoll", "ssZ.1": "false", "ssSD.1": "false"], zwavePollEditor(1))

        when:
        def result = script._rmModifyAction(100, 1, [action: "start", target: "switches"])

        then:
        result.success == true
        result.action == "start"
        (ma.writes as List)*.key == ["ssZ.1", "ssSD.1"]
        (ma.writes as List)*.value*.toString() == ["true", "true"]
    }

    def "modifyAction refuses a value outside the field's enum before opening the editor"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "sceneActs", "actSubType.1": "getFanSpeed", "fanDevice.1": ["12"], "fanSpeed.1": "low"],
            [[name: "fanSpeed.1", type: "enum"], [name: "actionDone", type: "button"]])

        when:
        script._rmModifyAction(100, 1, [speed: "turbo"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'speed' must be one of")
        (ma.clicks as List).isEmpty()
    }

    def "modifyAction refuses a mode id no hub mode carries"() {
        given:
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "2", name: "Night"]]
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "modeActs", "actSubType.1": "getSetMode", "mode.1": "1"],
            [[name: "mode.1", type: "enum"], [name: "actionDone", type: "button"]])

        when:
        script._rmModifyAction(100, 1, [modeId: 999])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("mode ID 999 is not offered")
        ex.message.contains("RM is not touched")
        (ma.clicks as List).isEmpty()
    }

    def "modifyAction refuses a setVariable target that is neither a hub nor a local variable"() {
        given:
        script.metaClass.getAllGlobalVars = { -> ["counter": [name: "counter", type: "integer", value: 0]] }
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "modeActs", "actSubType.1": "getSetVariable", "xVarV.1": "counter"],
            [[name: "xVarV.1", type: "enum"], [name: "actionDone", type: "button"]])

        when:
        script._rmModifyAction(100, 1, [variable: "nope"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'nope' is not a hub variable or one of this rule's local variables")
        ex.message.contains("counter")
        (ma.clicks as List).isEmpty()

        when:
        def ok = script._rmModifyAction(100, 1, [variable: "counter"])

        then:
        ok.success == true
    }

    def "modifyAction resolves runCommand's capability and command against RM's pickers"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "modeActs", "actSubType.1": "getDefinedAction", "myCapab.1": "Switch", "devices.1": ["8"], "cCmd.1": "on"], runCommandEditor(1))

        when:
        script._rmModifyAction(100, 1, [capabilityFilter: "purple"])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("'purple' is not a capability Rule Machine's Run Custom Action offers")
        !ex.message.contains("rolled back")
        (ma.clicks as List)*.name == ["1.0.false", "actionDone"]

        when:
        def result = script._rmModifyAction(100, 1, [command: "off"])

        then:
        result.success == true
        (ma.writes as List).any { it.key == "cCmd.1" && it.value?.toString() == "off" }
    }

    def "modifyAction marks the editor stuck when its Done fails after a refused write"() {
        given:
        script.metaClass.getAllGlobalVars = { -> ["counter": [name: "counter", type: "integer", value: 0]] }
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "modeActs", "actSubType.1": "getSetVariable", "xVarV.1": "counter"],
            [[name: "xVarV.1", type: "enum"], [name: "actionDone", type: "button"]]) { String path, Map body ->
            if (path == "/installedapp/btn" && body?.name?.toString() == "actionDone") throw new IllegalStateException("btn 500")
        }

        when:
        script._rmModifyAction(100, 1, [value: 5])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("has no field valNumber.1")
        ex.message.contains("[wizardStuck")
        ex.message.contains("actionCancel would delete the action")
        !(ma.clicks as List).any { it.name == "actionCancel" }
        script._rmOutcomeFlags(ex) == [wizardStuck: true, fieldsWritten: false]
    }

    def "modifyAction reports a failed Done after the writes with the fields that landed"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]], runRuleEditor(1)) { String path, Map body ->
            if (path == "/installedapp/btn" && body?.name?.toString() == "actionDone") throw new IllegalStateException("btn 500")
        }

        when:
        script._rmModifyAction(100, 1, [ruleIds: [300]])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("Done on action 1 failed")
        ex.message.contains("ruleAct.1")
        ex.message.contains("[wizardStuck")
        script._rmOutcomeFlags(ex) == [fieldsWritten: true, wizardStuck: true]
    }

    def "modifyAction reports an unreadable editor page as a possibly open editor"() {
        given:
        wireModifyEditorTransport(100, [1], ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]], runRuleEditor(1))
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> throw new IllegalStateException("timeout") }

        when:
        script._rmModifyAction(100, 1, [ruleIds: [300]])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("could not be read")
        ex.message.contains("[wizardStuck")
        script._rmOutcomeFlags(ex).wizardStuck == true
    }

    def "modifyAction is not verified when the action list cannot be read back"() {
        given:
        wireModifyEditorTransport(100, [1], ["actType.1": "rulesActs", "actSubType.1": "getRuleActions", "ruleAct.1": ["200"]], runRuleEditor(1))
        hubGet.register('/app/ruleBuilderJson/100') { params -> '{}' }

        when:
        def r = script._rmModifyAction(100, 1, [ruleIds: [300]])

        then:
        r.success == false
        r.error.contains("the action list could not be read")
    }

    def "modifyAction reports a device change Rule Machine did not keep"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 9, label: "B", capabilities: ["Switch"]]]) }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"B"}' }
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "switchActs", "actSubType.1": "getOnOffSwitch", "onOffSwitch.1": ["8"], "onOff.1": "true"],
            [[name: "onOffSwitch.1", type: "capability.switch", multiple: true], [name: "onOff.1", type: "bool"], [name: "actionDone", type: "button"]])
        ma.applyWrites = false

        when:
        def r = script._rmModifyAction(100, 1, [deviceIds: [9]])

        then:
        r.success == false
        (r.readbackMismatches as List).contains("onOffSwitch.1")
    }

    @spock.lang.Unroll
    def "_rmCheckCondSpec refuses #label"() {
        given:
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"T2"}' }

        when:
        script._rmCheckCondSpec(spec, kind, "addTrigger")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(text)

        where:
        label                                              | kind        | spec                                                                                              | text
        "compareToDevice without a comparator"             | "trigger"   | [capability: "Temperature", deviceIds: [8], compareToDevice: [deviceId: 9]]                        | "compareToDevice needs a comparator"
        "compareToDevice beside a value"                   | "trigger"   | [capability: "Temperature", deviceIds: [8], comparator: ">", value: 70, compareToDevice: [deviceId: 9]] | "cannot be combined with 'state'/'value'"
        "compareToDevice on a device-state capability"     | "trigger"   | [capability: "Switch", deviceIds: [8], comparator: "=", compareToDevice: [deviceId: 9]]            | "applies only to Rule Machine's numeric capabilities"
        "a hub-state trigger with no state"                | "trigger"   | [capability: "Location Event"]                                                                    | "Location Event needs state"
        "a trigger capability no writer models"            | "trigger"   | [capability: "Days of Week Schedule"]                                                             | "trigger this tool does not model"
        "a condition capability no writer models"          | "condition" | [capability: "Time of day"]                                                                       | "condition this tool does not model"
        "and stays on a capability without a duration"     | "trigger"   | [capability: "Button", deviceIds: [8], buttonNumber: 1, state: "pushed", andStays: [minutes: 1]]  | "no 'and stays' duration for a Button trigger"
        "an over comparator on a variable condition"       | "condition" | [capability: "Variable", variable: "x", comparator: "*increased*"]                                 | "not one Rule Machine offers for a Variable condition"
        "an over comparator on a variable condition (steer)" | "condition" | [capability: "Variable", variable: "x", comparator: "*increased*"]                               | "'*increased*' is a trigger-only comparator; add it as a trigger row (addTrigger) instead."
        "a change comparator on a numeric condition"       | "condition" | [capability: "Temperature", deviceIds: [8], comparator: "*changed*"]                              | "'*changed*' is a trigger-only comparator; add it as a trigger row (addTrigger) instead."
        "a change comparator on a Mode condition"          | "condition" | [capability: "Mode", comparator: "*changed*"]                                                     | "'*changed*' is a trigger-only comparator; add it as a trigger row (addTrigger) instead."
        "and stays with *changed* on a device-state trigger" | "trigger" | [capability: "Motion", deviceIds: [8], comparator: "*changed*", andStays: [minutes: 1]]           | "offers 'and stays' only for a state, not with the comparator '*changed*'"
        "and stays with *changed* on HSM Status"           | "trigger"   | [capability: "HSM Status", comparator: "*changed*", andStays: [minutes: 1]]                      | "offers 'and stays' only for a state, not with the comparator '*changed*'"
        "and stays with *changed* on Private Boolean"      | "trigger"   | [capability: "Private Boolean", comparator: "*changed*", andStays: [minutes: 1]]                 | "offers 'and stays' only for a state, not with the comparator '*changed*'"
        "and stays with *increased* on a numeric trigger"  | "trigger"   | [capability: "Temperature", deviceIds: [8], comparator: "*increased*", andStays: [minutes: 1]]    | "offers 'and stays' only for a state, not with the comparator '*increased*'"
        "and stays with *is empty* on a Variable trigger"  | "trigger"   | [capability: "Variable", variable: "x", comparator: "*is empty*", andStays: [minutes: 5]]         | "offers 'and stays' only for a state, not with the comparator '*is empty*' (it offers none with *changed*, *increased*, *decreased*, *is empty*)"
        "**Any Value** on a numeric trigger, with and stays" | "trigger" | [capability: "Temperature", deviceIds: [8], comparator: "**Any Value**", andStays: [minutes: 5]] | "comparator '**Any Value**' is not one Rule Machine offers for a Temperature trigger"
        "**Any Value** on a Variable trigger, with and stays" | "trigger" | [capability: "Variable", variable: "x", comparator: "**Any Value**", andStays: [minutes: 5]] | "comparator '**Any Value**' is not one Rule Machine offers for a Variable trigger"
        "*changed* on a Rule paused condition"             | "condition" | [capability: "Rule paused", ruleIds: [5], comparator: "*changed*"]                               | "Rule Machine offers '*changed*' on neither a Rule paused condition nor a Rule paused trigger."
    }

    def "_rmCheckCondSpec keeps the generic wording, without the trigger-row steer, where no change comparator was rejected"() {
        when:
        script._rmCheckCondSpec(spec, "condition", "addRequiredExpression")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(text)
        !ex.message.contains("trigger-only comparator")

        where:
        spec                                                         | text
        [capability: "Lock codes", comparator: "*changed*"]           | "is a Rule Machine condition this tool does not model; set it with rawSettings or walkStep"
        [capability: "Last Event Device"]                             | "is a Rule Machine condition this tool does not model; set it with rawSettings or walkStep"
        [capability: "Switch", deviceIds: [8], comparator: "="]       | "Rule Machine has no comparator for a Switch condition; pass state."
        [capability: "Rule paused", ruleIds: [5], comparator: "*changed*"] | "Rule Machine has no comparator for a Rule paused condition; pass state."
    }

    def "_rmCheckCondSpec accepts and stays with an over comparator, which RM offers it for"() {
        when:
        script._rmCheckCondSpec([capability: "Temperature", deviceIds: [8], comparator: "*increased by over*", value: 5, andStays: [minutes: 5]], "trigger", "addTrigger")
        script._rmCheckCondSpec([capability: "Temperature", deviceIds: [8], comparator: "*decreased by over*", value: 5, andStays: [minutes: 5]], "trigger", "addTrigger")

        then:
        noExceptionThrown()
    }

    def "discover's andStays field names every comparator the and-stays check refuses"() {
        expect:
        def stays = (script._rmCondDiscover("trigger", "Temperature").optionalFields as List).find { it.name == "andStays" }
        stays.description == "{hours?, minutes?, seconds?}; with a state, never with *changed*, *increased*, *decreased*, *is empty*"
    }

    def "_rmCheckCondSpec accepts and stays with a state, and *changed* without and stays, on a device-state trigger"() {
        when:
        script._rmCheckCondSpec([capability: "Motion", deviceIds: [8], state: "active", andStays: [minutes: 1]], "trigger", "addTrigger")
        script._rmCheckCondSpec([capability: "Motion", deviceIds: [8], comparator: "*changed*"], "trigger", "addTrigger")
        script._rmCheckCondSpec([capability: "Motion", deviceIds: [8], comparator: "*changed*", allOfThese: true], "trigger", "addTrigger")

        then:
        noExceptionThrown()
    }

    def "_rmCheckCondSpec accepts compareToDevice with a comparator on a numeric trigger and *changed* on a variable condition"() {
        given:
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"T2"}' }

        when:
        script._rmCheckCondSpec([capability: "Temperature", deviceIds: [8], comparator: ">", compareToDevice: [deviceId: 9, offset: 2]], "trigger", "addTrigger")
        script._rmCheckCondSpec([capability: "Variable", variable: "x", comparator: "*changed*"], "condition", "addRequiredExpression")

        then:
        noExceptionThrown()
    }

    def "discover describes compareToDevice on numeric triggers and the hub-state values from Rule Machine's lists"() {
        expect:
        script._rmCondDiscover("trigger", "Temperature").capability.optionalFields*.name.contains("compareToDevice")
        script._rmCondDiscover("condition", "Temperature").capability.optionalFields*.name.contains("compareToDevice")
        script._rmCondDiscover("trigger", "Location Event").capability.requiredFields.find { it.name == "state" }.values == script._rmCondTables().locationEvents
        script._rmCondDiscover("trigger", "HSM Alert").capability.requiredFields.find { it.name == "state" }.values == script._rmCondTables().hsmAlerts
        script._rmCondDiscover("trigger", "Days of Week Schedule").capability.authoring.contains("not modelled")
        script._rmCondDiscover("condition", "Time Since Event").capability.authoring.contains("not modelled")
    }

    @spock.lang.Unroll
    def "_rmWriteRunCommandFields refuses when #label"() {
        given:
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                               configPage: [name: "doActPage", title: "T", install: false, error: null, sections: [[title: "", input: inputs, paragraphs: []]]],
                               settings: [:], childApps: []])
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: ''] }

        when:
        script._rmWriteRunCommandFields(100, 1, [capabilityFilter: "Switch", command: "on", deviceIds: [8]], [], [])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(text)
        !ex.message.contains("rolled back")

        where:
        label                              | inputs                                                                                                                                                      | text
        "the capability picker is missing" | []                                                                                                                                                          | "capability picker 'myCapab.1' was not revealed"
        "no device picker appears"         | [[name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]]]                                                                                           | "revealed no device picker"
        "the command picker is missing"    | [[name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]], [name: "devices.1", type: "capability.switch", multiple: true]]                           | "command picker 'cCmd.1' was not revealed"
        "RM lists no commands"             | [[name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]], [name: "devices.1", type: "capability.switch", multiple: true], [name: "cCmd.1", type: "enum", options: [:]]] | "found no commands"
    }

    @spock.lang.Unroll
    def "_rmWriteRunCommandFields with useLastEventDevice=#useLast names the right command source when #label"() {
        given:
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            JsonOutput.toJson([app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                               configPage: [name: "doActPage", title: "T", install: false, error: null, sections: [[title: "", input: inputs, paragraphs: []]]],
                               settings: [:], childApps: []])
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: ''] }
        def spec = useLast ? [capabilityFilter: "Switch", command: "on", useLastEventDevice: true] : [capabilityFilter: "Switch", command: "on", deviceIds: [8]]

        when:
        script._rmWriteRunCommandFields(100, 1, spec, [], [])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(text)
        !ex.message.contains(notText)

        where:
        useLast | label                           | inputs                                                                                                                             | text                                                                                         | notText
        true    | "the command picker is missing" | [[name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]]]                                                                 | "with Use Last Event Device on, RM lists the commands of the rule's enabled trigger devices" | "accepted into that capability's picker"
        false   | "the command picker is missing" | [[name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]], [name: "devices.1", type: "capability.switch", multiple: true]] | "RM lists commands only for devices it accepted into that capability's picker"              | "trigger devices"
        true    | "RM lists no commands"          | [[name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]], [name: "cCmd.1", type: "enum", options: [:]]]                   | "found no commands for the rule's trigger device(s) with capability 'Switch'"                | "selected device(s)"
        true    | "the command is not offered"    | [[name: "myCapab.1", type: "enum", options: ["Switch": "Switch"]], [name: "cCmd.1", type: "enum", options: ["off": "off"]]]        | "'on' is not a command of the rule's trigger device(s) with capability 'Switch'"             | "selected device(s)"
    }

    def "_systemAppTypeId skips a deprecated type and records why the list could not be read"() {
        given:
        boolean readable = true
        hubGet.register('/hub2/appsList') { params ->
            if (!readable) throw new IllegalStateException("502")
            JsonOutput.toJson([apps: [], systemAppTypes: [[id: 24, name: "Notifications", allowNewInstances: true, deprecated: true]]])
        }

        when:
        def deprecated = script._systemAppTypeId("Notifications")

        then:
        deprecated == [id: null, error: null]

        when:
        readable = false
        def unreadable = script._systemAppTypeId("Notifications")

        then:
        unreadable.id == null
        unreadable.error.contains("502")
    }

    def "a failed built-in parent bootstrap names why the built-in app list could not be read"() {
        given: "no Rule Machine parent, a sysApp GET that creates nothing, and a list that fails on the type lookup"
        int listReads = 0
        hubGet.register('/hub2/appsList') { params ->
            if (++listReads >= 3) throw new IllegalStateException("502 Bad Gateway")
            JsonOutput.toJson([apps: [], systemAppTypes: []])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 -> [status: 200, location: null, data: ""] }

        when:
        script._discoverParentAppId("rule_machine")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("typeId=null (the built-in app list could not be read: 502 Bad Gateway)")
    }

    def "a numeric trigger's compareToDevice writes istDev, then the reference device, and the offset as the right-hand value"() {
        given:
        def w = wireTriggerWizard(temperatureTriggerInputs(true))
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        def spec = [capability: "Temperature", deviceIds: [8], comparator: ">", compareToDevice: [deviceId: 9, offset: 2]]

        when:
        script._rmAddTrigger(100, spec)

        then:
        def keys = w.writes*.key
        keys.indexOf("istDev1") >= 0
        keys.indexOf("istDev1") < keys.indexOf("reltDevice1")
        w.writes.find { it.key == "istDev1" }.value == true
        w.writes.find { it.key == "reltDevice1" }.value == "9"
        w.writes.find { it.key == "tstate1" }.value == 2

        and: "the caller's spec is not changed"
        !spec.containsKey("value")
        spec.compareToDevice == [deviceId: 9, offset: 2]
    }

    def "a numeric trigger's compareToDevice whose reference device does not land is cancelled, not committed"() {
        given: "every write lands except reltDevice1"
        def w = wireTriggerWizard(temperatureTriggerInputs(true))
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass._rmWriteSettingOnPage = { Integer appId, String pageName, String key, Object value, List applied, String typeHint = null, List skipped = null, Map cache = null ->
            w.writes << [key: key, value: value]
            if (key == "reltDevice1") skipped << [key: key, reason: "silent_rejection", value: value]
            else applied << key
        }

        when:
        script._rmAddTrigger(100, [capability: "Temperature", deviceIds: [8], comparator: ">", compareToDevice: [deviceId: 9, offset: 2]])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("reference device 9 did not land in reltDevice1")
        w.writes*.key.contains("reltDevice1")
        !w.writes*.key.contains("tstate1")
        !w.posts.any { it.body?.name == "hasAll" }
        w.posts.count { it.path == "/installedapp/btn" && it.body?.name == "cancelCapab" } == 1
    }

    def "a trigger failure after the editor opened cancels it once and carries no flag"() {
        given:
        def w = wireTriggerWizard(temperatureTriggerInputs(false))
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        script._rmAddTrigger(100, [capability: "Temperature", deviceIds: [8], comparator: ">", compareToDevice: [deviceId: 9]])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("reltDevice1 did not appear")
        !ex.message.contains("[wizardStuck")
        script._rmOutcomeFlags(ex).isEmpty()
        w.posts.count { it.path == "/installedapp/btn" && it.body?.name == "cancelCapab" } == 1
    }

    def "a trigger editor whose cancel fails is flagged wizardStuck, and the error envelope reads the flag"() {
        given:
        wireTriggerWizard(temperatureTriggerInputs(false))
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (body?.name == "cancelCapab") throw new IllegalStateException("btn 500")
            [status: 200, location: null, data: '']
        }

        when:
        script._rmAddTrigger(100, [capability: "Temperature", deviceIds: [8], comparator: ">", compareToDevice: [deviceId: 9]])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("[wizardStuck -- cancelCapab cleanup failed: btn 500")
        script._rmOutcomeFlags(ex).wizardStuck == true
        script._rmBuildUpdateErrorResponse(100, ex.message, [backupKey: "k"], "selectTriggers", ex).wizardStuck == true
    }

    def "a conditional trigger refused after its condition was built names the condition left over"() {
        given:
        def w = wireTriggerWizard([
            [name: "isCondTrig.1", type: "bool"],
            [name: "condTrig.1", type: "enum", options: ["a"]],
            [name: "rCapab_1", type: "enum", options: ["Switch"]],
            [name: "rDev_1", type: "capability.switch", multiple: true],
            [name: "state_1", type: "enum", options: ["on", "off"]],
            [name: "tCapab2", type: "enum", options: ["Switch"]],
            [name: "hasAll", type: "button"]
        ])
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when: "the trigger's capability is not in the picker, after condition 1 exists"
        script._rmAddTrigger(100, [capability: "Motion", deviceIds: [8], state: "active",
                                   condition: [capability: "Switch", deviceIds: [9], state: "on"]])

        then: "a runtime failure, not a validation error: a corrected retry would build another condition"
        def ex = thrown(IllegalStateException)
        ex.message.contains("addTrigger.capability 'Motion' is not in Rule Machine's trigger list")
        ex.message.contains("The condition created for this trigger (id 1) stays in the rule's conditions as unused")
        !ex.message.contains("RM is not touched")
        w.posts.count { it.path == "/installedapp/btn" && it.body?.name == "cancelCapab" } == 1
    }

    @spock.lang.Unroll
    def "the in-flight trigger slot is RM's counter when ahead (ndx=#ndx, committed=#existing)"() {
        given:
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([appSettings: [], appState: ndx == null ? [] : [[name: "ndx", value: ndx]]])
        }

        expect:
        script._rmInFlightTriggerIdx(100, existing) == expected

        where:
        ndx  | existing | expected
        5    | [1, 2]   | 5
        "7"  | []       | 7
        2    | [1, 2]   | 3
        null | [1, 2]   | 3
        null | []       | 1
    }

    def "a conditional trigger builds its condition on RM's counter and the trigger on the next slot"() {
        given:
        def w = wireTriggerWizard([
            [name: "tCapab5", type: "enum", options: ["Switch"]],
            [name: "tDev5", type: "capability.switch", multiple: true],
            [name: "tstate5", type: "enum", options: ["on", "off"]],
            [name: "isCondTrig.5", type: "bool"],
            [name: "condTrig.5", type: "enum", options: ["4"]],
            [name: "hasAll", type: "button"]
        ])
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([appSettings: [[name: "tCapab1", value: "Switch"]], appState: [[name: "ndx", value: 4]]])
        }

        when: "a cancelled edit left the counter at 4, past the next index (2)"
        script._rmAddTrigger(100, [capability: "Switch", deviceIds: [8], state: "on",
                                   condition: [capability: "Switch", deviceIds: [9], state: "off"]])

        then:
        def keys = w.writes*.key
        keys.contains("rCapab_4")
        !keys.contains("rCapab_2")
        keys.contains("tCapab5")
        w.writes.find { it.key == "condTrig.5" }?.value == "4"
    }

    def "an unreadable trigger counter refuses a conditional add before its condition is built"() {
        given:
        def w = wireTriggerWizard([[name: "hasAll", type: "button"]])
        int statusReads = 0
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (++statusReads > 1) throw new IllegalStateException("timeout")
            statusJson(100)
        }

        when:
        script._rmAddTrigger(100, [capability: "Switch", deviceIds: [8], state: "on",
                                   condition: [capability: "Switch", deviceIds: [9], state: "off"]])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("the rule's trigger counter could not be read for app 100")
        ex.message.contains("Nothing was written.")
        !w.writes.any { it.key.startsWith("rCapab_") || it.key.startsWith("isCondTrig") }
        w.posts.count { it.path == "/installedapp/btn" && it.body?.name == "cancelCapab" } == 1
    }

    def "with backups off the skip branch refuses a missing app id from its configure/json probe"() {
        given:
        settingsMap.backupBeforeRuleEdits = false
        hubGet.register('/installedapp/configure/json/504') { params -> throw new RuntimeException("HTTP 404 Not Found") }

        when:
        script._rmBackupBeforeEdit(504, "pre-addAction")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("No rule/app with id 504 exists on this hub")
    }

    @spock.lang.Unroll
    def "with backups off the skip branch reads the walk's page once and records whether the app is an RM rule (#typeName)"() {
        given:
        settingsMap.backupBeforeRuleEdits = false
        hubGet.register('/installedapp/configure/json/504/selectActions') { params ->
            JsonOutput.toJson([app: [id: 504, appType: [name: typeName, namespace: "hubitat"]], configPage: [name: "selectActions", sections: []], settings: [:]])
        }
        script._memoKeepPreEditStatus(504, [appState: []])

        when:
        def backup = script._rmBackupBeforeEdit(504, "pre-walkStep", "selectActions")

        then:
        backup.skipped && !backup.backupKey
        hubGet.calls*.path == ['/installedapp/configure/json/504/selectActions']
        script._memoKnowsRuleMachine(504)
        script._memoRuleMachine(504) == rm
        script._memoTakePreEditStatus(504) == null

        where:
        typeName          | rm
        "Rule-5.1"        | true
        "Package Manager" | false
    }

    def "with backups off a configure/json failure that is not a 404 refuses the edit, as the backup path does"() {
        given:
        settingsMap.backupBeforeRuleEdits = false
        hubGet.register('/installedapp/configure/json/504') { params -> throw new RuntimeException("HTTP 500 busy") }

        when:
        script._rmBackupBeforeEdit(504, "pre-addAction")

        then:
        def ex = thrown(IllegalStateException)
        ex.message == "Rule/app 504 could not be read before the edit (HTTP 500 busy). Nothing was changed."
        !script._memoKnowsRuleMachine(504)
    }

    def "restore hints name the backups setting only when the backup was skipped by it"() {
        given:
        def skipped = [skipped: "pre-edit backups are off in Advanced settings"]

        expect:
        script._rmBackupHint(skipped).contains("'Back up native apps before edits' is off in Advanced settings")
        script._rmRollbackIfNotCommittedHint(skipped).contains("'Back up native apps before edits' is off in Advanced settings")
        script._rmRestoreClause(skipped).startsWith("no pre-edit backup was taken ('Back up native apps before edits' is off")

        and: "a backup map with no key and no skip names no setting"
        script._rmBackupHint([:]) == "No backup key was returned for this edit, so there is no snapshot to restore; read the rule with hub_get_app_config and repair it in place."
        !script._rmRollbackIfNotCommittedHint([:]).contains("Back up native apps")
        script._rmRestoreClause([:]) == "no backup key was returned for this edit, so repair the rule in place"

        and: "a key is the restore handle"
        script._rmRestoreClause([backupKey: "k"]) == "restore via hub_restore_backup with backupKey='k'"
    }

    def "a modifyAction that does not verify with backups off says why there is no snapshot, never backupKey='null'"() {
        given:
        enableWrite()
        settingsMap.backupBeforeRuleEdits = false
        wireModifyEditorTransport(100, [1], ["actType.1": "deviceActs", "actSubType.1": "getStartStopZPoll", "ssZ.1": "false", "ssSD.1": "false"], zwavePollEditor(1))
        hubGet.register('/app/ruleBuilderJson/100') { params -> '{}' }
        def uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << fn }

        when:
        def r = script.toolSetRule([appId: 100, modifyAction: [index: 1, mods: [action: "start"]], confirm: true])

        then:
        r.success == false
        r.backup.skipped
        !r.backup.containsKey("backupKey")
        r.restoreHint.contains("'Back up native apps before edits' is off in Advanced settings")
        !JsonOutput.toJson(r).contains("backupKey='null'")
        uploads.isEmpty()
    }

    @spock.lang.Unroll
    def "with backups off an edit through the dispatch envelope reports the backup skipped (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        settingsMap.backupBeforeRuleEdits = false
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", [[name: "origLabel", type: "text"]]) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        def uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << fn }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def response = mcpDriver.callTool('hub_set_rule', [appId: 100, settings: [origLabel: "x"], confirm: true])

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.backup.skipped
        !inner.backup.containsKey("backupKey")
        uploads.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "a Button Controller flow failure without a backup key does not blame the backups setting"() {
        given:
        enableWrite()
        settingsMap.backupBeforeRuleEdits = false
        hubGet.register('/installedapp/configure/json/630') { params ->
            JsonOutput.toJson([
                app: [id: 630, name: "Button Controller-5.1", label: "BC", installed: true, appType: [name: "Button Controller-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", error: null, sections: [[title: "", input: [
                    [name: "buttonDev", type: "capability.pushableButton", multiple: true], [name: "origLabel", type: "text"]]]]],
                settings: [buttonDev: ["43": "Virtual Button"], origLabel: "BC"], childApps: []])
        }
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, String activePage = null -> [:] }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (body?.stateAttribute == "addBut") throw new IllegalStateException("btn 500")
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolSetNativeApp([buttonRule: [controllerId: 630, buttonNumber: 1, event: "pushed"], confirm: true])

        then:
        result.success == false
        result.note.contains("No backup key was returned for the controller")
        !result.note.contains("Back up native apps before edits")
    }

    def "every executeTool call starts with an empty execution memo"() {
        given: "one of each memo family and an outcome flag left from an earlier call"
        script._memoKeepDeviceList(["8": [label: "S", caps: ["switch"]]])
        script._memoNoteDeviceExists("8")
        script._memoKeepPreEditStatus(100, [appState: []])
        script._memoNoteRuleMachine(100, true)
        def flagged = script._rmFail(new IllegalStateException("x"), [wizardStuck: true])

        when:
        script.executeTool("hub_get_tool_guide", [:])

        then:
        (script.EXECUTION_CACHE as Map).isEmpty()
        script._memoDeviceList() == null
        !script._memoDeviceExists("8")
        script._memoTakePreEditStatus(100) == null
        !script._memoKnowsRuleMachine(100)
        script._rmOutcomeFlags(flagged).isEmpty()
    }

    def "modifyAction writes an enum value in the catalog's spelling"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "sceneActs", "actSubType.1": "getFanSpeed", "fanDevice.1": ["12"], "fanSpeed.1": "low"],
            [[name: "fanSpeed.1", type: "enum", options: ["low", "medium-low", "medium", "medium-high", "high", "on", "off", "auto"]], [name: "actionDone", type: "button"]])

        when:
        def result = script._rmModifyAction(100, 1, [speed: "HIGH"])

        then:
        result.success == true
        (ma.writes as List).find { it.key == "fanSpeed.1" }?.value?.toString() == "high"
    }

    def "modifyAction maps a zwavePoll target in any case to RM's boolean"() {
        given:
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "deviceActs", "actSubType.1": "getStartStopZPoll", "ssZ.1": "true", "ssSD.1": "false"], zwavePollEditor(1))

        when:
        def result = script._rmModifyAction(100, 1, [target: "Switches"])

        then:
        result.success == true
        (ma.writes as List).find { it.key == "ssSD.1" }?.value?.toString() == "true"
    }

    def "modifyAction refuses a device without the capability RM's picker lists, before opening the editor"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 26, label: "Temp", capabilities: ["TemperatureMeasurement"]]]) }
        hubGet.register('/device/fullJson/26') { params -> '{"id":"26","name":"Temp"}' }
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "switchActs", "actSubType.1": "getOnOffSwitch", "onOffSwitch.1": ["8"], "onOff.1": "true"],
            [[name: "onOffSwitch.1", type: "capability.switch", multiple: true], [name: "actionDone", type: "button"]])

        when:
        script._rmModifyAction(100, 1, [deviceIds: [26]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("device 26 (Temp) does not have the Switch capability")
        (ma.clicks as List).isEmpty()
    }

    def "a trigger or condition device must have the capability RM's picker for that capability lists"() {
        given: "a temperature sensor offered as a Garage door trigger and as a Switch condition"
        enableWrite()
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 26, label: "Temp", capabilities: ["TemperatureMeasurement"]]]) }
        hubGet.register('/device/fullJson/26') { params -> '{"id":"26","name":"Temp"}' }

        when:
        script._rmPrevalidateTriggerSpecList([[capability: "Garage door", deviceIds: [26], state: "open"]], "triggers")

        then:
        def trig = thrown(IllegalArgumentException)
        trig.message.contains("device 26 (Temp) does not have the garageDoorControl capability")

        when:
        script._rmPrevalidateConditionList([conditions: [[capability: "Switch", deviceIds: [26], state: "on"]]], "conditions")

        then:
        def cond = thrown(IllegalArgumentException)
        cond.message.contains("device 26 (Temp) does not have the switch capability")

        when: "a capability with no device picker is not checked"
        script._rmPrevalidateTriggerSpecList([[capability: "Mode", state: "Day"]], "triggers")

        then:
        notThrown(IllegalArgumentException)
    }

    def "a compareToDevice reference device must have the capability, on the #path path"() {
        given: "26 and 28 measure temperature; 27 is a switch"
        enableWrite()
        hubGet.register('/hub2/vrb/devices') { params ->
            JsonOutput.toJson([[id: 26, label: "Temp", capabilities: ["TemperatureMeasurement"]], [id: 27, label: "S", capabilities: ["Switch"]],
                               [id: 28, label: "Temp2", capabilities: ["TemperatureMeasurement"]]])
        }
        [26, 27, 28].each { id -> hubGet.register("/device/fullJson/${id}".toString()) { params -> JsonOutput.toJson([id: id]) } }
        def numeric = { ref -> [capability: "Temperature", deviceIds: [26], comparator: ">", compareToDevice: [deviceId: ref]] }
        def check = { ref ->
            if (path == "trigger") script._rmPrevalidateTriggerSpecList([numeric(ref)], "addTriggers")
            else if (path == "conditional trigger") script._rmPrevalidateTriggerSpecList([[capability: "Switch", deviceIds: [27], state: "on", condition: numeric(ref)]], "addTriggers")
            else script._rmPrevalidateConditionList([conditions: [numeric(ref)]], "addRequiredExpression")
        }

        when: "the reference is the switch"
        check(27)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("${label}: device 27 (S) does not have the temperatureMeasurement capability")

        when: "the reference measures temperature"
        check(28)

        then:
        notThrown(IllegalArgumentException)

        where:
        path                  | label
        "trigger"             | "addTrigger.compareToDevice.deviceId"
        "conditional trigger" | "addTrigger.condition.compareToDevice.deviceId"
        "expression"          | "addRequiredExpression.conditions[0].compareToDevice.deviceId"
    }

    def "modifyAction writes a changed useLastEventDevice and carries the committed one"() {
        given:
        wireModifyEditorTransport(100, [1], ["tDev1": ["8"], "actType.1": "modeActs", "actSubType.1": "getDefinedAction", "myCapab.1": "Switch", "devices.1": ["8"], "cCmd.1": "on", "useLastDev.1": "true"], runCommandEditor(1))

        when:
        def kept = script._rmPlanModifyAction(100, 1, [command: "off"])
        def changed = script._rmPlanModifyAction(100, 1, [command: "off", useLastEventDevice: false])

        then: "an unchanged flag is not rewritten, and RM's hidden device picker gets no list"
        kept.runCommand.useLastEventDevice == true
        kept.runCommand.deviceIds == null
        !kept.writes.containsKey("useLastDev.1")

        and: "a changed flag is written, and the committed devices return with the picker"
        changed.runCommand.useLastEventDevice == false
        changed.writes["useLastDev.1"] == false
        changed.runCommand.deviceIds == [8]
    }

    def "modifyAction reports a useLastEventDevice change Rule Machine did not keep"() {
        given:
        def settings = ["tDev1": ["8"], "actType.1": "modeActs", "actSubType.1": "getDefinedAction", "myCapab.1": "Switch", "devices.1": ["8"], "cCmd.1": "on", "useLastDev.1": "false"]
        def ma = wireModifyEditorTransport(100, [1], settings, [[name: "useLastDev.1", type: "bool"]] + runCommandEditor(1))
        ma.dropKeys = ["useLastDev.1"]

        when:
        def r = script._rmModifyAction(100, 1, [useLastEventDevice: true])

        then:
        (ma.writes as List).find { it.key == "useLastDev.1" }?.value?.toString() == "true"
        !(ma.writes as List).any { it.key == "devices.1" }
        r.success == false
        r.readbackMismatches == ["useLastDev.1"]
        r.error.contains("useLastDev.1 did not read back as written")
    }

    def "modifyAction on runCommand refuses a device list Rule Machine would hide, and needs one when the flag is off"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 8, label: "S1", capabilities: ["Switch"]]]) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        wireModifyEditorTransport(100, [1], ["actType.1": "modeActs", "actSubType.1": "getDefinedAction", "myCapab.1": "Switch", "cCmd.1": "on", "useLastDev.1": "true"], runCommandEditor(1))

        when:
        script._rmPlanModifyAction(100, 1, [useLastEventDevice: true, deviceIds: [8]])

        then:
        def hidden = thrown(IllegalArgumentException)
        hidden.message.contains("deviceIds cannot be set while useLastEventDevice is true")
        hidden.message.contains("RM is not touched")

        when:
        script._rmPlanModifyAction(100, 1, [useLastEventDevice: false])

        then:
        def none = thrown(IllegalArgumentException)
        none.message.contains("'deviceIds' is needed when useLastEventDevice is false")
    }

    def "useLastEventDevice needs an enabled trigger device with the row's capability, checked before any write"() {
        given: "a switch (8), a temperature sensor (26) and a dimmer (30); trigger 2 is disabled"
        hubGet.register('/hub2/vrb/devices') { params ->
            JsonOutput.toJson([[id: 8, label: "S1", capabilities: ["Switch"]], [id: 26, label: "Temp", capabilities: ["TemperatureMeasurement"]],
                               [id: 30, label: "D1", capabilities: ["Switch", "SwitchLevel"]]])
        }
        def row = ["actType.1": "modeActs", "actSubType.1": "getDefinedAction", "myCapab.1": "Switch", "devices.1": ["8"], "cCmd.1": "on", "useLastDev.1": "false"]
        def ma = wireModifyEditorTransport(100, [1], row, runCommandEditor(1))
        def serve = { Map triggers ->
            hubGet.register('/installedapp/statusJson/100') { params ->
                JsonOutput.toJson([installedApp: [id: 100],
                                   appSettings: (row + triggers).collect { k, v -> [name: k, value: v] + (v instanceof List ? [deviceIdsForDeviceList: v] : [:]) },
                                   eventSubscriptions: [], scheduledJobs: [],
                                   appState: [[name: "capabstrue", value: triggers.keySet().collectEntries { [(it - "tDev"): "t"] }], [name: "disabledTriggers", value: ["2"]]]])
            }
        }

        when: "the rule has no trigger"
        serve([:])
        script._rmPlanModifyAction(100, 1, [useLastEventDevice: true])

        then:
        def none = thrown(IllegalArgumentException)
        none.message.contains("with useLastEventDevice on, Rule Machine lists the commands of the rule's enabled trigger devices that have the 'Switch' capability, and this rule has none")
        none.message.contains("RM is not touched")

        when: "its trigger devices lack the capability, or the one that has it is on a disabled trigger"
        serve(["tDev1": ["26"], "tDev2": ["8"]])
        script._rmPlanModifyAction(100, 1, [useLastEventDevice: true])

        then:
        def lacking = thrown(IllegalArgumentException)
        lacking.message.contains("this rule has none")

        when: "the add path refuses the same rule before mapping any field"
        script._rmMapActionSpec(100, [command: "on", useLastEventDevice: true], "runCommand", null)

        then:
        def add = thrown(IllegalArgumentException)
        add.message.contains("runCommand: with useLastEventDevice on")

        when: "a capability given by its picker label maps to the device capability the triggers lack"
        serve(["tDev1": ["8"]])
        script._rmPlanModifyAction(100, 1, [useLastEventDevice: true, capabilityFilter: "Dimmer"])

        then:
        def dimmer = thrown(IllegalArgumentException)
        dimmer.message.contains("the 'Dimmer' capability, and this rule has none")

        when: "an enabled trigger is on a device with the capability"
        serve(["tDev1": ["26"], "tDev3": ["8"]])
        def plan = script._rmPlanModifyAction(100, 1, [useLastEventDevice: true])
        serve(["tDev1": ["30"]])
        def byLabel = script._rmPlanModifyAction(100, 1, [useLastEventDevice: true, capabilityFilter: "Dimmer"])

        then:
        plan.runCommand.useLastEventDevice == true
        plan.writes["useLastDev.1"] == true
        byLabel.runCommand.capabilityFilter == "Dimmer"

        and: "nothing was clicked or written"
        (ma.clicks as List).isEmpty()
        (ma.writes as List).isEmpty()
    }

    def "an add with useLastEventDevice counts the triggers the same call adds first"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 8, label: "S1", capabilities: ["Switch"]]]) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        def spec = [capability: "runCommand", command: "on", useLastEventDevice: true]

        when:
        script._rmPrevalidateActionSpecList([spec], "addActions", null, 100)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("addActions[0]: runCommand: with useLastEventDevice on")
        ex.message.contains("RM is not touched")

        when:
        script._rmPrevalidateActionSpecList([spec], "addActions", null, 100, [8])

        then:
        noExceptionThrown()
        !spec.containsKey("__pendingTriggerDeviceIds")
    }

    def "a useLastEventDevice addAction reads the rule's statusJson once for its pre-flight, index and mapping"() {
        given: "a switch trigger on device 8; the add is stopped at its first write"
        enableWrite()
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 8, label: "S1", capabilities: ["Switch"]]]) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, [[name: "tDev1", value: ["8"], deviceIdsForDeviceList: ["8"]]]) }
        script.metaClass._rmIsAppDisabled = { Integer id -> false }
        script.metaClass._rmBackupBeforeEdit = { Integer id, String reason, String page = null -> [backupKey: "k"] }
        Integer readsBeforeWrite = null
        script.metaClass._rmRunPendingPredCapabsClear = { Integer id ->
            readsBeforeWrite = hubGet.calls.count { it.path == '/installedapp/statusJson/100' } as Integer
            throw new IllegalStateException("stopped at the first write")
        }

        when:
        script.toolSetRule([appId: 100, addAction: [capability: "runCommand", command: "on", useLastEventDevice: true], confirm: true])

        then:
        readsBeforeWrite == 1
    }

    def "modifyAction on runCommand needs a command when neither the mods nor the committed row carry one"() {
        given:
        wireModifyEditorTransport(100, [1], ["actType.1": "modeActs", "actSubType.1": "getDefinedAction", "myCapab.1": "Switch", "devices.1": ["8"]], runCommandEditor(1))

        when:
        script._rmPlanModifyAction(100, 1, [capabilityFilter: "Dimmer"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("modifyAction on runCommand: 'command' is needed. RM is not touched.")
    }

    @spock.lang.Unroll
    def "the setVariable target check refuses only when the list it could belong to was read (#label)"() {
        given:
        def logs = []
        script.metaClass.mcpLog = { String level, String component, String msg, String ruleId = null, Map extra = null -> logs << [level: level, msg: msg] }
        script.metaClass.getAllGlobalVars = { -> if (!hubReadable) throw new IllegalStateException("hub vars down"); ["counter": [name: "counter", type: "integer"]] }
        hubGet.register('/installedapp/statusJson/100') { params ->
            if (!localsReadable) throw new IllegalStateException("status down")
            JsonOutput.toJson([appSettings: [], appState: []])
        }

        when:
        def refusal = null
        try { script._rmRequireVariableName(100, target) } catch (IllegalArgumentException e) { refusal = e }

        then:
        (refusal != null) == refused
        logs.any { it.level == "warn" && it.msg.contains("variable '${target}' was not checked") } == unchecked
        !hubReadable == logs.any { it.msg.contains("the hub variables could not be read (hub vars down)") }
        !localsReadable == logs.any { it.msg.contains("local variables could not be read") }

        where:
        label                                  | target    | hubReadable | localsReadable | refused | unchecked
        "both lists read, name in neither"     | "nope"    | true        | true           | true    | false
        "both lists read, a hub variable"      | "counter" | true        | true           | false   | false
        "hub list unreadable"                  | "nope"    | false       | true           | false   | true
        "local list unreadable"                | "nope"    | true        | false          | false   | true
        "local list unreadable, a hub variable"| "counter" | true        | false          | false   | false
    }

    def "a device the feed lists with no capabilities is unknown, not refused"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 78, label: "Opaque", capabilities: []]]) }

        expect:
        script._rmMapActionSpec(100, [deviceIds: [78]], "switch", "on").actSubType == "getOnOffSwitch"
    }

    @spock.lang.Unroll
    def "a #label device list is logged once and memoised for the execution"() {
        given:
        def logs = []
        script.metaClass.mcpLog = { String level, String component, String msg, String ruleId = null, Map extra = null -> logs << [level: level, msg: msg] }
        script.metaClass._typeName = { v -> "ShapeSentinel" }
        int reads = 0
        hubGet.register('/hub2/vrb/devices') { params -> reads++; if (body == null) throw new IllegalStateException("feed down"); body }

        when:
        script._rmMapActionSpec(100, [deviceIds: [26]], "switch", "on")
        script._rmMapActionSpec(100, [deviceIds: [26]], "switch", "off")

        then:
        reads == 1
        logs.count { it.level == "warn" && it.msg.contains(logged) } == 1

        where:
        label                | body             | logged
        "failed"             | null             | "device capabilities could not be read (feed down); the Switch check is skipped for this call"
        "oddly shaped"       | '{"devices":[]}' | "the device list had an unexpected shape (ShapeSentinel); the Switch check is skipped for this call"
    }

    def "a single value an enum picker does not list is skipped with what it offers; listed values and lists are written"() {
        given:
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            ruleConfigJson(100, "r", [
                [name: "fanSpeed.1", type: "enum", options: ["low", "high"]],
                [name: "pick.1", type: "enum", options: ["a": "A"]],
                [name: "vt.1", type: "enum", options: [[value: "on", text: "On"]]],
                [name: "modes.1", type: "enum", multiple: true, options: ["1": "Day"]]
            ])
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }
        def applied = []
        def skipped = []
        def written = { String key -> posts.any { it.path == "/installedapp/update/json" && (it.body as Map).containsKey("settings[${key}]".toString()) } }

        when:
        script._rmWriteSettingOnPage(100, "doActPage", "fanSpeed.1", "turbo", applied, null, skipped)
        script._rmWriteSettingOnPage(100, "doActPage", "pick.1", "b", applied, null, skipped)

        then:
        skipped == [[key: "fanSpeed.1", reason: "not_offered", value: "turbo", offered: ["low", "high"]],
                    [key: "pick.1", reason: "not_offered", value: "b", offered: ["a"]]]
        posts.isEmpty()

        when:
        script._rmWriteSettingOnPage(100, "doActPage", "fanSpeed.1", "high", applied, null, skipped)
        script._rmWriteSettingOnPage(100, "doActPage", "vt.1", "on", applied, null, skipped)
        script._rmWriteSettingOnPage(100, "doActPage", "modes.1", ["9"], applied, null, skipped)

        then:
        written("fanSpeed.1")
        written("vt.1")
        written("modes.1")
        skipped.count { it.reason == "not_offered" } == 2
    }

    def "a write counts as landed on a page whose timestamps change on every render only when #desc"() {
        given: "doActPage renders a new activity timestamp and epoch token on every fetch"
        int fetches = 0
        def posted = false
        hubGet.register('/installedapp/configure/json/100/doActPage') { params ->
            fetches++
            def input = [name: "msg.1", type: "text"] + (posted && echoes ? [value: "hello"] : [:])
            JsonOutput.toJson([app: [id: 100, name: "Rule-5.1", label: "r", installed: true, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "doActPage", error: null, sections: [[title: "", input: [input],
                    paragraphs: ["Last activity: 2026-10-19 10:00:${String.format('%02d', fetches)}".toString(), "token ${1760000000000L + fetches}".toString()]]]],
                settings: [:], childApps: []])
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if ((body as Map).containsKey("settings[msg.1]")) posted = true
            [status: 200, location: null, data: '']
        }
        def applied = []
        def skipped = []

        when:
        script._rmWriteSettingOnPage(100, "doActPage", "msg.1", "hello", applied, null, skipped)

        then:
        posted
        applied == (echoes ? ["msg.1"] : [])
        skipped*.reason == (echoes ? [] : ["silent_rejection"])

        where:
        desc                    | echoes
        "the value renders"     | true
        "only the render moves" | false
    }

    def "a device picker write counts as landed when the stored {id: label} setting holds the written id"() {
        given: "the page never re-renders; only the settings carry the picker's devices"
        def stored = [:]
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            ruleConfigJson(100, "r", [[name: "relDevice_1", type: "capability.temperatureMeasurement", multiple: false]], null, stored)
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if ((body as Map).containsKey("settings[relDevice_1]")) stored.relDevice_1 = keeps ? ["9": "T2"] : ["8": "T1"]
            [status: 200, location: null, data: '']
        }
        def applied = []
        def skipped = []

        when:
        script._rmWriteSettingOnPage(100, "selectTriggers", "relDevice_1", "9", applied, null, skipped)

        then:
        applied == (keeps ? ["relDevice_1"] : [])
        skipped*.reason == (keeps ? [] : ["silent_rejection"])

        where:
        keeps << [true, false]
    }

    def "an action sub-type the picker does not offer is refused naming what RM offers"() {
        when:
        script._rmRequireOfferedActionSubType("hsm", "getSetHSM", 3,
            [[key: "actSubType.3", reason: "not_offered", value: "getSetHSM", offered: ["getSetMode", "getSetVariable"]]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("does not offer 'hsm' (getSetHSM)")
        ex.message.contains("This hub's picker offers: getSetMode, getSetVariable.")

        when: "any other skip is not a refusal"
        script._rmRequireOfferedActionSubType("hsm", "getSetHSM", 3, [[key: "actSubType.3", reason: "silent_rejection"], [key: "actType.3", reason: "not_offered", offered: []]])

        then:
        noExceptionThrown()
    }

    def "a catalog entry's requires is enforced: an inner list needs one of its fields"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> "[]" }

        when:
        script._rmMapActionSpec(100, [command: "on"], "runCommand", null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("requires one of deviceIds, useLastEventDevice")

        when:
        script._rmMapActionSpec(100, [deviceIds: [1]], "dimmer", "setLevel")

        then:
        def ex2 = thrown(IllegalArgumentException)
        ex2.message.contains("dimmer.setLevel requires one of level, levelVariable.")
    }

    @spock.lang.Unroll
    def "the add path writes direction '#direction' from its canonical value"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> "[]" }

        expect: "startRaiseLower's dimRL is true for lower"
        script._rmMapActionSpec(100, [deviceIds: [1], direction: direction], "dimmer", "startRaiseLower").fields["dimRL.@N"] == lower

        where:
        direction | lower
        "RAISE"   | false
        "raise"   | false
        "Lower"   | true
    }

    def "an hsm command is written in RM's spelling whatever its case"() {
        expect:
        script._rmMapActionSpec(100, [command: "DISARMALL"], "hsm", null).fields["alarm.@N"] == "disarmAll"
    }

    @spock.lang.Unroll
    def "a #cap trigger's state must be one Rule Machine lists (#label)"() {
        when:
        script._rmCheckCondSpec([capability: cap, state: state], "trigger", "addTrigger")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(text)

        where:
        label         | cap              | state      | text
        "no state"    | "HSM Alert"      | null       | "HSM Alert needs state (one of intrusion, intrusion-delay"
        "not listed"  | "HSM Alert"      | "burglary" | "HSM Alert state 'burglary' is not one Rule Machine lists"
        "misspelt"    | "Location Event" | "sunsets"  | "Location Event state 'sunsets' is not one Rule Machine lists"
    }

    def "a Location Event state in another case is accepted and written in RM's spelling"() {
        given:
        def spec = [capability: "Location Event", state: " SUNSET "]

        when:
        script._rmCheckCondSpec(spec, "trigger", "addTrigger")

        then:
        spec.state == "sunset"
    }

    def "a listed hub-state value passes the check"() {
        when:
        script._rmCheckCondSpec([capability: "Location Event", state: "sunset"], "trigger", "addTrigger")
        script._rmCheckCondSpec([capability: "HSM Alert", state: "intrusion-home"], "trigger", "addTrigger")

        then:
        noExceptionThrown()
    }

    def "an unlisted HSM Alert state is refused at round zero, with no hub read and no backup"() {
        when:
        def r = script._rmRoundZeroNativeEditRefusal([appId: 100, confirm: true, addTrigger: [capability: "HSM Alert", state: "burglary"]])

        then:
        r.success == false
        r.error.contains("HSM Alert state 'burglary' is not one Rule Machine lists")
        !r.backup
        hubGet.calls.isEmpty()
    }

    def "_rmCheckCondSpec reads no hub state for a compareToDevice reference"() {
        when:
        script._rmCheckCondSpec([capability: "Temperature", deviceIds: [8], comparator: ">", compareToDevice: [deviceId: 99]], "trigger", "addTrigger")

        then:
        noExceptionThrown()
        hubGet.calls.isEmpty()
    }

    @spock.lang.Unroll
    def "a missing compareToDevice reference device is refused by the #label prevalidation"() {
        given:
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"T1"}' }
        hubGet.register('/device/fullJson/99') { params -> throw new RuntimeException("404 Not Found") }

        def spec = [capability: "Temperature", deviceIds: [8], comparator: ">", compareToDevice: [deviceId: 99]]

        when:
        if (label == "condition") script._rmPrevalidateConditionList([conditions: [spec]], "addRequiredExpression")
        else script._rmPrevalidateTriggerSpecList([spec], "addTriggers")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(text)

        where:
        label       | text
        "condition" | "addRequiredExpression.conditions[0].compareToDevice.deviceId contains device ID '99' which does not exist"
        "trigger"   | "addTriggers[0]: addTrigger.compareToDevice.deviceId contains device ID '99' which does not exist"
    }

    def "_rmOutcomeKeys carries only the flags that are set"() {
        expect:
        script._rmOutcomeKeys(script._rmFail(new IllegalStateException("x"), [wizardStuck: true, fieldsWritten: false])) == [wizardStuck: true]
        script._rmOutcomeKeys(new IllegalStateException("x [wizardStuck -- text only]")) == [:]
        script._rmOutcomeKeys(null) == [:]
    }

    @spock.lang.Unroll
    def "a bulk stop is partial only when something landed: #label"() {
        when:
        def out = script._rmBulkStoppedResult(100, [backupKey: "k"], "addActions[0]", stopItem, extra + [health: [ok: true, success: true]])

        then:
        out.partial == partial

        where:
        label                                     | stopItem                                     | extra                                                                                                          | partial
        "nothing landed"                          | [success: false, error: "x"]                 | [actions: [[success: false, error: "x"], [success: false, notAttempted: true]]]                                | false
        "an earlier item was rolled back"         | [success: false, error: "x"]                 | [actions: [[success: true, rolledBack: true], [success: false, error: "x"]]]                                   | false
        "the health read does not count"          | [success: false, error: "x"]                 | [actions: []]                                                                                                  | false
        "a nested patch item applied"             | [success: false, error: "x"]                 | [patches: [[success: false, op: "addActions", results: [[success: true], [success: false, error: "x"]]]]]     | true
        "the stop item itself was partial"        | [success: true, partial: true]               | [:]                                                                                                            | true
        "a replace already cleared rows"          | [success: false, error: "x"]                 | [removedIndices: [1, 2], addedActions: [[success: false, error: "x"]]]                                         | true
        "the only item was a partial row rolled back" | [success: false, partial: true, rolledBack: true, error: "x"] | [actions: [[success: false, partial: true, rolledBack: true, error: "x"]]]                   | false
    }

    def "a bulk stop lifts wizardStuck and fieldsWritten from any depth of the stop item"() {
        when:
        def stuck = script._rmBulkStoppedResult(100, [backupKey: "k"], "patches[0]",
            [success: false, op: "addActions", results: [[success: true], [success: false, wizardStuck: true]]], [health: [ok: true]])
        def written = script._rmBulkStoppedResult(100, [backupKey: "k"], "patches[0]",
            [success: false, op: "replaceActions", results: [[actions: [[success: false, fieldsWritten: true]]]]], [health: [ok: true]])

        then:
        stuck.wizardStuck == true
        stuck.repairHints[0].contains("button='actionCancel', pageName='doActPage'")
        !stuck.containsKey("fieldsWritten")
        written.fieldsWritten == true
        !written.containsKey("wizardStuck")
        written.repairHints.size() == 1
    }

    def "a rolled-back action whose health re-read fails reports the earlier non-map read as stale"() {
        given:
        enableWrite()
        def posts = wireUnbakedSwitchAction(false)
        script.metaClass._rmCheckRuleHealth = { Integer id, String src = "auto", String activePage = null ->
            if (posts.any { it.body?.name == "actionCancel" }) throw new IllegalStateException("health down")
            null
        }

        when:
        def r = script._rmAddAction(100, [capability: "switch", action: "on", deviceIds: [8]])

        then:
        r.rolledBack == true
        r.fieldsWritten == null
        r.health.stale == true
        r.health.unreadable == true
        r.health.checkErrors == ["health down"]
    }

    def "an unbaked action whose rollback fails answers applied:null with fieldsWritten through hub_set_rule"() {
        given:
        enableWrite()
        wireUnbakedSwitchAction(true)

        when:
        def r = script.toolSetRule([appId: 100, addAction: [capability: "switch", action: "on", deviceIds: [8]], confirm: true])

        then:
        r.success == false
        r.rolledBack == false
        r.fieldsWritten == true
        r.applied == null
    }

    def "a baked action with one skipped field stays applied"() {
        given:
        enableWrite()
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: ''] }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["switchActs": "Switches"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> fetchSeq++; doActPageSchemaJson(100, fetchSeq) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPageJson(100, "On: S1") }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        when: "onlyIfChanged's optSwitch is not on this editor page"
        def r = script.toolSetRule([appId: 100, addAction: [capability: "switch", action: "on", deviceIds: [8], onlyIfChanged: true], confirm: true])

        then:
        r.success == true
        (r.settingsSkipped as List).any { it.key == "optSwitch.1" }
        r.applied == true
        !r.containsKey("fieldsWritten") || r.fieldsWritten == null
    }

    def "the served guide and settings text carry the backup and outcome wording"() {
        given:
        def guide = script.getToolGuideSections()['set_rule_reference'] as String
        def source = new File('hubitat-mcp-server.groovy').getText('UTF-8')

        expect:
        guide.contains("Deletes always take a fresh snapshot, as does a standalone Required Expression replacement while pre-edit backups are on.")
        !guide.contains("remain fresh regardless")
        guide.contains("`null` when a failure left fields written or an editor open, so read the target before retrying")
        guide.contains("`verified` (activated, and the readback and the health check confirmed it)")
        guide.contains("An action's `conditionalRequired` lists the fields each of its actions needs; an inner list means one of those fields.")
        source.contains("Deletes always take a fresh snapshot; a standalone Required Expression replacement does too while pre-edit backups are on, and turning them off also overrides Back up before every native app edit.")
        !source.contains("Deletes and a standalone Required Expression replacement still take a fresh snapshot.")
    }

    def "a modifyAction whose Done fails answers wizardStuck, fieldsWritten and applied:null through hub_set_rule"() {
        given:
        wireRunRuleDoneFails()

        when:
        def r = script.toolSetRule([appId: 100, modifyAction: [index: 1, mods: [ruleIds: [300]]], confirm: true])

        then:
        r.success == false
        r.wizardStuck == true
        r.fieldsWritten == true
        r.applied == null
        r.restoreHint.contains("button='actionDone' for an editor modifyAction opened on a committed action")
    }

    def "a modifyAction patch whose Done fails stops the batch with the same flags"() {
        given:
        wireRunRuleDoneFails()

        when:
        def r = script.toolSetRule([appId: 100, patches: [[modifyAction: [index: 1, mods: [ruleIds: [300]]]]], confirm: true])

        then:
        r.success == false
        r.bulkStoppedAfter == "patches[0]"
        r.patches[0].op == "modifyAction"
        r.wizardStuck == true
        r.fieldsWritten == true
        r.applied == null
        r.repairHints[0].contains("button='actionDone' for one modifyAction opened on a committed action")
    }

    def "an addTrigger whose cancel fails answers wizardStuck and applied:null through hub_set_rule"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        wireTriggerWizard(temperatureTriggerInputs(false))
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (body?.name == "cancelCapab") throw new IllegalStateException("btn 500")
            [status: 200, location: null, data: '']
        }

        when:
        def r = script.toolSetRule([appId: 100, confirm: true,
            addTrigger: [capability: "Temperature", deviceIds: [8], comparator: ">", compareToDevice: [deviceId: 9]]])

        then:
        r.success == false
        r.error.contains("[wizardStuck -- cancelCapab cleanup failed: btn 500")
        r.wizardStuck == true
        r.applied == null
    }

    def "a runCommand edit stopped after its capability landed names the written fields and answers applied:null"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def ma = wireModifyEditorTransport(100, [1], ["actType.1": "modeActs", "actSubType.1": "getDefinedAction", "myCapab.1": "Switch", "devices.1": ["8"], "cCmd.1": "on"], runCommandEditor(1))
        def mods = [capabilityFilter: "SwitchLevel", command: "setLevel"]

        when:
        script._rmModifyAction(100, 1, mods)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("'setLevel' is not a command of the selected device(s) on action 1")
        ex.message.contains("Fields already written before the stop: myCapab.1")
        script._rmOutcomeFlags(ex).fieldsWritten == true
        !script._rmOutcomeFlags(ex).wizardStuck
        (ma.writes as List).find { it.key == "myCapab.1" }?.value == "SwitchLevel"
        !(ma.writes as List).any { it.key == "cCmd.1" }

        when:
        def r = script.toolSetRule([appId: 100, modifyAction: [index: 1, mods: mods], confirm: true])

        then:
        r.success == false
        r.fieldsWritten == true
        r.wizardStuck == false
        r.applied == null
    }

    def "modifyAction reads an emptied device picker back as a mismatch"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 9, label: "B", capabilities: ["Switch"]]]) }
        hubGet.register('/device/fullJson/9') { params -> '{"id":"9","name":"B"}' }
        def settings = ["actType.1": "switchActs", "actSubType.1": "getOnOffSwitch", "onOffSwitch.1": ["8"], "onOff.1": "true"]
        def ma = wireModifyEditorTransport(100, [1], settings,
            [[name: "onOffSwitch.1", type: "capability.switch", multiple: true], [name: "onOff.1", type: "bool"], [name: "actionDone", type: "button"]]) { String path, Map body ->
            if (body?.containsKey("settings[onOffSwitch.1]")) settings["onOffSwitch.1"] = []
        }
        ma.applyWrites = false

        when:
        def r = script._rmModifyAction(100, 1, [deviceIds: [9]])

        then:
        r.success == false
        r.readbackMismatches == ["onOffSwitch.1"]
        r.error.contains("onOffSwitch.1 did not read back as written")
    }

    def "a false Boolean does not meet a one-of on the add path; a true one does"() {
        given:
        hubGet.register('/hub2/vrb/devices') { params -> "[]" }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, [[name: "tDev1", value: "[8]", deviceIdsForDeviceList: ["8"]]]) }

        when:
        script._rmMapActionSpec(100, [command: "on", useLastEventDevice: false], "runCommand", null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("requires one of deviceIds, useLastEventDevice")

        when:
        def mapped = script._rmMapActionSpec(100, [command: "on", useLastEventDevice: true], "runCommand", null)

        then:
        mapped.actSubType == "getDefinedAction"
        mapped.fields == ["useLastDev.@N": true]
    }

    def "_rmFail inherits its cause's flags, and its own flags win"() {
        given:
        def inner = script._rmFail(new IllegalStateException("inner"), [fieldsWritten: true, rolledBack: true])

        expect:
        script._rmOutcomeFlags(script._rmFail(new IllegalStateException("outer", inner), [wizardStuck: true])) == [fieldsWritten: true, rolledBack: true, wizardStuck: true]
        script._rmOutcomeFlags(script._rmFail(new IllegalStateException("outer", inner), [rolledBack: false])).rolledBack == false
        script._rmOutcomeFlags(new IllegalStateException("plain rewrap", inner)).isEmpty()
    }

    @Unroll
    def "an add names the checks it could not run in checksSkipped"() {
        given:
        enableWrite()
        def fetchSeq = 0
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: ''] }
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            ruleConfigJson(100, "r", [[name: "actType.1", type: "enum", options: ["switchActs": "Switches"]]])
        }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> fetchSeq++; doActPageSchemaJson(100, fetchSeq) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> bakedMainPageJson(100, "On: S1") }
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([installedApp: [id: 100], appSettings: [], eventSubscriptions: [[name: "evt1"]], scheduledJobs: [],
                               appState: listed ? [[name: "actionList", value: ["1"]]] : [], childAppCount: 0, childDeviceCount: 0])
        }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        hubGet.register('/hub2/vrb/devices') { params ->
            if (!devicesReadable) throw new IllegalStateException("502 Bad Gateway")
            JsonOutput.toJson([[id: 8, label: "S1", capabilities: ["Switch"]]])
        }

        when:
        def r = script.toolSetRule([appId: 100, addAction: [capability: "switch", action: "on", deviceIds: [8]], confirm: true])

        then:
        r.success == true
        r.checksSkipped == expected

        where:
        label                  | devicesReadable | listed || expected
        "every read worked"    | true            | true   || null
        "no device list"       | false           | true   || ["device capabilities (the device list could not be read)"]
        "no actionList"        | true            | false  || ["action baked (the rule's state carries no actionList)"]
    }

    def "a modifyAction whose hub variables cannot be read names the skipped variable check"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.getAllGlobalVars = { -> throw new IllegalStateException("hub busy") }
        wireModifyEditorTransport(100, [1], ["actType.1": "modeActs", "actSubType.1": "getSetVariable", "xVarV.1": "counter"],
            [[name: "xVarV.1", type: "enum"], [name: "actionDone", type: "button"]])

        when:
        def r = script.toolSetRule([appId: 100, modifyAction: [index: 1, mods: [variable: "other"]], confirm: true])

        then:
        r.success == true
        r.checksSkipped == ["variable name (the hub variable list could not be read)"]
    }

    @Unroll
    def "creating a thermostat app goes under its parent and reports that the name has not landed yet"() {
        given:
        enableWrite()
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: 61, name: parent, type: parent, user: false, hidden: false], children: []]]])
        }
        hubGet.register('/installedapp/configure/json/790') { params ->
            JsonOutput.toJson([app: [id: 790, name: appName, label: appName, installed: true, appType: [name: appName, namespace: "hubitat"]],
                configPage: [name: "mainPage", error: null, sections: [[title: "", input: [[name: "origLabel", type: "text"], [name: "therm", type: "capability.thermostat"], [name: "update", type: "button"]]]]],
                settings: [:], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/790') { params -> statusJson(790) }
        def createCalls = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            createCalls << path; [status: 302, location: "/installedapp/configure/790", data: ""]
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]; [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolSetNativeApp([appType: appType, name: "Upstairs", confirm: true])

        then:
        createCalls.contains("/installedapp/createchild/hubitat/${appName}/parent/61".toString())
        result.appId == 790
        result.appType == appType
        result.parentAppId == 61
        result.labelApplied == false
        result.note.contains("NOTE: the requested name could not be verified as the app's display label")
        posts.any { it.path == "/installedapp/update/json" && it.body["settings[origLabel]"] == "Upstairs" }
        posts.any { it.path == "/installedapp/btn" && it.body?.name == "update" }
        !posts.any { it.path == "/installedapp/btn" && it.body?.name == "updateRule" }

        where:
        appType                 | parent                   | appName
        "thermostat_scheduler"  | "Thermostat Schedulers"  | "Thermostat Scheduler 2.0"
        "thermostat_controller" | "Thermostat Controllers" | "Thermostat Controller 2.0"
    }

    def "the backup toggles read the request's own view before the stored setting"() {
        given: "the stored toggles say backups on and strict off"
        settingsMap.backupBeforeRuleEdits = true
        settingsMap.backupEveryRuleWrite = false

        expect: "without a request view the stored values decide"
        !script._rmPreEditBackupsOff()
        !script._rmStrictBackupsOn()

        when: "a detached write carries the request's view"
        script._keepRequestSettings([backupBeforeRuleEdits: "false", backupEveryRuleWrite: "true"])

        then:
        script._rmPreEditBackupsOff()
        script._rmStrictBackupsOn()

        when: "the stored value is text from a form save"
        script._keepRequestSettings(null)
        settingsMap.backupBeforeRuleEdits = "false"

        then:
        script._rmPreEditBackupsOff()
    }

    // RM's actions page with one doAct edit button per name, as its row renders it.
    private static String selectActionsPage(int ruleId, List rowButtons) {
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true, appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "selectActions", title: "T", install: false, error: null, sections: [[title: "", input: [],
                body: rowButtons.collect { [description: "<input type='hidden' name='${it}.type' value='button'><span class='submitOnChange' data-stateAttribute='doAct'>IF (Switch on) Run Actions</span>".toString()] }]]],
            settings: [:], childApps: []
        ])
    }

    // statusJson for rule 100 with the given app state, its settings read live as the modify transport's are.
    private void committedStatus(Map settings, List appState) {
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([installedApp: [id: 100],
                               appSettings: settings.collect { k, v -> [name: k.toString(), value: v] + (v instanceof List ? [deviceIdsForDeviceList: (v as List).collect { it?.toString() }] : [:]) },
                               eventSubscriptions: [[name: "evt1"]], scheduledJobs: [], appState: appState, childAppCount: 0, childDeviceCount: 0])
        }
    }
}
