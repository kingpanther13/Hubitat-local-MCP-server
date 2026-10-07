package support

import groovy.json.JsonOutput

/**
 * Stateful stand-in for one RM 5.1 rule's Required Expression pages (live RM 5.1.8 wire format,
 * fw 2.5.2.129). Modes: committed, edit (after editST), token (after editToken; insertTok /
 * deleteToken clicks named by token index), insert (the newToken0 picker), cond (a condition
 * form, opened by `*` or cond=a), noRE. A cancelled `*` condition leaves a blank broken token; a
 * cancelCapab click without its stateAttribute leaves the cancel pending, so it kills the next
 * condition's hasAll, as on the live hub. Clicks apply immediately (production renders after each).
 */
class FakeRmExpressionEditor {
    Integer appId = 100
    List tokens = []
    Map<Integer, Map> conds = [:]
    Map settings = [useST: "true"]
    String mode = "committed"
    Integer insertPos = null
    Integer condIdx = null
    boolean condFromToken = false
    int nextSlot = 1
    List posts = []
    // Test knobs.
    boolean editorOpens = true
    Closure onUpdateRule = null      // return a status (>=400 rejects the click)
    Closure onClick = null           // observes every btn click: (name, stateAttribute)
    Set<Integer> deleteConIgnored = [] as Set
    boolean pendingInsert = false    // live RM: an expression built via cond=a reopens the token editor with an insert pending
    boolean conditionsOpened = false // Manage Conditions opened from STPage with pred:true (live RM ignores deleteCon otherwise)
    String mainExtra = null          // extra mainPage paragraph (e.g. a **Broken Action** marker)
    boolean cancelPending = false    // a bare cancelCapab click left state.cancelCapab set
    private HubInternalGetMock hubGet

    FakeRmExpressionEditor seedSwitch(Integer id, String state, Integer dev = 8) {
        conds[id] = [cap: "Switch", dev: dev, state: state, text: "Switch ${dev} is ${state}".toString()]
        settings["rCapab_${id}".toString()] = "Switch"
        settings["rDev_${id}".toString()] = [(dev.toString()): "S${dev}".toString()]
        settings["state_${id}".toString()] = state
        nextSlot = Math.max(nextSlot, id + 1)
        registerDevice(dev)
        return this
    }

    private void registerDevice(Integer dev) {
        hubGet?.register("/device/fullJson/${dev}".toString()) { params -> '{"device":{"id":"' + dev + '","label":"S' + dev + '"},"id":"' + dev + '","name":"S' + dev + '"}' }
    }

    FakeRmExpressionEditor seedMode(Integer id, List modeIds) {
        conds[id] = [cap: "Mode", modes: modeIds, text: "Mode is ${modeIds.join(',')}".toString()]
        settings["rCapab_${id}".toString()] = "Mode"
        settings["modes${id}".toString()] = modeIds
        nextSlot = Math.max(nextSlot, id + 1)
        return this
    }

    FakeRmExpressionEditor withTokens(List t) { tokens = new ArrayList(t); mode = t ? "committed" : "noRE"; return this }

    List renderedExpression() {
        tokens.collect { it instanceof Integer ? (conds[it]?.text ?: "**Broken Condition**") : it }
    }

    // ---- wiring ---------------------------------------------------------------------------

    void install(def script, HubInternalGetMock hubGet) {
        def fake = this
        this.hubGet = hubGet
        hubGet.register("/installedapp/configure/json/${appId}/STPage".toString()) { params -> fake.stPageJson() }
        hubGet.register("/installedapp/configure/json/${appId}/selectConditions".toString()) { params -> fake.pageJson("selectConditions", [], [:]) }
        hubGet.register("/installedapp/configure/json/${appId}/mainPage".toString()) { params -> fake.mainPageJson() }
        hubGet.register("/installedapp/configure/json/${appId}".toString()) { params -> fake.mainPageJson() }
        hubGet.register("/installedapp/configure/json/${appId}/selectActions".toString()) { params -> fake.pageJson("selectActions", [[name: "N", type: "button"]], [:]) }
        hubGet.register("/installedapp/configure/json/${appId}/doActPage".toString()) { params ->
            fake.pageJson("doActPage", [[name: "actType.1", type: "enum", options: ["condActs": "Conditional Actions"]],
                                        [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
                                        [name: "actionCancel", type: "button"]], [:])
        }
        hubGet.register("/app/ruleBuilderJson/${appId}".toString()) { params -> fake.ruleBuilderJson() }
        hubGet.register("/installedapp/statusJson/${appId}".toString()) { params -> fake.statusJson() }
        ([8] + conds.values().findAll { it.dev != null }.collect { it.dev as Integer }).unique().each { registerDevice(it) }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            fake.posts << [path: path, body: body]
            return fake.handlePost(path, body)
        }
    }

    List clicks() { posts.findAll { it.path == "/installedapp/btn" }.collect { it.body.name?.toString() } }
    List clickAttrs() { posts.findAll { it.path == "/installedapp/btn" }.collect { "${it.body.name}/${it.body.stateAttribute}".toString() } }

    // ---- POST handling --------------------------------------------------------------------

    Map handlePost(String path, Map body) {
        if (path == "/installedapp/btn") return click(body.name?.toString(), body.stateAttribute?.toString())
        if (path == "/installedapp/update/json") {
            if (body.any { k, v -> k.toString().startsWith("params_for_action_href_name|selectConditions|") && v.toString().contains('"pred":true') }) {
                conditionsOpened = true
            }
            body.each { k, v ->
                def m = (k.toString() =~ /^settings\[(.+)\]$/)
                if (m.matches()) write((m[0] as List)[1].toString(), v)
            }
        }
        return [status: 200, location: null, data: '']
    }

    Map click(String name, String attr) {
        onClick?.call(name, attr)
        if (name == "cancelCapab" && attr == null && mode == "cond") cancelPending = true
        if (name in ["editST", "doneST", "cancelST"]) conditionsOpened = false
        switch (attr ?: name) {
            case "editST": if (mode == "committed" && editorOpens) mode = "edit"; break
            case "editToken":
                if (mode == "edit") mode = "token"
                if (mode == "token" && pendingInsert) { insertPos = tokens.size(); mode = "insert"; pendingInsert = false }
                break
            case "doneToken": if (mode == "token") mode = "edit"; break
            case "doneST": if (mode in ["edit", "token", "committed", "sealed"]) mode = tokens ? "committed" : "noRE"; break
            case "cancelST": tokens = []; mode = "noRE"; break
            case "insertTok": if (mode == "token") { insertPos = name as Integer; mode = "insert" }; break
            case "deleteToken": def i = name as Integer; if (mode == "token" && i < tokens.size()) tokens.remove(i); break
            case "cancelInsert": if (mode == "insert") mode = "token"; break
            case "deleteCon":
                def id = name as Integer
                if (conditionsOpened && !deleteConIgnored.contains(id)) {
                    conds.remove(id)
                    settings.keySet().removeAll { it ==~ /^(rCapab_|rDev_|state_|not)${id}$|^modes${id}$/ }
                }
                break
            case "hasAll":
                if (cancelPending) { cancelPending = false; finishCondition(true) }
                else if (condComplete()) finishCondition(false)
                break
            case "cancelCapab": finishCondition(true); break
            case "updateRule":
                def st = onUpdateRule?.call()
                if (st instanceof Integer) return [status: st, location: null, data: '']
                break
        }
        return [status: 200, location: null, data: '']
    }

    // Picker values arrive as the wire encodes them: a device picker as CSV ("8"), a
    // multi-select enum as a JSON array ('["4"]'), or a seeded List / {id: label} Map.
    static List ids(Object v) {
        if (v == null) return []
        if (v instanceof Map) return (v as Map).keySet().collect { it.toString() }
        if (v instanceof Collection) return (v as Collection).collect { it.toString() }
        def s = v.toString().trim()
        if (s.startsWith("[")) return (new groovy.json.JsonSlurper().parseText(s) as List).collect { it.toString() }
        return s ? s.split(",").collect { it.trim() } : []
    }

    boolean condComplete() {
        def cap = settings["rCapab_${condIdx}".toString()]
        if (cap == "Mode") return settings["modes${condIdx}".toString()] != null
        return cap != null && settings["state_${condIdx}".toString()] != null
    }

    void finishCondition(boolean cancelled) {
        if (mode != "cond" || condIdx == null) return
        def cap = settings["rCapab_${condIdx}".toString()]
        if (cancelled) {
            conds[condIdx] = [cap: cap, broken: true, text: "**Broken Condition**"]
        } else if (cap == "Mode") {
            def m = ids(settings["modes${condIdx}".toString()])
            conds[condIdx] = [cap: "Mode", modes: m, text: "Mode is ${m.join(',')}".toString()]
        } else {
            def devId = ids(settings["rDev_${condIdx}".toString()])[0]
            def st = settings["state_${condIdx}".toString()]
            conds[condIdx] = [cap: cap, dev: devId, state: st, text: "${cap} ${devId} is ${st}".toString()]
        }
        if (condFromToken) {
            // RM leaves a cancelled token-condition in the expression as a blank broken token.
            tokens.add(insertPos, condIdx)
            mode = "token"
        } else {
            if (!cancelled) tokens << condIdx
            mode = cancelled ? "noRE" : "built"
        }
        condIdx = null
    }

    // RM opens a new condition past every slot that still has settings (a deleted condition's
    // settings linger, and a settings replay can write them back).
    int allocateSlot() {
        int top = settings.keySet().collect { k -> def m = (k =~ /^rCapab_(\d+)$/); m.matches() ? ((m[0] as List)[1] as Integer) : 0 }.max() ?: 0
        nextSlot = Math.max(nextSlot, top + 1)
        return nextSlot++
    }

    void write(String key, Object value) {
        if (key == "newToken0" && mode == "insert") {
            if (value == "*") {
                condIdx = allocateSlot()
                condFromToken = true
                mode = "cond"
            } else {
                def v = value.toString()
                tokens.add(insertPos, v.isInteger() ? (v as Integer) : v)
                mode = "token"
            }
            return
        }
        if (key == "cond" && value == "a" && mode == "noRE") {
            condIdx = allocateSlot()
            condFromToken = false
            mode = "cond"
            return
        }
        if (key == "hasRule" && mode == "built") { mode = "sealed"; return }
        if (key.endsWith(".type") || key.endsWith(".multiple")) return
        settings[key] = value
    }

    // ---- rendering ------------------------------------------------------------------------

    List stInputs() {
        switch (mode) {
            case "committed": return [[name: "cancelST", type: "button"], [name: "editST", type: "button"],
                                      [name: "stopOnST", type: "bool"], [name: "evalOnBoot", type: "bool"], [name: "doneST", type: "button"]]
            case "edit": return [[name: "oper", type: "enum", options: ["AND", "OR", "XOR"]], [name: "eraseRule", type: "button"],
                                 [name: "editToken", type: "button"], [name: "hasRule", type: "button"], [name: "doneST", type: "button"]]
            case "token": return [[name: "doneToken", type: "button"], [name: "doneST", type: "button"]]
            case "insert":
                def opts = ["(": "(", ")": ")", "NOT": "NOT", "AND": "AND", "OR": "OR", "XOR": "XOR", "*": "--> New Condition"]
                conds.each { id, c -> if (!c.broken) opts[id.toString()] = c.text }
                return [[name: "newToken0", type: "enum", options: opts], [name: "cancelInsert", type: "button"], [name: "doneST", type: "button"]]
            case "noRE": return [[name: "cond", type: "enum", options: ["a": "New condition", "b": "( sub-expression"]], [name: "doneST", type: "button"]]
            case "built": return [[name: "oper", type: "enum", options: ["AND", "OR", "XOR"]], [name: "hasRule", type: "button"], [name: "doneST", type: "button"]]
            case "sealed": return [[name: "doneST", type: "button"]]
            case "cond":
                def n = condIdx
                def ins = [[name: "rCapab_${n}".toString(), type: "enum", options: ["Switch", "Mode", "Motion"], value: settings["rCapab_${n}".toString()]]]
                def cap = settings["rCapab_${n}".toString()]
                if (cap == "Mode") {
                    ins << [name: "modes${n}".toString(), type: "enum", multiple: true,
                            options: ["1": "Day", "2": "Evening", "3": "Night", "4": "Away"], value: settings["modes${n}".toString()]]
                } else if (cap != null) {
                    ins << [name: "rDev_${n}".toString(), type: "capability.switch", multiple: true, value: settings["rDev_${n}".toString()]]
                    if (settings["rDev_${n}".toString()] != null) {
                        ins << [name: "state_${n}".toString(), type: "enum", options: ["on", "off"], value: settings["state_${n}".toString()]]
                    }
                }
                boolean complete = condComplete()
                if (cap != null) ins << [name: "not${n}".toString(), type: "bool"]
                ins << [name: "cancelCapab", type: "button"]
                if (complete) ins << [name: "hasAll", type: "button"]
                if (condFromToken) ins << [name: "cancelInsert", type: "button"]
                ins << [name: "doneST", type: "button"]
                return ins
        }
        return [[name: "doneST", type: "button"]]
    }

    String stPageJson() {
        def para = "Expression: ${renderedExpression().join(' ')}".toString()
        pageJson("STPage", stInputs(), [paragraphs: [para]])
    }

    String mainPageJson() {
        def text = tokens ? renderedExpression().join(' ') : "Define Required Expression"
        def label = tokens.any { it instanceof Integer && conds[it]?.broken } ? "r *BROKEN*" : "r"
        JsonOutput.toJson([
            app: [id: appId, name: "Rule-5.1", label: label, trueLabel: label, installed: true, version: "7",
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                         sections: [[title: "", input: [[name: "useST", type: "bool"]],
                                     body: [[element: "paragraph", description: text]] +
                                           (mainExtra ? [[element: "paragraph", description: mainExtra]] : [])]]],
            settings: [useST: settings.useST], childApps: []
        ])
    }

    String pageJson(String pageName, List inputs, Map extra) {
        def section = [title: "", input: inputs]
        if (extra.paragraphs) section.paragraphs = extra.paragraphs
        JsonOutput.toJson([
            app: [id: appId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true, version: "7",
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: pageName, title: pageName, install: false, error: null, sections: [section]],
            settings: [:], childApps: []
        ])
    }

    String ruleBuilderJson() {
        def texts = [:]
        conds.each { id, c -> texts[id.toString()] = c.text }
        JsonOutput.toJson([broken: false, hasPredicate: !tokens.isEmpty(), predCapabs: tokens.findAll { it instanceof Integer },
                           eval: tokens ? ["0": tokens] : [:], capabsfalse: texts, actionList: []])
    }

    String statusJson() {
        def appSettings = settings.collect { k, v ->
            def rec = [name: k, value: v, type: (v instanceof Map ? "capability.switch" : (v instanceof List ? "enum" : "text"))]
            if (v instanceof Map) rec.deviceIdsForDeviceList = (v as Map).keySet().collect { it.toString() }
            if (v instanceof Map || v instanceof List) rec.multiple = true
            rec
        }
        def texts = [:]
        conds.each { id, c -> texts[id.toString()] = c.text }
        JsonOutput.toJson([installedApp: [id: appId], appSettings: appSettings, eventSubscriptions: [[name: "evt1"]],
                           scheduledJobs: [], childAppCount: 0, childDeviceCount: 0,
                           appState: [[name: "eval", value: tokens ? ["0": tokens] : [:]], [name: "capabsfalse", value: texts]]])
    }
}
