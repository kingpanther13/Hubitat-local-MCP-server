package server

import support.TestLocation
import support.ToolSpecBase
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Unroll

class ToolRmNativeCrudSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()
    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    private void enableReadOnly() {
        settingsMap.enableRead = true
    }

    private static String _settingKeyOf(Object k) {
        def matcher = (k?.toString() ?: "") =~ /^settings\[(.+)\]$/
        return matcher.matches() ? matcher.group(1) : null
    }

    private String appsListJson(int rmId = 21) {
        JsonOutput.toJson([apps: [
            [data: [id: rmId, name: "Rule Machine", type: "Rule Machine", user: false, hidden: false], children: []]
        ]])
    }

    private String ruleConfigJson(int ruleId, String label = "BAT-RM-test", List inputs = [], Integer parentAppId = null, Map settings = [:]) {
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

    def "_settingKeyOf extracts the inner field name and is null for non-settings keys"() {
        // Pins the single source of truth every POST interceptor in this spec routes
        // through, so the stateful-matcher double-coercion shape cannot recur.
        expect:
        _settingKeyOf("settings[ReltDev1]") == "ReltDev1"
        _settingKeyOf("settings[state_1]") == "state_1"
        _settingKeyOf("settings[isCondTrig.1]") == "isCondTrig.1"
        _settingKeyOf("_action_previous") == null
        _settingKeyOf("currentPage") == null
        _settingKeyOf(null) == null
    }

    def "_settingKeyOf is repeat-call stable -- the matcher-exhaustion bug the helper exists to prevent"() {
        // The legacy idiom `def m = k =~ /.../; m[0][1]; m[0][1]` re-reads a STATEFUL
        // Matcher; under Groovy 3.0 the second read can return null because the prior
        // read advanced the matcher past its match. _settingKeyOf builds a fresh matcher
        // and reads group(1) exactly once per call, so it returns the SAME value across
        // repeated calls on the same key -- this is the property that lets every
        // interceptor read the key as many times as it needs without exhaustion.
        when:
        def first  = _settingKeyOf("settings[RelrDev_1]")
        def second = _settingKeyOf("settings[RelrDev_1]")
        def third  = _settingKeyOf("settings[RelrDev_1]")

        then:
        first == "RelrDev_1"
        second == "RelrDev_1"
        third == "RelrDev_1"
    }

    def "_discoverParentAppId bootstraps a missing built-in parent via sysApp, then re-discovers it by type -- primary path, no commit needed (issue #185)"() {
        given:
        // Verified live: the sysApp GET creates the parent server-side and it appears in
        // /hub2/appsList right away (installed). The redirect is absolute so the client
        // may follow it and return 200/no-location -- the code re-discovers by type rather
        // than parsing the response shape.
        boolean created = false
        hubGet.register('/hub2/appsList') { params ->
            created
                ? JsonOutput.toJson([apps: [[data: [id: 88, name: "Button Controllers", type: "Button Controllers", user: false, installed: true, hidden: false], children: []]]])
                : JsonOutput.toJson([apps: []])
        }
        def rawCalls = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            rawCalls << path
            if (path.startsWith("/installedapp/sysApp/")) created = true   // the GET creates the parent
            [status: 200, location: null, data: "<html>configure page</html>"]   // client followed the absolute redirect
        }

        when:
        def id = script._discoverParentAppId("button_controller")

        then: "the sysApp endpoint was hit with the LITERAL-SPACE parent name (the HTTP layer encodes; pre-encoding double-encodes to %2520)"
        rawCalls.any { it == "/installedapp/sysApp/Button Controllers" }

        and: "the parent is re-discovered by type and returned -- no commit needed (already installed)"
        id == 88
    }

    def "_discoverParentAppId commits a freshly-bootstrapped parent that surfaces PENDING (installed != true)"() {
        given:
        // The sysApp GET surfaces the parent but uninstalled -- it must be committed via
        // Done. The real _commitUserAppInstall runs (private, so its I/O is stubbed).
        boolean created = false
        hubGet.register('/hub2/appsList') { params ->
            created
                ? JsonOutput.toJson([apps: [[data: [id: 88, name: "Button Controllers", type: "Button Controllers", user: false, installed: null, hidden: false], children: []]]])
                : JsonOutput.toJson([apps: []])
        }
        hubGet.register('/installedapp/configure/json/88') { params ->
            JsonOutput.toJson([app: [id: 88, installed: true, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/configure/json/88/mainPage') { params ->
            JsonOutput.toJson([app: [id: 88, installed: true, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/statusJson/88') { params -> statusJson(88) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            if (path.startsWith("/installedapp/sysApp/")) created = true
            [status: 200, location: null, data: ""]
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def id = script._discoverParentAppId("button_controller")

        then: "the pending parent was committed via Done"
        posts.any { it.path == "/installedapp/update/json" && it.body._action_update == "Done" }

        and: "the parent id is returned"
        id == 88
    }

    def "_discoverParentAppId throws a clear, diagnostic error when sysApp does not surface the parent"() {
        given:
        // The GET neither surfaces the parent in appsList nor yields a parseable id --
        // the caller must get a clear error carrying the bootstrap diagnostics.
        hubGet.register('/hub2/appsList') { params -> JsonOutput.toJson([apps: []]) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 200, location: null, data: ""]
        }

        when:
        script._discoverParentAppId("button_controller")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Button Controllers")
        ex.message.contains("sysApp")
    }

    def "_discoverParentAppId extracts the new id from the followed configure-page body when appsList lags (the absolute-redirect 200 case)"() {
        given:
        // The MCP's client follows the absolute sysApp redirect and returns 200 with the
        // configure page; /hub2/appsList still doesn't list the new parent (cache lag).
        // The classic page injects `appId = <id>` -- the code pulls it from there, commits,
        // and uses that id directly rather than re-reading the lagging appsList.
        hubGet.register('/hub2/appsList') { params -> JsonOutput.toJson([apps: []]) }   // never surfaces
        hubGet.register('/installedapp/configure/json/91') { params ->
            JsonOutput.toJson([app: [id: 91, installed: true, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/configure/json/91/mainPage') { params ->
            JsonOutput.toJson([app: [id: 91, installed: true, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/statusJson/91') { params -> statusJson(91) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 200, location: null, data: "<html><script>var appId = 91; var foo = 1;</script>configure</html>"]
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def id = script._discoverParentAppId("button_controller")

        then: "the new id was pulled from the body's `appId = <id>`, committed, and returned directly"
        id == 91
        posts.any { it.path == "/installedapp/update/json" && it.body._action_update == "Done" }
    }

    def "_discoverParentAppId raises a clear IAE when the bootstrapped parent's install commit does not take (app.installed reads false)"() {
        given:
        // PENDING parent that gets committed via Done, but the post-commit
        // confirmation read (_commitUserAppInstall re-reads configure/json and
        // checks app.installed) comes back false -- an inert shell. The discovery
        // must surface that loudly rather than caching an id whose createchild
        // would hit a dead parent. configure/json/88 reports installed:false so
        // _commitUserAppInstall returns success:false.
        boolean created = false
        hubGet.register('/hub2/appsList') { params ->
            created
                ? JsonOutput.toJson([apps: [[data: [id: 88, name: "Button Controllers", type: "Button Controllers", user: false, installed: null, hidden: false], children: []]]])
                : JsonOutput.toJson([apps: []])
        }
        hubGet.register('/installedapp/configure/json/88') { params ->
            JsonOutput.toJson([app: [id: 88, installed: false, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/configure/json/88/mainPage') { params ->
            JsonOutput.toJson([app: [id: 88, installed: false, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/statusJson/88') { params -> statusJson(88) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            if (path.startsWith("/installedapp/sysApp/")) created = true
            [status: 200, location: null, data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        script._discoverParentAppId("button_controller")

        then: "the install-pending parent whose commit didn't take is surfaced loudly (app.installed reads false)"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("commit did not take")
        ex.message.contains("app.installed reads false")
    }

    def "_discoverParentAppId does NOT cache the parent id when the install commit threw (next create re-discovers)"() {
        given:
        // PENDING parent: the Done commit POST throws (transient hub failure). The
        // discovery proceeds (createchild may still work) but the id must NOT be
        // cached -- an unverified install should be re-discovered + re-verified on
        // the next call, not papered over in atomicState.parentAppIds. The Done POST
        // is the only POST in this flow, so failing every POST fails exactly the commit.
        boolean created = false
        hubGet.register('/hub2/appsList') { params ->
            created
                ? JsonOutput.toJson([apps: [[data: [id: 88, name: "Button Controllers", type: "Button Controllers", user: false, installed: null, hidden: false], children: []]]])
                : JsonOutput.toJson([apps: []])
        }
        hubGet.register('/installedapp/configure/json/88') { params ->
            JsonOutput.toJson([app: [id: 88, installed: true, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/configure/json/88/mainPage') { params ->
            JsonOutput.toJson([app: [id: 88, installed: true, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/statusJson/88') { params -> statusJson(88) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            if (path.startsWith("/installedapp/sysApp/")) created = true
            [status: 200, location: null, data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            throw new RuntimeException("transient commit failure")
        }

        when:
        def id = script._discoverParentAppId("button_controller")

        then: "the id is still returned (createchild may work), but the unverified install is NOT cached"
        id == 88
        !(atomicStateMap.parentAppIds?.containsKey("button_controller"))
    }

    def "_discoverParentAppId 302-Location bootstrap: pulls the id from the redirect Location, commits, and returns it"() {
        given:
        // Variant of the absolute-redirect 200 case: the sysApp GET 302-redirects
        // and the client preserves the Location header (rather than following it to
        // a 200 body). The new id rides in the Location's /installedapp/configure/<id>/<page>
        // path -- the code parses it from there, commits via Done, and returns it directly
        // without re-reading the lagging appsList.
        hubGet.register('/hub2/appsList') { params -> JsonOutput.toJson([apps: []]) }   // never surfaces
        hubGet.register('/installedapp/configure/json/91') { params ->
            JsonOutput.toJson([app: [id: 91, installed: true, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/configure/json/91/mainPage') { params ->
            JsonOutput.toJson([app: [id: 91, installed: true, version: 1], configPage: [name: "mainPage", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/statusJson/91') { params -> statusJson(91) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "http://hub/installedapp/configure/91/mainPage", data: ""]
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def id = script._discoverParentAppId("button_controller")

        then: "the id came from the 302 Location, the parent was committed via Done, and the id was returned"
        id == 91
        posts.any { it.path == "/installedapp/update/json" && it.body._action_update == "Done" }
    }

    def "_rmSubmitMainPageDone re-submits capability device ids from statusJson #idsField (value is null for device settings)"() {
        given:
        // Button Controller-5.1 keeps its device picker ON mainPage (unlike RM,
        // whose pickers live on sub-pages), and statusJson reports capability
        // settings with value=null -- the assigned ids live in
        // deviceIdsForDeviceList (with a deviceList id->label map alongside).
        // Rebuilding the Done form from `value` alone posts settings[buttonDev]=""
        // with _action_update=Done, which actively CLEARS the assignment
        // (verified live on fw 2.5.0.143 -- the button-rule e2e buttonDev wipe).
        hubGet.register('/installedapp/configure/json/600/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 600, name: "Button Controller-5.1", label: "BC", installed: true, version: 3,
                      appType: [name: "Button Controller-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", install: true, error: null, sections: [
                    [title: "", input: [[name: "buttonDev", type: "capability.pushableButton", multiple: true, required: true, submitOnChange: true]]]
                ]],
                settings: [buttonDev: ["479": "BtnDev"]],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/600') { params ->
            JsonOutput.toJson([
                installedApp: [id: 600],
                appSettings: [[name: "buttonDev", type: "capability.pushableButton", multiple: true, value: null] + [(idsField): idsValue]],
                eventSubscriptions: [], scheduledJobs: [], appState: [:], childAppCount: 0, childDeviceCount: 0
            ])
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        script._rmSubmitMainPageDone(600)

        then: "the Done submit carries the live device id -- NOT an empty value that wipes it"
        def done = posts.find { it.path == "/installedapp/update/json" && it.body._action_update == "Done" }
        done != null
        done.body["settings[buttonDev]"] == "479"
        done.body["buttonDev.type"] == "capability.pushableButton"
        done.body["buttonDev.multiple"] == "true"
        done.body.version == "3"

        and: "the classic Done form always carries the appTypeId/appTypeName/_cancellable trio (hub 500s without them on standalone classic apps)"
        done.body.appTypeId == ""
        done.body.appTypeName == ""
        done.body._cancellable == "false"

        where:
        idsField                 | idsValue
        "deviceIdsForDeviceList" | [479]
        "deviceList"             | ["479": "BtnDev"]
    }

    def "_rmSubmitSubPageDone reconstructs device-id settings from statusJson when rebuilding the sub-page Done form"() {
        given:
        // The sub-page Done form is rebuilt via _rmLiveSettingsFromStatus, the same
        // null-value device reconstruction _rmSubmitMainPageDone uses: a capability
        // setting reports value=null with the live ids in deviceIdsForDeviceList.
        // Rebuilding from `value` alone would post settings[tDev1]="" and WIPE the
        // assignment on the Done. With hrefParams=null and a non-JSON POST response,
        // _rmNavigateToPage returns null so the helper falls back to the plain page
        // fetch (registered below) -- that fallback path is the one under test (a
        // metaClass stub on the private _rmNavigateToPage is NOT honored on the
        // intra-class call, so we steer the real helper to return null instead).
        hubGet.register('/installedapp/configure/json/650/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 650, name: "Rule-5.1", label: "r", installed: true, version: 5,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", install: true, error: null, sections: [
                    [title: "", input: [[name: "tDev1", type: "capability.switch", multiple: true]]]
                ]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/650') { params ->
            JsonOutput.toJson([
                installedApp: [id: 650],
                appSettings: [[name: "tDev1", type: "capability.switch", multiple: true, value: null, deviceIdsForDeviceList: [8]]],
                eventSubscriptions: [], scheduledJobs: [], appState: [:], childAppCount: 0, childDeviceCount: 0
            ])
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            // Empty data so _rmNavigateToPage's nav POST yields no parseable JSON ->
            // navResp == null -> _rmSubmitSubPageDone falls back to the plain page fetch.
            [status: 200, location: null, data: '']
        }

        when:
        script._rmSubmitSubPageDone(650, "STPage", "mainPage", "name", null)

        then: "the back-nav Done carries the live device id -- NOT an empty value that wipes it"
        def done = posts.find { it.path == "/installedapp/update/json" && it.body._action_previous == "Done" }
        done != null
        done.body["settings[tDev1]"] == "8"
        done.body["tDev1.multiple"] == "true"
    }

    def "_rmSubmitMainPageDone returns done:false with the failure reason when the Done POST throws -- no exception propagates"() {
        given:
        // The session-end Done is best-effort: a thrown POST must degrade to a
        // structured [done:false, reason:...] signal (so commitButton:null app
        // types can surface the miss) rather than bubbling an exception up to the
        // create/edit caller.
        hubGet.register('/installedapp/configure/json/660/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 660, name: "Rule-5.1", label: "r", installed: true, version: 2,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", install: true, error: null, sections: [
                    [title: "", input: [[name: "origLabel", type: "text"]]]
                ]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/660') { params -> statusJson(660) }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            throw new RuntimeException("boom 500")
        }

        when:
        def r = script._rmSubmitMainPageDone(660)

        then:
        noExceptionThrown()
        r.done == false
        r.reason.contains("boom 500")
    }

    def "_rmSubmitMainPageDone skips the Done click when statusJson carries no appSettings list -- no blanking POST fires"() {
        given:
        // Anomalous-shape guard: if statusJson parses but has no appSettings LIST,
        // rebuilding the form would submit EVERY input as "" (the all-fields variant
        // of the capability wipe). The helper must skip the Done entirely -- NOT POST
        // a form that blanks the page's inputs.
        hubGet.register('/installedapp/configure/json/670/mainPage') { params ->
            JsonOutput.toJson([
                app: [id: 670, name: "Rule-5.1", label: "r", installed: true, version: 2,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", install: true, error: null, sections: [
                    [title: "", input: [[name: "origLabel", type: "text"]]]
                ]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/670') { params ->
            JsonOutput.toJson([installedApp: [id: 670]])   // NO appSettings key at all
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def r = script._rmSubmitMainPageDone(670)

        then: "the Done is skipped with an appSettings reason -- no update/json POST blanks the inputs"
        r.done == false
        r.reason.contains("appSettings")
        !posts.any { it.path == "/installedapp/update/json" }
    }

    def "_rmSubmitMainPageDone commits on the selectActions page for Button Rule-5.1 (NOT mainPage)"() {
        given:
        // Button Rule-5.1's root/commit page is named selectActions (playing
        // mainPage's role). The trailing Done must GET + POST selectActions; a
        // hardcoded mainPage logs "Cannot find page 'mainPage'" and the fetch
        // returns done:false. Only /selectActions is registered here, so a
        // mainPage fetch would miss and the assertions below would fail.
        def fetched = []
        hubGet.register('/installedapp/configure/json/710') { params ->
            fetched << "root"
            JsonOutput.toJson([app: [id: 710, installed: true, version: 3, appType: [name: "Button Rule-5.1", namespace: "hubitat"]],
                               configPage: [name: "selectActions", sections: []], settings: [:]])
        }
        hubGet.register('/installedapp/configure/json/710/selectActions') { params ->
            fetched << "selectActions"
            JsonOutput.toJson([app: [id: 710, name: "Button Rule-5.1", label: "r", installed: true, version: 3,
                                     appType: [name: "Button Rule-5.1", namespace: "hubitat"]],
                               configPage: [name: "selectActions", install: true, error: null, sections: [
                                   [title: "", input: [[name: "origLabel", type: "text"]]]
                               ]],
                               settings: [:], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/710') { statusJson(710) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def r = script._rmSubmitMainPageDone(710)

        then: "the commit page (selectActions), not mainPage, is fetched and posted -- and the Done succeeds"
        fetched.contains("selectActions")
        def done = posts.find { it.path == "/installedapp/update/json" }
        done != null
        done.body.currentPage == "selectActions"
        r.done == true
    }

    def "_appTypeRegistry registers button_rule with appName Button Rule-5.1 + commitButton null"() {
        when:
        def reg = script._appTypeRegistry()

        then:
        reg.button_rule == [namespace: "hubitat", appName: "Button Rule-5.1", parentTypeName: "Button Controllers", commitButton: null]

        and: "_resolveCommitButton returns a real null verdict for it (submitOnChange, no updateRule button)"
        script._resolveCommitButton("Button Rule-5.1") == null
    }

    def "native document reference is available through the read guide without write gates"() {
        when:
        def result = script.toolGetToolGuide('native_rule_documents')
        then:
        result.success
        result.section == 'native_rule_documents'
        result.content.contains('changes')
        result.content.contains('native names')
    }

    def "delete_rm_rule snapshots, then force-deletes when force=true"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/200') { params -> ruleConfigJson(200, "to-delete") }
        hubGet.register('/installedapp/statusJson/200') { params -> statusJson(200) }
        def files = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> files.put(fn, b) }
        script.metaClass.downloadHubFile = { String fn -> files.get(fn) }

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

        and: "the snapshot is registered in the unified item-backup manifest so hub_list_backups picks it up"
        backupIndex?.values()?.any { it.type == "rm-rule" && it.ruleId == 200 }

        when: "a client reads the generated handle as a JSON String"
        def backup = script.toolGetItemBackup([backupKey: result.backup.backupKey.toString()])

        then:
        backup.error == null
        backup.type == 'rm-rule'
        backup.howToRestore.contains('hub_restore_backup')
        backup.howToRestore.contains(result.backup.backupKey.toString())
        !backup.howToRestore.contains('Drivers Code')
        def snapshot = new JsonSlurper().parseText(backup.source)
        snapshot.appId == 200
        snapshot.appLabel == 'to-delete'
    }

    def "delete_rm_rule soft-delete surfaces hubMessage on refusal"() {
        given:
        enableWrite()
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

    def "strict snapshots retain a pinned baseline and a rolling next step"() {
        given:
        enableWrite()
        settingsMap.backupEveryRuleWrite = true
        def clock = [1234567890000L]
        NOW_OVERRIDE.set({ -> clock[0] })
        hubGet.register('/installedapp/configure/json/504') { params -> ruleConfigJson(504, "strict") }
        hubGet.register('/installedapp/statusJson/504') { params -> statusJson(504) }
        def uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << fn }

        when:
        def first = script._rmBackupRuleSnapshot(504, "pre-addAction")
        clock[0] += 1L
        def second = script._rmBackupRuleSnapshot(504, "pre-walkStep")

        then: 'the session baseline is pinned and the later per-write snapshot is a rolling step'
        first.backupKey != second.backupKey
        uploads.size() == 2
        first.retention == "pinned"
        first.retentionNote.startsWith("Pinned baseline")
        second.retention == "rolling"
        second.retentionNote.startsWith("Rolling step")
        backupIndex[first.backupKey].retention == "pinned"
        backupIndex[second.backupKey].retention == "rolling"
    }

    def "backupEveryRuleWrite keeps every rolling step while the budget has room"() {
        given:
        enableWrite()
        settingsMap.backupEveryRuleWrite = true
        def clock = [1234567890000L]
        NOW_OVERRIDE.set({ -> clock[0] })
        hubGet.register('/installedapp/configure/json/504') { params -> ruleConfigJson(504, "strict-cap") }
        hubGet.register('/installedapp/statusJson/504') { params -> statusJson(504) }
        def files = [:]
        List deleted = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> files[fn] = b }
        script.metaClass.deleteHubFile = { String fn -> deleted << fn; files.remove(fn) }
        backupIndex = ["rm-rule_77_other": [type: "rm-rule", id: 77, ruleId: 77, fileName: "mcp-rm-backup-77-other.json",
                                             timestamp: clock[0] - 120_000L, sourceLength: 10, sizeBytes: 10, retention: "rolling"]]

        when: 'eight strict writes land inside one editing session'
        List backups = (1..8).collect {
            clock[0] += 1000L
            script._rmBackupRuleSnapshot(504, "pre-update")
        }

        then: 'the first is pinned, the rest are rolling steps, and nothing is evicted by count'
        backups[0].retention == "pinned"
        backups.drop(1).every { it.retention == "rolling" }
        def kept = backupIndex.findAll { k, v -> v.ruleId == 504 }.keySet()
        kept == backups.collect { it.backupKey.toString() } as Set
        deleted.isEmpty()

        and: "another rule's rolling step is untouched"
        backupIndex.containsKey("rm-rule_77_other")
    }

    def "a strict-mode replaceRequiredExpression snapshot stays pinned inside a session"() {
        given:
        enableWrite()
        settingsMap.backupEveryRuleWrite = true
        def clock = [1234567890000L]
        NOW_OVERRIDE.set({ -> clock[0] })
        hubGet.register('/installedapp/configure/json/504') { params -> ruleConfigJson(504, "strict-re") }
        hubGet.register('/installedapp/statusJson/504') { params -> statusJson(504) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def first = script._rmBackupRuleSnapshot(504, "pre-addAction")
        clock[0] += 1000L
        def replace = script._rmBackupRuleSnapshot(504, "pre-replaceRequiredExpression")

        then:
        first.retention == "pinned"
        replace.retention == "pinned"
        replace.backupKey != first.backupKey
    }

    private List strictRule504(String label) {
        enableWrite()
        settingsMap.backupEveryRuleWrite = true
        def clock = [1234567890000L]
        NOW_OVERRIDE.set({ -> clock[0] })
        hubGet.register('/installedapp/configure/json/504') { params -> ruleConfigJson(504, label) }
        hubGet.register('/installedapp/statusJson/504') { params -> statusJson(504) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        return clock
    }

    def "a strict-mode snapshot more than an hour after the rule's last one starts a new pinned session"() {
        given:
        def clock = strictRule504("strict-gap")

        when:
        def first = script._rmBackupRuleSnapshot(504, "pre-addAction")
        clock[0] += 61 * 60 * 1000L
        def later = script._rmBackupRuleSnapshot(504, "pre-addAction")

        then:
        first.retention == "pinned"
        later.retention == "pinned"
    }

    def "a continuous strict-mode chain keeps the session open past the baseline's hour"() {
        given:
        def clock = strictRule504("strict-chain")

        when: 'writes every 40 minutes'
        def steps = (0..3).collect {
            if (it) clock[0] += 40 * 60 * 1000L
            script._rmBackupRuleSnapshot(504, "pre-update")
        }

        then: 'only the first is pinned, even two hours on'
        steps*.retention == ["pinned", "rolling", "rolling", "rolling"]
    }

    def "strict-mode pre-delete and pre-forcedelete snapshots inside a session stay pinned"() {
        given:
        def clock = strictRule504("strict-delete")

        when:
        script._rmBackupRuleSnapshot(504, "pre-update")
        clock[0] += 1000L
        def step = script._rmBackupRuleSnapshot(504, "pre-update")
        clock[0] += 1000L
        def delete = script._rmBackupRuleSnapshot(504, reason)

        then:
        step.retention == "rolling"
        delete.retention == "pinned"

        where:
        reason << ["pre-delete", "pre-forcedelete"]
    }

    def "hub_list_backups surfaces rm-rule entries with rule-specific metadata alongside app/driver entries"() {
        given:
        enableReadOnly()
        backupIndex = [
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

    def "hub_restore_backup dispatches rm-rule entries through the rule restore path (in-place when rule exists)"() {
        given:
        enableWrite()
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
        backupIndex = [
            "rm-rule_300_x": [type: "rm-rule", id: 300, ruleId: 300,
                              fileName: "mcp-rm-backup-300-x.json", reason: "pre-update",
                              appLabel: "restored", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn ->
            assert Thread.holdsLock(scriptStaticField('ITEM_BACKUP_MANIFESTS'))
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
            assert !Thread.holdsLock(scriptStaticField('ITEM_BACKUP_MANIFESTS'))
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

    @Unroll
    def "restore refuses recreation after a failed config read with #inventoryCase inventory"() {
        given:
        enableWrite()
        def snapshot = [schemaVersion: 1, ruleId: 400, appLabel: 'existing rule',
                        configJson: [settings: [origLabel: 'existing rule']], statusJson: [:]]
        backupIndex = [
            'rm-rule_400_x': [type: 'rm-rule', id: 400, ruleId: 400, fileName: 'snapshot.json']
        ]
        script.metaClass.downloadHubFile = { String name -> JsonOutput.toJson(snapshot).getBytes('UTF-8') }
        hubGet.register('/installedapp/configure/json/400') { params -> throw new IOException('temporary config failure') }
        hubGet.register('/hub2/appsList') { params -> inventory }
        hubGet.register('/installedapp/configure/json/401') { params -> ruleConfigJson(401) }
        hubGet.register('/installedapp/statusJson/401') { params -> statusJson(401) }
        def mutations = []
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, Integer timeout = 30 ->
            mutations << path
            [status: 302, location: '/installedapp/configure/401', data: '']
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            mutations << path
            [status: 200, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'rm-rule_400_x', confirm: true])

        then:
        result.success == false
        result.error.contains('400')
        result.note.contains('No replacement')
        mutations.isEmpty()

        where:
        inventoryCase        | inventory
        'existing target'    | '{"apps":[{"data":{"id":21},"children":[{"data":{"id":400}}]}]}'
        'nested target'      | '{"apps":[{"children":[{"data":{"id":21},"children":[{"data":{"id":400}}]}]}]}'
        'empty response'     | ''
        'invalid JSON'       | 'not JSON'
        'missing apps'       | '{}'
        'non-list apps'      | '{"apps":{}}'
        'malformed node'     | '{"apps":[{"data":{"id":21},"children":[null]}]}'
        'missing node id'    | '{"apps":[{"data":{"id":21},"children":[{"data":{"name":"unknown"}}]}]}'
        'invalid node id'    | '{"apps":[{"data":{"id":21},"children":[{"data":{"id":"unknown"}}]}]}'
        'non-list children'  | '{"apps":[{"data":{"id":21},"children":{}}]}'
    }

    @Unroll
    def "hub_restore_backup recreates a deleted rule with a fresh id (structuralContainer=#structuralContainer)"() {
        given:
        enableWrite()
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
        backupIndex = [
            "rm-rule_400_y": [type: "rm-rule", id: 400, ruleId: 400,
                              fileName: "mcp-rm-backup-400-y.json", reason: "pre-delete",
                              appLabel: "gone-rule", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn -> snapshotBytes }
        hubGet.register('/hub2/appsList') { params ->
            structuralContainer ? JsonOutput.toJson([apps: [[children: new JsonSlurper().parseText(appsListJson(21)).apps]]]) : appsListJson(21)
        }
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

        where:
        structuralContainer << [false, true]
    }

    def "hub_restore_backup uses rule_machine default when snapshot has no appType field (legacy snapshot)"() {
        given: 'a legacy rm-rule snapshot captured before the appType field was added'
        enableWrite()
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
        backupIndex = [
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

    def "hub_restore_backup surfaces success:false when settings replay throws mid-flow"() {
        given: 'rule still exists in-place; _rmUpdateAppSettings will throw mid-replay'
        enableWrite()
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
        backupIndex = [
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

    def "a restore whose settings replay commits but whose updateRule click fails says so"() {
        given: "the rule exists in place; the replay POST lands, the trailing button click does not"
        enableWrite()
        def snapshot = [
            schemaVersion: 1, ruleId: 361, reason: "pre-test", timestamp: 1000,
            timestampIso: "2026-01-01T00:00:00Z", appLabel: "click-err",
            configJson: [
                app: [id: 361, label: "click-err"],
                configPage: [sections: [[title: "", input: [[name: "origLabel", type: "text"]]]]],
                settings: [origLabel: "click-err"]
            ],
            statusJson: [:]
        ]
        def snapshotBytes = JsonOutput.toJson(snapshot).getBytes("UTF-8")
        backupIndex = [
            "rm-rule_361_w": [type: "rm-rule", id: 361, ruleId: 361,
                              fileName: "mcp-rm-backup-361-w.json", reason: "pre-test",
                              appLabel: "click-err", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn -> snapshotBytes }
        hubGet.register('/installedapp/configure/json/361') { params ->
            ruleConfigJson(361, "click-err", [[name: "origLabel", type: "text"]])
        }
        hubGet.register('/installedapp/statusJson/361') { params -> statusJson(361) }
        def posted = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posted << [path: path, body: body]
            if (path == "/installedapp/btn") throw new RuntimeException("status code: 500, reason phrase: Server Error")
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def result = script.toolRestoreItemBackup([backupKey: "rm-rule_361_w", confirm: true])

        then: "the envelope names the step and the recovery, instead of blaming the replay"
        result.success == false
        result.failedStep == "the final updateRule click"
        posted.any { it.path == "/installedapp/update/json" && it.body.containsKey("settings[origLabel]") }
        result.error?.contains("failed during the final updateRule click")
        result.note?.contains("Update Rule")
    }

    def "a restore replays value-bearing inputs only -- RM's button rows are never re-posted"() {
        given: "a snapshot whose settings carry RM's row buttons alongside real inputs"
        enableWrite()
        def snapshot = [
            schemaVersion: 1, ruleId: 362, reason: "pre-test", timestamp: 1000,
            timestampIso: "2026-01-01T00:00:00Z", appLabel: "btn-skip",
            configJson: [
                app: [id: 362, label: "btn-skip"],
                configPage: [sections: [[title: "", input: [[name: "origLabel", type: "text"], [name: "tstate3", type: "decimal"]]]]],
                settings: [origLabel: "btn-skip", tstate3: "72.5", "3": "", cut1: "", trashAll: ""]
            ],
            statusJson: [appSettings: [
                [name: "3", type: "button", multiple: false, value: ""],
                [name: "cut1", type: "button", multiple: false, value: ""],
                [name: "trashAll", type: "button", multiple: false, value: ""]
            ]]
        ]
        def snapshotBytes = JsonOutput.toJson(snapshot).getBytes("UTF-8")
        backupIndex = [
            "rm-rule_362_w": [type: "rm-rule", id: 362, ruleId: 362,
                              fileName: "mcp-rm-backup-362-w.json", reason: "pre-test",
                              appLabel: "btn-skip", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn -> snapshotBytes }
        hubGet.register('/installedapp/configure/json/362') { params ->
            ruleConfigJson(362, "btn-skip", [[name: "origLabel", type: "text"], [name: "tstate3", type: "decimal"]])
        }
        hubGet.register('/installedapp/statusJson/362') { params -> statusJson(362) }
        def posted = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posted << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def result = script.toolRestoreItemBackup([backupKey: "rm-rule_362_w", confirm: true])

        then: "the real inputs are replayed and every button row is skipped and reported"
        result.success == true
        def replay = posted.find { it.path == "/installedapp/update/json" }?.body
        replay != null
        replay.containsKey("settings[origLabel]")
        replay.containsKey("settings[tstate3]")
        !replay.containsKey("settings[3]")
        !replay.containsKey("settings[cut1]")
        !replay.containsKey("settings[trashAll]")
        result.settingsSkipped*.key == ["3", "cut1", "trashAll"]
        result.settingsSkipped.every { it.reason }
    }

    def "a restore replays a device picker as its ids, not as the snapshot's id-to-label map"() {
        given: "a snapshot whose switch picker is the {id: label} map configure/json renders"
        enableWrite()
        def snapshot = [
            schemaVersion: 1, ruleId: 363, reason: "pre-test", timestamp: 1000,
            timestampIso: "2026-01-01T00:00:00Z", appLabel: "picker",
            configJson: [
                app: [id: 363, label: "picker"],
                configPage: [sections: [[title: "", input: [[name: "origLabel", type: "text"]]]]],
                settings: [origLabel: "picker", "onOffSwitch.2": ["9": "MSHeatMode"], "tDev3": ["54": "T&H Sensor"],
                           "rDev_1": ["9": "MSHeatMode"], "notAPicker": ["k": "v"]]
            ],
            statusJson: [appSettings: [
                [name: "onOffSwitch.2", type: "capability.switch", multiple: true, value: null, deviceIdsForDeviceList: [9]],
                [name: "tDev3", type: "capability.temperatureMeasurement", multiple: true, value: null, deviceIdsForDeviceList: [54, 55]],
                [name: "rDev_1", type: "capability.switch", multiple: true, value: null, deviceIdsForDeviceList: []],
                [name: "notAPicker", type: "text", multiple: false, value: null]
            ]]
        ]
        def snapshotBytes = JsonOutput.toJson(snapshot).getBytes("UTF-8")
        backupIndex = [
            "rm-rule_363_w": [type: "rm-rule", id: 363, ruleId: 363,
                              fileName: "mcp-rm-backup-363-w.json", reason: "pre-test",
                              appLabel: "picker", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn -> snapshotBytes }
        hubGet.register('/installedapp/configure/json/363') { params ->
            ruleConfigJson(363, "picker", [[name: "origLabel", type: "text"]])
        }
        hubGet.register('/installedapp/statusJson/363') { params ->
            statusJson(363, [
                [name: "onOffSwitch.2", type: "capability.switch", multiple: true, value: null, deviceIdsForDeviceList: [9]],
                [name: "tDev3", type: "capability.temperatureMeasurement", multiple: true, value: null, deviceIdsForDeviceList: [54, 55]]
            ])
        }
        def posted = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posted << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def result = script.toolRestoreItemBackup([backupKey: "rm-rule_363_w", confirm: true])

        then: "the pickers post as id lists (statusJson's live ids win over the map's keys)"
        result.success == true
        def replay = posted.find { it.path == "/installedapp/update/json" }?.body
        replay["settings[onOffSwitch.2]"] == "9"
        replay["settings[tDev3]"] == "54,55"
        replay["onOffSwitch.2.multiple"] == "true"
        !replay.values().any { it?.toString()?.contains("MSHeatMode") }

        and: "an EMPTY live id list is not authoritative -- the map's keys are used, so the picker is not wiped"
        replay["settings[rDev_1]"] == "9"

        and: "a Map that is not a device picker is skipped with a reason, never rewritten as its keys"
        !replay.containsKey("settings[notAPicker]")
        result.settingsSkipped.find { it.key == "notAPicker" }?.reason?.contains("device-picker schema")
    }

    def "a restore replays a driver-specific device.* picker as ids, from its map or its list text"() {
        // Issue 489: only capability.* counted as a device picker, so a device.<Driver> selection was
        // skipped as a non-picker map (or posted as "[728]" and 500ed) and lost on restore.
        given:
        enableWrite()
        def snapshot = [
            schemaVersion: 1, ruleId: 364, reason: "pre-test", timestamp: 1000,
            timestampIso: "2026-01-01T00:00:00Z", appLabel: "diag",
            configJson: [
                app: [id: 364, label: "diag"],
                configPage: [sections: [[title: "", input: [[name: "probeDevices", type: "device.VirtualSwitch", multiple: true],
                                                            [name: "seeded", type: "device.VirtualSwitch", multiple: true]]]]],
                settings: [probeDevices: ["555": "BAT-Sw-A"], seeded: "[728, 730]"]
            ],
            statusJson: [appSettings: [
                [name: "probeDevices", type: "device.VirtualSwitch", multiple: true, value: null, deviceIdsForDeviceList: []],
                [name: "seeded", type: "device.VirtualSwitch", multiple: true, value: "[728, 730]", deviceIdsForDeviceList: []]
            ]]
        ]
        def snapshotBytes = JsonOutput.toJson(snapshot).getBytes("UTF-8")
        backupIndex = [
            "rm-rule_364_w": [type: "rm-rule", id: 364, ruleId: 364,
                              fileName: "mcp-rm-backup-364-w.json", reason: "pre-test",
                              appLabel: "diag", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn -> snapshotBytes }
        hubGet.register('/installedapp/configure/json/364') { params -> ruleConfigJson(364, "diag", []) }
        hubGet.register('/installedapp/statusJson/364') { params -> statusJson(364, []) }
        def posted = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posted << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def result = script.toolRestoreItemBackup([backupKey: "rm-rule_364_w", confirm: true])

        then:
        result.success == true
        !result.settingsSkipped?.any { it.key in ["probeDevices", "seeded"] }
        def replay = posted.find { it.path == "/installedapp/update/json" }?.body
        replay["settings[probeDevices]"] == "555"
        replay["settings[seeded]"] == "728,730"
        replay["deviceList"] in ["probeDevices", "seeded"]
    }

    def "a restore into a DISABLED rule is refused before any write, like an edit"() {
        given:
        enableWrite()
        def snapshot = [
            schemaVersion: 1, ruleId: 364, reason: "pre-test", timestamp: 1000,
            timestampIso: "2026-01-01T00:00:00Z", appLabel: "parked",
            configJson: [app: [id: 364, label: "parked"], configPage: [sections: [[title: "", input: [[name: "origLabel", type: "text"]]]]], settings: [origLabel: "parked"]],
            statusJson: [:]
        ]
        def snapshotBytes = JsonOutput.toJson(snapshot).getBytes("UTF-8")
        backupIndex = [
            "rm-rule_364_w": [type: "rm-rule", id: 364, ruleId: 364, fileName: "mcp-rm-backup-364-w.json",
                              reason: "pre-test", appLabel: "parked", timestamp: 1000, sourceLength: snapshotBytes.length]
        ]
        script.metaClass.downloadHubFile = { String fn -> snapshotBytes }
        hubGet.register('/installedapp/configure/json/364') { params -> ruleConfigJson(364, "parked", [[name: "origLabel", type: "text"]]) }
        hubGet.register('/installedapp/statusJson/364') { params -> statusJson(364) }
        hubGet.register('/installedapp/json/364') { params -> JsonOutput.toJson([id: 364, name: "Rule-5.1", type: "Rule-5.1", disabled: true]) }
        def posted = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> posted << path; [status: 200, location: null, data: ''] }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def result = null
        def refusal = null
        try { result = script.toolRestoreItemBackup([backupKey: "rm-rule_364_w", confirm: true]) }
        catch (IllegalArgumentException e) { refusal = e }

        then: "refused naming the disabled app, and nothing was posted"
        ((refusal?.message ?: result?.error ?: "") as String).toUpperCase().contains("DISABLED")
        posted.isEmpty()
    }

    @spock.lang.Unroll
    def "native input schema preserves data key #inputName and serializes false zero and null"() {
        given:
        def schema = script._rmCollectInputSchema([sections: [[input: [
            [name: inputName, type: 'bool', multiple: false, required: true]
        ]]]])

        expect:
        schema.get(inputName) == [name: inputName, type: 'bool', multiple: false, required: true,
                                  title: null, range: null, pattern: null, disabled: false, defaultValue: null]
        script._rmBuildSettingsBody(100, [(inputName): false], schema).get("settings[${inputName}]".toString()) == 'false'
        script._rmBuildSettingsBody(100, [(inputName): 0], schema).get("settings[${inputName}]".toString()) == '0'
        script._rmBuildSettingsBody(100, [(inputName): null], schema).get("settings[${inputName}]".toString()) == ''
        script._rmBuildSettingsBody(100, [(inputName): false], schema).get("${inputName}.multiple".toString()) == 'false'

        where:
        inputName << ['fields', 'class', 'metaClass', 'Fields', 'getClass']
    }

    def "_rmUpdateAppSettings throws when the hub rejects the settings write (status >= 400) rather than reporting success (issue #105 PR2a #4)"() {
        given: 'a rule whose settings POST the hub will reject with a 4xx (e.g. a stale version token)'
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, "r", [[name: "origLabel", type: "text"]])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        // hubInternalPostForm returns a status Map and does NOT throw on 4xx (its real contract --
        // siblings _rmClickAppButton/_rmSubmitFullPageForm already guard resp.status >= 400 and throw).
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 409, location: null, data: 'stale version token']
        }

        when: 'the central settings writer posts and the hub rejects it'
        script._rmUpdateAppSettings(100, [origLabel: "newlabel"], null)

        then: 'the rejection surfaces as a thrown error -- a rejected write must never be reported as success'
        def ex = thrown(IllegalStateException)
        ex.message.contains("409")
        ex.message.contains("100")
    }

    def "STPage cond/oper picker writes always emit their enum type sidecar, so a lagged cached echo can't no-op them (multi-condition + sub-expression RE regression)"() {
        // Regression gate for the multi-condition / sub-expression Required Expression failures
        // (live-root-caused 2026-06-21 via the native RM wizard + claude-in-chrome). The request-
        // scoped page cache consumes each write's inline POST echo with NO verify-GET -- that is the
        // round-trip win that keeps a multi-condition build under the relay budget. But a
        // submitOnChange echo for the `cond`/`oper` pickers LAGS one render behind: a gap-operator
        // (and the close-sub-expression operator) echoes a page that OMITS the very field the NEXT
        // picker write needs. Reading that cached schema, _rmBuildSettingsBody would find no
        // `cond`/`oper` entry and DROP the `<key>.type=enum` sidecar, which RM silently no-ops -- the
        // walker then throws "rCapab_<N> not in STPage schema after cond=a". The fix emits the
        // picker's KNOWN enum type explicitly, so the write lands against the hub's real page
        // regardless of the cached schema -- no invalidation, no extra re-fetch (the alternative,
        // re-fetching the settled page after every operator, is exactly the round-trip cost this PR
        // exists to remove). This stub primes the cache with a lagged oper echo that omits `cond`.
        given:
        // The page the oper write caches LACKS `cond` (its echo lags -- still the oper page).
        def operPage = [[name: "oper", type: "enum", options: ["": "Click to set", "AND": "AND"]], [name: "doneST", type: "button"]]
        int getCount = 0
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            getCount++
            ruleConfigJson(100, "r", operPage)
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            // Lagged echo: still the oper page (omits `cond`) -- this is what the cache stores.
            [status: 200, location: null, data: ruleConfigJson(100, "r", operPage)]
        }

        when: "an oper write caches its lagged echo, then a cond=a write builds its body from that cache"
        def cache = [:]
        script._rmWriteSubPageField(100, "STPage", "mainPage", "name", 0, [unUsed: null], "oper", "AND", cache)
        int getsAfterOper = getCount
        script._rmWriteSubPageField(100, "STPage", "mainPage", "name", 0, [unUsed: null], "cond", "a", cache)

        then: "the cond=a body carries cond.type=enum despite the cached schema lacking `cond` (forced, not schema-derived)"
        def condWrite = posts.reverse().find { it.body["settings[cond]"] == "a" }
        condWrite != null
        condWrite.body["cond.type"] == "enum"
        condWrite.body["cond.multiple"] == "false"

        and: "the oper write likewise forces its enum type"
        def operWrite = posts.find { it.body["settings[oper]"] == "AND" }
        operWrite.body["oper.type"] == "enum"

        and: "no extra re-fetch: the cond=a write read the cached page (no STPage GET beyond the oper write's own)"
        getCount == getsAfterOper
    }

    def "cached cond=a echo: the rCapab discovery read HITs the request cache, no extra STPage GET"() {
        // TEST E (fuller walker variant). Guards lib ~10105: after the walker writes cond=a via
        // _rmWriteSubPageField (plain-params path), the POST's inline page echo -- which carries the
        // freshly-allocated rCapab_<N> slot -- is stored in the request cache via _rmCacheStore. The
        // very next _rmFetchConfigJson(appId, "STPage", rmCache) that discovers the condition slot
        // index (cIdx) MUST resolve off that cached echo, issuing NO additional live STPage GET. This
        // is the round-trip win the PR depends on; a regression that invalidated the cond=a echo would
        // re-fetch here and re-introduce a round-trip per condition.
        //
        // This is the FULLER walker variant requested: it drives the real cond=a write primitive
        // (_rmWriteSubPageField, exactly as the STPage walker's writeST closure does) plus the real
        // discovery read (_rmFetchConfigJson), so the cache contract is exercised end-to-end through
        // production code rather than asserted directly on _rmCacheStore.
        given:
        enableWrite()
        int getCount = 0
        // The live STPage GET (cache MISS path). If the discovery read ever issues a live GET, this
        // increments -- the negative assertion below catches it. rCapab_7 to prove cIdx parses off the echo.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            getCount++
            ruleConfigJson(100, "r", [
                [name: "cond", type: "enum", options: ["a": "New condition", "b": "( sub-expression"]],
                [name: "rCapab_7", type: "enum", options: ["Switch", "Contact", "Motion"]],
                [name: "doneST", type: "button"]
            ])
        }
        // The cond=a write's inline POST echo: the SETTLED new-condition page carrying the allocated
        // rCapab_7 slot. _rmWriteSubPageField stores this in the cache via _rmCacheStore, so the
        // discovery read that follows is a HIT. (Same echo-construction idiom as the ~1573 sidecar test.)
        def condAEchoPage = [
            [name: "cond", type: "enum", options: ["a": "New condition", "b": "( sub-expression"]],
            [name: "rCapab_7", type: "enum", options: ["Switch", "Contact", "Motion"]],
            [name: "rDev_7", type: "capability.sensor", multiple: true],
            [name: "doneST", type: "button"]
        ]
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            // Inline page echo = the post-write re-rendered STPage carrying rCapab_7.
            [status: 200, location: null, data: ruleConfigJson(100, "r", condAEchoPage)]
        }

        when: "the walker writes cond=a (plain-params STPage write, exactly as writeST does), then discovers cIdx"
        def rmCache = [:]
        // _rmWriteSubPageField with all-null hrefParams takes the plain-GET path and consumes the
        // inline echo into the cache -- this is the production cond=a write primitive.
        script._rmWriteSubPageField(100, "STPage", "mainPage", "name", 0, [unUsed: null], "cond", "a", rmCache)
        int getsAfterCondWrite = getCount
        // The discovery read (lib ~10105): resolves the rCapab_<N> slot off the cached cond=a echo.
        def navResp = script._rmFetchConfigJson(100, "STPage", rmCache)
        def stInputs = (navResp?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        def rCapabInput = stInputs.find { it?.name?.toString()?.startsWith("rCapab_") }
        def m = rCapabInput?.name?.toString() =~ /rCapab_(\d+)/
        def cIdx = m ? ((m[0] as List)[1] as Integer) : null

        then: "the discovery read did NOT issue an additional STPage GET (it HIT the cached cond=a echo)"
        getCount == getsAfterCondWrite

        and: "the cond=a write itself emitted its enum-type sidecar (forced, regardless of cached schema)"
        def condWrite = posts.find { it.body["settings[cond]"] == "a" }
        condWrite != null
        condWrite.body["cond.type"] == "enum"

        and: "the slot index resolved correctly off the cached echo (rCapab_7 -> cIdx 7)"
        cIdx == 7
    }

    def "toolDeleteNativeApp force-delete drops the deferred predCapabs-clear flag"() {
        // F. A deleted rule can't carry a deferred clear, so toolDeleteNativeApp drops the flag on a
        // successful delete (both force and soft paths; lib ~12528/~12551) -- otherwise a later rule
        // reusing the same appId would inherit a stale flag and fire a wasted ghost clear. Force path
        // here (copies the existing force-delete spec's hubInternalGetRaw 302 stubbing).
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "to-delete") }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/installedapps", data: ""]
        }
        atomicStateMap.predClearPending = ["100": true]

        when: "the rule is force-deleted while flagged"
        def result = script.toolDeleteNativeApp([appId: 100, confirm: true, force: true])

        then: "the delete succeeded"
        result.success == true

        and: "the deferred predCapabs-clear flag is dropped -- the rule is gone, no stale flag survives"
        !atomicStateMap.predClearPending?.get("100")
    }

    def "_rmSendActionErrorNote steers a load-failed pause AND resume to the single pausRule toggle button"() {
        // Pause and resume share ONE toggle page button, pausRule, whose title flips with the rule's run
        // state (like stopRule) -- clicking pausRule on a paused rule resumes it. There is no resRule button,
        // so BOTH the pause and resume notes must steer to pausRule and neither may mention resRule. The
        // per-app load limiter is sticky (an app disable/enable clears only the app instance's block, so a
        // bounced retry can re-trip at once; only a reboot resets the counters), so the note must not
        // advise a bare retry. A non-load failure keeps the plain note. Both-ways: re-introducing a resRule
        // steer for resume makes the resume assertion red; dropping the sticky wording drops it from the
        // note; dropping the marker gate makes an unrelated error emit the load steer.
        expect: "a load-failed resume steers to the pausRule toggle and never resRule, with sticky-limiter wording"
        def resumeNote = script._rmSendActionErrorNote("resumeRule", [100], "App 100 generates excessive hub load")
        resumeNote.contains("pausRule")
        resumeNote.contains("hub_call_app_form(app_id=100")
        resumeNote.contains("returned pause control")
        !resumeNote.contains("resRule")
        resumeNote.contains("sticky")

        and: "a load-failed pause steers to the same pausRule toggle and never resRule"
        def pauseNote = script._rmSendActionErrorNote("pauseRule", [100], "App 100 generates excessive hub load")
        pauseNote.contains("pausRule")
        pauseNote.contains("returned pause control")
        !pauseNote.contains("resRule")
        pauseNote.contains("sticky")

        and: "a load-failed MULTI-id pause steers to a per-rule pausRule drive naming every id"
        def multiNote = script._rmSendActionErrorNote("pauseRule", [100, 101], "App 100 generates excessive hub load")
        multiNote.contains("pausRule")
        multiNote.contains("per rule")
        multiNote.contains("100, 101")
        multiNote.contains("CHECK EACH RULE'S CURRENT STATE FIRST")
        !multiNote.contains("resRule")
        multiNote.contains("sticky")

        and: "a non-pause/resume load failure gets the sticky-limiter note with no page button"
        def genNote = script._rmSendActionErrorNote("runRuleAct", [100], "App 100 generates excessive hub load")
        genNote.contains("sticky")
        !genNote.contains("resRule")
        !genNote.contains("pausRule")

        and: "an unrelated (non-load) failure keeps the plain note -- the marker gate is closed"
        def plain = script._rmSendActionErrorNote("resumeRule", [100], "some other error")
        !plain.contains("pausRule")
        !plain.contains("resRule")
        !plain.contains("sticky")
    }

    def "walk schema keeps sandbox-sensitive enum option keys and labels"() {
        given:
        def options = [fields: 'Fields label', class: 'Class label', metaClass: 'Meta label', Fields: 'Upper label', getClass: 'Data label']
        def page = [sections: [[input: [[name: 'choice', type: 'enum',
                                       options: options.collect { key, value -> [(key): value] }]]]]]

        when:
        def result = script._rmCollectWalkSchema(page, [choice: 'fields'])

        then:
        result.inputs[0].options == options.collect { key, value -> [value: key, label: value] }
        result.inputs[0].currentValue == 'fields'
    }

    private void registerEmptyRenderRule(int appId, Closure doActPageGet) {
        hubGet.register("/installedapp/configure/json/${appId}") { params -> ruleConfigJson(appId, "r", []) }
        hubGet.register("/installedapp/configure/json/${appId}/selectActions") { params ->
            JsonOutput.toJson([
                app: [id: appId, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true, version: 7,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "selectActions", title: "A", install: false, error: null,
                             sections: [[title: "", input: [], body: [[element: "href", name: "name", page: "doActPage"]]]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register("/installedapp/configure/json/${appId}/doActPage", doActPageGet)
        hubGet.register("/installedapp/statusJson/${appId}") { params -> statusJson(appId) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.pauseExecution = { Long ms -> }
    }

    def "a non-walker navigate (the commit-only callers) never re-reads an empty render"() {
        given: "a direct call without the walker's opt-in"
        enableWrite()
        def rereads = 0
        registerEmptyRenderRule(100) { params ->
            rereads++
            JsonOutput.toJson([app: [id: 100, version: 7], configPage: [name: "doActPage", sections: [[input: [[name: "actType.1", type: "enum"]]]]]])
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: JsonOutput.toJson([app: [id: 100, version: 7], configPage: [name: "doActPage", sections: []]])]
        }

        when:
        def navResp = script._rmNavigateToPage(100, "selectActions", "doActPage")

        then: 'the empty render comes back as-is: no pause, no GET, no marker'
        rereads == 0
        navResp.configPage.sections == []
        !navResp.containsKey("navRetried")
    }

    def "hub_get_rule_health surfaces ok=true with no broken markers on a clean rule"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Healthy Rule", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == true
        result.label == "Healthy Rule"
        result.brokenMarkers == [] || result.brokenMarkers?.isEmpty()
        result.multipleFlagPoison == [] || result.multipleFlagPoison?.isEmpty()
    }

    def "hub_get_rule_health reads stopped from the config-page label decoration"() {
        given: "the runtime decoration arrives as markup a user-typed name cannot contain"
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params ->
            ruleConfigJson(100, 'Porch Light <span style="color:green">(Stopped)</span>', [])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolCheckRuleHealth([appId: 100])

        then:
        result.stopped == true

        and: "the decoration is stripped so the label stays the rule's real name"
        result.label == "Porch Light"
    }

    def "hub_get_rule_health reports stopped=false on an undecorated label"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Porch Light", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolCheckRuleHealth([appId: 100])

        then:
        result.stopped == false
        result.label == "Porch Light"
    }

    def "a rule literally NAMED ending in (Stopped) is not reported as stopped"() {
        given: "a plain unmarked suffix with no runtime stopped flag behind it"
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Alert When Stopped (Stopped)", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolCheckRuleHealth([appId: 100])

        then: "the name is left alone -- stripping it would rename the rule in every report"
        result.stopped == false
        result.label == "Alert When Stopped (Stopped)"
    }

    def "a stopped rule can also have a literal (Stopped) name suffix"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Porch Light (Stopped)", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([installedApp: [id: 100], appSettings: [], eventSubscriptions: [],
                               scheduledJobs: [], appState: [[name: "stopped", value: true]]])
        }

        when:
        def result = script.toolCheckRuleHealth([appId: 100])

        then:
        result.stopped == true
        result.label == "Porch Light (Stopped)"
    }

    def "hub_get_rule_health reports 0 live counts when statusJson has no subscriptions section"() {
        given: "a schedule-only rule carries no eventSubscriptions list at all"
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Timer Rule", []) }
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([installedApp: [id: 100], appSettings: [], appState: [:]])
        }

        when:
        def result = script.toolCheckRuleHealth([appId: 100])

        then: "0, not null -- null is reserved for the fetch itself failing"
        result.eventSubscriptionCount == 0
        result.scheduledJobCount == 0
        !result.checkErrors.any { it.toString().startsWith("statusJson:") }
    }

    def "an unreadable statusJson yields null counts AND a checkErrors entry"() {
        given: "the config page reads fine but the runtime status does not"
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Porch Light", []) }

        when:
        def result = script.toolCheckRuleHealth([appId: 100])

        then: "null counts alone would read as 'this rule has none' -- record the reason"
        result.eventSubscriptionCount == null
        result.scheduledJobCount == null
        result.checkErrors.any { it.toString().startsWith("statusJson:") }
    }

    def "hub_get_rule_health flags BROKEN marker in label as ok=false"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Some Rule *BROKEN*", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == false
        result.issues != null
        result.issues.any { it.toString().toLowerCase().contains("broken") }
    }

    private List ifStructureSettings(List<Map> rows) {
        // rows = [[idx: N, actType: "condActs", actSubType: "getIfThen"], ...]
        // A row that OMITS a key emits no setting for it — that is the UI-built
        // shape, where RM 5.1 persists actSubType alone on ELSE / ELSE-IF /
        // END-IF rows. An explicit "" emits the key with an empty value.
        def out = []
        rows.each { r ->
            if (r.containsKey("actType")) out << [name: "actType.${r.idx}".toString(), value: r.actType]
            if (r.containsKey("actSubType")) out << [name: "actSubType.${r.idx}".toString(), value: r.actSubType]
        }
        out
    }

    def "hub_get_rule_health flags structural imbalance when an IF has no closing END-IF (issue #178)"() {
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
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues instanceof List
        result.structuralIssues.size() == 1
        result.structuralIssues[0].toString().contains("action 9")
        result.structuralIssues[0].toString().contains("never closed")
        result.issues.any { it.toString().contains("structural imbalance") }
    }

    def "hub_get_rule_health flags an orphaned END-IF (more END-IFs than IFs)"() {
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
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.any { it.toString().contains("orphaned closer") }
        result.structuralIssues.any { it.toString().contains("action 4") }
    }

    def "hub_get_rule_health flags an orphaned End-Repeat closing nothing"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "lockActs", actSubType: "getLULock"],
            [idx: 2, actType: "repeatActs", actSubType: "getStopRepeat"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Orphan Stop", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.any { it.toString().contains("End Repeat") }
        result.structuralIssues.any { it.toString().contains("orphaned closer") }
    }

    def "hub_get_rule_health flags ELSE-IF outside any IF block"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "condActs", actSubType: "getElseIf"],
            [idx: 2, actType: "lockActs", actSubType: "getLULock"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Stray ElseIf", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.any { it.toString().contains("ELSE-IF") }
        result.structuralIssues.any { it.toString().contains("outside any IF block") }
    }

    def "hub_get_rule_health flags END-IF closing a Repeat block (mismatched closer)"() {
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
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.any { it.toString().contains("END-IF") && it.toString().contains("closes a Repeat block") }
    }

    def "hub_get_rule_health reports ok on a balanced nested IF / IF / END-IF / IF / END-IF / END-IF"() {
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
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    def "hub_get_rule_health reports ok on IF / ELSE-IF / ELSE / END-IF sequence"() {
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
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    def "hub_get_rule_health reports ok on Repeat / End-Repeat sequence"() {
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
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    def "hub_get_rule_health reports ok on a UI-built nested IF ELSE IF ELSE END-IF END-IF with no actType on the closers"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 2,  actType: "condActs",   actSubType: "getIfThen"],
            [idx: 3,  actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 4,                         actSubType: "getElse"],
            [idx: 7,  actType: "condActs",   actSubType: "getIfThen"],
            [idx: 8,  actType: "switchActs", actSubType: "getOnOffSwitch"],
            // Both halves present but empty — a real shape on live rules; empty
            // must read as absent, not as a partial commit or a phantom block.
            [idx: 10, actType: "",           actSubType: ""],
            [idx: 11, actType: "modeActs",   actSubType: "getSetVariable"],
            [idx: 12,                        actSubType: "getElse"],
            [idx: 13, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 14, actType: "modeActs",   actSubType: "getSetVariable"],
            [idx: 15,                        actSubType: "getEndIf"],
            [idx: 16,                        actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "UI Built Nested", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        // actionList present, so the walk is scoped to the rule's own actions.
        hubGet.register('/app/ruleBuilderJson/100') { params ->
            JsonOutput.toJson([broken: false,
                               actionList: ["2", "3", "4", "7", "8", "11", "12", "13", "14", "15", "16"]])
        }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    def "hub_get_rule_health reports ok on a UI-built IF ELSE-IF ELSE END-IF with no actType on any branch row"() {
        given: "the ELSE-IF row is actSubType-only too -- the one branch keyword the other fixtures never exercise"
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "condActs",   actSubType: "getIfThen"],
            [idx: 2, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 3,                        actSubType: "getElseIf"],
            [idx: 4, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 5,                        actSubType: "getElse"],
            [idx: 6, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 7,                        actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "UI ElseIf", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        hubGet.register('/app/ruleBuilderJson/100') { params ->
            JsonOutput.toJson([broken: false, actionList: ["1", "2", "3", "4", "5", "6", "7"]])
        }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    def "hub_get_rule_health reports ok on a UI-built REPEAT ... END-REP with no actType on the closer"() {
        given: "the repeat family: the walker must infer repeatActs, not condActs, for these rows"
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1,                        actSubType: "getRepeat"],
            [idx: 2, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 3,                        actSubType: "getStopRepeat"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "UI Repeat", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        hubGet.register('/app/ruleBuilderJson/100') { params ->
            JsonOutput.toJson([broken: false, actionList: ["1", "2", "3"]])
        }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    def "hub_get_rule_health still catches a REPEAT closed by an END-IF when neither row carries an actType"() {
        given: "the discriminating negative: a helper that mapped every subtype to condActs would pass the two tests above and miss this"
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1,                        actSubType: "getRepeat"],
            [idx: 2, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 3,                        actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "UI Mismatch", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        hubGet.register('/app/ruleBuilderJson/100') { params ->
            JsonOutput.toJson([broken: false, actionList: ["1", "2", "3"]])
        }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then: "a mismatched closer is a structural issue even with the actType inferred"
        !(result.structuralIssues == null || result.structuralIssues.isEmpty())
        result.structuralIssues.any { it.toString().toLowerCase().contains("mismatched") }
    }

    def "hub_get_rule_health honours an EMPTY actionList: leftover rows are orphans, not an unclosed block"() {
        given: "every action was removed but its actType/actSubType rows survived in settings"
        // The compiled list is [] -- the rule genuinely has no actions. Treating empty as unknown
        // sent the health walk back down the settings scan, where the stale IF read as an
        // opened-never-closed block: #393 in its zero-action form.
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 7, actType: "condActs", actSubType: "getIfThen"],
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Emptied", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        hubGet.register('/app/ruleBuilderJson/100') { params ->
            JsonOutput.toJson([broken: false, actionList: []])
        }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then: "no structural issue, and the leftover is named as an orphan"
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
        result.orphanedActionRows.any { it.toString().contains("action 7") && it.toString().contains("getIfThen") }
    }

    def "_rmActTypeForStructuralSubType infers the family for every structural subtype (#subType)"() {
        expect: "UI-authored rows carry no actType; the walker needs it to pair openers with closers"
        script._rmActTypeForStructuralSubType(subType) == family

        where:
        subType         | family
        'getIfThen'     | 'condActs'
        'getElseIf'     | 'condActs'
        'getElse'       | 'condActs'
        'getEndIf'      | 'condActs'
        'getRepeat'     | 'repeatActs'
        'getWhile'      | 'repeatActs'
        'getStopRepeat' | 'repeatActs'
        'getLogMsg'     | null
    }

    def "hub_get_rule_health reports ok on a UI-built flat IF ELSE END-IF with no actType on the closers"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "condActs",   actSubType: "getIfThen"],
            [idx: 2, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 3,                        actSubType: "getElse"],
            [idx: 4, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 5,                        actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "UI Built Flat", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        hubGet.register('/app/ruleBuilderJson/100') { params ->
            JsonOutput.toJson([broken: false, actionList: ["1", "2", "3", "4", "5"]])
        }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == true
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
    }

    def "hub_get_rule_health still flags the outer IF when a UI-built rule is genuinely missing its last END-IF"() {
        given:
        enableReadOnly()
        // The nested UI-built rule above with the outermost END-IF row gone.
        def settings = ifStructureSettings([
            [idx: 2,  actType: "condActs",   actSubType: "getIfThen"],
            [idx: 3,  actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 4,                         actSubType: "getElse"],
            [idx: 7,  actType: "condActs",   actSubType: "getIfThen"],
            [idx: 8,  actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 11, actType: "modeActs",   actSubType: "getSetVariable"],
            [idx: 12,                        actSubType: "getElse"],
            [idx: 13, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 14, actType: "modeActs",   actSubType: "getSetVariable"],
            [idx: 15,                        actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "UI Built Unclosed", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then: "exactly one issue, naming the OUTER IF — the inner IF is closed by the surviving END-IF"
        result.ok == false
        result.structuralIssues.size() == 1
        result.structuralIssues[0].toString().contains("action 2")
        result.structuralIssues[0].toString().contains("never closed")
    }

    def "hub_get_rule_health flags a UI-built orphaned END-IF that has no actType"() {
        given:
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 2,                        actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "UI Built Orphan", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.any { it.toString().contains("orphaned closer") }
        result.structuralIssues.any { it.toString().contains("action 2") }
    }

    private String ruleBuilderJsonWithActionList(List actionList) {
        JsonOutput.toJson([broken: false, actionList: actionList])
    }

    def "hub_get_rule_health ignores a stale settings row that is not one of the rule's actions"() {
        given: "a balanced rule whose settings also carry a stale IF and three blanked rows"
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1,  actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 3,                         actSubType: ""],
            [idx: 6,  actType: "condActs",   actSubType: "getIfThen"],
            [idx: 8,  actType: "modeActs",   actSubType: "getSetVariable"],
            [idx: 10, actType: "",           actSubType: ""],
            [idx: 12, actType: "condActs",   actSubType: "getIfThen"],
            [idx: 13, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 14,                        actSubType: "getElse"],
            [idx: 15, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 16,                        actSubType: "getEndIf"],
            [idx: 17, actType: "",           actSubType: ""]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Stale Row", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        // 3, 6, 10 and 17 are in settings but NOT in the rule.
        hubGet.register('/app/ruleBuilderJson/100') { params ->
            ruleBuilderJsonWithActionList(["1", "8", "12", "13", "14", "15", "16"])
        }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then: "the stale IF at 6 is not walked, so the rule reads balanced"
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
        !result.issues.any { it.toString().contains("action 6") }
        result.ok == true

        and: "it is still surfaced — as a leftover row, not as an imbalance"
        result.orphanedActionRows.any { it.toString().contains("action 6") }
        result.orphanedActionRows.any { it.toString().contains("getIfThen") }

        and: "rows blank on both keys are vestigial, not leftovers"
        !result.orphanedActionRows.any { it.toString().contains("action 10") }
        !result.orphanedActionRows.any { it.toString().contains("action 17") }
    }

    def "hub_get_rule_health walks a stale row when the rule's action list cannot be read"() {
        given: "the same settings, but no compiled action list to scope membership"
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1,  actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 6,  actType: "condActs",   actSubType: "getIfThen"],
            [idx: 12, actType: "condActs",   actSubType: "getIfThen"],
            [idx: 13, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 16,                        actSubType: "getEndIf"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "No Action List", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then: "the fallback cannot tell a leftover from a live row, so it walks it"
        result.structuralIssues.any { it.toString().contains("action 6") }

        and: "and it reports no leftovers, because membership is unknown"
        result.orphanedActionRows == [] || result.orphanedActionRows?.isEmpty()
    }

    def "hub_get_rule_health still flags a genuine missing END-IF when the action list is present"() {
        given: "an unclosed IF that IS one of the rule's actions"
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "condActs",   actSubType: "getIfThen"],
            [idx: 2, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 3,                        actSubType: "getElse"],
            [idx: 4, actType: "switchActs", actSubType: "getOnOffSwitch"]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Unclosed In Rule", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        hubGet.register('/app/ruleBuilderJson/100') { params ->
            ruleBuilderJsonWithActionList(["1", "2", "3", "4"])
        }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then: "membership scoping does not blind the walker to real damage"
        result.ok == false
        result.structuralIssues.size() == 1
        result.structuralIssues[0].toString().contains("action 1")
        result.structuralIssues[0].toString().contains("never closed")
    }

    def "hub_get_rule_health keeps the half-commit diagnostic for a row the rule does contain"() {
        given: "actType committed, actSubType empty, on an index the rule lists"
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 2, actType: "condActs",   actSubType: ""]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "In Rule Half", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        hubGet.register('/app/ruleBuilderJson/100') { params -> ruleBuilderJsonWithActionList(["1", "2"]) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then: "a half-written row INSIDE the rule is still an opaque block boundary"
        result.structuralIssues.size() == 1
        result.structuralIssues[0].toString().contains("partial-commit state")
        result.orphanedActionRows == [] || result.orphanedActionRows?.isEmpty()
    }

    def "hub_get_rule_health reports a half-written row outside the rule as a leftover, not an imbalance"() {
        given: "the same half-written shape on an index the rule does NOT list"
        enableReadOnly()
        def settings = ifStructureSettings([
            [idx: 1, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 2, actType: "condActs",   actSubType: ""]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Out Of Rule Half", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }
        hubGet.register('/app/ruleBuilderJson/100') { params -> ruleBuilderJsonWithActionList(["1"]) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then: "the detection survives, moved to the non-gating field"
        result.structuralIssues == [] || result.structuralIssues?.isEmpty()
        result.orphanedActionRows.any { it.toString().contains("action 2") }
        result.orphanedActionRows.any { it.toString().contains("condActs") }
        result.ok == true
    }

    def "hub_get_rule_health still reports a half-committed action whose actType landed without its actSubType"() {
        given:
        enableReadOnly()
        // The reverse half-pair of the UI shape: actType committed, actSubType
        // left empty by an interrupted wizard write. Still a partial commit.
        def settings = ifStructureSettings([
            [idx: 1, actType: "switchActs", actSubType: "getOnOffSwitch"],
            [idx: 2, actType: "condActs",   actSubType: ""]
        ])
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Half Committed", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100, settings) }

        when:
        def result = script.handleGateway("hub_manage_native_rules_and_apps", "hub_get_rule_health", [appId: 100])

        then:
        result.ok == false
        result.structuralIssues.size() == 1
        result.structuralIssues[0].toString().contains("action 2")
        result.structuralIssues[0].toString().contains("partial-commit state")
    }

    private String parentConfigJson(int parentId, List<Map> children) {
        JsonOutput.toJson([
            app: [id: parentId, label: "Rule Machine"],
            configPage: [name: "mainPage", title: "RM", install: true, error: null, sections: []],
            settings: [:],
            childApps: children
        ])
    }

    def "hub_clone_native_app requires confirm=true"() {
        given: enableWrite()

        when: script.toolCloneNativeApp([sourceAppId: 100])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("SAFETY CHECK FAILED")
    }

    def "hub_clone_native_app throws when sourceAppId is missing"() {
        given: enableWrite()

        when: script.toolCloneNativeApp([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains("sourceappid")
    }

    def "hub_clone_native_app throws when source app config fetch returns empty"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/999') { params -> "" }

        when: script.toolCloneNativeApp([sourceAppId: 999, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("999")
        ex.message.toLowerCase().contains("not found")
    }

    def "hub_clone_native_app drives the full appCloner wizard and returns the new appId"() {
        given:
        enableWrite()
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

    def "hub_clone_native_app stageDisabled disables the new app AND every descendant after the clone"() {
        given: "the full clone harness, a child AND grandchild under the new clone, and the disable endpoints"
        enableWrite()
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
        hubGet.register('/installedapp/configure/json/250') { params -> parentConfigJson(250, [[id: 251, label: "child controller"]]) }
        hubGet.register('/installedapp/configure/json/251') { params -> parentConfigJson(251, [[id: 252, label: "grandchild button rule"]]) }
        hubGet.register('/installedapp/configure/json/252') { params -> parentConfigJson(252, []) }
        hubGet.register('/installedapp/json/250') { params -> '{"id":250,"disabled":true}' }
        hubGet.register('/installedapp/json/251') { params -> '{"id":251,"disabled":true}' }
        hubGet.register('/installedapp/json/252') { params -> '{"id":252,"disabled":true}' }
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
        def disablePosts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            disablePosts << [path: path, body: body]
            '{"status":"success"}'
        }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, stageDisabled: true, confirm: true])

        then: "the clone succeeds and the app, its child, AND its grandchild were disabled + read-back verified"
        result.success == true
        result.newAppId == 250
        result.stagedDisabled == [250, 251, 252]
        result.stageFailures == null
        result.note.contains("Staged inactive")

        and: "each /installedapp/disable request names a distinct expected id (not the same app twice)"
        def disabledIds = disablePosts.findAll { it.path == "/installedapp/disable" }
            .collect { new groovy.json.JsonSlurper().parseText(it.body).id }
        disabledIds == [250, 251, 252]
    }

    def "hub_clone_native_app surfaces isError + error when child discovery returns null (soft-failure shape)"() {
        // Cloner fires but _appClonerDiscoverNewChild can't find the new
        // child (race, parent re-fetch lag, etc.). Pre-fix the return was
        // {success: false, newAppId: null, note: "..."} with no isError/error
        // — divergent from the rest of the file's error contract and
        // invisible to LLM callers that branch on isError.
        given:
        enableWrite()
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

    def "hub_clone_native_app rename writes settings[newName<sourceId>] before importNow"() {
        given:
        enableWrite()
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

    def "hub_export_native_app pulls JSON from the cloner's form-refresh response"() {
        given:
        enableWrite()
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

    def "hub_export_native_app saveAs uploads to File Manager"() {
        given:
        enableWrite()
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

    def "hub_import_native_app requires parentHintAppId + confirm"() {
        given: enableWrite()

        when:
        script.toolImportNativeApp([jsonContent: '{"appReplacements":{"100":{}}}'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("SAFETY CHECK FAILED") || ex.message.toLowerCase().contains("parenthint")
    }

    def "hub_import_native_app rejects non-JSON content"() {
        given: enableWrite()

        when:
        script.toolImportNativeApp([jsonContent: 'not-json', parentHintAppId: 100, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains("not valid json") || ex.message.toLowerCase().contains("appreplacements")
    }

    def "hub_import_native_app rejects JSON without appReplacements"() {
        given: enableWrite()

        when:
        script.toolImportNativeApp([jsonContent: '{"foo":"bar"}', parentHintAppId: 100, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains("appreplacements")
    }

    def "hub_import_native_app throws when neither jsonContent nor fromFile is provided"() {
        // Pre-issue-#204, a top-level anyOf in the inputSchema rejected the
        // no-payload call at the MCP boundary. The anyOf was removed because
        // Anthropic's input_schema validator HTTP-400s on top-level
        // anyOf/oneOf/allOf (first surfaced via Haiku 4.5), so the runtime
        // throw in toolImportNativeApp is now the sole guard for this case.
        given: enableWrite()

        when:
        script.toolImportNativeApp([parentHintAppId: 100, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains("jsoncontent or fromfile")
    }

    def "hub_import_native_app drives the cloner with settings[ruleUpload]= and finds the new appId"() {
        given:
        enableWrite()
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
        importRulePosts.every { it.body?.getAt("settings[newName42]") == "" }
        !importRulePosts.any { it.body?.containsKey("settings[newName100]") }
    }

    def "hub_import_native_app stageDisabled disables the imported app + descendants (same contract as clone)"() {
        given: "the import harness plus a child under the new app and the disable endpoints"
        enableWrite()
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
        hubGet.register('/installedapp/configure/json/700') { params -> parentConfigJson(700, [[id: 701, label: "imported child"]]) }
        hubGet.register('/installedapp/configure/json/701') { params -> parentConfigJson(701, []) }
        hubGet.register('/installedapp/json/700') { params -> '{"id":700,"disabled":true}' }
        hubGet.register('/installedapp/json/701') { params -> '{"id":701,"disabled":true}' }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        def disablePosts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            disablePosts << [path: path, body: body]
            '{"status":"success"}'
        }

        when:
        def result = script.toolImportNativeApp([jsonContent: importJson, parentHintAppId: 100, stageDisabled: true, confirm: true])

        then:
        result.success == true
        result.newAppId == 700
        result.stagedDisabled == [700, 701]
        result.stageFailures == null
        result.note.contains("Staged inactive")
        def disabledIds = disablePosts.findAll { it.path == "/installedapp/disable" }
            .collect { new groovy.json.JsonSlurper().parseText(it.body).id }
        disabledIds == [700, 701]
    }

    def "hub_clone_native_app stageDisabled FAILURE flips success false with the anti-re-clone error"() {
        given: "the clone harness; the child's disable read-back reports still-enabled"
        enableWrite()
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
        hubGet.register('/installedapp/configure/json/250') { params -> parentConfigJson(250, [[id: 251, label: "child"]]) }
        hubGet.register('/installedapp/configure/json/251') { params -> parentConfigJson(251, []) }
        hubGet.register('/installedapp/json/250') { params -> '{"id":250,"disabled":true}' }
        hubGet.register('/installedapp/json/251') { params -> '{"id":251,"disabled":false}' }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> '{"status":"success"}' }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, stageDisabled: true, confirm: true])

        then: "the requested safety property did not hold -> success:false + isError, but the error forbids re-cloning"
        result.success == false
        result.isError == true
        result.newAppId == 250
        result.stagedDisabled == [250]
        result.stageFailures.size() == 1
        result.stageFailures[0].appId == 251
        result.error.contains("do NOT re-issue")
        result.error.contains("250")
    }

    def "hub_clone_native_app stageDisabled child-enumeration failure is scoped to descendants and still fails the staging"() {
        given: "the clone harness; the new app's own config read throws so its subtree is unknowable"
        enableWrite()
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
        hubGet.register('/installedapp/configure/json/250') { params -> throw new IllegalStateException("config read failed") }
        hubGet.register('/installedapp/json/250') { params -> '{"id":250,"disabled":true}' }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> '{"status":"success"}' }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, stageDisabled: true, confirm: true])

        then: "the app itself IS disabled, the enumeration failure is a distinct kind, and staging still reports failure"
        result.success == false
        result.stagedDisabled == [250]
        result.stageFailures.size() == 1
        result.stageFailures[0].kind == "childEnumeration"
        result.stageFailures[0].error.contains("DESCENDANTS")
    }

    def "hub_clone_native_app stageDisabled via gateway dispatch returns the staged envelope"() {
        given:
        enableWrite()
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
        hubGet.register('/installedapp/configure/json/250') { params -> parentConfigJson(250, []) }
        hubGet.register('/installedapp/json/250') { params -> '{"id":250,"disabled":true}' }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> '{"status":"success"}' }

        when:
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_clone_native_app',
            [sourceAppId: 100, stageDisabled: true, confirm: true])

        then:
        result.success == true
        result.stagedDisabled == [250]
        result.note.contains("Staged inactive")
    }

    private String stagingAppsList(List subtree) {
        JsonOutput.toJson([apps: [
            [data: [id: 21, name: "Rule Machine", type: "Rule Machine", disabled: false], children: [
                [data: [id: 100, name: "Source Rule", type: "Rule-5.1", disabled: false], children: []]
            ] + subtree]
        ]])
    }

    private List wireStagedCloneHarness(List disabledIds) {
        enableWrite()
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
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            if (path == "/installedapp/disable") {
                disabledIds << new groovy.json.JsonSlurper().parseText(body).id
            }
            '{"status":"success"}'
        }
        return disabledIds
    }

    def "stageDisabled enumerates the whole subtree from ONE appsList read, with no per-node configPage render"() {
        given: "a clone with a child AND a grandchild, all present in the appsList tree"
        def disabledIds = wireStagedCloneHarness([])
        hubGet.register('/hub2/appsList') { params -> stagingAppsList([
            [data: [id: 250, name: "Source Rule clone", disabled: false], children: [
                [data: [id: 251, name: "child controller", disabled: false], children: [
                    [data: [id: 252, name: "grandchild button rule", disabled: false], children: []]
                ]]
            ]]
        ]) }
        hubGet.register('/installedapp/json/250') { params -> '{"id":250,"disabled":true}' }
        hubGet.register('/installedapp/json/251') { params -> '{"id":251,"disabled":true}' }
        hubGet.register('/installedapp/json/252') { params -> '{"id":252,"disabled":true}' }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, stageDisabled: true, confirm: true])

        then: "depth-first from the clone root: the new app, its child, then the grandchild"
        result.success == true
        result.stagedDisabled == [250, 251, 252]
        result.stageFailures == null
        disabledIds == [250, 251, 252]

        and: "NOT ONE configure/json render for any staged app -- the whole point of the appsList read"
        // The BFS fallback would render /installedapp/configure/json/{250,251,252}; none of
        // those paths is even registered here, so a regression back to the BFS surfaces as a
        // childEnumeration failure rather than a silently slower success.
        def stagingRenders = hubGet.calls.findAll {
            it.path in ['/installedapp/configure/json/250', '/installedapp/configure/json/251',
                        '/installedapp/configure/json/252']
        }
        stagingRenders.isEmpty()

        and: "exactly one tree read served the whole enumeration"
        hubGet.calls.count { it.path == '/hub2/appsList' } == 1
    }

    def "stageDisabled subtree walk terminates on a cyclic appsList tree without duplicating a target"() {
        given: "the grandchild node points back at the clone root -- a cycle the recursion must not follow"
        def disabledIds = wireStagedCloneHarness([])
        hubGet.register('/hub2/appsList') { params -> stagingAppsList([
            [data: [id: 250, name: "Source Rule clone", disabled: false], children: [
                [data: [id: 251, name: "child controller", disabled: false], children: [
                    [data: [id: 250, name: "cycle back to the root", disabled: false], children: []]
                ]]
            ]]
        ]) }
        hubGet.register('/installedapp/json/250') { params -> '{"id":250,"disabled":true}' }
        hubGet.register('/installedapp/json/251') { params -> '{"id":251,"disabled":true}' }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, stageDisabled: true, confirm: true])

        then: "the visited set stopped the walk, and 250 was disabled ONCE"
        result.success == true
        result.stagedDisabled == [250, 251]
        disabledIds == [250, 251]
    }

    def "stageDisabled reports the GRANDCHILD id when a deep descendant fails to disable"() {
        given: "the grandchild's read-back still reports enabled"
        def disabledIds = wireStagedCloneHarness([])
        hubGet.register('/hub2/appsList') { params -> stagingAppsList([
            [data: [id: 250, name: "Source Rule clone", disabled: false], children: [
                [data: [id: 251, name: "child controller", disabled: false], children: [
                    [data: [id: 252, name: "grandchild button rule", disabled: false], children: []]
                ]]
            ]]
        ]) }
        hubGet.register('/installedapp/json/250') { params -> '{"id":250,"disabled":true}' }
        hubGet.register('/installedapp/json/251') { params -> '{"id":251,"disabled":true}' }
        hubGet.register('/installedapp/json/252') { params -> '{"id":252,"disabled":false}' }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, stageDisabled: true, confirm: true])

        then: "the failure names 252 -- not the root, and not the child that DID land"
        result.success == false
        result.isError == true
        result.stagedDisabled == [250, 251]
        result.stageFailures.size() == 1
        result.stageFailures[0].appId == 252
        result.stageFailures[0].kind == "disable"

        and: "the anti-re-clone steer stands, and the root is NOT reported as still live"
        result.error.contains("do NOT re-issue")
        !result.error.contains("The NEW APP ITSELF")
    }

    def "stageDisabled requested on a DISCOVERY MISS says so instead of silently not running"() {
        given: "the cloner fires but the parent never acquires the new child, so newAppId stays null"
        enableWrite()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Source Rule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context page</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentConfigJson(21, [[id: 100, label: "Source Rule"]])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 -> [status: 200, location: null, data: '{"status":"success"}'] }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, stageDisabled: true, confirm: true])

        then: "the clone may well EXIST and be LIVE -- the caller asked for staged-inactive and must hear it never ran"
        result.success == false
        result.newAppId == null
        result.stagedDisabled == []
        result.error.contains("no new child appeared")
        result.error.contains("stageDisabled was requested but could NOT run")
        result.error.contains("hub_set_app_disabled")
    }

    def "stageDisabled pauses at the response-budget checkpoint and hands back the un-attempted ids"() {
        given: "a three-app subtree and an already-spent budget clock"
        // Real _timeBudgetExceeded: a 6000ms LAN budget against a t0 of 1 is past due at the
        // harness's fixed now(). The loop deliberately skips the check on the FIRST target,
        // so the new app itself is always attempted before any pause.
        settingsMap.lanBudgetMs = 6000
        def disabledIds = wireStagedCloneHarness([])
        hubGet.register('/hub2/appsList') { params -> stagingAppsList([
            [data: [id: 250, name: "Source Rule clone", disabled: false], children: [
                [data: [id: 251, name: "child controller", disabled: false], children: [
                    [data: [id: 252, name: "grandchild button rule", disabled: false], children: []]
                ]]
            ]]
        ]) }
        hubGet.register('/installedapp/json/250') { params -> '{"id":250,"disabled":true}' }

        when:
        def result = script.toolCloneNativeApp([sourceAppId: 100, stageDisabled: true, confirm: true, __reqT0: 1L])

        then: "the root landed; the remainder is NAMED rather than lost with the whole response"
        result.stagedDisabled == [250]
        result.stageRemaining == [251, 252]
        disabledIds == [250]
        result.success == false
        result.partial == true

        and: "the error distinguishes not-yet-attempted from failed, and still forbids a re-clone"
        result.error.contains("not yet attempted")
        result.error.contains("251, 252")
        result.error.contains("do NOT re-issue")
        result.note.contains("STAGING INCOMPLETE")
    }

    def "hub_import_native_app surfaces isError + error when child discovery returns null (soft-failure shape)"() {
        // Wizard fires but _appClonerDiscoverNewChild can't diff a new child
        // under the parent. Pre-fix this returned {success: false, newAppId:
        // null, note: "..."} with no isError/error fields. LLM callers that
        // branch on isError saw nothing actionable. Mirrors the clone test
        // immediately above — same contract, same enforcement.
        given:
        enableWrite()
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

    def "hub_import_native_app preserves backslash-escapes in settings[ruleUpload] (HTTPBuilder Map encoder mangles them)"() {
        given:
        enableWrite()
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

    def "hub_export_native_app collapses appCloner's over-escaped multi-select values"() {
        given:
        enableWrite()
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

    def "hub_export_native_app extraction failure names the Required Expression as the known cause"() {
        given:
        enableWrite()
        // A rule with a Required Expression is the known appCloner-export failure
        // case: the form refresh is well-formed JSON but renders NO downloadable
        // filecontent, so extraction returns null. The error must name the Required
        // Expression + a workaround rather than only "wire format may have changed".
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Source Rule", [], 21) }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params -> clonerPageStateWithIdx("importRule", 0) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/apps/api/4242/app/100", data: ""]
        }
        def refreshResp = JsonOutput.toJson([configPage: [name: "main", sections: [[input: []]]]])
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encodedBody, Integer t = 420 ->
            [status: 200, location: null, data: refreshResp]
        }

        when:
        script.toolExportNativeApp([sourceAppId: 100])

        then: "the throw names the Required Expression as the likely cause + a workaround"
        def e = thrown(IllegalStateException)
        e.message.contains("Required Expression")
        e.message.contains("replaceRequiredExpression")
        e.message.contains("appCloner export")
    }

    def "hub_import_native_app refuses parentHintAppId that has no parent (no diff target -> would silently false-fail)"() {
        given:
        enableWrite()
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
        enableWrite()
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

    def "custom_* dispatch — every renamed tool name is routable (no Unknown tool error)"() {
        given:
        settingsMap.enableCustomRuleEngine = true

        // All 8 custom_* renames split across dispatch paths:
        //   - executeTool top-level switch: every custom_* has a case there
        //   - hub_manage_custom_rules gateway:    delete/test/export/import/clone
        // (list + diagnostics folded into hub_get_custom_rule; diagnostics now
        //  ride hub_get_custom_rule with detailed=true, not a hub_manage_diagnostics
        //  sub-tool.)
        // The point of this test is the dispatch wiring: a regression that
        // typos a case label or drops a tool from a gateway tools[] list
        // would surface as "Unknown tool" — that's the failure we're guarding.
        // We don't exercise the full underlying tool* impls; their behaviour
        // is covered elsewhere and would require setting up child apps,
        // requireDestructiveConfirm, and the rule-engine state machine.

        when: "every renamed tool name is dispatched"
        // Top-level executeTool has cases for all 8 renames.
        def topLevel = ["hub_get_custom_rule", "hub_create_custom_rule",
                        "hub_update_custom_rule", "hub_delete_custom_rule", "hub_test_custom_rule",
                        "hub_export_custom_rule", "hub_import_custom_rule", "hub_clone_custom_rule"]
        def topLevelErrors = topLevel.collect { name ->
            try { script.executeTool(name, [:]); return null }
            catch (IllegalArgumentException e) { return e.message }
            catch (Exception ignored) { return null }
        }

        // hub_manage_custom_rules gateway lists 5 tools — verify gateway routes them.
        def rulesAdminTools = ["hub_delete_custom_rule", "hub_test_custom_rule",
                               "hub_export_custom_rule", "hub_import_custom_rule", "hub_clone_custom_rule"]
        def rulesAdminErrors = rulesAdminTools.collect { name ->
            try { script.handleGateway("hub_manage_custom_rules", name, [:]); return null }
            catch (IllegalArgumentException e) { return e.message }
            catch (Exception ignored) { return null }
        }

        then: "no top-level dispatch returns 'Unknown tool: custom_*' for any of the 8 renames"
        topLevelErrors.every { msg -> msg == null || !msg.contains("Unknown tool") }

        and: "no hub_manage_custom_rules gateway dispatch returns 'Unknown tool ... in hub_manage_custom_rules'"
        rulesAdminErrors.every { msg -> msg == null || !(msg.contains("Unknown tool") && msg.contains("hub_manage_custom_rules")) }
    }

    def "_rmWriteSubPageField: a write into a hidden field with a STABLE render verifies as NOT persisted (no renderShifted mask)"() {
        // S2 genuine-rejection-not-masked pin, direct on _rmWriteSubPageField. When a
        // write targets a field absent from the page AND the page's render is stable
        // across the write (schema unchanged, value absent, render hash identical,
        // settings absent), persisted MUST be false so the caller records a genuine
        // silent_rejection. This is the contract the Site B neither-rendered integration
        // spec depends on (it deliberately uses a state-driven, NOT per-fetch, paragraph
        // so renderShifted stays false across the rejected write). renderShifted remains a
        // coarse signal that CAN mask on an unrelated paragraph delta -- that known
        // limitation is documented at the renderShifted assignment; this spec pins the
        // honest no-mask baseline so a regression that always-credits persisted is caught.
        // Both-ways pending (orchestrator).
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            [status: 200, location: null, data: '']
        }
        // STPage schema is byte-stable across the write: RelrDev_1 is NEVER present
        // (the hidden-field case) and the paragraph text does not change.
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            JsonOutput.toJson([
                app: [id: 100, name: "Rule-5.1", label: "r", trueLabel: "r", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "STPage", title: "RE", install: false, error: null,
                             sections: [[title: "", input: [
                                 [name: "cond",     type: "enum", options: ["a": "New condition"]],
                                 [name: "rCapab_1", type: "enum", options: ["Temperature"]],
                                 [name: "hasAll",   type: "button"]
                                 // RelrDev_1 intentionally absent -- the hidden-field case.
                             ], paragraphs: ["stable"]]]],
                settings: [:], childApps: []
            ])
        }

        when:
        def wr = script._rmWriteSubPageField(100, "STPage", "mainPage", "name", 0, [unUsed: null], "RelrDev_1", ">")

        then: "the write is NOT credited persisted -- a genuine silent rejection, not masked"
        wr.persisted == false

        and: "every persistence signal is honestly false (no value landed, no schema/render/settings shift)"
        wr.valueLanded == false
        wr.schemaShifted == false
        wr.renderShifted == false
        wr.settingsLanded == false
        wr.verifyFetchFailed == false
    }

    def "public native writer documents verification instead of old wizard self-bake promises"() {
        when:
        def guide = script.toolGetToolGuide('native_rule_documents').content
        then:
        guide.contains('typed readback')
        guide.contains('independently verify device behavior')
        !script.getAllToolDefinitions().any { it.name == 'hub_set_rule' }
    }

    def "custom_* visibility: full mode (engine ON) shows all 8 custom_* tools"() {
        // When enableCustomRuleEngine=true, all 8 custom_* tools must be present
        // in getToolDefinitions() regardless of the Read master state.
        // (list + diagnostics folded into hub_get_custom_rule.)
        given:
        settingsMap.enableCustomRuleEngine = true
        settingsMap.useGateways = false     // flat mode -- all tools visible by individual name

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: "all 8 custom_* tools are visible"
        ["hub_get_custom_rule", "hub_create_custom_rule", "hub_update_custom_rule",
         "hub_delete_custom_rule", "hub_test_custom_rule",
         "hub_export_custom_rule", "hub_import_custom_rule", "hub_clone_custom_rule"].every { names.contains(it) }
    }

    def "custom_* visibility: read-only mode (engine OFF + Read master ON) shows read subset only"() {
        // When enableCustomRuleEngine=false AND the Read master is ON, only the 3 read
        // tools should be visible; the 5 write/structural tools must be hidden.
        // (list + diagnostics folded into hub_get_custom_rule, which stays visible.)
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableRead = true
        settingsMap.useGateways = false

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: "read subset is visible"
        ["hub_get_custom_rule", "hub_update_custom_rule",
         "hub_test_custom_rule"].every { names.contains(it) }

        and: "write/structural subset is hidden"
        ["hub_create_custom_rule", "hub_delete_custom_rule", "hub_export_custom_rule",
         "hub_import_custom_rule", "hub_clone_custom_rule"].every { !names.contains(it) }
    }

    def "custom_* visibility: full-hide mode (engine OFF + Read master OFF) hides all 8 custom_* tools"() {
        // When the engine is OFF and the Read master is OFF (customEngineMode=off),
        // no custom_* tools should appear.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableRead = false
        settingsMap.useGateways = false

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: "all 8 custom_* tools are hidden"
        ["hub_get_custom_rule", "hub_create_custom_rule", "hub_update_custom_rule",
         "hub_delete_custom_rule", "hub_test_custom_rule",
         "hub_export_custom_rule", "hub_import_custom_rule", "hub_clone_custom_rule"].every { !names.contains(it) }
    }

    def "hub_get_custom_rule (list mode) includes source: mcp_custom_engine on every rule"() {
        // The source marker lets LLMs distinguish MCP-managed rules from
        // native RM rules (hub_list_rules / hub_manage_native_rules_and_apps).
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

    def "hub_get_custom_rule includes source: mcp_custom_engine in response"() {
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

    def "hub_update_custom_rule read-only mode: enabled field allowed"() {
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

    def "hub_update_custom_rule read-only mode: structural field rejected with legacy hint"() {
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
        ex.message.contains("hub_manage_rule_machine")
    }

    def "executeTool rejects write tool hub_create_custom_rule when customEngineMode is readonly"() {
        // Engine OFF + Read master ON => readonly mode. Write tools (create/delete/
        // export/import/clone) must be blocked at the executeTool dispatch gate,
        // not just inside toolCreateRule. This ensures the gate fires even when
        // the tool is reached via the top-level switch directly.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableRead = true

        when:
        script.executeTool("hub_create_custom_rule", [name: "x",
            triggers: [[type: "time", time: "00:00"]],
            actions: [[type: "log", message: "y"]]])

        then: "write tool blocked with read-only mode message"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("read-only mode")
        ex.message.contains("hub_manage_native_rules_and_apps")
    }

    def "hub_search_tools filters write custom_* tools in readonly mode"() {
        // Engine OFF + Read master ON => readonly mode. hub_search_tools must not
        // surface write/structural custom_* tools (create/delete/export/import/clone)
        // even though they live in the cached full corpus. Read-subset tools
        // (hub_get_custom_rule, hub_update_custom_rule, hub_test_custom_rule) must
        // still appear in results.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableRead = true
        // stateMap is cleared by setup() so corpus rebuilds fresh this test

        when:
        def result = script.toolSearchTools([query: "custom create rule", maxResults: 10])
        def resultNames = result.results*.tool as Set

        then: "write tools are filtered out of search results"
        !resultNames.contains("hub_create_custom_rule")
        !resultNames.contains("hub_delete_custom_rule")
        !resultNames.contains("hub_export_custom_rule")
        !resultNames.contains("hub_import_custom_rule")
        !resultNames.contains("hub_clone_custom_rule")

        and: "only tools from the visible count are searched"
        result.totalToolsSearched < script.buildToolSearchCorpus().size()
    }

    def "hub_search_tools filters all custom_* tools in off mode"() {
        // Engine OFF + Read master OFF => off mode. hub_search_tools must not surface
        // ANY custom_* tools -- the full set of 8 must be excluded from scoring.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableRead = false

        when: "searching for a query that would normally surface hub_get_custom_rule (list mode)"
        def result = script.toolSearchTools([query: "custom list rules", maxResults: 10])
        def resultNames = result.results*.tool as Set

        then: "no custom_* tools appear in off-mode search results"
        resultNames.every { !it.contains("custom_rule") }

        and: "visible corpus is smaller than full corpus by at least 8 (the hidden custom_* set)"
        def fullCorpusSize = script.buildToolSearchCorpus().size()
        result.totalToolsSearched <= fullCorpusSize - 8
    }

    def "executeTool rejects hub_create_custom_rule when customEngineMode is off"() {
        // Engine OFF + Read master OFF => off mode. All custom_* tools must be
        // blocked at the executeTool gate with a message that names both the engine
        // and the Read master and points to the native Rule Machine alternative.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableRead = false

        when:
        script.executeTool("hub_create_custom_rule", [name: "x",
            triggers: [[type: "time", time: "00:00"]],
            actions: [[type: "log", message: "y"]]])

        then: "off-mode gate fires naming the engine + Read master and the native alternative"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Custom Rule Engine")
        ex.message.contains("Read master")
        ex.message.contains("hub_manage_native_rules_and_apps")
    }

    def "executeTool rejects hub_get_custom_rule in off mode (read tools also blocked)"() {
        // In off mode (engine OFF + Read master OFF) even read-only custom_* tools are
        // blocked -- the entire engine is off. This is different from readonly mode where
        // the read subset is allowed. With Read OFF, hub_get_custom_rule (a read tool) is
        // stopped at the central master gate before the custom-engine gate is reached.
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableRead = false

        when:
        script.executeTool("hub_get_custom_rule", [:])

        then: "read tool blocked by the Read master in full-off mode"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Read tools are disabled")
    }

    @spock.lang.Unroll
    def "native create requires confirmation through public dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        when:
        def response = mcpDriver.callTool('hub_create_rule_document', [parent_id: 21, name: 'BAT-RM-demo', changes: [[part:'required', value:[tokens:['1']]]]])
        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('SAFETY CHECK FAILED')
        where:
        useGateways << [true, false]
    }

    def "native create requires a name through public dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        when:
        def response = mcpDriver.callTool('hub_create_rule_document', [parent_id: 21, changes: [[part:'required', value:[tokens:['1']]]], confirm:true])
        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('rule name')
        where:
        useGateways << [true, false]
    }

    def "native create dispatcher preserves verified native rule identity (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        Map received
        script.metaClass.toolCreateRuleDocument = { Map args -> received = args; [success: true, created: true, rule_id: 974] }
        def args = [parent_id: 21, name: 'BAT-RM-demo', changes: [[part:'required', value:[tokens:['1']]]], confirm:true]
        when:
        def response = mcpDriver.callTool('hub_create_rule_document', args)
        then:
        response.error == null
        !response.result.isError
        mcpDriver.parseInner(response).rule_id == 974
        received == args
        where:
        useGateways << [true, false]
    }

    def "hub_update_rule_document public validation returns an error envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        when:
        def response = mcpDriver.callTool('hub_update_rule_document', [rule_id: 100, changes: [[part:'required', value:[tokens:['1']]]]])
        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('SAFETY CHECK FAILED')
        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "native write partial rejection remains failure through the public envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.toolUpdateRuleDocument = { Map args -> [success: false, partial: true, rule_id: args.rule_id, error: 'Native typed readback rejected'] }
        when:
        def response = mcpDriver.callTool('hub_update_rule_document', [rule_id:100, changes:[[part:'required', value:[tokens:['1']]]], confirm:true])
        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        !inner.success
        inner.partial
        inner.rule_id == 100
        inner.error.contains('readback rejected')
        where:
        useGateways << [true, false]
    }

    def "hub_delete_native_app via dispatch force-deletes with snapshot when force=true (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/installedapp/configure/json/200') { params -> ruleConfigJson(200, "to-delete") }
        hubGet.register('/installedapp/statusJson/200') { params -> statusJson(200) }
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def rawCalls = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            rawCalls << path
            [status: 302, location: "/installedapps", data: ""]
        }

        when:
        def response = mcpDriver.callTool('hub_delete_native_app', [appId: 200, force: true, confirm: true])

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

    @spock.lang.Unroll
    def "hub_clone_native_app via dispatch returns isError validation result envelope when sourceAppId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_clone_native_app', [confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains("sourceappid")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_clone_native_app via dispatch drives the full appCloner wizard and returns the new appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
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
        def response = mcpDriver.callTool('hub_clone_native_app', [sourceAppId: 100, confirm: true])

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

    @spock.lang.Unroll
    def "hub_export_native_app via dispatch pulls JSON from the cloner's form-refresh response (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
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
        def response = mcpDriver.callTool('hub_export_native_app', [sourceAppId: 100])

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

    @spock.lang.Unroll
    def "hub_import_native_app via dispatch returns isError validation result envelope on non-JSON content (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_import_native_app', [jsonContent: 'not-json', parentHintAppId: 100, confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains("not valid json") ||
            mcpDriver.parseInner(response).error.toLowerCase().contains("appreplacements")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_import_native_app via dispatch returns isError validation result envelope on JSON without appReplacements (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_import_native_app', [jsonContent: '{"foo":"bar"}', parentHintAppId: 100, confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains("appreplacements")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_import_native_app via dispatch drives the cloner with settings[ruleUpload] and finds the new appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
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
        def response = mcpDriver.callTool('hub_import_native_app', [jsonContent: importJson, parentHintAppId: 100, confirm: true])

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

    @spock.lang.Unroll
    def "hub_get_rule_health via dispatch surfaces ok=true on a clean rule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Healthy Rule", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def response = mcpDriver.callTool('hub_get_rule_health', [appId: 100])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.ok == true
        inner.label == "Healthy Rule"
        inner.brokenMarkers == [] || inner.brokenMarkers?.isEmpty()
        inner.multipleFlagPoison == [] || inner.multipleFlagPoison?.isEmpty()
        inner.eventSubscriptionCount == 1
        inner.scheduledJobCount == 0

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_rule_health via dispatch flags BROKEN marker in label as ok=false (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableReadOnly()
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "Some Rule *BROKEN*", []) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def response = mcpDriver.callTool('hub_get_rule_health', [appId: 100])

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

    private String statusJsonWithLocals(int ruleId, Map locals, int subs = 1) {
        JsonOutput.toJson([
            installedApp: [id: ruleId],
            appSettings: [],
            eventSubscriptions: (1..subs).collect { [name: "evt${it}"] },
            scheduledJobs: [],
            appState: [[name: "allLocalVars", value: locals]],
            state: [:]
        ])
    }

    def "_rmReadLocalVarsMap: a non-empty Map-shaped appState surfaces a read failure (not a false empty)"() {
        // REGRESSION GUARD for the appState-shape assumption: every local-var read goes
        // through _rmReadLocalVarsMap, which assumes appState is a List of {name,value} entries.
        // A NON-EMPTY non-List shape (an unexpected contract shift) carries data we cannot
        // interpret -- returning ok:true would MASK a read failure as a genuinely-empty rule.
        // The helper must return ok:false so hub_list_rule_local_variables raises a read error
        // (distinguishable from total:0). Goes RED if the non-List guard reverts to ok:true
        // (the tool would then return a clean total:0 instead of throwing).
        given:
        enableReadOnly()
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100], appSettings: [],
                eventSubscriptions: [[name: "evt1"]], scheduledJobs: [],
                // appState as a non-empty MAP -- the contract-shift shape the guard catches.
                appState: [allLocalVars: [doomed: [type: "integer", value: 1]]], state: [:]
            ])
        }

        when:
        script.toolListRuleLocalVariables([appId: 100])

        then: "the unexpected non-empty shape surfaces as a read failure, not a false total:0"
        def ex = thrown(IllegalStateException)
        ex.message.contains("could not read")
    }

    def "_rmReadLocalVarsMap: an EMPTY Map-shaped appState still reads as zero locals (no data to lose)"() {
        // Companion to the non-empty shape-guard: an EMPTY non-List appState (e.g. [:]) carries
        // no data, so it stays a clean no-locals read (ok:true, total:0) rather than a read
        // failure. Pins the empty-vs-non-empty boundary so the read-failure branch does not
        // over-fire on a genuinely empty rule.
        given:
        enableReadOnly()
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([
                installedApp: [id: 100], appSettings: [],
                eventSubscriptions: [[name: "evt1"]], scheduledJobs: [],
                appState: [:], state: [:]
            ])
        }

        when:
        def result = script.toolListRuleLocalVariables([appId: 100])

        then: "an empty non-List shape reads as zero locals, not an error"
        result.total == 0
        result.localVariables == []
    }

    def "hub_list_rule_local_variables returns sorted name/type/value from appState.allLocalVars"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/statusJson/100') { params ->
            statusJsonWithLocals(100, [zeta: [type: "string", value: "z"], alpha: [type: "integer", value: 5]])
        }

        when:
        def result = script.toolListRuleLocalVariables([appId: 100])

        then: "every local surfaces, sorted by name, with type + value"
        result.appId == 100
        result.total == 2
        result.localVariables*.name == ["alpha", "zeta"]
        result.localVariables[0].type == "integer"
        result.localVariables[0].value == 5
        result.localVariables[1].type == "string"
    }

    @Unroll
    def "hub_list_rule_local_variables reads live #localName and preserves fallback values"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([installedApp: [id: 100], appSettings: [], eventSubscriptions: [], scheduledJobs: [],
                appState: [
                    [name: "lv_${localName}", value: [name: localName, type: "integer", value: 99], type: "HashMap"],
                    [name: "allLocalVars", value: [(localName): [type: "integer", value: 1], zzS: [type: "string", value: "a"]], type: "HashMap"]
                ], state: [:]])
        }

        when:
        def result = script.toolListRuleLocalVariables([appId: 100])

        then: "the named local reads live 99; zzS falls back to allLocalVars"
        result.localVariables == [[name: localName, type: "integer", value: 99], [name: "zzS", type: "string", value: "a"]]

        where:
        localName << ["zzN", "fields", "class", "metaClass"]
    }

    def "hub_list_rule_local_variables returns empty list when the rule has no locals"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson(100) }

        when:
        def result = script.toolListRuleLocalVariables([appId: 100])

        then: "no locals -> empty list, total 0 (not an error)"
        result.total == 0
        result.localVariables == []
    }

    def "hub_list_rule_local_variables dispatches through executeTool via hub_read_rules"() {
        given:
        enableReadOnly()
        hubGet.register('/installedapp/statusJson/100') { params -> statusJsonWithLocals(100, [k: [type: "integer", value: 1]]) }

        when:
        def result = script.executeTool("hub_list_rule_local_variables", [appId: 100])

        then: "the dispatch case routes to the implementation"
        result.total == 1
        result.localVariables[0].name == "k"
    }

    def "hub_list_rule_local_variables re-raises a transient status-read failure (not a false empty list)"() {
        // REGRESSION GUARD for the read-failure re-raise: when the status read itself fails
        // (channel down, NOT a shape contract shift), _rmReadLocalVarsMap returns ok:false and
        // the pure-read tool must RE-RAISE that as an error rather than silently returning an
        // empty list that an LLM would read as "this rule has no locals". Goes RED if the
        // ok:false re-raise at the tool boundary is dropped.
        given:
        enableReadOnly()
        hubGet.register('/installedapp/statusJson/100') { params -> throw new RuntimeException("simulated status-read channel failure") }

        when:
        script.toolListRuleLocalVariables([appId: 100])

        then: "the transient read failure surfaces as an error, not a clean total:0"
        def ex = thrown(IllegalStateException)
        ex.message.contains("could not read")
        ex.message.contains("100")
    }

    @spock.lang.Unroll
    def "hub_clone_native_app via dispatch returns isError validation result when source config fetch returns empty (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/installedapp/configure/json/999') { params -> "" }

        when:
        def response = mcpDriver.callTool('hub_clone_native_app', [sourceAppId: 999, confirm: true])

        then: "empty config fetch surfaces as isError validation result IAE (matches the direct-call test's IllegalArgumentException)"
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("999")
        mcpDriver.parseInner(response).error.toLowerCase().contains("not found")

        where:
        useGateways << [true, false]
    }

    def "native authoring reference requires native field contracts without inventing enum values"() {
        expect:
        script.toolGetToolGuide('native_rule_documents').content.contains('exact native names')
        script.toolGetToolGuide('native_app_forms').content.contains('Native enum keys')
    }

    def "_appTypeRegistry registers basic_rule with the live-verified triple + commitButton null"() {
        when:
        def reg = script._appTypeRegistry()

        then: "basic_rule is a classic dynamicPage app (generic createchild works) but submitOnChange (commitButton null)"
        reg.basic_rule == [namespace: "hubitat", appName: "Basic Rule-1.0", parentTypeName: "Basic Rules", commitButton: null]

    }

    def "_resolveCommitButton: null for submitOnChange Basic Rule, updateRule for RM-family/unknown (issue #185 item 3)"() {
        expect:
        script._resolveCommitButton("Basic Rule-1.0") == null
        script._resolveCommitButton("Rule-5.1") == "updateRule"
        script._resolveCommitButton("Some Unregistered App") == "updateRule"
        script._resolveCommitButton(null) == "updateRule"
    }

    def "retired writer has no public buttonRule creation alias"() {
        expect:
        !script.getAllToolDefinitions().any { it.name == 'hub_set_rule' }
        !script.getAllToolDefinitions().any { it.name == 'hub_set_native_app' }
    }

    def "native create gateway preserves explicit parent and ordered changes"() {
        given:
        enableWrite()
        Map received
        script.metaClass.toolCreateRuleDocument = { Map args -> received = args; [success:true, rule_id: 974] }
        def args = [parent_id:21, name:'BAT-gw-rule', changes:[[part:'required', value:[tokens:['1']]]], confirm:true]
        when:
        def result = script.handleGateway('hub_manage_rule_machine', 'hub_create_rule_document', args)
        then:
        result.rule_id == 974
        received == args
    }

    def "_resolveCommitButton: Room Lights commits with its own update button; the Room Lighting parent gets no commit click"() {
        expect:
        script._resolveCommitButton("Room Lights") == "update"
        script._resolveCommitButton("Room Lighting") == null
    }

    def "_rmBuildSettingsBody sends a device picker's {id: label} map as its id list"() {
        given:
        def schema = [roomDevsL: [name: "roomDevsL", type: "capability.switch", multiple: true]]

        when:
        def body = script._rmBuildSettingsBody(100, [roomDevsL: ["11": "Lamp", "386": "Night 1"]], schema)

        then: "the hub 500s on the map's text form; the id list is what the UI posts"
        body["settings[roomDevsL]"] == "11,386"
        body["roomDevsL.multiple"] == "true"
        body["deviceList"] == "roomDevsL"
    }

    @spock.lang.Unroll
    def "_rmBuildSettingsBody sends a device.* picker as its id list: #label"() {
        given:
        def schema = [probeDevices: [name: "probeDevices", type: "device.VirtualSwitch", multiple: true]]

        when:
        def body = script._rmBuildSettingsBody(100, [probeDevices: value], schema)

        then:
        body["settings[probeDevices]"] == expected
        body["probeDevices.type"] == "device.VirtualSwitch"
        body["deviceList"] == "probeDevices"

        where:
        label                    | value                              | expected
        'the configure/json map' | ["555": "BAT-Sw-A", "556": "B"]    | "555,556"
        'a list'                 | ["555"]                            | "555"
        "statusJson's list text" | "[728, 730]"                       | "728,730"
    }

    @spock.lang.Unroll
    def "_uiNavigationViolations mirrors the UI's page checks: #label"() {
        expect:
        script._uiNavigationViolations([f: meta], [f: value])*.problem == expected

        where:
        label                                        | meta                                                         | value   | expected
        'required device picker, no devices'         | [type: 'capability.switch', multiple: true, required: true] | [:]     | ['required but empty']
        'required enum, "[]" as statusJson stores it' | [type: 'enum', multiple: true, required: true]             | '[]'    | ['required but empty']
        'required text, zero length'                 | [type: 'text', required: true]                              | ''      | ['required but empty']
        'required text, whitespace (browser keeps)'  | [type: 'text', required: true]                              | '  '    | []
        'required text, the word null'               | [type: 'text', required: true]                              | 'null'  | []
        'required text, the text []'                 | [type: 'text', required: true]                              | '[]'    | []
        'required number, whitespace'                | [type: 'number', required: true]                            | '  '    | ['required but empty']
        'required device.* picker, no devices'       | [type: 'device.VirtualSwitch', multiple: true, required: true] | '[]' | ['required but empty']
        'required number, unset, has a default'      | [type: 'number', required: true, defaultValue: 7]           | null    | []
        'required text, filled'                      | [type: 'text', required: true]                              | 'x'     | []
        'optional empty number passes'               | [type: 'number']                                            | ''      | []
        'number below the implicit min 0'            | [type: 'number']                                            | '-1'    | ['-1 is below the minimum 0']
        'number with a fraction'                     | [type: 'number']                                            | '1.5'   | ['1.5 is not a whole number']
        'number inside its range'                    | [type: 'number', range: '-60..60']                          | '-5'    | []
        'number above its range'                     | [type: 'number', range: '1..10']                            | '11'    | ['11 is above the maximum 10']
        'range side "*" is unbounded'                | [type: 'number', range: '*..10']                            | '-500'  | []
        'decimal with two places'                    | [type: 'decimal']                                           | '2.55'  | []
        'decimal with three places'                  | [type: 'decimal']                                           | '2.555' | ['2.555 has more than two decimal places']
        'decimal may be negative without a range'    | [type: 'decimal']                                           | '-3.5'  | []
        'not a number'                               | [type: 'number']                                            | 'abc'   | ["'abc' is not a number"]
        'pattern mismatch'                           | [type: 'text', pattern: '[0-9]{3}']                         | '12a'   | ["'12a' does not match the required format"]
        'email syntax'                               | [type: 'email']                                             | 'nope'  | ["'nope' is not a valid email address"]
        'disabled input is skipped'                  | [type: 'text', required: true, disabled: true]              | ''      | []
        'buttons are never checked'                  | [type: 'button', required: true]                            | ''      | []
    }

    def "_rmSubmitSubPageDone refuses a Done the UI would block (required input empty) and posts no Done"() {
        given:
        hubGet.register('/installedapp/configure/json/650/offMeansPage') { params ->
            JsonOutput.toJson([app: [id: 650, name: "Room Lights", version: 3, appType: [name: "Room Lights", namespace: "hubitat"]],
                configPage: [name: "offMeansPage", install: false, error: null, sections: [[title: "", input: [
                    [name: "offMeans", type: "enum", multiple: true],
                    [name: "illumsOff", type: "capability.illuminanceMeasurement", multiple: true, required: true, title: "Illuminance Sensors that rise"]
                ]]]], settings: [offMeans: ["illuminance rises"]], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/650') { params ->
            statusJson(650, [[name: "offMeans", type: "enum", multiple: true, value: '["illuminance rises"]'],
                             [name: "illumsOff", type: "capability.illuminanceMeasurement", multiple: true, value: null]])
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]; [status: 200, location: null, data: '']
        }

        when:
        script._rmSubmitSubPageDone(650, "offMeansPage", "mainPage", "name", null)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("illumsOff (Illuminance Sensors that rise): required but empty")
        !posts.any { it.body?._action_previous == "Done" }
    }

    def "navigation that a default lets through also saves that default, as the page submits it"() {
        given: "a page whose required number is unset but defaults to 7"
        hubGet.register('/installedapp/configure/json/660/defPage') { params ->
            JsonOutput.toJson([app: [id: 660, name: "Diag", version: 2, appType: [name: "Diag", namespace: "x"]],
                configPage: [name: "defPage", error: null, sections: [[title: "", input: [
                    [name: "defNum", type: "number", required: true, defaultValue: 7, title: "Default number"],
                    [name: "otherText", type: "text", required: false]
                ]]]], settings: [otherText: "keep"], childApps: []])
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]; [status: 200, location: null, data: '']
        }

        when:
        script._rmNavigateToPage(660, "defPage", "mainPage", 0, "name", null, null, null, false, true)

        then: "the navigation posts the default, and nothing for the input that already has a value"
        posts.size() == 1
        posts[0].body["settings[defNum]"] == "7"
        posts[0].body["defNum.type"] == "number"
        !posts[0].body.containsKey("settings[otherText]")
    }

    def "_rmSubmitMainPageDone returns done:false uiBlocked instead of posting a Done the UI would refuse"() {
        given:
        hubGet.register('/installedapp/configure/json/653/mainPage') { params ->
            JsonOutput.toJson([app: [id: 653, name: "Group-2.1", version: 1, appType: [name: "Group-2.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", install: true, error: null, sections: [[title: "", input: [[name: "name", type: "text", required: true, title: "Select name for Group"]]]]],
                settings: [:], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/653') { params -> statusJson(653, []) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]; [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def r = script._rmSubmitMainPageDone(653)

        then:
        r.done == false
        r.uiBlocked == true
        r.reason.contains("name (Select name for Group): required but empty")
        posts.isEmpty()
    }

    def "_rmNavigateToPage with uiValidate refuses to leave a page whose required input is empty; an internal re-render is not checked"() {
        given:
        hubGet.register('/installedapp/configure/json/652/onMeansPage') { params ->
            JsonOutput.toJson([app: [id: 652, version: 2], configPage: [name: "onMeansPage", error: null, sections: [[title: "", input: [
                [name: "motions", type: "capability.motionSensor", multiple: true, required: true, title: "Motion Sensors that become active"]]]]],
                settings: [:]])
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]; [status: 200, location: null, data: '']
        }

        when: "a UI-link navigation (walkStep navigate)"
        script._rmNavigateToPage(652, "onMeansPage", "optionsOnPage", 4, "name", null, null, null, false, true)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("motions (Motion Sensors that become active): required but empty")
        posts.isEmpty()

        when: "an internal re-render of a page the caller is already working on"
        script._rmNavigateToPage(652, "onMeansPage", "optionsOnPage", 4, "name", null, null, null, false, false)

        then:
        notThrown(IllegalStateException)
        posts.size() == 1
    }

    def "pageBreadcrumbs carry forward: the trail the hub returned for a page is reused when submitting from that page"() {
        given: "an update/json answer rendering offMeansPage, trail as the hub encodes it"
        def answer = '{"status":"success","pageBreadcrumbs":"%5B%22mainPage%22%5D","cancelButton":false,' +
                     '"configPage":{"popToAncestor":null,"onUpdate":null,"name":"offMeansPage","sections":[{"input":[{"name":"offMeans"}]}]}}'

        when:
        script._rmNoteHubBreadcrumbs("2518", [status: 200, data: answer])

        then: "same app + same page -> the hub's own trail, byte for byte"
        script._rmPageBreadcrumbs(2518, "offMeansPage", '["mainPage","mainPage"]') == '%5B%22mainPage%22%5D'

        and: "another page or another app -> the caller's default"
        script._rmPageBreadcrumbs(2518, "mainPage", '[]') == '[]'
        script._rmPageBreadcrumbs(2519, "offMeansPage", 'X') == 'X'

        when: "the recorded trail is older than the TTL"
        script.HUB_PAGE_BREADCRUMBS.get("2518").at = 0L

        then:
        script._rmPageBreadcrumbs(2518, "offMeansPage", 'X') == 'X'
    }

    def "pageBreadcrumbs carry forward unescapes a JSON-escaped trail (RM back-navigation to mainPage)"() {
        given: "the hub answers a selectTriggers -> mainPage navigation with an escaped, not URL-encoded, trail"
        def answer = '{"status":"success","pageBreadcrumbs":"[\\"mainPage\\"]","cancelButton":false,' +
                     '"configPage":{"name":"mainPage","sections":[]}}'

        when:
        script._rmNoteHubBreadcrumbs("2524", [status: 200, data: answer])

        then: "the whole trail is kept, unescaped -- not cut at the first escaped quote"
        script._rmPageBreadcrumbs(2524, "mainPage", 'X') == '["mainPage"]'
    }

    def "pageBreadcrumbs carry forward ignores answers without a rendered page and never throws"() {
        when:
        script._rmNoteHubBreadcrumbs("2518", [status: 200, data: '{"status":"success"}'])
        script._rmNoteHubBreadcrumbs("2518", [status: 500, data: null])
        script._rmNoteHubBreadcrumbs(null, [status: 200, data: '{"pageBreadcrumbs":"%5B%5D","configPage":{"name":"x"}}'])

        then:
        script.HUB_PAGE_BREADCRUMBS.isEmpty()
        script._rmPageBreadcrumbs(2518, "offMeansPage", 'X') == 'X'
    }

    def "a sub-page Done submits an unset input's defaultValue, as the page does (lux 100, not null)"() {
        given: "Room Lighting's 'illuminance rises' fields: sensor chosen, lux never touched (default 100)"
        hubGet.register('/installedapp/configure/json/654/offMeansPage') { params ->
            JsonOutput.toJson([app: [id: 654, name: "Room Lights", version: 3, appType: [name: "Room Lights", namespace: "hubitat"]],
                configPage: [name: "offMeansPage", install: false, error: null, sections: [[title: "", input: [
                    [name: "offMeans", type: "enum", multiple: true],
                    [name: "illumsOff", type: "capability.illuminanceMeasurement", multiple: true, required: true],
                    [name: "luxOff", type: "number", defaultValue: 100]
                ]]]], settings: [:], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/654') { params ->
            statusJson(654, [[name: "offMeans", type: "enum", multiple: true, value: '["illuminance rises"]'],
                             [name: "illumsOff", type: "capability.illuminanceMeasurement", multiple: true, value: null, deviceIdsForDeviceList: [696]]])
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]; [status: 200, location: null, data: '']
        }

        when:
        script._rmSubmitSubPageDone(654, "offMeansPage", "mainPage", "name", null)

        then:
        def done = posts.find { it.body?._action_previous == "Done" }
        done.body["settings[luxOff]"] == "100"
        done.body["settings[illumsOff]"] == "696"
    }
}
