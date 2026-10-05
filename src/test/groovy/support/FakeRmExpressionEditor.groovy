package support

import groovy.json.JsonOutput

/**
 * Stateful stand-in for one Rule Machine 5.1 rule's Required Expression pages, modelled on the
 * live RM 5.1.8 wire format (captured from the UI on fw 2.5.2.129):
 *
 *  - committed : STPage shows cancelST / editST / stopOnST / evalOnBoot / doneST.
 *  - edit      : after editST -- oper / eraseRule / editToken / hasRule / doneST.
 *  - token     : after editToken -- the token editor (doneToken / doneST); its buttons are
 *                /installedapp/btn clicks named by token index with stateAttribute insertTok
 *                (insert at that index) or deleteToken.
 *  - insert    : after insertTok -- the newToken0 picker (operators, parens, `*`, condition ids).
 *  - cond      : a condition form (rCapab_<N> -> rDev_<N> -> state_<N> -> hasAll, or
 *                rCapab_<N> -> modes<N> -> hasAll), opened by newToken0=`*` or by cond=a.
 *  - noRE      : no committed expression -- the new-expression selector (cond / doneST).
 *
 * Cancelling a condition opened from a `*` token leaves a blank broken token and a broken
 * condition, as RM does. ruleBuilderJson serves eval['0'] (the token list) and the condition
 * texts; statusJson serves the settings. Clicks apply immediately (the live hub applies them on
 * the next render; every production path renders after a click).
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
    String mainExtra = null          // extra mainPage paragraph (e.g. a **Broken Action** marker)

    FakeRmExpressionEditor seedSwitch(Integer id, String state, Integer dev = 8) {
        conds[id] = [cap: "Switch", dev: dev, state: state, text: "Switch ${dev} is ${state}".toString()]
        settings["rCapab_${id}".toString()] = "Switch"
        settings["rDev_${id}".toString()] = [(dev.toString()): "S${dev}".toString()]
        settings["state_${id}".toString()] = state
        nextSlot = Math.max(nextSlot, id + 1)
        return this
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
        hubGet.register('/device/fullJson/8') { params -> '{"device":{"id":"8","label":"S8"},"id":"8","name":"S8"}' }
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
            body.each { k, v ->
                def m = (k.toString() =~ /^settings\[(.+)\]$/)
                if (m.matches()) write((m[0] as List)[1].toString(), v)
            }
        }
        return [status: 200, location: null, data: '']
    }

    Map click(String name, String attr) {
        onClick?.call(name, attr)
        switch (attr ?: name) {
            case "editST": if (mode == "committed" && editorOpens) mode = "edit"; break
            case "editToken": if (mode == "edit") mode = "token"; break
            case "doneToken": if (mode == "token") mode = "edit"; break
            case "doneST": if (mode in ["edit", "token", "committed", "sealed"]) mode = tokens ? "committed" : "noRE"; break
            case "cancelST": tokens = []; mode = "noRE"; break
            case "insertTok": if (mode == "token") { insertPos = name as Integer; mode = "insert" }; break
            case "deleteToken": def i = name as Integer; if (i < tokens.size()) tokens.remove(i); break
            case "cancelInsert": if (mode == "insert") mode = "token"; break
            case "deleteCon":
                def id = name as Integer
                if (!deleteConIgnored.contains(id)) {
                    conds.remove(id)
                    settings.keySet().removeAll { it ==~ /^(rCapab_|rDev_|state_|not)${id}$|^modes${id}$/ }
                }
                break
            case "hasAll": finishCondition(false); break
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
                boolean complete = false
                if (cap == "Mode") {
                    ins << [name: "modes${n}".toString(), type: "enum", multiple: true,
                            options: ["1": "Day", "2": "Evening", "3": "Night", "4": "Away"], value: settings["modes${n}".toString()]]
                    complete = settings["modes${n}".toString()] != null
                } else if (cap != null) {
                    ins << [name: "rDev_${n}".toString(), type: "capability.switch", multiple: true, value: settings["rDev_${n}".toString()]]
                    if (settings["rDev_${n}".toString()] != null) {
                        ins << [name: "state_${n}".toString(), type: "enum", options: ["on", "off"], value: settings["state_${n}".toString()]]
                        complete = settings["state_${n}".toString()] != null
                    }
                }
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
