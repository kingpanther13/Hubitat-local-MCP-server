package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

// Direct-call and dispatch tests for the dashboard tools (Easy and legacy); each hub stub answers in
// the shape the hub sends on current firmware.
class ToolDashboardSpec extends ToolSpecBase {

    private static final String PIN_TOKEN = 'tok-page'
    private static final String SESSION = 'HUBSESSION=node0test.node0'
    private static final Map SELECT_PAGE = [status: 200, location: null, setCookie: SESSION,
        data: "<script>var globalDashboardPinToken = '${PIN_TOKEN}';</script>".toString()]

    // One /dashboard/all row with every key the hub sends; overrides replace or add keys.
    private static Map easyRow(int id, String name, Map overrides = [:]) {
        [localURL: "http://null/dashboard/ui/${id}?t=x".toString(), showEdit: true, showModeTile: false,
         parsedNavigationSelection: [], showHSMTile: false, showNavigation: true, localOnly: false,
         name: name, accessToken: "easy-tok-${id}".toString(), showCalendarTile: false, theme: 'dark',
         installedAppId: id, dashboardPin: '', hsmPin: '', showClockTile: true, navigationSelection: '[]',
         version: '2.0', appTypeId: 139, cloudURL: "https://cloud.example/${id}".toString(), deviceIds: [2],
         roomId: 0] + overrides
    }

    private static Map legacyRow(int id, String name, Map overrides = [:]) {
        easyRow(id, name, [theme: '', hsmPin: null, navigationSelection: null, version: '1.0', appTypeId: 140,
            localURL: "http://null/apps/api/${id}/dashboard/${id}?access_token=x".toString(),
            accessToken: "tok-row-${id}".toString()] + overrides)
    }

    private static final String LIST_JSON = JsonOutput.toJson([
        easyRow(412, 'Living Room', [showModeTile: true, showClockTile: false, deviceIds: [12, 34]]),
        easyRow(500, 'Garage', [theme: 'legacy', deviceIds: [99]])])

    // statusJson for an Easy Dashboard, as the hub answers it.
    private static String easyStatusJson(int id, String label = 'Easy') {
        JsonOutput.toJson([installedApp: [id: id, name: 'Easy Dashboard', systemAppType: true, label: label,
                trueLabel: label, installed: true, disabled: false, parentAppId: 16, appTypeId: 139],
            showTempValues: false, appSettings: [], tempSettings: [], eventSubscriptions: [], scheduledJobs: null,
            childAppCount: 0, childDeviceCount: 0, childDevices: [], appState: [], tempState: []])
    }

    // /installedapp/configure/json/<id> for an Easy Dashboard: every setting is a string.
    private static String easyConfigJson(String label, Map settings = [:]) {
        def all = [devicesPicked: ['2': 'Lamp'], showModeTile: 'false', showClockTile: 'false',
                   showCalendarTile: 'false', showHSMTile: 'false', showEdit: 'true', showNavigation: 'true',
                   dashboardPin: '', localOnly: 'false', hsmPin: '', navigationSelection: '[]'] + settings
        JsonOutput.toJson([pageBreadcrumbs: [], cancelButton: null, formAction: 'update', removeButton: null,
            configPage: [name: 'mainPage', sections: []], pageType: 'dynamic', clock: null,
            app: [id: 0, label: label], childApps: [], childDevices: [], settings: all, modeMap: [:]])
    }

    // A legacy dashboard's design (tiles + grid + colors) as its layout endpoint returns it.
    // Serialized fresh on every stub call, so tests never mutate the shared fixture.
    private static final Map LEGACY_LAYOUT = [
        name: 'Kitchen', cols: 6, rows: 4, bgColor: '#000000',
        tiles: [
            [id: 0, template: 'clock', col: 1, row: 1, colSpan: 1, rowSpan: 1],
            [id: 1, template: 'attribute', device: '16', col: 2, row: 1, colSpan: 1, rowSpan: 1]
        ]
    ]

    // Ids the default /dashboard/delete stub was called with; their statusJson then answers {}.
    private Set<String> easyDeleted = [] as Set

    def setup() {
        settingsMap.bypassDeviceAllowlist = true
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false -> defaultRaw(p) }
        script.metaClass.hubInternalGetInSession = { String p, Map q, String session, int t = 30 -> hubGet.call(p, q) }
        hubGet.register('/dashboard/all') { params -> params?.pinToken == PIN_TOKEN ? LIST_JSON : '[]' }
        hubGet.register('/dashboard/create') { params -> '{"success":true,"message":null,"id":777}' }
        hubGet.register('/dashboard/update') { params ->
            ['412', '38', '777'].contains(params?.id?.toString()) ? "{\"success\":true,\"message\":null,\"id\":${params.id}}".toString()
                : '{"success":false,"message":null}'
        }
        hubGet.register('/dashboard/delete') { params -> easyDeleted << params?.id?.toString(); '{"success":false,"message":null}' }
        hubGet.register('/installedapp/statusJson/412') { params -> easyDeleted.contains('412') ? '{}' : easyStatusJson(412, 'Living Room') }
        hubGet.register('/installedapp/statusJson/38') { params -> easyDeleted.contains('38') ? '{}' : easyStatusJson(38, 'Dashboard 1') }
    }

    // The raw GETs every flow can make: the Dashboards page that mints the pinToken, and a benign
    // answer for anything else.
    private static Map defaultRaw(String p) {
        p == '/dashboard/select' ? SELECT_PAGE : [status: 200, location: null, data: '']
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    // ---------- legacy Hubitat® Dashboard stub helpers ----------

    // Force-delete paths stubForceDelete saw, and the app id the last one named;
    // registerLegacyStatus(afterDelete: ...) changes only the status of that id.
    private List<String> forceDeletePaths = []
    private String forceDeletedId = null

    // Register /installedapp/statusJson/<id> in the hub's legacy-dashboard shape. opts: accessToken
    // (String, or key present + null to OMIT it), deviceIds (a List, or key present + null for a
    // devicesPicked setting without its device list), devicesPicked: false to omit that setting (the
    // hub's shape before any device is picked), label, localAccess (bool; the hub only lists it when set).
    private void registerLegacyStatus(String id, Map opts = [:]) {
        String token = opts.containsKey('accessToken') ? opts.accessToken : ('tok-' + id)
        def appState = [[name: 'layout', value: '{}'], [name: 'hsm', value: null]]
        if (token) appState << [name: 'accessToken', value: token]
        List deviceIds = opts.containsKey('deviceIds') ? opts.deviceIds : [16, 4]
        def picked = [name: 'devicesPicked', type: 'capability.*', value: null, multiple: true]
        if (deviceIds != null) {
            picked.deviceIdsForDeviceList = deviceIds
            picked.deviceList = deviceIds.collectEntries { [(it.toString()): "Device ${it}".toString()] }
        }
        def appSettings = [[name: 'allowGoBack', type: 'bool', value: 'true']]
        if (opts.devicesPicked != false) appSettings << picked
        if (opts.containsKey('localAccess')) appSettings << [name: 'localAccess', type: 'bool', value: opts.localAccess.toString()]
        String label = opts.label ?: 'Kitchen'
        def json = JsonOutput.toJson([installedApp: [id: id as Integer, name: 'Dashboard', systemAppType: true, label: label,
                                                     trueLabel: label, installed: true, disabled: false, parentAppId: 21, appTypeId: 140],
                                      showTempValues: false, appSettings: appSettings, tempSettings: [], eventSubscriptions: [],
                                      scheduledJobs: null, childAppCount: 0, childDeviceCount: 0, childDevices: [],
                                      appState: appState, tempState: []])
        // afterDelete: 'gone' answers {} (the hub's shape for a removed app) and 'fail' throws, once
        // the force-delete has fired; omitted, the dashboard stays present.
        hubGet.register("/installedapp/statusJson/${id}".toString()) { params ->
            if (forceDeletedId == id && opts.afterDelete == 'gone') return '{}'
            if (forceDeletedId == id && opts.afterDelete == 'fail') throw new RuntimeException('status read down')
            json
        }
    }

    // The dashboard-page render that mints the per-load requestToken the layout endpoint demands.
    private void registerLegacyMint(String id) {
        hubGet.register("/apps/api/${id}/dashboard/${id}".toString()) { params ->
            "<html><script>var javascriptRequestToken = \"rt-${id}\";</script></html>".toString()
        }
    }

    // Mint page + a current-layout GET returning `layout` (the granular ops read this before saving).
    private void registerLegacyLayout(String id, Map layout) {
        registerLegacyMint(id)
        hubGet.register("/apps/api/${id}/dashboard/${id}/layout".toString()) { params -> JsonOutput.toJson(layout) }
    }

    // An unreadable /dashboard/all list, so dashboard 700 is found through the legacy parent's child
    // apps (id + name only) and its token and devices must come from statusJson.
    private void registerLegacyChildAppList(String id = '700', String name = 'Studio') {
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: 21, name: 'HD', type: 'Hubitat® Dashboard'],
                children: [[data: [id: id as Integer, name: name, type: 'Dashboard'], children: []]]]]])
        }
    }

    // /hub2/appsList carrying the legacy "Hubitat® Dashboard" parent at `parentId` (create/copy need it).
    private void registerLegacyParent(int parentId = 21) {
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: parentId, name: 'HD', type: 'Hubitat® Dashboard'], children: []]]])
        }
    }

    // Stub createchild (hubInternalGetRaw) to mint `newId` from the 302 Location; other raw GETs get
    // the defaults (the Dashboards page pinToken included). Returns the list of raw GET paths.
    private List stubCreateChild(String newId) {
        def rawPaths = []
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            rawPaths << p
            if (p.startsWith('/installedapp/createchild')) {
                return [status: 302, location: "/installedapp/configure/${newId}".toString(), data: '']
            }
            return defaultRaw(p)
        }
        return rawPaths
    }

    // Stub forcedelete (hubInternalGetRaw). The hub answers HTTP 500 with an HTML error page even
    // when the delete committed; dropConnection makes the request throw instead.
    private void stubForceDelete(boolean dropConnection = false) {
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            def m = (p =~ '^/installedapp/forcedelete/(\\d+)/quiet$')
            if (p.contains('/installedapp/forcedelete/')) {
                forceDeletePaths << p
                forceDeletedId = m.find() ? m.group(1) : null
                if (dropConnection) throw new RuntimeException('connection reset')
                return [status: 500, location: null, data: '<html><body>Error 500</body></html>']
            }
            return defaultRaw(p)
        }
    }

    // Capture legacy classic-form POSTs (label / devicesPicked writes). Returns the capture list.
    private List captureFormPosts() {
        def posts = []
        script.metaClass.hubInternalPostForm = { String p, Map b, int t = 420, boolean r = false ->
            posts << [path: p, body: b]; [status: 200, location: null, data: '']
        }
        return posts
    }

    // Capture legacy layout saves (POST JSON). The stub echoes the parsed body back, as the layout
    // endpoint echoes the saved layout. The layout POST passes the [access_token, requestToken] query
    // as the trailing param. Returns the capture list.
    private List captureLayoutPosts() {
        def posts = []
        script.metaClass.hubInternalPostJson = { String p, String body, int t = 420, boolean r = false, Map q = null ->
            def parsed = new JsonSlurper().parseText(body)
            posts << [path: p, body: parsed, query: q]
            return parsed
        }
        return posts
    }

    // ---------- hub_list_dashboards ----------

    def "list returns summarized dashboards with id + name + config"() {
        when:
        def r = script.toolListDashboards([:])

        then:
        r.count == 2
        r.dashboards[0].id == '412'
        r.dashboards[0].name == 'Living Room'
        r.dashboards[0].theme == 'dark'
        r.dashboards[0].deviceIds == ['12', '34']
    }

    def "a caller pinToken is never sent: the hub honours only the token of its own page session"() {
        when:
        script.toolListDashboards([pinToken: 'tok-123'])

        then:
        def call = hubGet.calls.find { it.path == '/dashboard/all' }
        call.params.pinToken == PIN_TOKEN
    }

    def "list omits pinToken when none is supplied and the page token can't be scraped"() {
        given: 'the /dashboard/select page carries no globalDashboardPinToken'
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            [status: 200, location: null, data: '<html></html>']
        }

        when:
        script.toolListDashboards([:])

        then:
        def call = hubGet.calls.find { it.path == '/dashboard/all' }
        !call.params.containsKey('pinToken')
    }

    def "list tolerates a genuinely-empty hub (/dashboard/all empty + child read OK but no dashboards)"() {
        given: 'both reads succeed; /dashboard/all is empty and the apps tree has no Easy Dashboard Parent'
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params -> '{"apps":[]}' }

        when:
        def r = script.toolListDashboards([:])

        then: 'a CONFIRMED-empty hub reports count:0 and no longer asks for a pinToken (none would work)'
        r.count == 0
        r.dashboards == []
        r.note.contains('No dashboards found')
        !r.note.toLowerCase().contains('pintoken')
    }

    def "list surfaces success:false when /dashboard/all is empty but the child-app READ fails (not a fake 'zero')"() {
        given: '/dashboard/all returns empty, but the child-app fallback read errors (/hub2/appsList unmocked -> throws)'
        hubGet.register('/dashboard/all') { params -> '[]' }

        when:
        def r = script.toolListDashboards([:])

        then: 'a FAILED child-app read is NOT reported as count:0 / "no child apps present"'
        r.success == false
        r.error != null
        r.dashboards == null
    }

    def "list surfaces success:false (not a fake empty) when BOTH /dashboard/all and the child-app fallback fail"() {
        given: 'both reads error -- /dashboard/all throws and /hub2/appsList is unmocked (also throws)'
        hubGet.register('/dashboard/all') { params -> throw new RuntimeException('endpoint down') }

        when:
        def r = script.toolListDashboards([:])

        then: 'a transient read failure is reported as an error, NEVER as "this hub has zero dashboards" (no throw)'
        r.success == false
        r.error != null
        r.dashboards == null
    }

    def "list falls back to child apps with a non-pinToken note when /dashboard/all ERRORS but child apps read"() {
        given: '/dashboard/all errors, but the child-app enumeration succeeds'
        hubGet.register('/dashboard/all') { params -> throw new RuntimeException('endpoint down') }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]}]}]}'
        }

        when:
        def r = script.toolListDashboards([:])

        then: 'lists via child apps; the note must NOT misattribute the hub error to a missing pinToken'
        r.source == 'child-apps'
        r.count == 1
        !r.note.toLowerCase().contains('pintoken')
        r.note.toLowerCase().contains('errored')
    }

    def "list scrapes the page pinToken and forwards it to /dashboard/all when none is supplied"() {
        given: 'the /dashboard/select page exposes globalDashboardPinToken (mock the raw fetch the harness lacks)'
        // hubInternalGetRaw returns the struct [status, location, data] (returnShape:'struct'), so the
        // body lives under .data -- mock that real shape, not a bare String.
        script.metaClass.hubInternalGetRaw = { String p ->
            p == '/dashboard/select' ? [status: 200, location: null, data: "<script>var globalDashboardPinToken = 'tok-scraped';</script>"] : null
        }

        when:
        script.toolListDashboards([:])

        then: 'the scraped token is sent to /dashboard/all so the (otherwise pinToken-gated) list returns'
        def call = hubGet.calls.find { it.path == '/dashboard/all' }
        call.params.pinToken == 'tok-scraped'
    }

    def "list asks /dashboard/all in the session that issued the page pinToken, so theme and PINs come back"() {
        given: 'the hub honours the page token only with the HUBSESSION cookie the page set'
        script.metaClass.hubInternalGetRaw = { String p ->
            p == '/dashboard/select' ? [status: 200, location: null, setCookie: 'HUBSESSION=node0abc.node0',
                                        data: "<script>var globalDashboardPinToken = 'tok-session';</script>"] : null
        }
        def sent = [:]
        script.metaClass.hubInternalGetInSession = { String path, Map q, String session, int t = 30 ->
            sent.path = path; sent.q = q; sent.session = session
            JsonOutput.toJson([easyRow(31, 'BAT Dash', [dashboardPin: '1234', deviceIds: [3]])])
        }

        when:
        def r = script.toolListDashboards([:])

        then:
        sent.path == '/dashboard/all'
        sent.q.pinToken == 'tok-session'
        sent.session == 'HUBSESSION=node0abc.node0'
        r.source == 'dashboard-all'
        r.dashboards[0].theme == 'dark'
    }

    def "list falls back to Easy Dashboard Parent child apps when /dashboard/all is empty"() {
        given: 'no pinToken-backed list, but the apps tree has an Easy Dashboard Parent with one child'
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]}]}]}'
        }

        when:
        def r = script.toolListDashboards([:])

        then: 'the child apps under the Easy Dashboard Parent become dashboards (id + name), no pinToken needed'
        r.source == 'child-apps'
        r.count == 1
        r.dashboards[0].id == '38'
        r.dashboards[0].name == 'Dashboard 1'
        r.note != null
    }

    // ---------- hub_get_dashboard: one dashboard in full ----------

    def "get filters the list by id"() {
        when:
        def r = script.toolGetDashboard([dashboardId: '500'])

        then:
        r.id == '500'
        r.name == 'Garage'
    }

    def "get with an empty id throws"() {
        when:
        script.toolGetDashboard([dashboardId: ''])

        then:
        thrown(IllegalArgumentException)
    }

    def "get with an unknown id returns a structured not-found (no throw) listing available ids"() {
        when:
        def r = script.toolGetDashboard([dashboardId: '9999'])

        then:
        r.success == false
        r.availableIds.containsAll(['412', '500'])
    }

    def "get rejects a non-numeric id (the URL-splice guard) with a validation error"() {
        when:
        script.toolGetDashboard([dashboardId: 'abc; rm'])

        then:
        thrown(IllegalArgumentException)
    }

    def "get enriches a child-app-sourced match from /installedapp/configure/json (tiles, devices, pins)"() {
        given: 'the list is child-app-sourced (id+name only); the app-config page carries the full settings'
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]}]}]}'
        }
        hubGet.register('/installedapp/configure/json/38') { params ->
            easyConfigJson('Dashboard 1', [showClockTile: 'true', devicesPicked: ['8': 'Lamp', '1': 'Fan']])
        }

        when:
        def r = script.toolGetDashboard([dashboardId: '38'])

        then: 'string tile toggles coerce to booleans, devicesPicked keys become deviceIds, unset pins are omitted'
        r.id == '38'
        r.showClockTile == true
        r.showModeTile == false
        r.deviceIds.sort() == ['1', '8']
        !r.containsKey('dashboardPin')   // "" -> omitted
        !r.containsKey('hsmPin')         // "" -> omitted
    }

    def "get flags partial when a child-app match can't be enriched (config read fails)"() {
        given: 'list is child-app-sourced (id+name only) and the app-config enrichment read fails (unmocked -> throws)'
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]}]}]}'
        }

        when:
        def r = script.toolGetDashboard([dashboardId: '38'])

        then: 'returns id+name but FLAGS partial -- a caller must not read it as a complete (all-default) config'
        r.id == '38'
        r.name == 'Dashboard 1'
        r.partial == true
        r.note != null
    }

    def "get includes pins and a CSV navigationSelection when the hub exposes them (read-then-update round-trip)"() {
        given: 'a hub list entry that carries pins and a bracketed navigationSelection string'
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([easyRow(412, 'Living Room', [deviceIds: [12, 34], dashboardPin: '1111', hsmPin: '2222',
                                                           navigationSelection: '[5,6]'])])
        }
        enableWrite()

        when: 'read the dashboard, then feed the read shape straight back into update'
        def got = script.toolGetDashboard([dashboardId: '412'])

        then: 'pins are surfaced and nav is normalized to a CSV the update endpoint accepts'
        got.dashboardPin == '1111'
        got.hsmPin == '2222'
        got.navigationSelection == '5,6'

        when: 'round-trip: pass the get output back through update, config fields inside options'
        script.toolUpdateDashboard([dashboardId: got.id, name: got.name, deviceIds: '12,34',
                                    options: [dashboardPin: got.dashboardPin, hsmPin: got.hsmPin,
                                              navigationSelection: got.navigationSelection]])

        then: 'the pins are preserved (not cleared) and nav stays CSV'
        def call = hubGet.calls.find { it.path == '/dashboard/update' }
        call.params.dashboardPin == '1111'
        call.params.hsmPin == '2222'
        call.params.navigationSelection == '5,6'
    }

    def "get normalizes a BRACKETED-STRING navigationSelection from app-config to CSV (the round-trip the hub corrupts otherwise)"() {
        given: 'list is child-app-sourced; app-config returns navigationSelection as the hub PERSISTS it -- a JSON-array STRING "[1,2]", not an array (verified live on the hub)'
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Nav Dash","type":"Easy Dashboard"},"children":[]}]}]}'
        }
        hubGet.register('/installedapp/configure/json/38') { params ->
            easyConfigJson('Nav Dash', [showNavigation: 'true', navigationSelection: '[1,2]', devicesPicked: ['14': 'Lamp']])
        }
        enableWrite()

        when: 'read the dashboard'
        def got = script.toolGetDashboard([dashboardId: '38'])

        then: 'the bracketed hub string is normalized to CSV -- NOT returned verbatim as "[1,2]" (which the hub misparses to index 0)'
        got.navigationSelection == '1,2'

        when: 'round-trip: feed the get output back through update'
        script.toolUpdateDashboard([dashboardId: got.id, name: got.name, deviceIds: ['14'], options: [navigationSelection: got.navigationSelection]])

        then: 'update sends a CSV the hub parses correctly, so nav survives the get->update round-trip'
        def call = hubGet.calls.find { it.path == '/dashboard/update' }
        call.params.navigationSelection == '1,2'
    }

    // ---------- hub_create_dashboard ----------

    def "create serializes deviceIds as CSV and booleans as the strings true/false"() {
        given:
        enableWrite()

        when: 'config supplied via the advertised options object'
        def r = script.toolCreateDashboard([name: 'New Dash', deviceIds: ['12', '34'], options: [showModeTile: true, showClockTile: false]])

        then: 'the hub answers {success, message:null, id}, so the result carries the new id'
        r.success == true
        r.id == '777'
        r.message == 'Dashboard create succeeded.'
        !r.containsKey('dashboard')
        def call = hubGet.calls.find { it.path == '/dashboard/create' }
        call.params.name == 'New Dash'
        call.params.deviceIds == '12,34'
        call.params.showModeTile == 'true'
        call.params.showClockTile == 'false'
    }

    def "#tool refuses config fields sent top level instead of inside options, before any write"() {
        given:
        enableWrite()

        when:
        script.executeTool(tool, args)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unknown ${tool} argument")
        e.message.contains('showModeTile')
        !hubGet.calls.any { it.path in ['/dashboard/create', '/dashboard/update'] }

        where:
        tool                   | args
        'hub_create_dashboard' | [name: 'New Dash', deviceIds: ['12'], showModeTile: true, theme: 'dark']
        'hub_update_dashboard' | [dashboardId: '412', name: 'Den', deviceIds: ['12'], showModeTile: true, dashboardPin: '1']
    }

    def "create defaults theme to legacy, omitted tile toggles to false, and omitted showEdit/showNavigation to the hub's on"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([name: 'New Dash', deviceIds: ['12']])

        then:
        def call = hubGet.calls.find { it.path == '/dashboard/create' }
        call.params.theme == 'legacy'
        call.params.showHSMTile == 'false'
        call.params.showTutorial == 'false'
        call.params.showEdit == 'true'
        call.params.showNavigation == 'true'

        and: 'both are on, so no follow-up update is needed'
        !hubGet.calls.any { it.path == '/dashboard/update' }
        call.params.navigationSelection == ''
        call.params.dashboardPin == ''
        call.params.hsmPin == ''
    }

    def "create serializes navigationSelection (dashboard ids) as CSV"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([name: 'New Dash', deviceIds: ['12'], options: [navigationSelection: ['5', '6']]])

        then:
        def call = hubGet.calls.find { it.path == '/dashboard/create' }
        call.params.navigationSelection == '5,6'
    }

    def "create without name throws"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([deviceIds: ['12']])

        then:
        thrown(IllegalArgumentException)
    }

    def "create with empty deviceIds throws (an Easy Dashboard needs >=1 device)"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([name: 'New Dash', deviceIds: []])

        then:
        thrown(IllegalArgumentException)
    }

    def "create with a non-numeric device id throws"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([name: 'New Dash', deviceIds: ['abc']])

        then:
        thrown(IllegalArgumentException)
    }

    def "create with an invalid theme throws (validated through options)"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([name: 'New Dash', deviceIds: ['12'], options: [theme: 'neon']])

        then:
        thrown(IllegalArgumentException)
    }

    def "create accepts a CSV string of deviceIds without splitting it into characters"() {
        given:
        enableWrite()

        when: 'deviceIds passed as a pre-joined CSV string, not an array'
        script.toolCreateDashboard([name: 'New Dash', deviceIds: '12,34'])

        then: 'the CSV is preserved, not iterated char-by-char (which would yield "1,2,,3,4")'
        def call = hubGet.calls.find { it.path == '/dashboard/create' }
        call.params.deviceIds == '12,34'
    }

    def "create accepts a single bare device id string (not split into digits)"() {
        given:
        enableWrite()

        when: 'a lone id "12" -- must NOT become "1,2"'
        script.toolCreateDashboard([name: 'New Dash', deviceIds: '12'])

        then:
        def call = hubGet.calls.find { it.path == '/dashboard/create' }
        call.params.deviceIds == '12'
    }

    def "create reports success:false when the hub answers success:false with no message"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/create') { params -> '{"success":false,"message":null}' }

        when:
        def r = script.toolCreateDashboard([name: 'Dup', deviceIds: ['12']])

        then:
        r.success == false
        !r.containsKey('isError')
        r.error == 'hub reported failure'
        r.note.endsWith("See hub_get_tool_guide(section='dashboards').")
    }

    def "update reports the hub's {success:false, message:null} answer as a runtime failure"() {
        given: 'statusJson still lists the app, but /dashboard/update answers {success:false, message:null}'
        enableWrite()
        hubGet.register('/installedapp/configure/json/412') { params -> easyConfigJson('Living Room') }
        hubGet.register('/dashboard/update') { params -> '{"success":false,"message":null}' }

        when:
        def r = script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: ['12']])

        then:
        r.success == false
        !r.containsKey('isError')
        r.error == 'hub reported failure'
    }

    def "create reports success:false (outcome unconfirmed) when the hub echoes the list instead of a status"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/create') { params -> LIST_JSON }

        when:
        def r = script.toolCreateDashboard([name: 'New Dash', deviceIds: ['12']])

        then:
        r.success == false
        r.error.toLowerCase().contains('list')
        r.note != null
    }

    // ---------- hub_update_dashboard ----------

    def "update adds id and the full wholesale config to /dashboard/update"() {
        given:
        enableWrite()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: ['12', '34'], options: [theme: 'dark']])

        then:
        r.success == true
        def call = hubGet.calls.find { it.path == '/dashboard/update' }
        call.params.id == '412'
        call.params.name == 'Living Room'
        call.params.deviceIds == '12,34'
        call.params.theme == 'dark'
    }

    def "update without id throws"() {
        given:
        enableWrite()

        when:
        script.toolUpdateDashboard([name: 'X', deviceIds: ['12']])

        then:
        thrown(IllegalArgumentException)
    }

    def "update without name throws (wholesale replace requires the full config)"() {
        given:
        enableWrite()

        when:
        script.toolUpdateDashboard([dashboardId: '412', deviceIds: ['12']])

        then:
        thrown(IllegalArgumentException)
    }

    def "update with empty deviceIds throws (won't silently blank the dashboard's devices)"() {
        given:
        enableWrite()

        when:
        script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: []])

        then:
        thrown(IllegalArgumentException)
    }

    def "dispatch masks dashboard PINs while Hide passwords and credentials is on, and returns them as stored when off"() {
        given:
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([easyRow(412, 'Living Room', [deviceIds: [12, 34], dashboardPin: '1111', hsmPin: '2222'])])
        }
        settingsMap.enableRead = true
        settingsMap.redactCredentials = toggle

        when:
        def listed = script.executeTool('hub_list_dashboards', [:])
        def got = script.executeTool('hub_get_dashboard', [dashboardId: '412'])

        then:
        listed.dashboards[0].dashboardPin == shown
        got.dashboardPin == shown
        got.hsmPin == (toggle == false ? '2222' : '***redacted (password)***')

        where:
        toggle | shown
        null   | '***redacted (password)***'
        false  | '1111'
    }

    def "update fed a masked read keeps the stored PINs; create refuses the marker before any write"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/412') { params ->
            easyConfigJson('Living Room', [dashboardPin: '4242', hsmPin: '7777'])
        }

        when: 'the masked PINs from a read are passed back'
        script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: ['12', '34'],
                                    options: [dashboardPin: '***redacted (password)***', hsmPin: '***redacted (password)***']])

        then: 'the stored PINs are re-sent, not the marker'
        def call = hubGet.calls.find { it.path == '/dashboard/update' }
        call.params.dashboardPin == '4242'
        call.params.hsmPin == '7777'

        when:
        script.toolCreateDashboard([name: 'Copy', deviceIds: ['12'], options: [dashboardPin: '***redacted (password)***']])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('redaction marker')
        !hubGet.calls.any { it.path == '/dashboard/create' }
    }

    def "update preserves an existing PIN the caller didn't pass (wholesale replace must not clear it)"() {
        given: 'the dashboard currently has a dashboardPin, and the caller updates WITHOUT supplying one'
        enableWrite()
        hubGet.register('/installedapp/configure/json/412') { params ->
            easyConfigJson('Living Room', [dashboardPin: '4242'])
        }

        when: 'an unrelated edit (no pin in args)'
        script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: ['12', '34'], options: [theme: 'dark']])

        then: 'the current PIN is read back and re-sent, so it is not blanked'
        def call = hubGet.calls.find { it.path == '/dashboard/update' }
        call.params.dashboardPin == '4242'
    }

    def "update flags pinPreserveFailed when it can't read the config to preserve an omitted PIN"() {
        given: 'the caller omits the PIN and the preservation read fails (/installedapp/configure/json/412 unmocked -> throws)'
        enableWrite()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: ['12', '34'], options: [theme: 'dark']])

        then: 'the update applies but FLAGS that an omitted PIN may have been cleared -- the clear is not silent'
        r.success == true
        r.pinPreserveFailed == true
        r.note != null
    }

    def "update preserves an existing hsmPin the caller didn't pass (exercises the hsmPin preservation line)"() {
        given: 'the config has a real hsmPin (and no dashboardPin) and the caller updates without supplying one'
        enableWrite()
        hubGet.register('/installedapp/configure/json/412') { params ->
            easyConfigJson('Living Room', [hsmPin: '7777'])
        }

        when: 'an unrelated edit (no hsmPin in args)'
        script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: ['12', '34'], options: [theme: 'dark']])

        then: 'the current hsmPin is read back and re-sent, not blanked'
        def call = hubGet.calls.find { it.path == '/dashboard/update' }
        call.params.hsmPin == '7777'
    }

    def "update applies the pin the caller DID pass while preserving the OMITTED one (mixed case)"() {
        given: 'the config has both pins; the caller changes dashboardPin but omits hsmPin'
        enableWrite()
        hubGet.register('/installedapp/configure/json/412') { params ->
            easyConfigJson('Living Room', [dashboardPin: '4242', hsmPin: '7777'])
        }

        when:
        script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: ['12', '34'], options: [dashboardPin: '9999']])

        then: 'the new dashboardPin is sent and the omitted hsmPin is preserved (not cleared)'
        def call = hubGet.calls.find { it.path == '/dashboard/update' }
        call.params.dashboardPin == '9999'
        call.params.hsmPin == '7777'
    }

    // ---------- hub_delete_dashboard ----------

    def "delete removes by id (confirm-gated), proven by statusJson since /dashboard/delete always answers success:false"() {
        given:
        enableWrite()

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then:
        r.success == true
        r.id == '412'
        def call = hubGet.calls.find { it.path == '/dashboard/delete' }
        call.params.id == '412'
        hubGet.calls.count { it.path == '/installedapp/statusJson/412' } == 2
    }

    def "delete without confirm throws the destructive gate"() {
        given:
        settingsMap.enableWrite = true   // Write master on, but NO recent backup -> confirm gate fails

        when:
        script.toolDeleteDashboard([dashboardId: '412'])

        then:
        thrown(IllegalArgumentException)
    }

    def "delete without id throws"() {
        given:
        enableWrite()

        when:
        script.toolDeleteDashboard([confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    def "a delete that leaves the dashboard in place returns a structured failure (does NOT throw)"() {
        given: '/dashboard/delete answers as always, but statusJson still lists the app'
        enableWrite()
        hubGet.register('/dashboard/delete') { params -> '{"success":false,"message":null}' }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then:
        r.success == false
        !r.containsKey('isError')
        r.error.contains('still present')
        r.note.contains('Nothing was deleted')
        r.note.contains("hub_get_tool_guide(section='dashboards')")
    }

    def "delete succeeds by EFFECT when /dashboard/delete lies with success:false but the dashboard is gone"() {
        given: 'the real-hub shape: delete returns success:false even though it deleted; statusJson then answers {}'
        enableWrite()
        def gone = [false]
        hubGet.register('/dashboard/delete') { params -> gone[0] = true; '{"success":false,"message":null}' }
        hubGet.register('/installedapp/statusJson/412') { params ->
            gone[0] ? '{}' : easyStatusJson(412, 'Living Room')
        }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then: 'verify-by-effect: one status read shows 412 gone, so the delete is reported as success'
        r.success == true
        r.id == '412'
        hubGet.calls.count { it.path == '/installedapp/statusJson/412' } == 2

        and: 'no app-tree walk is spent on the confirmation'
        !hubGet.calls.any { it.path == '/hub2/appsList' || it.path == '/dashboard/all' }
    }

    def "delete of an id whose status page answers {} reports already absent without a delete"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/statusJson/412') { params -> '{}' }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then:
        r.success == true
        r.alreadyAbsent == true
        !hubGet.calls.any { it.path == '/dashboard/delete' }
    }

    def "delete does NOT false-claim success when removal cannot be confirmed (the verification read fails)"() {
        given: 'delete lies with success:false AND the status read after it fails'
        enableWrite()
        def deleted = [false]
        hubGet.register('/dashboard/delete') { params -> deleted[0] = true; '{"success":false,"message":null}' }
        hubGet.register('/installedapp/statusJson/412') { params ->
            if (deleted[0]) throw new RuntimeException('endpoint down')
            easyStatusJson(412, 'Living Room')
        }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then: 'a destructive op is NOT reported as success without confirming evidence'
        r.success == false
        r.outcomeUnknown == true
        !r.containsKey('isError')
        r.error.toLowerCase().contains('could not be confirmed')
        r.note != null
    }

    // ---------- hub_create_dashboard with sourceId: copy by value ----------

    def "copy carries the source config into a new dashboard (clone-by-value, not the session-bound cloneAsEasy)"() {
        given:
        enableWrite()

        when:
        def r = script.toolCreateDashboard([sourceId: '412'])

        then: 'reads the source (412) via the list, then creates a copy via /dashboard/create'
        r.success == true
        r.sourceId == '412'
        r.id == '777'
        def createCall = hubGet.calls.find { it.path == '/dashboard/create' }
        createCall.params.name == 'Living Room (copy)'
        createCall.params.deviceIds == '12,34'

        and: 'it does NOT call the session-bound cloneAsEasy endpoint (which fails from the server)'
        !hubGet.calls.any { it.path.startsWith('/dashboard/cloneAsEasy') }
    }

    def "copy carries the source's full config (theme + tiles) by value into the create call"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([sourceId: '412'])

        then: 'the source 412 config (theme dark, showModeTile true) flows into /dashboard/create, not just name+devices'
        def createCall = hubGet.calls.find { it.path == '/dashboard/create' }
        createCall.params.theme == 'dark'
        createCall.params.showModeTile == 'true'
    }

    def "copy returns a structured runtime failure (not a validation error) when the source can't be read"() {
        given: 'a source id that is not listable -> get returns success:false, so there is no device list to copy'
        enableWrite()

        when:
        def r = script.toolCreateDashboard([sourceId: '9999'])

        then: 'copy surfaces a runtime failure rather than letting create throw IllegalArgumentException'
        r.success == false
        r.sourceId == '9999'
        r.error.toLowerCase().contains('source')
    }

    def "copy surfaces a structured failure when the source reads PARTIAL (id+name, no device list)"() {
        given: 'a child-app-sourced source whose enrichment fails -> get returns {id,name,partial:true} with NO deviceIds (src.success is not false, so the other guard is skipped)'
        enableWrite()
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]}]}]}'
        }
        // /installedapp/configure/json/38 unmocked -> enrichment fails -> the get match is partial (no deviceIds)

        when:
        def r = script.toolCreateDashboard([sourceId: '38'])

        then: 'copy returns a runtime failure naming the device list, not a validation error thrown by create'
        r.success == false
        r.sourceId == '38'
        r.error.toLowerCase().contains('device list')
    }

    def "copy refuses #arg beside sourceId before reading or writing anything"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([sourceId: '412', (arg): value])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(arg)
        e.message.contains('sourceId')
        hubGet.calls.isEmpty()

        where:
        arg         | value
        'type'      | 'legacy'
        'deviceIds' | ['12']
        'options'   | [theme: 'dark']
    }

    def "copy rejects a non-numeric sourceId"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([sourceId: 'abc'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('sourceId')
    }

    def "copy takes a caller name instead of the '(copy)' default"() {
        given:
        enableWrite()

        when:
        def r = script.toolCreateDashboard([sourceId: '412', name: 'Kitchen Wall'])

        then:
        r.success == true
        r.name == 'Kitchen Wall'
        hubGet.calls.find { it.path == '/dashboard/create' }.params.name == 'Kitchen Wall'
    }

    def "copy carries the source PINs into the new dashboard even though the result masks them"() {
        given:
        enableWrite()
        settingsMap.enableRead = true
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([easyRow(412, 'Living Room', [deviceIds: [12], dashboardPin: '1111', hsmPin: '2222'])])
        }

        when:
        def r = script.executeTool('hub_create_dashboard', [sourceId: '412'])

        then:
        r.success == true
        def createCall = hubGet.calls.find { it.path == '/dashboard/create' }
        createCall.params.dashboardPin == '1111'
        createCall.params.hsmPin == '2222'

        and: 'no PIN reaches the result'
        !JsonOutput.toJson(r).contains('1111')
        !JsonOutput.toJson(r).contains('2222')
    }

    // ---------- dispatch envelopes (via mcpDriver.callTool) ----------

    def "hub_list_dashboards via dispatch returns the success envelope"() {
        when:
        def resp = mcpDriver.callTool('hub_list_dashboards', [:])

        then:
        mcpDriver.parseInner(resp).count == 2
    }

    def "hub_get_dashboard via dispatch returns the one dashboard"() {
        when:
        def resp = mcpDriver.callTool('hub_get_dashboard', [dashboardId: '412'])

        then:
        mcpDriver.parseInner(resp).name == 'Living Room'
    }

    def "hub_create_dashboard via dispatch returns the success envelope"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_create_dashboard', [name: 'New Dash', deviceIds: ['12']])

        then:
        mcpDriver.parseInner(resp).success == true
    }

    def "hub_update_dashboard via dispatch returns the success envelope"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_update_dashboard', [dashboardId: '412', name: 'Living Room', deviceIds: ['12', '34']])

        then:
        mcpDriver.parseInner(resp).success == true
    }

    def "hub_delete_dashboard via dispatch returns the success envelope"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_delete_dashboard', [dashboardId: '412', confirm: true])

        then:
        mcpDriver.parseInner(resp).success == true
    }

    def "hub_create_dashboard copy via dispatch returns the success envelope"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_create_dashboard', [sourceId: '412'])

        then:
        mcpDriver.parseInner(resp).success == true
    }

    def "hub_create_dashboard via dispatch surfaces a validation-error envelope for a missing name"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_create_dashboard', [deviceIds: ['12']])

        then: 'an IllegalArgumentException maps to an isError validation result, not a success'
        resp.error == null
        resp.result?.isError == true
    }

    // ---------- dispatch envelopes in both routing modes ----------

    // Calls a dashboard tool through handleMcpRequest, as a gateway sub-tool or as a flat tool, and
    // checks the JSON-RPC envelope; returns the decoded response.
    private Map callDashboard(boolean gatewayMode, String gateway, String tool, Map args) {
        settingsMap.useGateways = gatewayMode
        settingsMap.enableMandatoryBPS = false
        def resp = gatewayMode ? mcpDriver.callTool(gateway, [tool: tool, args: args]) : mcpDriver.callTool(tool, args)
        assert resp.jsonrpc == '2.0'
        assert resp.id == mcpDriver.lastSentId
        assert resp.error == null
        return mcpDriver.decodeToolCallResponse(resp)
    }

    private static String resultText(Map decoded) { decoded.result.content[0].text as String }

    def "hub_get_dashboard dispatch (gateway=#gatewayMode): happy path, not-found and bad id"() {
        when:
        def ok = callDashboard(gatewayMode, 'hub_read_dashboards', 'hub_get_dashboard', [dashboardId: '412'])
        def missing = callDashboard(gatewayMode, 'hub_read_dashboards', 'hub_get_dashboard', [dashboardId: '9999'])
        def bad = callDashboard(gatewayMode, 'hub_read_dashboards', 'hub_get_dashboard', [dashboardId: 'abc'])

        then:
        ok.result.isError != true
        new JsonSlurper().parseText(resultText(ok)).name == 'Living Room'

        and: 'a not-found is a runtime failure: success:false in the result, flagged isError on the envelope'
        missing.result.isError == true
        def m = new JsonSlurper().parseText(resultText(missing))
        m.success == false
        m.error.contains('No dashboard found')
        m.note.contains("hub_get_tool_guide(section='dashboards')")

        and: 'a bad id is a validation failure, not a protocol error'
        bad.result.isError == true
        resultText(bad).contains('numeric')

        where:
        gatewayMode << [true, false]
    }

    def "hub_create_dashboard copy dispatch (gateway=#gatewayMode): happy path, refusal and runtime failure"() {
        given:
        enableWrite()

        when:
        def ok = callDashboard(gatewayMode, 'hub_manage_dashboards', 'hub_create_dashboard', [sourceId: '412'])
        def refused = callDashboard(gatewayMode, 'hub_manage_dashboards', 'hub_create_dashboard', [sourceId: '412', deviceIds: ['12']])
        def creates = hubGet.calls.count { it.path == '/dashboard/create' }
        def failed = callDashboard(gatewayMode, 'hub_manage_dashboards', 'hub_create_dashboard', [sourceId: '9999'])

        then:
        ok.result.isError != true
        def o = new JsonSlurper().parseText(resultText(ok))
        o.success == true
        o.id == '777'
        o.sourceId == '412'

        and: 'deviceIds beside sourceId is refused before any create'
        refused.result.isError == true
        resultText(refused).contains('sourceId')
        creates == 1

        and: 'an unreadable source is a structured runtime failure, flagged isError on the envelope'
        failed.result.isError == true
        def f = new JsonSlurper().parseText(resultText(failed))
        f.success == false
        f.sourceId == '9999'
        f.error.toLowerCase().contains('source')

        where:
        gatewayMode << [true, false]
    }

    // ---------- result envelopes, removed ids, copy warnings, partial lists, delete by effect ----------

    def "a runtime failure result is success:false with its note pointing at the guide, and sets no isError itself"() {
        when:
        def r = script.toolGetDashboard([dashboardId: '9999'])

        then: 'the recovery advice is kept and the guide pointer is appended once'
        r.success == false
        !r.containsKey('isError')
        r.note == "Call hub_list_dashboards to see valid ids. See hub_get_tool_guide(section='dashboards')."
    }

    def "a success result, including an alreadyAbsent delete, carries no isError"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/statusJson/9999') { params -> '{}' }

        when:
        def got = script.toolGetDashboard([dashboardId: '412'])
        def listed = script.toolListDashboards()
        def absent = script.toolDeleteDashboard([dashboardId: '9999', confirm: true])

        then:
        [got, listed, absent].every { r -> r.success != false && !r.containsKey('isError') }
        absent.alreadyAbsent == true
    }

    def "update of a removed id (statusJson answers {}) fails without any write, for Easy and legacy args"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/statusJson/9999') { params -> '{}' }

        when:
        def easy = script.toolUpdateDashboard([dashboardId: '9999', name: 'Gone', deviceIds: ['12']])
        def legacy = script.toolUpdateDashboard([dashboardId: '9999', addTiles: [[template: 'clock', col: 1, row: 1]]])

        then: 'a not-found failure, not an Easy write and not a "this is an Easy Dashboard" refusal'
        [easy, legacy].every { r -> r.success == false && !r.containsKey('isError') && r.id == '9999' && r.error.contains('No dashboard found') }
        [easy, legacy].every { r -> r.note == "Call hub_list_dashboards to see valid ids. See hub_get_tool_guide(section='dashboards')." }
        !hubGet.calls.any { it.path == '/dashboard/update' }
        !hubGet.calls.any { it.path.contains('/layout') }
    }

    def "hub_update_dashboard of a removed id via dispatch (gateway=#gatewayMode) is a success:false result the envelope flags isError"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/statusJson/9999') { params -> '{}' }

        when:
        def resp = callDashboard(gatewayMode, 'hub_manage_dashboards', 'hub_update_dashboard', [dashboardId: '9999', name: 'Gone', deviceIds: ['12']])

        then:
        resp.result.isError == true
        def body = new JsonSlurper().parseText(resultText(resp))
        body.success == false
        body.error.contains('No dashboard found')
        body.note.endsWith("See hub_get_tool_guide(section='dashboards').")
        !hubGet.calls.any { it.path == '/dashboard/update' }

        where:
        gatewayMode << [true, false]
    }

    def "an Easy copy warns when the source theme was unreadable (child-app fallback), and not when it was read"() {
        given: 'the hub list is unreadable, so the source comes from the child-app tree + app-config page (no theme)'
        enableWrite()
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]}]}]}'
        }
        hubGet.register('/installedapp/configure/json/38') { params ->
            easyConfigJson('Dashboard 1', [showClockTile: 'true', devicesPicked: ['8': 'Lamp']])
        }

        when:
        def fallback = script.toolCreateDashboard([sourceId: '38'])

        then:
        fallback.success == true
        fallback.warnings.size() == 1
        fallback.warnings[0].contains('theme')
        hubGet.calls.find { it.path == '/dashboard/create' }.params.theme == 'legacy'

        when: 'the hub list is readable again, the source theme is copied and nothing is warned'
        hubGet.register('/dashboard/all') { params -> LIST_JSON }
        def read = script.toolCreateDashboard([sourceId: '412'])

        then:
        read.success == true
        !read.containsKey('warnings')
        hubGet.calls.findAll { it.path == '/dashboard/create' }.last().params.theme == 'dark'
    }

    def "a child-app fallback over a malformed app tree is reported partial, and a missing id says the list was incomplete"() {
        given: '/dashboard/all is empty; the app tree has one well-formed Easy child and one node of the wrong shape'
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]},"oops"]}]}'
        }

        when:
        def listed = script.toolListDashboards([:])
        def got = script.toolGetDashboard([dashboardId: '55'])

        then:
        listed.count == 1
        listed.partial == true
        listed.note.contains('may be missing')

        and:
        got.success == false
        got.partial == true
        got.error.contains('incomplete')
        !got.error.startsWith('No dashboard found')
    }

    def "an Easy delete whose request throws is still verified by effect (#outcome)"() {
        given:
        enableWrite()
        def sent = [false]
        hubGet.register('/dashboard/delete') { params -> sent[0] = true; throw new IOException('response body lost') }
        hubGet.register('/installedapp/statusJson/412') { params ->
            if (!sent[0]) return easyStatusJson(412, 'Living Room')
            if (after == 'fail') throw new RuntimeException('status down')
            after == 'gone' ? '{}' : easyStatusJson(412, 'Living Room')
        }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then:
        r.success == success
        (r.outcomeUnknown == true) == unknown
        !r.containsKey('isError')
        !(r.note ?: '').contains('Nothing was deleted') || after == 'present'
        after == 'gone' || r.error.contains('response body lost')

        where:
        outcome          | after     | success | unknown
        'deleted'        | 'gone'    | true    | false
        'unverifiable'   | 'fail'    | false   | true
        'still present'  | 'present' | false   | false
    }

    // ---------- through the gateways (membership + routing) ----------

    def "the hub_manage_dashboards gateway catalog lists every dashboard sub-tool (membership)"() {
        when:
        def cat = script.handleGateway('hub_manage_dashboards', null, null)

        then:
        (cat.tools*.name as Set) == ['hub_list_dashboards', 'hub_get_dashboard', 'hub_create_dashboard',
                                     'hub_update_dashboard', 'hub_delete_dashboard'] as Set
    }

    def "the hub_read_dashboards gateway catalog lists only the read sub-tools"() {
        when:
        def cat = script.handleGateway('hub_read_dashboards', null, null)

        then:
        (cat.tools*.name as Set) == ['hub_list_dashboards', 'hub_get_dashboard'] as Set
    }

    def "hub_list_dashboards routes THROUGH the hub_manage_dashboards gateway"() {
        when:
        def r = script.handleGateway('hub_manage_dashboards', 'hub_list_dashboards', [:])

        then:
        r.count == 2
    }

    def "hub_list_dashboards routes THROUGH the hub_read_dashboards gateway"() {
        when:
        def r = script.handleGateway('hub_read_dashboards', 'hub_list_dashboards', [:])

        then:
        r.count == 2
    }

    def "hub_delete_dashboard routes THROUGH the hub_manage_dashboards gateway"() {
        given:
        enableWrite()

        when:
        def r = script.handleGateway('hub_manage_dashboards', 'hub_delete_dashboard', [dashboardId: '412', confirm: true])

        then:
        r.success == true
    }

    // ================= legacy Hubitat® Dashboard coverage =================

    // ---------- list: type tagging ----------

    def "list tags /dashboard/all rows by version: 1.0 legacy, 2.0 easy, and never returns a row's access token"() {
        given:
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([easyRow(412, 'Living Room', [deviceIds: [12]]), legacyRow(800, 'Old Board', [deviceIds: [9]])])
        }

        when:
        def r = script.toolListDashboards([:])

        then:
        r.count == 2
        r.dashboards.find { it.id == '800' }.type == 'legacy'
        r.dashboards.find { it.id == '800' }.deviceIds == ['9']
        r.dashboards.find { it.id == '412' }.type == 'easy'
        !JsonOutput.toJson(r).contains('tok-')
    }

    def "list tags child-app-sourced dashboards: Easy parent -> easy, Hubitat® parent -> legacy"() {
        given: 'both dashboard parents present; /dashboard/all empty so the child-app walker runs'
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [
                [data: [id: 19, name: 'Easy Dashboards', type: 'Easy Dashboard Parent'],
                 children: [[data: [id: 38, name: 'Easy One', type: 'Easy Dashboard'], children: []]]],
                [data: [id: 21, name: 'Hubitat Dashboards', type: 'Hubitat® Dashboard'],
                 children: [
                    [data: [id: 808, name: 'Foyer', type: 'Dashboard'], children: []],
                    [data: [id: 809, name: 'NotADash', type: 'SomethingElse'], children: []]
                 ]]
            ]])
        }

        when:
        def r = script.toolListDashboards([:])

        then: 'both sources are tagged; a non-Dashboard child of the legacy parent is excluded'
        r.source == 'child-apps'
        r.count == 2
        r.dashboards.find { it.id == '38' }.type == 'easy'
        r.dashboards.find { it.id == '808' }.type == 'legacy'
        !r.dashboards.any { it.id == '809' }
    }

    // ---------- get: legacy happy path + partials ----------

    def "get legacy listed from the child apps: statusJson supplies the label, devices and token for the layout read"() {
        given:
        registerLegacyChildAppList('700', 'Studio')
        registerLegacyStatus('700', [label: 'Studio Label', deviceIds: [16, 4]])
        registerLegacyLayout('700', LEGACY_LAYOUT)

        when:
        def r = script.toolGetDashboard([dashboardId: '700'])

        then:
        r.id == '700'
        r.type == 'legacy'
        r.name == 'Studio Label'              // from the app label in statusJson
        r.deviceIds == ['16', '4']
        hubGet.calls.find { it.path == '/apps/api/700/dashboard/700/layout' }.params.access_token == 'tok-700'
        r.layout.cols == 6
        r.layout.tiles.size() == 2
        !r.partial
    }

    def "get legacy: the list row's access token and devices reach the layout without a status read, and the token never leaks"() {
        given: 'the /dashboard/all row carries what the hub UI builds the legacy URL from (accessToken) plus deviceIds'
        settingsMap.enableRead = true
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([legacyRow(700, 'Studio', [deviceIds: [16, 4], accessToken: 'tok-row'])])
        }
        registerLegacyLayout('700', LEGACY_LAYOUT)

        when:
        def listed = script.executeTool('hub_list_dashboards', [:])
        def r = script.executeTool('hub_get_dashboard', [dashboardId: '700'])

        then: 'the layout read used the row token; the status page was never read'
        r.type == 'legacy'
        r.name == 'Studio'
        r.deviceIds == ['16', '4']
        r.layout.tiles.size() == 2
        !r.partial
        hubGet.calls.find { it.path == '/apps/api/700/dashboard/700/layout' }.params.access_token == 'tok-row'
        !hubGet.calls.any { it.path == '/installedapp/statusJson/700' }

        and: 'no result carries the token'
        !JsonOutput.toJson(listed).contains('tok-row')
        !JsonOutput.toJson(r).contains('tok-row')
    }

    def "get legacy: flags partial when the status page is unreadable (layout unavailable, not empty)"() {
        given: 'the child apps list 700 under the legacy parent, but statusJson/700 is unreadable (unregistered -> throws)'
        registerLegacyChildAppList('700', 'Studio')

        when:
        def r = script.toolGetDashboard([dashboardId: '700'])

        then:
        r.id == '700'
        r.type == 'legacy'
        r.partial == true
        r.note != null
    }

    def "get legacy: flags partial when the access token can't be read (layout endpoint unreachable)"() {
        given:
        registerLegacyChildAppList('700', 'Studio')
        registerLegacyStatus('700', [accessToken: null, label: 'Studio'])   // legacy:true but no accessToken

        when:
        def r = script.toolGetDashboard([dashboardId: '700'])

        then:
        r.type == 'legacy'
        r.deviceIds == ['16', '4']            // still surfaced from the probe
        r.partial == true
        r.note.toLowerCase().contains('access token')
    }

    def "get legacy: flags partial when the layout endpoint is unreadable, noting disabled LAN access"() {
        given: 'token mints, but the layout GET is unregistered -> unreadable; LAN access is off'
        registerLegacyChildAppList('700', 'Studio')
        registerLegacyStatus('700', [label: 'Studio', localAccess: false])
        registerLegacyMint('700')

        when:
        def r = script.toolGetDashboard([dashboardId: '700'])

        then:
        r.type == 'legacy'
        r.partial == true
        r.note.toLowerCase().contains('layout')
        r.note.toLowerCase().contains('lan')   // the localAccess=false hint is appended
    }

    // ---------- create legacy ----------

    def "a bare id in place of dashboardId, and the version argument, are refused through dispatch before any write"() {
        given:
        enableWrite()

        when:
        script.executeTool(tool, args)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unknown ${tool} argument: ${bad}.")
        e.message.contains(valid)
        hubGet.calls.isEmpty()

        where:
        tool                   | args                                                 | bad       | valid
        'hub_create_dashboard' | [name: 'Patio', deviceIds: ['12'], version: '2.0']   | 'version' | 'sourceId'
        'hub_get_dashboard'    | [id: '412']                                          | 'id'      | 'dashboardId'
        'hub_update_dashboard' | [id: '412', name: 'X', deviceIds: ['12']]            | 'id'      | 'dashboardId'
        'hub_delete_dashboard' | [id: '412', confirm: true]                           | 'id'      | 'dashboardId'
    }

    def "create legacy refuses a protected parent before creating or configuring a dashboard"() {
        given:
        enableWrite()
        registerLegacyParent(21)
        atomicStateMap.protectedAppsPolicy = [ids: ['21']]
        def rawPaths = stubCreateChild('850')
        def forms = captureFormPosts()

        when:
        script.toolCreateDashboard([name: 'Patio', type: 'legacy'])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('protected')
        error.message.contains('21')
        rawPaths.empty
        forms.empty
    }

    def "create legacy: createchild under the legacy parent, then writes label + devicesPicked"() {
        given:
        enableWrite()
        registerLegacyParent(21)
        def rawPaths = stubCreateChild('850')
        def forms = captureFormPosts()

        when:
        def r = script.toolCreateDashboard([name: 'Patio', type: 'legacy', deviceIds: ['16', '4']])

        then: 'the new legacy dashboard is created with the child id parsed from the createchild Location'
        r.success == true
        r.id == '850'
        r.type == 'legacy'
        r.name == 'Patio'

        and: 'createchild hit the legacy parent (id 21)'
        rawPaths.contains('/installedapp/createchild/hubitat/Dashboard/parent/21')

        and: 'the label write carries the classic label sidecars'
        def label = forms.find { it.body.containsKey('label') }
        label.path == '/installedapp/update/json'
        label.body.label == 'Patio'
        label.body['label.type'] == 'text'
        label.body['label.multiple'] == 'false'

        and: 'the devicesPicked write carries the capability multi-select sidecars'
        def dev = forms.find { it.body.containsKey('settings[devicesPicked]') }
        dev.body['settings[devicesPicked]'] == '16,4'
        dev.body['devicesPicked.type'] == 'capability.*'
        dev.body['devicesPicked.multiple'] == 'true'
    }

    def "create legacy: without deviceIds writes only the label (empty grid, no device authorization)"() {
        given:
        enableWrite()
        registerLegacyParent(21)
        stubCreateChild('851')
        def forms = captureFormPosts()

        when:
        def r = script.toolCreateDashboard([name: 'Empty', type: 'legacy'])

        then:
        r.success == true
        forms.size() == 1
        forms[0].body.label == 'Empty'
        !forms.any { it.body.containsKey('settings[devicesPicked]') }
    }

    def "create legacy: a missing parent that the Add Built-In App bootstrap can't surface returns a structured error (no throw)"() {
        given: 'the apps tree never has the legacy parent, and the sysApp bootstrap GET yields nothing'
        enableWrite()
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: 19, name: 'Easy Dashboards', type: 'Easy Dashboard Parent'], children: []]]])
        }
        def rawPaths = []
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            rawPaths << p
            [status: 200, location: null, data: '']   // bootstrap answered but created nothing
        }

        when:
        def r = script.toolCreateDashboard([name: 'Patio', type: 'legacy', deviceIds: ['16']])

        then: 'the bootstrap was ATTEMPTED before giving up'
        rawPaths.any { it.startsWith('/installedapp/sysApp/') }
        r.success == false
        r.error.toLowerCase().contains('parent')
        r.note != null
    }

    def "create legacy: a missing parent is auto-installed via Add Built-In App, then create proceeds"() {
        given: 'the parent is absent until the sysApp bootstrap GET fires, then appsList surfaces it'
        enableWrite()
        def parentInstalled = [false]
        hubGet.register('/hub2/appsList') { params ->
            parentInstalled[0]
                ? JsonOutput.toJson([apps: [[data: [id: 21, name: 'HD', type: 'Hubitat® Dashboard', installed: true], children: []]]])
                : JsonOutput.toJson([apps: [[data: [id: 19, name: 'Easy Dashboards', type: 'Easy Dashboard Parent'], children: []]]])
        }
        def rawPaths = []
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            rawPaths << p
            if (p.startsWith('/installedapp/sysApp/')) {
                parentInstalled[0] = true
                return [status: 302, location: '/installedapp/configure/21', data: '']
            }
            if (p.startsWith('/installedapp/createchild')) {
                return [status: 302, location: '/installedapp/configure/860', data: '']
            }
            return [status: 200, location: null, data: '']
        }
        captureFormPosts()

        when:
        def r = script.toolCreateDashboard([name: 'Bootstrapped', type: 'legacy'])

        then: 'the sysApp bootstrap fired, then createchild landed under the bootstrapped parent'
        r.success == true
        r.id == '860'
        rawPaths.any { it.startsWith('/installedapp/sysApp/') }
        rawPaths.contains('/installedapp/createchild/hubitat/Dashboard/parent/21')
    }

    def "create legacy: createchild returning no new id surfaces a structured error"() {
        given:
        enableWrite()
        registerLegacyParent(21)
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            [status: 200, location: null, data: '']   // no Location header -> no id to parse
        }

        when:
        def r = script.toolCreateDashboard([name: 'Patio', type: 'legacy'])

        then:
        r.success == false
        r.error.toLowerCase().contains('new app id')
    }

    def "create legacy: the options arg is rejected (a legacy dashboard's look lives in its layout)"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([name: 'Patio', type: 'legacy', options: [theme: 'dark']])

        then:
        thrown(IllegalArgumentException)
    }

    // ---------- update legacy: wholesale layout ----------

    def "update legacy: a wholesale layout replace posts the new layout and confirms via the echo"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyMint('700')   // the POST mints its own token; wholesale never reads the current layout
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700',
            layout: [cols: 8, tiles: [[id: 5, template: 'mode', col: 1, row: 1]]]])

        then:
        r.success == true
        r.type == 'legacy'
        r.applied == ['layout']
        r.tileCount == 1
        r.layout.cols == 8

        and: 'the saved layout was posted with the access_token + requestToken query'
        posts.size() == 1
        posts[0].body.cols == 8
        posts[0].query.access_token == 'tok-700'
        posts[0].query.requestToken == 'rt-700'
    }

    // ---------- update legacy: granular ops ----------

    def "update legacy: addTiles assigns the next id and defaults colSpan/rowSpan to 1"() {
        given:
        enableWrite()
        registerLegacyStatus('700')                 // authorized devices [16, 4]
        registerLegacyLayout('700', LEGACY_LAYOUT)  // current tiles ids 0, 1
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700',
            addTiles: [[template: 'attribute', device: '4', col: 3, row: 2]]])

        then:
        r.success == true
        r.applied == ['addTiles']
        r.tileCount == 3
        def added = posts[0].body.tiles.find { it.template == 'attribute' && it.col == 3 }
        added.id == 2                               // max(0,1) + 1
        added.colSpan == 1
        added.rowSpan == 1

        and: 'an authorized device raises no warning'
        !r.warnings
    }

    def "update legacy: addTiles onto a layout whose only tile is id 0 assigns id 1 (0 is falsy, not absent)"() {
        given: 'the canonical fresh-dashboard state: exactly one tile, id 0'
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', [name: 'Fresh', cols: 4, tiles: [[id: 0, template: 'clock', col: 1, row: 1, colSpan: 1, rowSpan: 1]]])
        def posts = captureLayoutPosts()

        when: 'the second add in the tool-documented workflow'
        def r = script.toolUpdateDashboard([dashboardId: '700', addTiles: [[template: 'mode', col: 2, row: 1]]])

        then: 'no id collision with the existing tile 0'
        r.success == true
        posts[0].body.tiles*.id == [0, 1]
    }

    def "update legacy: an addTiles differing only in templateExtra is NOT deduped as identical"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)   // tile 0: clock col 1 row 1, no templateExtra
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700',
            addTiles: [[template: 'clock', col: 1, row: 1, templateExtra: 'seconds']]])

        then:
        r.success == true
        posts[0].body.tiles.size() == 3
        !r.warnings
    }

    def "update legacy: an addTiles identical to an existing tile is skipped with a warning (retry-safe)"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def posts = captureLayoutPosts()

        when: 'add a tile identical to the existing clock (id 0): same template/col/row, default spans'
        def r = script.toolUpdateDashboard([dashboardId: '700', addTiles: [[template: 'clock', col: 1, row: 1]]])

        then:
        r.success == true
        r.tileCount == 2                            // unchanged; the duplicate was skipped
        r.warnings.any { it.toLowerCase().contains('identical') }
        posts[0].body.tiles.size() == 2
    }

    def "update legacy: a tile bound to an unauthorized device is added but warned about"() {
        given:
        enableWrite()
        registerLegacyStatus('700')                 // authorized devices [16, 4]
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700',
            addTiles: [[template: 'attribute', device: '99', col: 4, row: 4]]])

        then:
        r.success == true
        r.tileCount == 3
        r.warnings.any { it.contains('99') && it.toLowerCase().contains('authorized') }
        posts[0].body.tiles.any { it.device == '99' }
    }

    def "update legacy: a tile for a device authorized in the same call is not warned about"() {
        given:
        enableWrite()
        registerLegacyStatus('700')                 // authorized devices [16, 4]
        registerLegacyLayout('700', LEGACY_LAYOUT)
        captureFormPosts()
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', deviceIds: ['16', '99'],
            addTiles: [[template: 'attribute', device: '99', col: 4, row: 4]]])

        then:
        r.success == true
        r.applied == ['deviceIds', 'addTiles']
        !r.warnings
        posts[0].body.tiles.any { it.device == '99' }
    }

    def "update legacy: a bad tile op beside #writes throws before any label, device or layout write"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def forms = captureFormPosts()
        def posts = captureLayoutPosts()

        when:
        script.toolUpdateDashboard([dashboardId: '700'] + extra + [(op): value])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(msg)
        forms.isEmpty()
        posts.isEmpty()

        where:
        writes          | extra                              | op            | value                                      | msg
        'deviceIds'     | [deviceIds: ['5']]                 | 'addTiles'    | [[template: 'clock', colum: 1, row: 1]]    | 'unknown tile key'
        'name'          | [name: 'X']                        | 'addTiles'    | [[template: 'clock', colum: 1, row: 1]]    | 'unknown tile key'
        'name+deviceIds'| [name: 'X', deviceIds: ['5']]      | 'addTiles'    | [[template: 'clock', col: 1]]              | 'row'
        'name+deviceIds'| [name: 'X', deviceIds: ['5']]      | 'updateTiles' | [[id: 99, col: 1]]                         | '99'
        'name+deviceIds'| [name: 'X', deviceIds: ['5']]      | 'setOptions'  | [tiles: []]                                | 'setOptions cannot replace tiles'
        'name+deviceIds'| [name: 'X', deviceIds: ['5']]      | 'addTiles'    | 'not-an-array'                             | 'addTiles must be an array'
    }

    def "update legacy: addTiles missing template/col/row throws"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)

        when:
        script.toolUpdateDashboard([dashboardId: '700', addTiles: [[template: 'clock', col: 1]]])   // no row

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('row')
    }

    def "update legacy: updateTiles mutates only the named fields of an existing tile"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', updateTiles: [[id: 0, col: 4, colSpan: 2]]])

        then:
        r.success == true
        r.applied == ['updateTiles']
        def t0 = posts[0].body.tiles.find { it.id == 0 }
        t0.col == 4
        t0.colSpan == 2
        t0.template == 'clock'                      // untouched field preserved
    }

    def "update legacy: an unknown #op key is named before any layout save, ahead of a missing-field error"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def posts = captureLayoutPosts()

        when:
        script.toolUpdateDashboard([dashboardId: '700', (op): [entry]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("${op}: unknown tile key".toString())
        e.message.contains(bad)
        e.message.contains('Allowed keys: id, template, device, col, row, colSpan, rowSpan, templateExtra')
        posts.isEmpty()

        where:
        op            | entry                                                    | bad
        'addTiles'    | [template: 'clock', colum: 1, row: 1]                    | 'colum'     // col is missing too
        'updateTiles' | [colum: 2]                                               | 'colum'     // the id selector is missing too
        'updateTiles' | [id: 0, colum: 2]                                        | 'colum'
        'updateTiles' | [id: 0, metaClass: [Fields: [getClass: [0, null]]]]      | 'metaClass'
    }

    def "update legacy: updateTiles with an unknown id throws, listing the existing ids"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)

        when:
        script.toolUpdateDashboard([dashboardId: '700', updateTiles: [[id: 99, col: 1]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('99')
    }

    def "update legacy: removeTileIds drops present tiles and warns (not errors) on an already-gone id"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', removeTileIds: [1, 9]])

        then:
        r.success == true
        r.tileCount == 1
        posts[0].body.tiles*.id == [0]
        r.warnings.any { it.contains('9') }
    }

    def "update legacy: remove -> update -> add apply in one save with the add id recomputed after removal"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)   // tiles ids 0 (clock), 1 (attribute/16)
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700',
            removeTileIds: [0],
            updateTiles: [[id: 1, col: 5]],
            addTiles: [[template: 'mode', col: 3, row: 2]]])

        then:
        r.success == true
        r.tileCount == 2
        def tiles = posts[0].body.tiles
        tiles*.id == [1, 2]                          // id 0 removed; the add id is max(remaining)+1 = 2
        tiles.find { it.id == 1 }.col == 5
        tiles.find { it.id == 2 }.template == 'mode'

        and: 'applied names the granular ops in the canonical (source-list) order, in one save'
        r.applied == ['removeTileIds', 'updateTiles', 'addTiles']
        posts.size() == 1
    }

    def "update legacy: setOptions merges top-level layout fields and leaves tiles untouched"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', setOptions: [
            cols: 10, bgColor: '#123456', fields: false, metaClass: [Fields: [getClass: [0, null]]]
        ]])

        then:
        r.success == true
        posts[0].body.cols == 10
        posts[0].body.bgColor == '#123456'
        posts[0].body.tiles*.id == [0, 1]
        posts[0].body.get('fields').is(false)
        posts[0].body.get('metaClass') == [Fields: [getClass: [0, null]]]
    }

    def "update legacy: setOptions rejects the tiles and name keys"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)

        when: 'tiles must go through the tile ops, not setOptions'
        script.toolUpdateDashboard([dashboardId: '700', setOptions: [tiles: []]])
        then:
        thrown(IllegalArgumentException)

        when: 'the name is the app label, not a layout field'
        script.toolUpdateDashboard([dashboardId: '700', setOptions: [name: 'X']])
        then:
        thrown(IllegalArgumentException)
    }

    // ---------- update legacy: write-failure + partial-degradation branches ----------

    def "update legacy: layout ops with an unreadable access token fail structured (endpoint unreachable)"() {
        given: 'legacy probe succeeds but carries NO accessToken (app never opened)'
        enableWrite()
        registerLegacyStatus('700', [accessToken: null])
        def forms = captureFormPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', name: 'Study', deviceIds: ['16'],
                                            addTiles: [[template: 'clock', col: 1, row: 1]]])

        then: 'the token check runs before the rename and the device write'
        r.success == false
        r.error.toLowerCase().contains('access token')
        r.note != null
        r.applied == []
        forms.isEmpty()
    }

    def "update legacy: a failed read-before-edit of the current layout fails structured, noting disabled LAN access"() {
        given: 'token mints but the layout GET is unregistered -> unreadable; LAN access is off'
        enableWrite()
        registerLegacyStatus('700', [localAccess: false])
        registerLegacyMint('700')
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', setOptions: [cols: 5]])

        then: 'no save was fired on top of an unreadable layout'
        r.success == false
        r.error.toLowerCase().contains('current layout')
        r.note.toLowerCase().contains('lan')
        posts.isEmpty()
    }

    def "update legacy: a rename write failure stops the update before any layout op fires"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        script.metaClass.hubInternalPostForm = { String p, Map b, int t = 420, boolean r = false ->
            throw new RuntimeException('hub choked')
        }
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', name: 'X', setOptions: [cols: 5]])

        then: 'the failure reports nothing after the rename was attempted -- and the layout save never fired'
        r.success == false
        r.error.toLowerCase().contains('rename failed')
        r.applied == []
        posts.isEmpty()
    }

    def "update legacy: a deviceIds write failure fails structured without touching the layout"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        script.metaClass.hubInternalPostForm = { String p, Map b, int t = 420, boolean r = false ->
            [status: b.containsKey('settings[devicesPicked]') ? 500 : 200, location: null, data: '']
        }
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', name: 'Study', deviceIds: ['5'], setOptions: [cols: 5]])

        then: 'the rename that ran first is reported, and the layout is never saved'
        r.success == false
        r.error.toLowerCase().contains('device authorization')
        r.applied == ['name']
        posts.isEmpty()
    }

    def "update legacy: a layout save that throws fails structured (layout may be unchanged)"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        script.metaClass.hubInternalPostJson = { String p, String body, int t = 420, boolean r = false, Map q = null ->
            throw new RuntimeException('relay dropped')
        }

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', setOptions: [cols: 5]])

        then:
        r.success == false
        r.error.toLowerCase().contains('layout save failed')
        r.note.toLowerCase().contains('hub_get_dashboard')
    }

    def "update legacy: a layout save whose echo has no tiles list reports the outcome unconfirmed"() {
        given: 'the POST answers 200 but echoes junk instead of the saved layout'
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        script.metaClass.hubInternalPostJson = { String p, String body, int t = 420, boolean r = false, Map q = null ->
            [error: true]
        }

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', setOptions: [cols: 5]])

        then:
        r.success == false
        r.error.toLowerCase().contains('unconfirmed')
    }

    def "update legacy: a wholesale layout that is not an object throws"() {
        given:
        enableWrite()
        registerLegacyStatus('700')

        when:
        script.toolUpdateDashboard([dashboardId: '700', layout: 'not-a-map'])

        then:
        thrown(IllegalArgumentException)
    }

    def "create legacy: a failed label write still creates, surfacing the fallback-label warning"() {
        given:
        enableWrite()
        registerLegacyParent(21)
        stubCreateChild('852')
        script.metaClass.hubInternalPostForm = { String p, Map b, int t = 420, boolean r = false ->
            throw new RuntimeException('label write dropped')
        }

        when:
        def r = script.toolCreateDashboard([name: 'Patio', type: 'legacy'])

        then: "the dashboard exists (createchild committed) -- the caller is told the label didn't take"
        r.success == true
        r.id == '852'
        r.warnings.any { it.toLowerCase().contains('label') }

        and: "the result does NOT echo a name the hub doesn't carry (the label is still 'Dashboard')"
        !r.containsKey('name')
    }

    def "delete: a probe read failure fails safe without firing any delete"() {
        given: 'id 909 has no statusJson stub -> the probe read throws -> null (type unknown)'
        enableWrite()
        def deleteFired = [false]
        hubGet.register('/dashboard/delete') { params -> deleteFired[0] = true; '{"success":false,"message":null}' }
        def rawPaths = []
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            rawPaths << p
            [status: 200, location: null, data: '']
        }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '909', confirm: true])

        then: 'neither the Easy delete endpoint nor the force-delete was touched'
        r.success == false
        r.error.toLowerCase().contains('type')
        !deleteFired[0]
        !rawPaths.any { it.contains('forcedelete') }
    }

    def "copy legacy: a layout save whose echo is empty reports the copy's layout unconfirmed"() {
        given: 'the copy is created and has a token, but the layout POST echoes nothing (dropped body)'
        enableWrite()
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([legacyRow(700, 'Studio', [deviceIds: [16, 4]])])
        }
        registerLegacyStatus('700', [label: 'Studio', deviceIds: [16, 4]])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        registerLegacyParent(21)
        stubCreateChild('882')
        captureFormPosts()
        registerLegacyStatus('882', [accessToken: 'tok-882', label: 'Studio (copy)'])
        registerLegacyMint('882')
        script.metaClass.hubInternalPostJson = { String p, String body, int t = 420, boolean r = false, Map q = null ->
            null
        }

        when:
        def r = script.toolCreateDashboard([sourceId: '700'])

        then:
        r.success == false
        r.id == '882'
        r.error.toLowerCase().contains('unconfirmed')
    }

    def "list: the legacy parent type matches without the trademark glyph (encoding-tolerant walker)"() {
        given: 'a parent whose type name lost the (R) glyph somewhere in the chain'
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: 21, name: 'HD', type: 'Hubitat Dashboard'],
                children: [[data: [id: 707, name: 'Porch', type: 'Dashboard'], children: []]]]]])
        }

        when:
        def r = script.toolListDashboards([:])

        then:
        r.dashboards.find { it.id == '707' }?.type == 'legacy'
    }

    def "delete legacy: reports failure when removal can't be CONFIRMED (verification read failed)"() {
        given: 'forcedelete fires but the status read after it fails -> tri-state null'
        enableWrite()
        registerLegacyStatus('700', [afterDelete: 'fail'])
        stubForceDelete(false)

        when:
        def r = script.toolDeleteDashboard([dashboardId: '700', confirm: true])

        then: 'a destructive op never claims success it cannot verify'
        r.success == false
        r.error.toLowerCase().contains('could not be confirmed')
        forceDeletePaths == ['/installedapp/forcedelete/700/quiet']
    }

    // ---------- update legacy: name / deviceIds / validation ----------

    def "update legacy: a name-only update writes the app label"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        def forms = captureFormPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', name: 'Renamed'])

        then:
        r.success == true
        r.type == 'legacy'
        r.applied == ['name']
        r.name == 'Renamed'
        def label = forms.find { it.body.containsKey('label') }
        label.path == '/installedapp/update/json'
        label.body.label == 'Renamed'
        label.body['label.multiple'] == 'false'
    }

    def "update legacy: name + deviceIds writes both via the classic form (with capability sidecars)"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        def forms = captureFormPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', name: 'Combo', deviceIds: ['5', '6']])

        then:
        r.success == true
        r.applied == ['name', 'deviceIds']
        r.name == 'Combo'
        r.deviceIds == ['5', '6']

        and: 'the label write'
        forms.find { it.body.label == 'Combo' } != null

        and: 'the devicesPicked write carries the multi-select sidecars'
        def dev = forms.find { it.body.containsKey('settings[devicesPicked]') }
        dev.body['settings[devicesPicked]'] == '5,6'
        dev.body['devicesPicked.type'] == 'capability.*'
        dev.body['devicesPicked.multiple'] == 'true'
    }

    def "update legacy: passing both layout and granular ops throws"() {
        given:
        enableWrite()
        registerLegacyStatus('700')

        when:
        script.toolUpdateDashboard([dashboardId: '700', layout: [tiles: []], addTiles: [[template: 'x', col: 1, row: 1]]])

        then:
        thrown(IllegalArgumentException)
    }

    def "update legacy: no name/deviceIds/layout/granular ops throws"() {
        given:
        enableWrite()
        registerLegacyStatus('700')

        when:
        script.toolUpdateDashboard([dashboardId: '700'])

        then:
        thrown(IllegalArgumentException)
    }

    def "update legacy: the Easy-only options arg is rejected"() {
        given:
        enableWrite()
        registerLegacyStatus('700')

        when:
        script.toolUpdateDashboard([dashboardId: '700', name: 'Kept', options: [theme: 'dark']])

        then:
        thrown(IllegalArgumentException)
    }

    def "update: legacy-only args against an Easy Dashboard throw (not silently applied)"() {
        given: 'id 412 probes as a NON-legacy (Easy) dashboard via the setup() statusJson stub'
        enableWrite()

        when:
        script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: ['12'],
            addTiles: [[template: 'clock', col: 1, row: 1]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('legacy')
    }

    def "update: a probe read failure returns a safe error without firing any write"() {
        given: 'id 909 has no statusJson stub -> the probe read throws -> null (type unknown)'
        enableWrite()
        def forms = captureFormPosts()
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '909', name: 'X', deviceIds: ['5'], layout: [tiles: []]])

        then: 'no write fired; the type could not be determined'
        r.success == false
        r.id == '909'
        r.error.toLowerCase().contains('type')
        forms.isEmpty()
        posts.isEmpty()
    }

    // ---------- delete legacy ----------

    def "delete legacy: force-deletes (the hub answers 500 even when it deleted) then confirms removal by absence"() {
        given: 'statusJson answers {} once the force-delete fired -> confirmed absent'
        enableWrite()
        registerLegacyStatus('700', [afterDelete: 'gone'])
        stubForceDelete(false)

        when:
        def r = script.toolDeleteDashboard([dashboardId: '700', confirm: true])

        then:
        r.success == true
        r.type == 'legacy'
        r.id == '700'
        forceDeletePaths == ['/installedapp/forcedelete/700/quiet']
    }

    def "delete legacy: a forcedelete whose connection drops still confirms deletion by effect"() {
        given: 'the forcedelete request throws, but the status page shows 700 gone'
        enableWrite()
        registerLegacyStatus('700', [afterDelete: 'gone'])
        stubForceDelete(true)

        when:
        def r = script.toolDeleteDashboard([dashboardId: '700', confirm: true])

        then:
        r.success == true
        r.id == '700'
        forceDeletePaths == ['/installedapp/forcedelete/700/quiet']
    }

    def "delete legacy: reports success:false when the dashboard is still present after force-delete"() {
        given:
        enableWrite()
        registerLegacyStatus('700')   // 700 STILL present after the force-delete
        stubForceDelete(false)

        when:
        def r = script.toolDeleteDashboard([dashboardId: '700', confirm: true])

        then:
        r.success == false
        !r.containsKey('isError')
        r.type == 'legacy'
        r.error.toLowerCase().contains('still present')
        forceDeletePaths == ['/installedapp/forcedelete/700/quiet']
    }

    // ---------- copy legacy ----------

    def "copy legacy: copies the source layout into a fresh legacy dashboard"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([legacyRow(700, 'Studio', [deviceIds: [16, 4]])])
        }
        registerLegacyStatus('700', [label: 'Studio', deviceIds: [16, 4]])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        registerLegacyParent(21)
        stubCreateChild('880')
        captureFormPosts()
        // the copy probes legacy:true with its own token + mint page so the source layout can be saved into it
        registerLegacyStatus('880', [accessToken: 'tok-880', label: 'Studio (copy)'])
        registerLegacyMint('880')
        def posts = captureLayoutPosts()

        when:
        def r = script.toolCreateDashboard([sourceId: '700'])

        then:
        r.success == true
        r.sourceId == '700'
        r.id == '880'
        r.type == 'legacy'
        r.message.toLowerCase().contains('copied')

        and: "the source's layout was posted to the new dashboard, authorized by the copy's own token"
        posts.size() == 1
        posts[0].body.tiles.size() == 2
        posts[0].query.access_token == 'tok-880'
    }

    def "copy legacy: surfaces a structured failure when the copy's layout can't be written"() {
        given: "the copy is created but probes WITHOUT an access token -> its layout endpoint is unreachable"
        enableWrite()
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([legacyRow(700, 'Studio', [deviceIds: [16, 4]])])
        }
        registerLegacyStatus('700', [label: 'Studio', deviceIds: [16, 4]])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        registerLegacyParent(21)
        stubCreateChild('881')
        captureFormPosts()
        registerLegacyStatus('881', [accessToken: null, label: 'Studio (copy)'])

        when:
        def r = script.toolCreateDashboard([sourceId: '700'])

        then:
        r.success == false
        r.sourceId == '700'
        r.id == '881'                            // the copy WAS created; only the layout write failed
        r.type == 'legacy'
        r.error.toLowerCase().contains('layout could not be written')
        r.note != null
    }

    def "copy legacy: an unreadable source device list fails before the copy is created"() {
        given: 'listed from the child apps (no device list) and statusJson carries devicesPicked without its ids'
        enableWrite()
        registerLegacyChildAppList('700', 'Studio')
        registerLegacyStatus('700', [label: 'Studio', deviceIds: null])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def rawPaths = stubCreateChild('882')
        def forms = captureFormPosts()
        def posts = captureLayoutPosts()

        when:
        def r = script.toolCreateDashboard([sourceId: '700'])

        then:
        r.success == false
        !r.containsKey('isError')
        r.sourceId == '700'
        r.error.contains('authorized device list')
        r.note.contains('hub_get_dashboard')
        !rawPaths.any { it.startsWith('/installedapp/createchild') }
        forms.isEmpty()
        posts.isEmpty()
    }

    def "copy legacy: an explicitly empty source device list is copied as empty"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([legacyRow(700, 'Studio', [deviceIds: []])])
        }
        registerLegacyLayout('700', LEGACY_LAYOUT)
        registerLegacyParent(21)
        def rawPaths = stubCreateChild('883')
        def forms = captureFormPosts()
        registerLegacyStatus('883', [accessToken: 'tok-883', label: 'Studio (copy)', deviceIds: []])
        registerLegacyMint('883')
        def posts = captureLayoutPosts()

        when:
        def r = script.toolCreateDashboard([sourceId: '700'])

        then:
        r.success == true
        r.id == '883'
        rawPaths.any { it.startsWith('/installedapp/createchild') }
        !forms.any { it.body.containsKey('settings[devicesPicked]') }
        posts.size() == 1
        posts[0].body.tiles.size() == 2
    }

    // ---------- legacy through the gateway / dispatch envelope ----------

    def "a legacy get routes THROUGH the hub_manage_dashboards gateway"() {
        given:
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([legacyRow(700, 'Studio', [deviceIds: [16, 4]])])
        }
        registerLegacyStatus('700', [label: 'Studio'])
        registerLegacyLayout('700', LEGACY_LAYOUT)

        when:
        def r = script.handleGateway('hub_manage_dashboards', 'hub_get_dashboard', [dashboardId: '700'])

        then:
        r.type == 'legacy'
        r.id == '700'
        r.layout.tiles.size() == 2
    }

    def "hub_update_dashboard (legacy name write) via the dispatch envelope returns the success payload"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        captureFormPosts()

        when:
        def resp = mcpDriver.callTool('hub_update_dashboard', [dashboardId: '700', name: 'Envelope'])

        then:
        def inner = mcpDriver.parseInner(resp)
        inner.success == true
        inner.type == 'legacy'
        inner.applied == ['name']
    }
    def "create legacy reports unknown outcome when the create response is lost"() {
        given:
        enableWrite()
        registerLegacyParent(21)
        def rawPaths = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int timeout = 30, boolean raw = false ->
            rawPaths << path
            throw new IOException('connection reset after create')
        }

        when:
        def result = script.toolCreateDashboard([name: 'Patio', type: 'legacy'])

        then:
        result.success == false
        result.outcomeUnknown == true
        result.parentAppId == 21
        result.note.contains('may have been created')
        result.note.contains('hub_list_dashboards')
        result.note.contains('hub_list_apps')
        result.note.contains('before retrying')
        !result.note.contains('Nothing was created')
        rawPaths == ['/installedapp/createchild/hubitat/Dashboard/parent/21']
    }

    def "create Easy reports unknown outcome when the create response is lost"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/dashboard/create') { throw new IOException('connection reset after create') }

        when:
        def result = script.toolCreateDashboard([name: 'Patio', deviceIds: ['1']])

        then:
        result.success == false
        result.outcomeUnknown == true
        result.note.contains('may have been created')
        result.note.contains('hub_list_dashboards')
        result.note.contains('before retrying')
        !result.note.contains('Nothing was created')
        hubGet.calls.count { it.path == '/dashboard/create' } == 1
    }

    // ---------- the MCP device selection ----------

    /** Bypass off, with {@code ids} selected for MCP. */
    private void selectOnly(List ids) {
        settingsMap.bypassDeviceAllowlist = false
        settingsMap.selectedDevices = ids.collect { new TestDevice(id: it, name: "dev${it}", label: "Dev ${it}") }
    }

    /** Runs {@code call}, returning its result or the IllegalArgumentException it threw. */
    private static Object outcomeOf(Closure call) {
        try {
            return call()
        } catch (IllegalArgumentException e) {
            return e
        }
    }

    @Unroll
    def "create #kind with the bypass off refuses #label before any request"() {
        given: 'device 12 selected, 34 a child of this app, 56 neither'
        enableWrite()
        selectOnly([12])
        childDevicesList << new TestDevice(id: 34, name: 'child', label: 'Child')
        registerLegacyParent(21)
        def rawPaths = stubCreateChild('850')
        def forms = captureFormPosts()

        when:
        script.toolCreateDashboard([name: 'Patio', type: kind, deviceIds: ids])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.startsWith(needle)
        ex.message.contains('Bypass Device Allowlist')

        and: 'the kept-id hint fits only an update, so a create refusal leaves it out'
        !ex.message.contains('hub_get_dashboard')
        !hubGet.calls.any { it.path == '/dashboard/create' }
        !rawPaths.any { it.startsWith('/installedapp/createchild') }
        forms.isEmpty()

        where:
        kind     | label              | ids                      | needle
        'easy'   | 'an unselected id' | ['12', '34', '56']       | 'deviceIds 56 is outside the MCP device selection'
        'easy'   | 'two of them'      | ['57', '12', '56', '57'] | 'deviceIds 57, 56 are outside the MCP device selection'
        'legacy' | 'an unselected id' | ['12', '56']             | 'deviceIds 56 is outside the MCP device selection'
    }

    def "create refuses an unknown theme and a missing name ahead of the device selection"() {
        given:
        enableWrite()
        selectOnly([12])

        when:
        def missingName = outcomeOf { script.toolCreateDashboard([deviceIds: ['56']]) }
        def badTheme = outcomeOf { script.toolCreateDashboard([name: 'Patio', deviceIds: ['56'], options: [theme: 'neon']]) }
        def noDevices = outcomeOf { script.toolCreateDashboard([name: 'Patio', deviceIds: []]) }

        then:
        missingName.message.startsWith('name is required')
        badTheme.message.startsWith('theme must be one of')
        noDevices.message.startsWith('deviceIds must contain at least one device id')
        !hubGet.calls.any { it.path == '/dashboard/create' }
    }

    @Unroll
    def "create #kind saves selected and child devices with the bypass off, and any device with it on (bypass=#bypass)"() {
        given:
        enableWrite()
        selectOnly([12])
        settingsMap.bypassDeviceAllowlist = bypass
        childDevicesList << new TestDevice(id: 34, name: 'child', label: 'Child')
        registerLegacyParent(21)
        stubCreateChild('850')
        def forms = captureFormPosts()

        when:
        def r = script.toolCreateDashboard([name: 'Patio', type: kind, deviceIds: ids])

        then:
        r.success == true
        kind == 'easy'
            ? hubGet.calls.find { it.path == '/dashboard/create' }.params.deviceIds == ids.join(',')
            : forms.find { it.body.containsKey('settings[devicesPicked]') }.body['settings[devicesPicked]'] == ids.join(',')

        where:
        kind     | bypass | ids
        'easy'   | false  | ['12', '34']
        'easy'   | true   | ['12', '34', '56']
        'legacy' | false  | ['12', '34']
        'legacy' | true   | ['12', '56']
    }

    @Unroll
    def "copy of #kind dashboard #source with the bypass off refuses a source device outside the selection, and copies with it on"() {
        given: 'Easy 412 shows devices 12 and 34; legacy 700 authorizes 16 and 4'
        enableWrite()
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([easyRow(412, 'Living Room', [deviceIds: [12, 34]]), legacyRow(700, 'Studio', [deviceIds: [16, 4]])])
        }
        registerLegacyStatus('700', [label: 'Studio', deviceIds: [16, 4]])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        registerLegacyParent(21)
        def rawPaths = stubCreateChild('882')
        def forms = captureFormPosts()
        def posts = captureLayoutPosts()
        registerLegacyStatus('882', [accessToken: 'tok-882', label: 'Studio (copy)'])
        registerLegacyMint('882')
        selectOnly(selected)

        when:
        def refused = outcomeOf { script.toolCreateDashboard([sourceId: source]) }

        then:
        refused instanceof IllegalArgumentException
        refused.message.startsWith("deviceIds ${outside} is outside the MCP device selection, so a copy of dashboard ${source} cannot authorize it")
        !refused.message.contains('hub_get_dashboard')
        !hubGet.calls.any { it.path == '/dashboard/create' }
        !rawPaths.any { it.startsWith('/installedapp/createchild') }
        forms.isEmpty()
        posts.isEmpty()

        when:
        settingsMap.bypassDeviceAllowlist = true
        def copied = script.toolCreateDashboard([sourceId: source])

        then:
        copied.success == true
        copied.sourceId == source

        where:
        kind     | source | selected | outside
        'easy'   | '412'  | [12]     | '34'
        'legacy' | '700'  | [4]      | '16'
    }

    @Unroll
    def "Easy update with the bypass off checks only the ids whose membership changes: #label"() {
        given: 'dashboard 412 shows 600 (not selected) and 100 (selected); 101 is selected, 700 is not'
        enableWrite()
        selectOnly([100, 101])
        hubGet.register('/installedapp/configure/json/412') { params ->
            easyConfigJson('Living Room', [devicesPicked: ['600': 'Lamp', '100': 'Fan']])
        }

        when:
        def outcome = outcomeOf { script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ids]) }

        then:
        refused ? (outcome instanceof IllegalArgumentException && outcome.message.startsWith(refused)) : outcome.success == true
        refused ? outcome.message.endsWith('An id hub_get_dashboard returns stays in the list you send.') : true
        hubGet.calls.count { it.path == '/dashboard/update' } == (refused ? 0 : 1)

        where:
        label                                    | ids                   | refused
        'a rename keeps the unselected device'   | ['600', '100']        | null
        're-sending it with a selected addition' | ['600', '100', '101'] | null
        'dropping a selected device'             | ['600']               | null
        'dropping the unselected device'         | ['100']               | 'deviceIds 600 is outside the MCP device selection, so this dashboard write cannot add or remove it'
        'adding an unselected device'            | ['600', '100', '700'] | 'deviceIds 700 is outside the MCP device selection'
        'both at once'                           | ['100', '700']        | 'deviceIds 700, 600 are outside the MCP device selection'
    }

    def "Easy update with the bypass on changes any device without reading the device list for the check"() {
        given: 'both PINs and localOnly passed, so nothing else needs the config read'
        enableWrite()
        selectOnly([100])
        settingsMap.bypassDeviceAllowlist = true

        when:
        def r = script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ['700'], options: [dashboardPin: '1', hsmPin: '2', localOnly: false]])

        then:
        r.success == true
        hubGet.calls.find { it.path == '/dashboard/update' }.params.deviceIds == '700'
        !hubGet.calls.any { it.path == '/installedapp/configure/json/412' }
    }

    def "Easy update with the bypass off and an unreadable current device list fails without a write"() {
        given:
        enableWrite()
        selectOnly([100])
        hubGet.register('/installedapp/configure/json/412') { params -> throw new RuntimeException('config page down') }

        when:
        def r = script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ['100']])

        then:
        r.success == false
        r.error.contains('MCP device selection')
        r.note.endsWith("See hub_get_tool_guide(section='dashboards').")
        !hubGet.calls.any { it.path == '/dashboard/update' }
    }

    @Unroll
    def "legacy update with the bypass off checks only the ids whose membership changes: #label"() {
        given: 'legacy 700 authorizes 600 (not selected) and 100 (selected); 101 is selected, 700 is not'
        enableWrite()
        selectOnly([100, 101])
        registerLegacyStatus('700', [deviceIds: [600, 100]])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def forms = captureFormPosts()
        def posts = captureLayoutPosts()

        when:
        def outcome = outcomeOf { script.toolUpdateDashboard(args + [dashboardId: '700']) }

        then:
        refused ? (outcome instanceof IllegalArgumentException && outcome.message.startsWith(refused)) : outcome.success == true
        refused ? outcome.message.endsWith('An id hub_get_dashboard returns stays in the list you send.') : true
        forms.count { it.body.containsKey('settings[devicesPicked]') } == deviceWrites
        refused ? (forms.isEmpty() && posts.isEmpty()) : true

        where:
        label                                    | args                                                       | refused                                                    | deviceWrites
        'a rename keeps the unselected device'   | [name: 'Study']                                            | null                                                       | 0
        're-sending it with a selected addition' | [name: 'Study', deviceIds: ['600', '100', '101']]          | null                                                       | 1
        'dropping a selected device'             | [deviceIds: ['600']]                                       | null                                                       | 1
        'dropping every device but one selected' | [deviceIds: ['100']]                                       | 'deviceIds 600 is outside the MCP device selection'        | 0
        'adding an unselected device'            | [deviceIds: ['600', '100', '700'], setOptions: [cols: 5]] | 'deviceIds 700 is outside the MCP device selection'        | 0
        'both at once'                           | [name: 'Study', deviceIds: ['100', '700']]                 | 'deviceIds 700, 600 are outside the MCP device selection'  | 0
    }

    def "legacy update names an unknown tile key ahead of the device selection"() {
        given:
        enableWrite()
        selectOnly([100])
        registerLegacyStatus('700', [deviceIds: [100]])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def forms = captureFormPosts()

        when:
        script.toolUpdateDashboard([dashboardId: '700', deviceIds: ['100', '700'],
                                    addTiles: [[template: 'clock', col: 1, row: 1, colour: 'red']]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('unknown tile key colour')
        forms.isEmpty()
    }

    def "legacy update with the bypass off and an unreadable current device list fails without a write"() {
        given: 'statusJson carries devicesPicked without its device list'
        enableWrite()
        selectOnly([100])
        registerLegacyStatus('700', [deviceIds: null])
        def forms = captureFormPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', name: 'Study', deviceIds: ['100']])

        then:
        r.success == false
        r.applied == []
        r.error.contains('MCP device selection')
        forms.isEmpty()
    }

    def "legacy update with the bypass on changes any device"() {
        given:
        enableWrite()
        selectOnly([100])
        settingsMap.bypassDeviceAllowlist = true
        registerLegacyStatus('700', [deviceIds: [600, 100]])
        def forms = captureFormPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', deviceIds: ['100', '700']])

        then:
        r.success == true
        r.deviceIds == ['100', '700']
        forms.find { it.body.containsKey('settings[devicesPicked]') }.body['settings[devicesPicked]'] == '100,700'
    }

    @Unroll
    def "#tool via dispatch with bypass=#bypass #outcome an unselected device (gateway=#gatewayMode)"() {
        given:
        enableWrite()
        selectOnly([12])
        settingsMap.bypassDeviceAllowlist = bypass
        hubGet.register('/installedapp/configure/json/412') { params -> easyConfigJson('Living Room', [devicesPicked: ['12': 'Lamp']]) }
        registerLegacyStatus('700', [deviceIds: [12]])
        def forms = captureFormPosts()
        def writes = { -> hubGet.calls.count { it.path in ['/dashboard/create', '/dashboard/update'] } + forms.size() }

        when:
        def resp = callDashboard(gatewayMode, 'hub_manage_dashboards', tool, args)

        then:
        def body = new JsonSlurper().parseText(resultText(resp))
        if (bypass) {
            assert resp.result.isError != true
            assert body.success == true
            assert writes() == 1
        } else {
            assert resp.result.isError == true
            assert resultText(resp).contains('deviceIds 56 is outside the MCP device selection')
            assert writes() == 0
        }

        where:
        [tool, args, bypass, gatewayMode] << [
            ['hub_create_dashboard', [name: 'Patio', deviceIds: ['12', '56']]],
            ['hub_update_dashboard', [dashboardId: '412', name: 'Den', deviceIds: ['12', '56'], options: [dashboardPin: '', hsmPin: '']]],
            ['hub_update_dashboard', [dashboardId: '700', deviceIds: ['12', '56']]]
        ].collectMany { row -> [[false, true], [true, false]].combinations().collect { row + it } }
        outcome = bypass ? 'saves' : 'refuses'
    }

    def "hub_update_dashboard holds its own lock, not the write reservation lock"() {
        expect:
        script.DASHBOARD_UPDATE_LOCK != null
        !script.DASHBOARD_UPDATE_LOCK.is(script.WRITE_RESERVATION_LOCK)
    }

    def "overlapping legacy updates run one at a time, so neither added tile is lost"() {
        given: 'a layout read that the first update pauses inside until released'
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyMint('700')
        def saved = new groovy.json.JsonSlurper().parseText(JsonOutput.toJson(LEGACY_LAYOUT))
        def reads = new java.util.concurrent.atomic.AtomicInteger()
        def firstReadEntered = new java.util.concurrent.CountDownLatch(1)
        def release = new java.util.concurrent.CountDownLatch(1)
        hubGet.register('/apps/api/700/dashboard/700/layout') { params ->
            String snapshot
            synchronized (saved) { snapshot = JsonOutput.toJson(saved) }
            if (reads.incrementAndGet() == 1) {
                firstReadEntered.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            snapshot
        }
        script.metaClass.hubInternalPostJson = { String p, String body, int t = 420, boolean r = false, Map q = null ->
            def parsed = new groovy.json.JsonSlurper().parseText(body)
            synchronized (saved) { saved.clear(); saved.putAll(parsed) }
            return parsed
        }
        def results = Collections.synchronizedList([])
        def update = { int col -> { -> results << script.toolUpdateDashboard([dashboardId: '700',
            addTiles: [[template: 'clock', col: col, row: 5]]]) } as Runnable }

        when: 'the second update starts while the first is paused after its read'
        def first = new Thread(update(1))
        first.start()
        assert firstReadEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)
        def second = new Thread(update(2))
        second.start()
        // Wait until the second worker is parked on the update lock (or has finished, which only
        // happens without the lock), instead of assuming it got scheduled within a fixed delay.
        def mx = java.lang.management.ManagementFactory.getThreadMXBean()
        int lockHash = System.identityHashCode(script.DASHBOARD_UPDATE_LOCK)
        def onUpdateLock = { ->
            def info = mx.getThreadInfo(second.id)
            info?.threadState == Thread.State.BLOCKED && info.lockInfo?.identityHashCode == lockHash
        }
        long deadline = System.currentTimeMillis() + 10000
        while (!onUpdateLock() && second.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        boolean secondBlockedOnLock = onUpdateLock()
        int readsWhilePaused = reads.get()
        release.countDown()
        first.join(10000)
        second.join(10000)

        then: 'the second waited on the update lock and could not read the layout until the first had saved'
        secondBlockedOnLock
        readsWhilePaused == 1
        reads.get() == 2

        and: 'both updates succeeded and the saved layout holds both tiles'
        results.size() == 2
        results.every { it.success == true }
        saved.tiles.findAll { it.template == 'clock' && it.row == 5 }*.col.sort() == [1, 2]
    }

    // ---------- a legacy dashboard with no devices ----------

    def "a legacy status page without devicesPicked reads as no devices, so the bypass-off check lets a selected device in"() {
        given: 'legacy 700 has never had a device picked; 100 is selected, 700 is not'
        enableWrite()
        selectOnly([100])
        registerLegacyChildAppList('700', 'Studio')
        registerLegacyStatus('700', [devicesPicked: false])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def forms = captureFormPosts()

        when:
        def got = script.toolGetDashboard([dashboardId: '700'])
        def added = script.toolUpdateDashboard([dashboardId: '700', deviceIds: ['100']])
        def refused = outcomeOf { script.toolUpdateDashboard([dashboardId: '700', deviceIds: ['700']]) }

        then:
        got.deviceIds == []
        !got.partial
        added.success == true
        added.applied == ['deviceIds']
        forms.count { it.body['settings[devicesPicked]'] == '100' } == 1

        and: 'an unselected device is still refused, before any further write'
        refused instanceof IllegalArgumentException
        refused.message.startsWith('deviceIds 700 is outside the MCP device selection')
        forms.size() == 1
    }

    def "an Easy config without devicesPicked reads as no devices for the bypass-off check"() {
        given:
        enableWrite()
        selectOnly([100])
        hubGet.register('/installedapp/configure/json/412') { params ->
            new JsonSlurper().parseText(easyConfigJson('Living Room')).with { m -> m.settings.remove('devicesPicked'); JsonOutput.toJson(m) }
        }

        when:
        def added = script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ['100']])
        def refused = outcomeOf { script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ['700']]) }

        then:
        added.success == true
        refused instanceof IllegalArgumentException
        hubGet.calls.count { it.path == '/dashboard/update' } == 1
    }

    // ---------- tile keys the hub's tile editor saves ----------

    def "update legacy: addTiles and updateTiles accept the tile editor's own keys (#keys)"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700'] + args)

        then:
        r.success == true
        def tile = posts[0].body.tiles.find { it.id == tileId }
        extra.every { k, v -> tile[k] == v }

        where:
        keys                                | tileId | args                                                                                                      | extra
        'globalVar on addTiles'             | 2      | [addTiles: [[template: 'global-variable', col: 3, row: 1, globalVar: 'tStr']]]                            | [globalVar: 'tStr']
        'linkName on updateTiles'           | 0      | [updateTiles: [[id: 0, linkName: 'Docs']]]                                                                | [linkName: 'Docs']
        'bgImage, hideUnit, refreshInterval' | 1     | [updateTiles: [[id: 1, bgImage: 'http://x/y.png', hideUnit: true, refreshInterval: 30]]]                  | [bgImage: 'http://x/y.png', hideUnit: true, refreshInterval: 30]
    }

    // ---------- Easy "Local access only" ----------

    def "get returns an Easy dashboard's localOnly from the hub list and from the app-config fallback"() {
        given:
        hubGet.register('/dashboard/all') { params -> JsonOutput.toJson([easyRow(412, 'Living Room', [localOnly: true])]) }

        when:
        def fromList = script.toolGetDashboard([dashboardId: '412'])

        then:
        fromList.localOnly == true

        when:
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]}]}]}'
        }
        hubGet.register('/installedapp/configure/json/38') { params -> easyConfigJson('Dashboard 1', [localOnly: 'true']) }
        def fromConfig = script.toolGetDashboard([dashboardId: '38'])

        then:
        fromConfig.localOnly == true
    }

    def "create sends options.localOnly, and leaves it to the hub's default when omitted"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([name: 'A', deviceIds: ['12'], options: [localOnly: true, showEdit: true, showNavigation: true]])
        script.toolCreateDashboard([name: 'B', deviceIds: ['12'], options: [showEdit: true, showNavigation: true]])

        then:
        def creates = hubGet.calls.findAll { it.path == '/dashboard/create' }
        creates[0].params.localOnly == 'true'
        !creates[1].params.containsKey('localOnly')
    }

    def "update re-sends the stored localOnly when it is omitted, and sends the given value otherwise"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/412') { params -> easyConfigJson('Living Room', [localOnly: 'true']) }

        when:
        script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ['12']])
        script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ['12'], options: [localOnly: false]])

        then:
        def updates = hubGet.calls.findAll { it.path == '/dashboard/update' }
        updates[0].params.localOnly == 'true'
        updates[1].params.localOnly == 'false'
    }

    def "update flags localOnlyPreserveFailed when the config read to keep an omitted localOnly fails"() {
        given: 'both PINs passed; /installedapp/configure/json/412 unmocked, so the read throws'
        enableWrite()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ['12'], options: [dashboardPin: '', hsmPin: '']])

        then:
        r.success == true
        r.localOnlyPreserveFailed == true
        !r.containsKey('pinPreserveFailed')
        r.note.contains("'Local access only' is off")
        !hubGet.calls.find { it.path == '/dashboard/update' }.params.containsKey('localOnly')
    }

    def "an Easy copy carries the source's localOnly"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/all') { params -> JsonOutput.toJson([easyRow(412, 'Living Room', [localOnly: true])]) }

        when:
        def r = script.toolCreateDashboard([sourceId: '412'])

        then:
        r.success == true
        hubGet.calls.find { it.path == '/dashboard/create' }.params.localOnly == 'true'
    }

    // ---------- showEdit / showNavigation after an Easy create ----------

    def "an Easy create asking for #label off follows with an update that sets it, and reports it"() {
        given:
        enableWrite()

        when:
        def r = script.toolCreateDashboard([name: 'Wall', deviceIds: ['12'], options: options])

        then:
        r.success == true
        r.id == '777'
        r.offAfterCreate == off
        !r.containsKey('warnings')
        def upd = hubGet.calls.find { it.path == '/dashboard/update' }
        upd.params.id == '777'
        upd.params.name == 'Wall'
        upd.params.showEdit == options.showEdit.toString()
        upd.params.showNavigation == options.showNavigation.toString()
        !upd.params.containsKey('version')

        where:
        label            | options                                    | off
        'showEdit'       | [showEdit: false, showNavigation: true]    | ['showEdit']
        'both'           | [showEdit: false, showNavigation: false]   | ['showEdit', 'showNavigation']
        'showNavigation' | [showEdit: true, showNavigation: false]    | ['showNavigation']
    }

    def "an Easy create with showEdit and showNavigation on sends no follow-up update"() {
        given:
        enableWrite()

        when:
        def r = script.toolCreateDashboard([name: 'Wall', deviceIds: ['12'], options: [showEdit: true, showNavigation: true]])

        then:
        r.success == true
        !r.containsKey('offAfterCreate')
        !hubGet.calls.any { it.path == '/dashboard/update' }
    }

    def "a failed follow-up update after an Easy create still returns the new id, with a warning naming the setting left on (#how)"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/update') { params ->
            if (how == 'throws') throw new IOException('relay dropped')
            '{"success":false,"message":null}'
        }

        when:
        def r = script.toolCreateDashboard([name: 'Wall', deviceIds: ['12'], options: [showEdit: false, showNavigation: true]])

        then:
        r.success == true
        r.id == '777'
        !r.containsKey('offAfterCreate')
        r.warnings.size() == 1
        r.warnings[0].startsWith('The dashboard was created with showEdit on')
        !r.warnings[0].contains('showNavigation')
        r.warnings[0].contains('hub_update_dashboard')

        where:
        how << ['answers success:false', 'throws']
    }

    def "an Easy copy of a dashboard with showEdit off sets it with a follow-up update"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/all') { params -> JsonOutput.toJson([easyRow(412, 'Living Room', [showEdit: false])]) }

        when:
        def r = script.toolCreateDashboard([sourceId: '412'])

        then:
        r.success == true
        r.offAfterCreate == ['showEdit']
        hubGet.calls.find { it.path == '/dashboard/create' }.params.showEdit == 'false'
        hubGet.calls.find { it.path == '/dashboard/update' }.params.showEdit == 'false'
    }

    // ---------- an id that is not a dashboard ----------

    // statusJson for a Rule Machine rule: present, but neither dashboard kind.
    private void registerRuleStatus(String id) {
        hubGet.register("/installedapp/statusJson/${id}".toString()) { params ->
            JsonOutput.toJson([installedApp: [id: id as Integer, name: 'Rule-5.1', systemAppType: true, label: 'Porch rule',
                                              trueLabel: 'Porch rule', installed: true, disabled: false, parentAppId: 5, appTypeId: 300],
                               showTempValues: false, appSettings: [], tempSettings: [], eventSubscriptions: [], scheduledJobs: null,
                               childAppCount: 0, childDeviceCount: 0, childDevices: [], appState: [], tempState: []])
        }
    }

    def "update of an app that is not a dashboard (bypass=#bypass, #kind args) is refused like hub_get_dashboard, before any write"() {
        given:
        enableWrite()
        selectOnly([12])
        settingsMap.bypassDeviceAllowlist = bypass
        registerRuleStatus('414')
        def forms = captureFormPosts()
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '414'] + args)

        then:
        r.success == false
        r.id == '414'
        r.error == "No dashboard found with id '414'."
        r.availableIds == ['412', '500']
        !r.note.toLowerCase().contains('retry')
        r.note.endsWith("See hub_get_tool_guide(section='dashboards').")
        !hubGet.calls.any { it.path == '/dashboard/update' || it.path.startsWith('/installedapp/configure/json') }
        forms.isEmpty()
        posts.isEmpty()

        where:
        [bypass, kind, args] << [[true, false], [['easy', [name: 'Den', deviceIds: ['12']]],
                                                  ['legacy', [name: 'Den', addTiles: [[template: 'clock', col: 1, row: 1]]]]]]
            .combinations().collect { [it[0], it[1][0], it[1][1]] }
    }

    def "delete of an app that is not a dashboard is refused like hub_get_dashboard, before any delete"() {
        given:
        enableWrite()
        registerRuleStatus('414')
        def rawPaths = []
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false -> rawPaths << p; defaultRaw(p) }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '414', confirm: true])

        then:
        r.success == false
        r.id == '414'
        r.error == "No dashboard found with id '414'."
        !r.containsKey('alreadyAbsent')
        !hubGet.calls.any { it.path == '/dashboard/delete' }
        !rawPaths.any { it.contains('forcedelete') }
    }

    def "an app whose status page names neither kind is updated when the dashboard list has it, and refused when that list is unreadable"() {
        given: 'statusJson for 412 carries an unexpected app name'
        enableWrite()
        hubGet.register('/installedapp/statusJson/412') { params ->
            JsonOutput.toJson([installedApp: [id: 412, name: 'Easy Dashboard v2', systemAppType: true, label: 'Living Room'], appSettings: [], appState: []])
        }
        def args = [dashboardId: '412', name: 'Den', deviceIds: ['12'], options: [dashboardPin: '', hsmPin: '', localOnly: false]]

        when:
        def listed = script.toolUpdateDashboard(args)

        then:
        listed.success == true
        hubGet.calls.count { it.path == '/dashboard/update' } == 1

        when:
        hubGet.register('/dashboard/all') { params -> throw new RuntimeException('list down') }
        hubGet.register('/hub2/appsList') { params -> throw new RuntimeException('tree down') }
        def unreadable = script.toolUpdateDashboard(args)

        then:
        unreadable.success == false
        unreadable.error.startsWith('Could not confirm that app 412 is a dashboard')
        hubGet.calls.count { it.path == '/dashboard/update' } == 1
    }

    // ---------- wholesale layout save failures ----------

    def "update legacy: a wholesale layout whose requestToken can't be minted says it was not saved and names LAN access"() {
        given: 'the dashboard page renders without a requestToken; LAN access is off'
        enableWrite()
        registerLegacyStatus('700', [localAccess: false])
        hubGet.register('/apps/api/700/dashboard/700') { params -> '<html></html>' }
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', layout: LEGACY_LAYOUT])

        then:
        r.success == false
        r.error.contains('the layout was not saved')
        r.note.contains("'Allow LAN access' disabled")
        !r.note.contains('may be unchanged')
        r.applied == []
        posts.isEmpty()
    }

    def "update legacy: a rejected #kind save names the whole-layout rule only for a wholesale layout"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        script.metaClass.hubInternalPostJson = { String p, String body, int t = 420, boolean r = false, Map q = null ->
            throw new RuntimeException('status code: 500')
        }

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700'] + args)

        then:
        r.success == false
        r.error.contains('status code: 500')
        r.note.contains('the whole object hub_get_dashboard returns') == wholesale

        where:
        kind        | args                                       | wholesale
        'wholesale' | [layout: [cols: 6, rows: 4, tiles: []]]    | true
        'granular'  | [setOptions: [cols: 5]]                    | false
    }

    // ---------- list rows by kind ----------

    def "a legacy list entry carries no Easy-only tile, theme, navigation, PIN or localOnly fields"() {
        given:
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([easyRow(412, 'Living Room', [dashboardPin: '1111']), legacyRow(800, 'Old Board', [dashboardPin: '9999'])])
        }
        settingsMap.redactCredentials = false

        when:
        def r = script.toolListDashboards([:])

        then:
        def legacy = r.dashboards.find { it.id == '800' }
        legacy.keySet() == ['id', 'name', 'type', 'deviceIds'] as Set
        def easy = r.dashboards.find { it.id == '412' }
        (['showEdit', 'showNavigation', 'theme', 'localOnly', 'navigationSelection', 'dashboardPin'] as Set).every { easy.containsKey(it) }
    }

    // ---------- lost create responses on a copy, and a token-less legacy list row ----------

    def "a #kind copy whose create response is lost reports outcomeUnknown with the sourceId"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([easyRow(412, 'Living Room', [deviceIds: [12]]), legacyRow(700, 'Studio', [deviceIds: [16, 4]])])
        }
        registerLegacyLayout('700', LEGACY_LAYOUT)
        registerLegacyParent(21)
        hubGet.register('/dashboard/create') { params -> throw new IOException('response lost') }
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            if (p.startsWith('/installedapp/createchild')) throw new IOException('response lost')
            defaultRaw(p)
        }

        when:
        def r = script.toolCreateDashboard([sourceId: source])

        then:
        r.success == false
        r.outcomeUnknown == true
        r.sourceId == source
        r.note.contains('hub_list_dashboards')

        where:
        kind     | source
        'Easy'   | '412'
        'legacy' | '700'
    }

    def "get legacy: a list row without an access token reads the token from the status page"() {
        given:
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([legacyRow(700, 'Studio', [deviceIds: [16, 4], accessToken: null])])
        }
        registerLegacyStatus('700', [label: 'Studio'])
        registerLegacyLayout('700', LEGACY_LAYOUT)

        when:
        def r = script.toolGetDashboard([dashboardId: '700'])

        then:
        !r.partial
        r.deviceIds == ['16', '4']
        r.layout.tiles.size() == 2
        hubGet.calls.count { it.path == '/installedapp/statusJson/700' } == 1
        hubGet.calls.find { it.path == '/apps/api/700/dashboard/700/layout' }.params.access_token == 'tok-700'
    }

    // ---------- unreadable vs empty device lists ----------

    // statusJson for legacy 700 with the given appSettings value (absent when the key is omitted).
    private void registerLegacyStatusSettings(Map extra) {
        hubGet.register('/installedapp/statusJson/700') { params ->
            JsonOutput.toJson([installedApp: [id: 700, name: 'Dashboard', systemAppType: true, label: 'Kitchen'],
                               appState: [[name: 'accessToken', value: 'tok-700']]] + extra)
        }
    }

    def "legacy update with the bypass off refuses a drop when the device list is unreadable: #label"() {
        given:
        enableWrite()
        selectOnly([100])
        registerLegacyStatusSettings(extra)
        def forms = captureFormPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', deviceIds: ['100']])

        then:
        r.success == false
        r.error.contains('MCP device selection')
        r.applied == []
        forms.isEmpty()

        where:
        label                              | extra
        'no appSettings key'               | [:]
        'appSettings is not a list'        | [appSettings: [devicesPicked: [100, 600]]]
        'devicesPicked entry is malformed' | [appSettings: [[name: 'devicesPicked', deviceIdsForDeviceList: [bad: 1]]]]
    }

    def "Easy update with the bypass off refuses a drop when devicesPicked is not a map"() {
        given:
        enableWrite()
        selectOnly([100])
        hubGet.register('/installedapp/configure/json/412') { params ->
            new JsonSlurper().parseText(easyConfigJson('Living Room')).with { m -> m.settings.devicesPicked = '600,100'; JsonOutput.toJson(m) }
        }

        when:
        def r = script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ['100']])

        then:
        r.success == false
        r.error.contains('MCP device selection')
        !hubGet.calls.any { it.path == '/dashboard/update' }
    }

    // ---------- showEdit / showNavigation defaults ----------

    def "an omitted showEdit/showNavigation is sent as on by update and by a copy whose source lacks them"() {
        given: 'the copy source comes from the child-app fallback, whose config has neither key'
        enableWrite()
        hubGet.register('/installedapp/configure/json/412') { params -> easyConfigJson('Living Room') }
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]}]}]}'
        }
        hubGet.register('/installedapp/configure/json/38') { params ->
            new JsonSlurper().parseText(easyConfigJson('Dashboard 1', [devicesPicked: ['8': 'Lamp']])).with { m ->
                m.settings.remove('showEdit'); m.settings.remove('showNavigation'); JsonOutput.toJson(m)
            }
        }

        when:
        script.toolUpdateDashboard([dashboardId: '412', name: 'Den', deviceIds: ['12']])
        def copy = script.toolCreateDashboard([sourceId: '38'])

        then:
        def upd = hubGet.calls.find { it.path == '/dashboard/update' }
        upd.params.showEdit == 'true' && upd.params.showNavigation == 'true'
        def create = hubGet.calls.find { it.path == '/dashboard/create' }
        create.params.showEdit == 'true' && create.params.showNavigation == 'true'
        copy.success == true
        !copy.containsKey('offAfterCreate')
        hubGet.calls.count { it.path == '/dashboard/update' } == 1
    }

    // ---------- a requestToken mint that throws ----------

    def "update legacy: a wholesale layout whose requestToken mint throws says it was not saved and names LAN access"() {
        given:
        enableWrite()
        registerLegacyStatus('700', [localAccess: false])
        hubGet.register('/apps/api/700/dashboard/700') { params -> throw new RuntimeException('page render failed') }
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', layout: LEGACY_LAYOUT])

        then:
        r.success == false
        r.error.contains('the layout was not saved')
        r.note.contains("'Allow LAN access' disabled")
        !r.note.contains('may be unchanged')
        posts.isEmpty()
    }

    def "update legacy: a wholesale layout save with no echo names the whole-layout rule and LAN access"() {
        given:
        enableWrite()
        registerLegacyStatus('700', [localAccess: false])
        registerLegacyMint('700')
        script.metaClass.hubInternalPostJson = { String p, String body, int t = 420, boolean r = false, Map q = null -> [error: true] }

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', layout: [cols: 6, rows: 4, tiles: []]])

        then:
        r.success == false
        r.error.contains('outcome unconfirmed')
        r.note.contains('the whole object hub_get_dashboard returns')
        r.note.contains("'Allow LAN access' disabled")
    }

}
