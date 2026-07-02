package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import support.ToolSpecBase

/**
 * Spec for the dashboard CRUD surface (implemented in the McpDashboardsLib library):
 * hub_list_dashboards / hub_get_dashboard / hub_create_dashboard / hub_update_dashboard /
 * hub_delete_dashboard / hub_clone_dashboard. Covers BOTH the Easy Dashboards added in issue
 * #259 item #9 AND the legacy "Hubitat® Dashboard" support (this issue). The GET /dashboard/*
 * and /apps/api/* endpoints are stubbed via hubGet.register (the mock keys on path; the query
 * Map is recorded in hubGet.calls, not matched); the write helpers hubInternalGetRaw /
 * hubInternalPostForm / hubInternalPostJson are per-test metaClass stubs (they aren't part of
 * AppExecutor). Each tool gets a direct-call unit test AND a dispatch-envelope test; the
 * gateway tools are also routed THROUGH hub_manage_dashboards / hub_read_dashboards.
 *
 * enableWrite() sets the Write master + a recent backup so the destructive-confirm gate on
 * hub_delete_dashboard passes; the read/create/update/clone tools only need the Write master
 * (create/update/clone) or nothing (reads).
 *
 * NOTE on the legacy probe: update AND delete now call _legacyDashboardProbe(id) first
 * (GET /installedapp/statusJson/<id>). For update a null probe (unreadable status) fails safe
 * WITHOUT firing the Easy /dashboard/update, so every Easy update test needs a statusJson stub
 * answering a NON-legacy installed app -> the probe returns [legacy:false] and the Easy path
 * runs. Those defaults for the Easy fixture ids (412, 38) are seeded in setup() below.
 */
class ToolDashboardSpec extends ToolSpecBase {

    private static final String LIST_JSON =
        '[{"installedAppId":412,"name":"Living Room","showModeTile":true,"showClockTile":false,' +
        '"showCalendarTile":false,"showHSMTile":false,"showEdit":true,"showNavigation":true,' +
        '"showTutorial":false,"navigationSelection":"","theme":"dark","deviceIds":"[12,34]"},' +
        '{"installedAppId":500,"name":"Garage","theme":"legacy","deviceIds":"[99]"}]'

    // A legacy dashboard's design (tiles + grid + colors) as its layout endpoint returns it.
    // Serialized fresh on every stub call, so tests never mutate the shared fixture.
    private static final Map LEGACY_LAYOUT = [
        name: 'Kitchen', cols: 6, rows: 4, bgColor: '#000000',
        tiles: [
            [id: 0, template: 'clock', col: 1, row: 1, colSpan: 1, rowSpan: 1],
            [id: 1, template: 'attribute', device: '16', col: 2, row: 1, colSpan: 1, rowSpan: 1]
        ]
    ]

    def setup() {
        hubGet.register('/dashboard/all') { params -> LIST_JSON }
        hubGet.register('/dashboard/create') { params -> '{"success":true,"installedAppId":777,"name":"New Dash"}' }
        hubGet.register('/dashboard/update') { params -> '{"success":true,"installedAppId":412,"name":"Living Room"}' }
        hubGet.register('/dashboard/delete') { params -> '{"success":true,"message":"deleted"}' }
        // Easy update/delete now probe /installedapp/statusJson/<id> for legacy detection. Answer the
        // Easy fixture ids (412, 38) as NON-legacy so the probe returns [legacy:false] and the Easy
        // path runs (a null probe would early-return a safe error from update). Legacy tests register
        // their own statusJson per id (register() overwrites, so those still win where they collide).
        def easyStatus = JsonOutput.toJson([installedApp: [name: 'Easy Dashboard', systemAppType: false]])
        hubGet.register('/installedapp/statusJson/412') { params -> easyStatus }
        hubGet.register('/installedapp/statusJson/38') { params -> easyStatus }
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    // ---------- legacy Hubitat® Dashboard stub helpers ----------

    // Register /installedapp/statusJson/<id> so _legacyDashboardProbe classifies <id> as a legacy
    // dashboard. opts: accessToken (String, or key present + null to OMIT the token), deviceIds
    // (a JSON-array string like "[16, 4]"), label, localAccess (bool).
    private void registerLegacyStatus(String id, Map opts = [:]) {
        String token = opts.containsKey('accessToken') ? opts.accessToken : ('tok-' + id)
        def appState = token ? [[name: 'accessToken', value: token]] : []
        def deviceIds = opts.containsKey('deviceIds') ? opts.deviceIds : '[16, 4]'
        def appSettings = [[name: 'devicesPicked', deviceIdsForDeviceList: deviceIds],
                           [name: 'localAccess', value: (opts.containsKey('localAccess') ? opts.localAccess : true).toString()]]
        def json = JsonOutput.toJson([installedApp: [name: 'Dashboard', systemAppType: true, label: (opts.label ?: 'Kitchen')],
                                      appState: appState, appSettings: appSettings])
        hubGet.register("/installedapp/statusJson/${id}".toString()) { params -> json }
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

    // /hub2/appsList carrying the legacy "Hubitat® Dashboard" parent at `parentId` (create/clone need it).
    private void registerLegacyParent(int parentId = 21) {
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: parentId, name: 'HD', type: 'Hubitat® Dashboard'], children: []]]])
        }
    }

    // Stub createchild (hubInternalGetRaw) to mint `newId` from the 302 Location; benign struct for
    // other raw GETs (e.g. the /dashboard/select pinToken scrape). Returns the list of raw GET paths.
    private List stubCreateChild(String newId) {
        def rawPaths = []
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            rawPaths << p
            if (p.startsWith('/installedapp/createchild')) {
                return [status: 302, location: "/installedapp/configure/${newId}".toString(), data: '']
            }
            return [status: 200, location: null, data: '']
        }
        return rawPaths
    }

    // Stub forcedelete (hubInternalGetRaw). throwOnDelete simulates the endpoint answering 500/dropping
    // (a committed-but-error shape the delete path must tolerate + verify by effect).
    private void stubForceDelete(boolean throwOnDelete = false) {
        script.metaClass.hubInternalGetRaw = { String p, Map q = null, int t = 30, boolean r = false ->
            if (p.contains('/installedapp/forcedelete/')) {
                if (throwOnDelete) throw new RuntimeException('forcedelete answered 500')
                return [status: 302, location: '/installedapps', data: '']
            }
            return [status: 200, location: null, data: '']
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

    // Capture legacy layout saves (POST JSON). The stub echoes the parsed body back -- the library's
    // write-confirmation shape (a Map with a tiles List). Note the 5-arg signature: the layout POST
    // passes the [access_token, requestToken] query as the trailing param. Returns the capture list.
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

    def "list passes pinToken through to /dashboard/all when supplied"() {
        when:
        script.toolListDashboards([pinToken: 'tok-123'])

        then:
        def call = hubGet.calls.find { it.path == '/dashboard/all' }
        call.params.pinToken == 'tok-123'
    }

    def "list omits pinToken when none is supplied and the page token can't be scraped"() {
        // No pinToken arg: the tool scrapes globalDashboardPinToken from /dashboard/select via
        // hubInternalGetRaw, which the harness doesn't mock -> it degrades to null, so no pinToken
        // param is sent. (The scrape-succeeds path is covered below.)
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

        then: 'a CONFIRMED-empty hub reports count:0 and suggests a pinToken'
        r.count == 0
        r.dashboards == []
        r.note.toLowerCase().contains('pintoken')
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

    // ---------- hub_get_dashboard ----------

    def "get filters the list by id"() {
        when:
        def r = script.toolGetDashboard([dashboardId: '500'])

        then:
        r.id == '500'
        r.name == 'Garage'
    }

    def "get without an id throws"() {
        when:
        script.toolGetDashboard([:])

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
            '{"app":{"label":"Dashboard 1"},"settings":{"showClockTile":"true","showModeTile":"false",' +
            '"devicesPicked":{"8":"Lamp","1":"Fan"},"dashboardPin":"","hsmPin":"null"}}'
        }

        when:
        def r = script.toolGetDashboard([dashboardId: '38'])

        then: 'string tile toggles coerce to booleans, devicesPicked keys become deviceIds, unset pins are omitted'
        r.id == '38'
        r.showClockTile == true
        r.showModeTile == false
        r.deviceIds.sort() == ['1', '8']
        !r.containsKey('dashboardPin')   // "" -> omitted
        !r.containsKey('hsmPin')         // "null" -> omitted
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
        given: 'a hub list entry that carries pins and an ARRAY navigationSelection'
        hubGet.register('/dashboard/all') { params ->
            '[{"installedAppId":412,"name":"Living Room","theme":"dark","deviceIds":"12,34",' +
            '"dashboardPin":"1111","hsmPin":"2222","navigationSelection":["5","6"]}]'
        }
        enableWrite()

        when: 'read the dashboard, then feed the read shape straight back into update'
        def got = script.toolGetDashboard([dashboardId: '412'])

        then: 'pins are surfaced and nav is normalized to a CSV the update endpoint accepts'
        got.dashboardPin == '1111'
        got.hsmPin == '2222'
        got.navigationSelection == '5,6'

        when: 'round-trip: pass the get output (flattened) back through update'
        script.toolUpdateDashboard([dashboardId: got.id, name: got.name, deviceIds: '12,34',
                                    dashboardPin: got.dashboardPin, hsmPin: got.hsmPin,
                                    navigationSelection: got.navigationSelection])

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
            '{"app":{"label":"Nav Dash"},"settings":{"showNavigation":"true","navigationSelection":"[1,2]",' +
            '"devicesPicked":{"14":"Lamp"}}}'
        }
        enableWrite()

        when: 'read the dashboard'
        def got = script.toolGetDashboard([dashboardId: '38'])

        then: 'the bracketed hub string is normalized to CSV -- NOT returned verbatim as "[1,2]" (which the hub misparses to index 0)'
        got.navigationSelection == '1,2'

        when: 'round-trip: feed the get output back through update'
        script.toolUpdateDashboard([dashboardId: got.id, name: got.name, deviceIds: ['14'], navigationSelection: got.navigationSelection])

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

        then:
        r.success == true
        def call = hubGet.calls.find { it.path == '/dashboard/create' }
        call.params.name == 'New Dash'
        call.params.deviceIds == '12,34'
        call.params.showModeTile == 'true'
        call.params.showClockTile == 'false'
    }

    def "create also accepts the tile toggles flattened at the top level (back-compat)"() {
        given:
        enableWrite()

        when: 'config supplied flat (not under options) -- the fallback path'
        script.toolCreateDashboard([name: 'New Dash', deviceIds: ['12'], showModeTile: true, theme: 'dark'])

        then:
        def call = hubGet.calls.find { it.path == '/dashboard/create' }
        call.params.showModeTile == 'true'
        call.params.theme == 'dark'
    }

    def "create defaults theme to legacy and omitted booleans to false"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([name: 'New Dash', deviceIds: ['12']])

        then:
        def call = hubGet.calls.find { it.path == '/dashboard/create' }
        call.params.theme == 'legacy'
        call.params.showHSMTile == 'false'
        call.params.showNavigation == 'false'
        call.params.navigationSelection == ''
        call.params.dashboardPin == ''
        call.params.hsmPin == ''
    }

    def "create serializes navigationSelection (dashboard ids) as CSV"() {
        given:
        enableWrite()

        when:
        script.toolCreateDashboard([name: 'New Dash', deviceIds: ['12'], navigationSelection: ['5', '6']])

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

    def "create reports success:false (not success) when the hub rejects but echoes the record"() {
        given:
        enableWrite()
        // A rejected write that still echoes installedAppId must NOT be inferred as success.
        hubGet.register('/dashboard/create') { params -> '{"success":false,"installedAppId":777,"message":"name in use"}' }

        when:
        def r = script.toolCreateDashboard([name: 'Dup', deviceIds: ['12']])

        then:
        r.success == false
        r.error.contains('name in use')
    }

    def "create returns the chainable id when the hub echoes a bare id (no installedAppId)"() {
        given:
        enableWrite()
        // success absent + a bare `id` (not installedAppId) -> infer success and surface id (Codex P2).
        hubGet.register('/dashboard/create') { params -> '{"id":777,"name":"New Dash"}' }

        when:
        def r = script.toolCreateDashboard([name: 'New Dash', deviceIds: ['12']])

        then:
        r.success == true
        r.id == '777'
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

    def "update preserves an existing PIN the caller didn't pass (wholesale replace must not clear it)"() {
        given: 'the dashboard currently has a dashboardPin, and the caller updates WITHOUT supplying one'
        enableWrite()
        hubGet.register('/installedapp/configure/json/412') { params ->
            '{"app":{"label":"Living Room"},"settings":{"dashboardPin":"4242","hsmPin":"null"}}'
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
        given: 'the config has a real hsmPin (not the "null" sentinel) and the caller updates without supplying one'
        enableWrite()
        hubGet.register('/installedapp/configure/json/412') { params ->
            '{"app":{"label":"Living Room"},"settings":{"dashboardPin":"","hsmPin":"7777"}}'
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
            '{"app":{"label":"Living Room"},"settings":{"dashboardPin":"4242","hsmPin":"7777"}}'
        }

        when:
        script.toolUpdateDashboard([dashboardId: '412', name: 'Living Room', deviceIds: ['12', '34'], dashboardPin: '9999'])

        then: 'the new dashboardPin is sent and the omitted hsmPin is preserved (not cleared)'
        def call = hubGet.calls.find { it.path == '/dashboard/update' }
        call.params.dashboardPin == '9999'
        call.params.hsmPin == '7777'
    }

    // ---------- hub_delete_dashboard ----------

    def "delete removes by id (confirm-gated)"() {
        given:
        enableWrite()

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then:
        r.success == true
        r.id == '412'
        def call = hubGet.calls.find { it.path == '/dashboard/delete' }
        call.params.id == '412'
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

    def "a failing delete returns the structured error envelope (does NOT throw)"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/delete') { params -> '{"success":false,"message":"not found"}' }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then:
        r.success == false
        r.error.contains('not found')
        r.note != null
    }

    def "delete succeeds by EFFECT when /dashboard/delete lies with success:false but the dashboard is gone"() {
        given: 'the real-hub shape: delete returns success:false even though it deleted; child-app check shows 412 absent'
        enableWrite()
        hubGet.register('/dashboard/delete') { params -> '{"success":false,"message":null}' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":99,"name":"Other","type":"Easy Dashboard"},"children":[]}]}]}'
        }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then: 'verify-by-effect: 412 is no longer present, so the delete is reported as success'
        r.success == true
        r.id == '412'
    }

    def "delete does NOT false-claim success when removal cannot be confirmed (both reads fail)"() {
        given: 'delete lies with success:false AND both presence reads fail (/hub2/appsList unmocked + /dashboard/all throws)'
        enableWrite()
        hubGet.register('/dashboard/delete') { params -> '{"success":false,"message":null}' }
        hubGet.register('/dashboard/all') { params -> throw new RuntimeException('endpoint down') }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '412', confirm: true])

        then: 'a destructive op is NOT reported as success without confirming evidence'
        r.success == false
        r.error.toLowerCase().contains('could not be confirmed')
        r.note != null
    }

    // ---------- hub_clone_dashboard ----------

    def "clone copies the source config into a new dashboard (clone-by-value, not the session-bound cloneAsEasy)"() {
        given:
        enableWrite()

        when:
        def r = script.toolCloneDashboard([dashboardId: '412'])

        then: 'reads the source (412) via the list, then creates a copy via /dashboard/create'
        r.success == true
        r.sourceId == '412'
        r.newId == '777'
        def createCall = hubGet.calls.find { it.path == '/dashboard/create' }
        createCall.params.name == 'Living Room (copy)'
        createCall.params.deviceIds == '12,34'

        and: 'it does NOT call the session-bound cloneAsEasy endpoint (which fails from the server)'
        !hubGet.calls.any { it.path.startsWith('/dashboard/cloneAsEasy') }
    }

    def "clone copies the source's full config (theme + tiles) by value into the create call"() {
        given:
        enableWrite()

        when:
        script.toolCloneDashboard([dashboardId: '412'])

        then: 'the source 412 config (theme dark, showModeTile true) flows into /dashboard/create, not just name+devices'
        def createCall = hubGet.calls.find { it.path == '/dashboard/create' }
        createCall.params.theme == 'dark'
        createCall.params.showModeTile == 'true'
    }

    def "clone returns a structured failure (not a caller-blaming -32602) when the source can't be read"() {
        given: 'a source id that is not listable -> get returns success:false, so there is no device list to clone'
        enableWrite()

        when:
        def r = script.toolCloneDashboard([dashboardId: '9999'])

        then: 'clone surfaces a runtime failure rather than letting create throw IllegalArgumentException'
        r.success == false
        r.sourceId == '9999'
        r.error.toLowerCase().contains('source')
    }

    def "clone surfaces a structured failure when the source reads PARTIAL (id+name, no device list)"() {
        given: 'a child-app-sourced source whose enrichment fails -> get returns {id,name,partial:true} with NO deviceIds (src.success is not false, so the other guard is skipped)'
        enableWrite()
        hubGet.register('/dashboard/all') { params -> '[]' }
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":19,"name":"Easy Dashboards","type":"Easy Dashboard Parent"},' +
            '"children":[{"data":{"id":38,"name":"Dashboard 1","type":"Easy Dashboard"},"children":[]}]}]}'
        }
        // /installedapp/configure/json/38 unmocked -> enrichment fails -> the get match is partial (no deviceIds)

        when:
        def r = script.toolCloneDashboard([dashboardId: '38'])

        then: 'clone returns a runtime failure naming the device list -- NOT a caller-blaming -32602 thrown by create'
        r.success == false
        r.sourceId == '38'
        r.error.toLowerCase().contains('device list')
    }

    def "clone without id throws"() {
        given:
        enableWrite()

        when:
        script.toolCloneDashboard([:])

        then:
        thrown(IllegalArgumentException)
    }

    // ---------- dispatch envelopes (via mcpDriver.callTool) ----------

    def "hub_list_dashboards via dispatch returns the success envelope"() {
        when:
        def resp = mcpDriver.callTool('hub_list_dashboards', [:])

        then:
        mcpDriver.parseInner(resp).count == 2
    }

    def "hub_get_dashboard via dispatch returns the dashboard"() {
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

    def "hub_clone_dashboard via dispatch returns the success envelope"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_clone_dashboard', [dashboardId: '412'])

        then:
        mcpDriver.parseInner(resp).success == true
    }

    def "hub_create_dashboard via dispatch surfaces a validation-error envelope for a missing name"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_create_dashboard', [deviceIds: ['12']])

        then: 'an IllegalArgumentException maps to a -32602 (or isError) envelope, not a success'
        resp.error?.code == -32602 || resp.result?.isError == true
    }

    // ---------- through the gateways (membership + routing) ----------

    def "the hub_manage_dashboards gateway catalog lists every dashboard sub-tool (membership)"() {
        when:
        def cat = script.handleGateway('hub_manage_dashboards', null, null)

        then:
        cat.tools*.name.containsAll([
            'hub_list_dashboards', 'hub_get_dashboard', 'hub_create_dashboard',
            'hub_update_dashboard', 'hub_delete_dashboard', 'hub_clone_dashboard'])
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

    def "list tags /dashboard/all records by version: 1.x legacy, 2.x/absent easy"() {
        given:
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([
                [installedAppId: 412, name: 'Living Room', version: '2.0', deviceIds: '[12]'],
                [installedAppId: 800, name: 'Old Board', version: '1.0', deviceIds: '[9]'],
                [installedAppId: 900, name: 'Echo', deviceIds: '[7]']   // no version -> easy (create/update echo shape)
            ])
        }

        when:
        def r = script.toolListDashboards([:])

        then:
        r.count == 3
        r.dashboards.find { it.id == '800' }.type == 'legacy'
        r.dashboards.find { it.id == '412' }.type == 'easy'
        r.dashboards.find { it.id == '900' }.type == 'easy'
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

    def "get legacy: returns the nested layout + authorized deviceIds"() {
        given:
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([[installedAppId: 700, name: 'Studio', version: '1.0', deviceIds: '[16,4]']])
        }
        registerLegacyStatus('700', [label: 'Studio', deviceIds: '[16, 4]'])
        registerLegacyLayout('700', LEGACY_LAYOUT)

        when:
        def r = script.toolGetDashboard([dashboardId: '700'])

        then:
        r.id == '700'
        r.type == 'legacy'
        r.name == 'Studio'                    // from the app label in statusJson
        r.deviceIds == ['16', '4']
        r.layout.cols == 6
        r.layout.tiles.size() == 2
        !r.partial
    }

    def "get legacy: flags partial when the status page is unreadable (layout unavailable, not empty)"() {
        given: 'the list classifies 700 as legacy (v1.0), but statusJson/700 is unreadable (unregistered -> throws)'
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([[installedAppId: 700, name: 'Studio', version: '1.0', deviceIds: '[16,4]']])
        }

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
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([[installedAppId: 700, name: 'Studio', version: '1.0', deviceIds: '[16,4]']])
        }
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
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([[installedAppId: 700, name: 'Studio', version: '1.0', deviceIds: '[16,4]']])
        }
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
        r.applied == ['addTiles, updateTiles, removeTileIds']
        posts.size() == 1
    }

    def "update legacy: setOptions merges top-level layout fields and leaves tiles untouched"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        registerLegacyLayout('700', LEGACY_LAYOUT)
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', setOptions: [cols: 10, bgColor: '#123456']])

        then:
        r.success == true
        posts[0].body.cols == 10
        posts[0].body.bgColor == '#123456'
        posts[0].body.tiles*.id == [0, 1]
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

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', addTiles: [[template: 'clock', col: 1, row: 1]]])

        then:
        r.success == false
        r.error.toLowerCase().contains('access token')
        r.note != null
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
        script.metaClass.hubInternalPostForm = { String p, Map b, int t = 420, boolean r = false ->
            [status: 500, location: null, data: '']
        }
        def posts = captureLayoutPosts()

        when:
        def r = script.toolUpdateDashboard([dashboardId: '700', deviceIds: ['5'], setOptions: [cols: 5]])

        then:
        r.success == false
        r.error.toLowerCase().contains('device authorization')
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
        hubGet.register('/dashboard/delete') { params -> deleteFired[0] = true; '{"success":true}' }
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

    def "clone legacy: a layout save whose echo is empty reports the copy's layout unconfirmed"() {
        given: 'the copy is created and has a token, but the layout POST echoes nothing (dropped body)'
        enableWrite()
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([[installedAppId: 700, name: 'Studio', version: '1.0', deviceIds: '[16,4]']])
        }
        registerLegacyStatus('700', [label: 'Studio', deviceIds: '[16, 4]'])
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
        def r = script.toolCloneDashboard([dashboardId: '700'])

        then:
        r.success == false
        r.newId == '882'
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
        given: 'forcedelete fires but both presence sources are unreadable -> tri-state null'
        enableWrite()
        registerLegacyStatus('700')
        stubForceDelete(false)
        hubGet.register('/hub2/appsList') { params -> throw new RuntimeException('appsList down') }
        hubGet.register('/dashboard/all') { params -> throw new RuntimeException('dashboard/all down') }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '700', confirm: true])

        then: 'a destructive op never claims success it cannot verify'
        r.success == false
        r.error.toLowerCase().contains('could not be confirmed')
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

    def "delete legacy: force-deletes then confirms removal by absence"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        stubForceDelete(false)
        hubGet.register('/hub2/appsList') { params ->   // 700 no longer present -> confirmed absent
            JsonOutput.toJson([apps: [[data: [id: 21, name: 'HD', type: 'Hubitat® Dashboard'], children: []]]])
        }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '700', confirm: true])

        then:
        r.success == true
        r.type == 'legacy'
        r.id == '700'
    }

    def "delete legacy: a forcedelete that throws still confirms deletion by effect"() {
        given: 'forcedelete answers 500/drops (committed-but-error), but the child list shows 700 gone'
        enableWrite()
        registerLegacyStatus('700')
        stubForceDelete(true)
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: 21, name: 'HD', type: 'Hubitat® Dashboard'], children: []]]])
        }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '700', confirm: true])

        then:
        r.success == true
        r.id == '700'
    }

    def "delete legacy: reports success:false when the dashboard is still present after force-delete"() {
        given:
        enableWrite()
        registerLegacyStatus('700')
        stubForceDelete(false)
        hubGet.register('/hub2/appsList') { params ->   // 700 STILL present
            JsonOutput.toJson([apps: [[data: [id: 21, name: 'HD', type: 'Hubitat® Dashboard'],
                children: [[data: [id: 700, name: 'Kitchen', type: 'Dashboard'], children: []]]]]])
        }

        when:
        def r = script.toolDeleteDashboard([dashboardId: '700', confirm: true])

        then:
        r.success == false
        r.type == 'legacy'
        r.error.toLowerCase().contains('still present')
    }

    // ---------- clone legacy ----------

    def "clone legacy: copies the source layout into a fresh legacy dashboard"() {
        given:
        enableWrite()
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([[installedAppId: 700, name: 'Studio', version: '1.0', deviceIds: '[16,4]']])
        }
        registerLegacyStatus('700', [label: 'Studio', deviceIds: '[16, 4]'])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        registerLegacyParent(21)
        stubCreateChild('880')
        captureFormPosts()
        // the copy probes legacy:true with its own token + mint page so the source layout can be saved into it
        registerLegacyStatus('880', [accessToken: 'tok-880', label: 'Studio (copy)'])
        registerLegacyMint('880')
        def posts = captureLayoutPosts()

        when:
        def r = script.toolCloneDashboard([dashboardId: '700'])

        then:
        r.success == true
        r.sourceId == '700'
        r.newId == '880'
        r.type == 'legacy'
        r.message.toLowerCase().contains('cloned')

        and: "the source's layout was posted to the new dashboard, authorized by the copy's own token"
        posts.size() == 1
        posts[0].body.tiles.size() == 2
        posts[0].query.access_token == 'tok-880'
    }

    def "clone legacy: surfaces a structured failure when the copy's layout can't be written"() {
        given: "the copy is created but probes WITHOUT an access token -> its layout endpoint is unreachable"
        enableWrite()
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([[installedAppId: 700, name: 'Studio', version: '1.0', deviceIds: '[16,4]']])
        }
        registerLegacyStatus('700', [label: 'Studio', deviceIds: '[16, 4]'])
        registerLegacyLayout('700', LEGACY_LAYOUT)
        registerLegacyParent(21)
        stubCreateChild('881')
        captureFormPosts()
        registerLegacyStatus('881', [accessToken: null, label: 'Studio (copy)'])

        when:
        def r = script.toolCloneDashboard([dashboardId: '700'])

        then:
        r.success == false
        r.sourceId == '700'
        r.newId == '881'                            // the copy WAS created; only the layout write failed
        r.type == 'legacy'
        r.error.toLowerCase().contains('layout could not be written')
        r.note != null
    }

    // ---------- legacy through the gateway / dispatch envelope ----------

    def "a legacy get routes THROUGH the hub_manage_dashboards gateway"() {
        given:
        hubGet.register('/dashboard/all') { params ->
            JsonOutput.toJson([[installedAppId: 700, name: 'Studio', version: '1.0', deviceIds: '[16,4]']])
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
}
