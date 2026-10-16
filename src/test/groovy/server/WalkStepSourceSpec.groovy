package server

import support.ToolSpecBase
import groovy.json.JsonOutput

/**
 * walkStep checked against Rule Machine's own page code: a click must name a button the page
 * renders, every write goes through the schema writer (so an absent field or a value the picker
 * does not list is refused unsent), and a page RM builds only inside an open editor is refused or
 * explained from the walker's prerequisite table.
 */
class WalkStepSourceSpec extends ToolSpecBase {

    private void enableWrite() {
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass._rmStructuralBaseline = { Integer id -> [] }
        // No deferred predCapabs clear from another spec: it would open an action editor first.
        script.metaClass._rmPendingPredClearSnapshot = { -> [:] }
        script.metaClass._rmCheckRuleHealth = { Integer id, String source = "auto", String activePage = null ->
            [ok: true, unreadable: false, broken: false, brokenMarkers: [], orphanedActionRows: [], structuralIssues: [],
             validationErrors: [], multipleFlagPoison: [], issues: [], configPageError: null]
        }
    }

    private static String page(String name, List inputs, List body = [], Integer version = null) {
        def app = [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                   appType: [name: "Rule-5.1", namespace: "hubitat"]]
        if (version != null) app.version = version
        JsonOutput.toJson([app: app, configPage: [name: name, title: "", install: false, error: null,
                                                  sections: [[title: "", input: inputs, body: body]]],
                           settings: [:], childApps: []])
    }

    private static String status(List appState = null, List appSettings = []) {
        JsonOutput.toJson([installedApp: [id: 100], appSettings: appSettings, eventSubscriptions: [],
                           scheduledJobs: [], appState: appState == null ? [:] : appState,
                           childAppCount: 0, childDeviceCount: 0])
    }

    // Verbatim selectActions row (fw 2.5.2 / RM 5.1): three buttonLinks, each a hidden <name>.type
    // input followed by its clickable element; only the edit link carries a data-stateAttribute.
    private static final String RM_ACTION_ROW =
        "<tr><th><div class='form-group'><input type='hidden' name='chkBox1.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' style='color:purple;cursor:pointer;font-size:15px' title='Select for Copy Action'><i class='he-checkbox-unchecked'></i></div></div><input type='hidden' name='settings[chkBox1]' value=''></th>" +
        "<td style='text-align:left;min-width:250px' title='Edit Action'><div class='form-group'><input type='hidden' name='1.0.false.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' data-stateAttribute='doAct' style='color:purple;cursor:pointer;font-size:15px'>Log: 'one'</div></div><input type='hidden' name='settings[1.0.false]' value='' /></td>" +
        "<td><div class='form-group'><input type='hidden' name='disable1.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' style='color:purple;cursor:pointer;font-size:15px' title='Disable Action'><i class='he-checkbox-unchecked'></i></div></div><input type='hidden' name='settings[disable1]' value=''></td></tr>"

    // RM's buttonLink markup for the trigger page's "Create New Trigger" link (name true, moreCond).
    private static final String RM_NEW_TRIGGER_LINK =
        "<div class='form-group'><input type='hidden' name='true.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' data-stateAttribute='moreCond' style='color:#007009;cursor:pointer;font-size:15px' title='Create New Trigger'><b> &#65291</b></div></div><input type='hidden' name='settings[true]' value=''>"

    // ---------- the rendered-button reader ----------

    def "rendered buttons come from button inputs and from RM buttonLinks with their data-stateAttribute"() {
        given:
        def configPage = [sections: [[
            input: [[name: "hasAll", type: "button"], [name: "editST", type: "button", state: "editST"], [name: "tCapab1", type: "enum"]],
            body: [[element: "paragraph", description: "<table>" + RM_ACTION_ROW + "</table>"]]
        ]]]

        expect:
        script._rmRenderedButtons(configPage) == [
            [name: "hasAll"], [name: "editST", stateAttribute: "editST"],
            [name: "chkBox1"], [name: "1.0.false", stateAttribute: "doAct"], [name: "disable1"]]
    }

    // ---------- clicks ----------

    def "walkStep click on a button the page does not render is refused unsent, naming the rendered buttons"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> page("mainPage", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            page("selectTriggers", [[name: "hasAll", type: "button"]], [[description: RM_NEW_TRIGGER_LINK]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> status() }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> posts << path; [status: 200, data: ''] }

        when:
        def result = script.toolSetRule([appId: 100, confirm: true,
            walkStep: [page: "selectTriggers", operation: "click", click: [name: "moreCond"]]])

        then:
        result.success == false
        result.error.contains("Button 'moreCond' is not rendered on page 'selectTriggers'")
        result.error.contains('{"name":"hasAll"}')
        result.error.contains('{"name":"true","stateAttribute":"moreCond"}')
        result.restoreHint.contains("nothing needs to be restored")
        !posts.contains("/installedapp/btn")
    }

    def "walkStep click on a rendered buttonLink is posted with the caller's stateAttribute"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> page("mainPage", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params ->
            page("selectActions", [], [[description: "<table>" + RM_ACTION_ROW + "</table>"]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> status() }
        def clicks = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn") clicks << [name: body.name, stateAttribute: body.stateAttribute]
            [status: 200, data: '']
        }

        when:
        def result = script.toolSetRule([appId: 100, confirm: true,
            walkStep: [page: "selectActions", operation: "click", click: [name: "1.0.false", stateAttribute: "doAct"]]])

        then:
        result.success == true
        clicks == [[name: "1.0.false", stateAttribute: "doAct"]]
    }

    def "a raw button click with pageName is checked against that page; without pageName it is not"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> page("mainPage", [[name: "updateRule", type: "button"]]) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> page("mainPage", [[name: "updateRule", type: "button"]]) }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> page("doActPage", [[name: "actionDone", type: "button"]]) }
        hubGet.register('/installedapp/statusJson/100') { params -> status() }
        def clicks = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (path == "/installedapp/btn") clicks << body.name
            [status: 200, data: '']
        }

        when:
        def refused = script.toolSetRule([appId: 100, confirm: true, button: "hasAll", pageName: "doActPage"])
        def pageCheckedReads = hubGet.calls.count { it.path == '/installedapp/configure/json/100/doActPage' }
        def clicked = script.toolSetRule([appId: 100, confirm: true, button: "actionDone", pageName: "doActPage"])

        then:
        refused.success == false
        refused.error.contains("Button 'hasAll' is not rendered on page 'doActPage'")
        refused.error.contains('{"name":"actionDone"}')
        refused.restoreHint.contains("nothing needs to be restored")
        !clicks.contains("hasAll")
        clicks.contains("actionDone")

        and: "the check reads the page once, the read the click takes for its version token"
        pageCheckedReads == 1
    }

    // ---------- one write path ----------

    def "a walkStep write of a value the picker does not list is refused unsent on every page kind"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> page("mainPage", [[name: "logging", type: "enum", options: ["None", "Events"]]]) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> page("mainPage", [[name: "logging", type: "enum", options: ["None", "Events"]]]) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            page("selectTriggers", [[name: "tCapab1", type: "enum", options: [["Switch": "Switch"]]]], [], 7)
        }
        hubGet.register('/installedapp/configure/json/100/periodic') { params ->
            page("periodic", [[name: "whichPeriod1", type: "enum", options: ["Hourly"]]], [], 7)
        }
        hubGet.register('/installedapp/statusJson/100') { params -> status() }
        def settingPosts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (body.keySet().any { it.toString().startsWith("settings[") }) settingPosts << body
            [status: 200, data: page("periodic", [[name: "whichPeriod1", type: "enum", options: ["Hourly"]]], [], 7)]
        }

        when:
        def results = [
            script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "selectTriggers", operation: "write", write: [tCapab1: "Bogus"]]]),
            script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "periodic", operation: "write", write: [whichPeriod1: "Weekly"],
                hrefContext: [fromPage: "selectTriggers", hrefName: "periodic1", hrefParams: [n: 1]]]]),
            script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "mainPage", operation: "write", write: [logging: "Everything"]]])
        ]

        then: "nothing carrying the setting was posted"
        settingPosts.isEmpty()

        and: "each step reports the same refusal"
        results.every { it.success == false && it.silentRejection == false && it.opResult.wrote == null && it.valueEcho == null }
        results*.opResult*.refused*.reason == ["not_offered", "not_offered", "not_offered"]
        results[0].opResult.refused.offered == ["Switch"]
        results[1].opResult.refused.offered == ["Hourly"]
        results[2].opResult.refused.offered == ["None", "Events"]
        results[0].error.contains("the 'tCapab1' picker does not offer Bogus")
    }

    def "an href sub-page write goes through the schema writer with its href markers and page context"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> page("mainPage", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params -> page("selectTriggers", [], [], 7) }
        hubGet.register('/installedapp/configure/json/100/periodic') { params -> page("periodic", [[name: "whichPeriod1", type: "enum", options: ["Hourly"]]], [], 7) }
        hubGet.register('/installedapp/statusJson/100') { params -> status(null, [[name: "whichPeriod1", type: "enum", value: "Hourly"]]) }
        def writes = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (body.containsKey("settings[whichPeriod1]")) writes << body
            [status: 200, data: page("periodic", [[name: "whichPeriod1", type: "enum", options: ["Hourly"], value: "Hourly"]], [], 7)]
        }

        when:
        def result = script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "periodic", operation: "write", write: [whichPeriod1: "Hourly"],
            hrefContext: [fromPage: "selectTriggers", hrefName: "periodic1", hrefParams: [n: 1]]]])

        then:
        writes.size() == 1
        writes[0].containsKey("_action_href_periodic1|periodic|1")
        writes[0]["params_for_action_href_periodic1|periodic|1"].toString().contains('"n":1')
        writes[0].currentPage == "periodic"
        writes[0].formAction == "update"
        writes[0].version == "7"
        result.opResult.wrote == [whichPeriod1: "Hourly"]
        result.valueEcho.match == true
    }

    def "a walkStep write of a field the page does not show is refused unsent, with the slot hint from the table"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> page("mainPage", []) }
        hubGet.register('/installedapp/configure/json/100/selectTriggers') { params ->
            page("selectTriggers", [[name: "tCapab1", type: "enum", options: ["Switch"]]], [[description: RM_NEW_TRIGGER_LINK]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> status([[name: "ndx", value: 3]]) }
        def settingPosts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (body.keySet().any { it.toString().startsWith("settings[") }) settingPosts << body
            [status: 200, data: '']
        }

        when:
        def result = script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "selectTriggers", operation: "write", write: [tCapab3: "Switch"]]])

        then:
        settingPosts.isEmpty()
        result.success == false
        result.opResult.refused == [key: "tCapab3", reason: "not_in_schema", value: "Switch", available: ["tCapab1"]]
        result.error.contains("'tCapab3' is not an input on page 'selectTriggers'")
        result.repairHints.any { it.contains("click true with stateAttribute moreCond") && it.contains("state.ndx is 3") }
    }

    // ---------- the prerequisite table ----------

    def "doActPage entered before RM ever opened an action editor is refused before any render or write"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> page("mainPage", []) }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> page("selectActions", []) }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> page("doActPage", [[name: "actType.1", type: "enum"]]) }
        hubGet.register('/installedapp/statusJson/100') { params -> status([[name: "actNdx", value: 1]]) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> posts << path; [status: 200, data: ''] }

        when:
        def byName = script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "doActPage", operation: "write", write: ["actType.1": "x"]]])
        def byNavigate = script.toolSetRule([appId: 100, confirm: true,
            walkStep: [page: "selectActions", operation: "navigate", navigate: [targetPage: "doActPage"]]])

        then:
        [byName, byNavigate].every {
            it.success == false && it.error.contains("doActPage renders only inside an open action editor") &&
                it.error.contains("click N with stateAttribute doActN") && it.restoreHint.contains("nothing needs to be restored")
        }
        !hubGet.calls.any { it.path == '/installedapp/configure/json/100/doActPage' }
        !posts.any { it in ['/installedapp/update/json', '/installedapp/btn'] }
    }

    def "doActPage with an open editor is walked, and a missing key names the editor's slot from state.doActN"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> page("mainPage", []) }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> page("doActPage", [[name: "actType.4", type: "enum", options: ["messageActs"]]]) }
        hubGet.register('/installedapp/statusJson/100') { params -> status([[name: "doActN", value: "N"], [name: "actNdx", value: 4]]) }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, data: ''] }

        when:
        def seen = script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "doActPage", operation: "introspect"]])
        def missed = script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "doActPage", operation: "write", write: ["logmsg.9": "x"]]])

        then:
        seen.success == true
        missed.opResult.refused.reason == "not_in_schema"
        missed.repairHints.any { it.contains("slot 4") && it.contains("actType.4") }
    }

    def "a saved Required Expression's missing editor field points at editST; a cancelCapab click reports its next-render effect"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> page("mainPage", []) }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            page("STPage", [[name: "cancelST", type: "button", state: "cancelST"], [name: "editST", type: "button", state: "editST"],
                            [name: "cancelCapab", type: "button"]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> status() }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, data: ''] }

        when:
        def write = script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "STPage", operation: "write", write: [cond: "a"]]])
        def click = script.toolSetRule([appId: 100, confirm: true, walkStep: [page: "STPage", operation: "click", click: [name: "cancelCapab"]]])

        then:
        write.repairHints.any { it.contains("click editST") }
        click.opResult.note == script._rmWizardPrerequisites().buttons.cancelCapab
    }

    def "the prerequisite table names each wizard page RM gates and the guide covers every entry"() {
        given:
        def table = script._rmWizardPrerequisites()
        def guide = script.toolGetToolGuide('set_rule_reference_walkstep').content as String

        expect:
        table.pages.keySet() == ["doActPage", "selectTriggers", "selectConditions", "STPage", "periodic"] as Set
        table.pages.doActPage.appState == "doActN"
        table.pages.selectTriggers.openerButton == "true"
        table.buttons.keySet() == ["cancelCapab"] as Set
        ["doActN", "moreCond", "editCond", "editST", "hrefContext", "cancelCapab"].every { guide.contains(it) }
    }

    // ---------- served text ----------

    // hub_set_rule's walkStep bound sits with its other size pins in UpdateNativeAppSchemaTrimSpec.
    def "walkStep served text stays compact: both field descriptions point at a guide block under 3 KB"() {
        given:
        def defs = script.getAllToolDefinitions()
        def ruleWalk = defs.find { it.name == 'hub_set_rule' }.inputSchema.properties.walkStep.description as String
        def appWalk = defs.find { it.name == 'hub_set_native_app' }.inputSchema.properties.walkStep.description as String
        def block = script.guideSectionBlocks(script.getToolGuideSections()['set_rule_reference'])
            .find { it.startsWith('### `walkStep`') } as String

        expect:
        appWalk.getBytes('UTF-8').length <= 300
        [ruleWalk, appWalk].every { it.contains("hub_get_tool_guide(section='set_rule_reference_walkstep')") }
        block.getBytes('UTF-8').length < 3000
    }
}
