package server

import support.ToolSpecBase

/**
 * Spec for the Easy Dashboard CRUD surface added in issue #259 item #9 (implemented in the
 * McpDashboardsLib library):
 * hub_list_dashboards / hub_get_dashboard / hub_create_dashboard / hub_update_dashboard /
 * hub_delete_dashboard / hub_clone_dashboard. The GET /dashboard/* endpoints are stubbed via
 * hubGet.register (the mock keys on path; the query Map is recorded in hubGet.calls, not
 * matched). Each tool gets a direct-call unit test AND a dispatch-envelope test; the gateway
 * tools are also routed THROUGH hub_manage_dashboards / hub_read_dashboards.
 *
 * enableWrite() sets the Write master + a recent backup so the destructive-confirm gate on
 * hub_delete_dashboard passes; the read/create/update/clone tools only need the Write master
 * (create/update/clone) or nothing (reads).
 */
class ToolDashboardSpec extends ToolSpecBase {

    private static final String LIST_JSON =
        '[{"installedAppId":412,"name":"Living Room","showModeTile":true,"showClockTile":false,' +
        '"showCalendarTile":false,"showHSMTile":false,"showEdit":true,"showNavigation":true,' +
        '"showTutorial":false,"navigationSelection":"","theme":"dark","deviceIds":"[12,34]"},' +
        '{"installedAppId":500,"name":"Garage","theme":"legacy","deviceIds":"[99]"}]'

    def setup() {
        hubGet.register('/dashboard/all') { params -> LIST_JSON }
        hubGet.register('/dashboard/create') { params -> '{"success":true,"installedAppId":777,"name":"New Dash"}' }
        hubGet.register('/dashboard/update') { params -> '{"success":true,"installedAppId":412,"name":"Living Room"}' }
        hubGet.register('/dashboard/delete') { params -> '{"success":true,"message":"deleted"}' }
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
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
        def r = script.toolGetDashboard([id: '500'])

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
        def r = script.toolGetDashboard([id: '9999'])

        then:
        r.success == false
        r.availableIds.containsAll(['412', '500'])
    }

    def "get rejects a non-numeric id (the URL-splice guard) with a validation error"() {
        when:
        script.toolGetDashboard([id: 'abc; rm'])

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
        def r = script.toolGetDashboard([id: '38'])

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
        def r = script.toolGetDashboard([id: '38'])

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
        def got = script.toolGetDashboard([id: '412'])

        then: 'pins are surfaced and nav is normalized to a CSV the update endpoint accepts'
        got.dashboardPin == '1111'
        got.hsmPin == '2222'
        got.navigationSelection == '5,6'

        when: 'round-trip: pass the get output (flattened) back through update'
        script.toolUpdateDashboard([id: got.id, name: got.name, deviceIds: '12,34',
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
        def got = script.toolGetDashboard([id: '38'])

        then: 'the bracketed hub string is normalized to CSV -- NOT returned verbatim as "[1,2]" (which the hub misparses to index 0)'
        got.navigationSelection == '1,2'

        when: 'round-trip: feed the get output back through update'
        script.toolUpdateDashboard([id: got.id, name: got.name, deviceIds: ['14'], navigationSelection: got.navigationSelection])

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
        def r = script.toolUpdateDashboard([id: '412', name: 'Living Room', deviceIds: ['12', '34'], options: [theme: 'dark']])

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
        script.toolUpdateDashboard([id: '412', deviceIds: ['12']])

        then:
        thrown(IllegalArgumentException)
    }

    def "update with empty deviceIds throws (won't silently blank the dashboard's devices)"() {
        given:
        enableWrite()

        when:
        script.toolUpdateDashboard([id: '412', name: 'Living Room', deviceIds: []])

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
        script.toolUpdateDashboard([id: '412', name: 'Living Room', deviceIds: ['12', '34'], options: [theme: 'dark']])

        then: 'the current PIN is read back and re-sent, so it is not blanked'
        def call = hubGet.calls.find { it.path == '/dashboard/update' }
        call.params.dashboardPin == '4242'
    }

    def "update flags pinPreserveFailed when it can't read the config to preserve an omitted PIN"() {
        given: 'the caller omits the PIN and the preservation read fails (/installedapp/configure/json/412 unmocked -> throws)'
        enableWrite()

        when:
        def r = script.toolUpdateDashboard([id: '412', name: 'Living Room', deviceIds: ['12', '34'], options: [theme: 'dark']])

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
        script.toolUpdateDashboard([id: '412', name: 'Living Room', deviceIds: ['12', '34'], options: [theme: 'dark']])

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
        script.toolUpdateDashboard([id: '412', name: 'Living Room', deviceIds: ['12', '34'], dashboardPin: '9999'])

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
        def r = script.toolDeleteDashboard([id: '412', confirm: true])

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
        script.toolDeleteDashboard([id: '412'])

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
        def r = script.toolDeleteDashboard([id: '412', confirm: true])

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
        def r = script.toolDeleteDashboard([id: '412', confirm: true])

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
        def r = script.toolDeleteDashboard([id: '412', confirm: true])

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
        def r = script.toolCloneDashboard([id: '412'])

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
        script.toolCloneDashboard([id: '412'])

        then: 'the source 412 config (theme dark, showModeTile true) flows into /dashboard/create, not just name+devices'
        def createCall = hubGet.calls.find { it.path == '/dashboard/create' }
        createCall.params.theme == 'dark'
        createCall.params.showModeTile == 'true'
    }

    def "clone returns a structured failure (not a caller-blaming -32602) when the source can't be read"() {
        given: 'a source id that is not listable -> get returns success:false, so there is no device list to clone'
        enableWrite()

        when:
        def r = script.toolCloneDashboard([id: '9999'])

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
        def r = script.toolCloneDashboard([id: '38'])

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
        def resp = mcpDriver.callTool('hub_get_dashboard', [id: '412'])

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
        def resp = mcpDriver.callTool('hub_update_dashboard', [id: '412', name: 'Living Room', deviceIds: ['12', '34']])

        then:
        mcpDriver.parseInner(resp).success == true
    }

    def "hub_delete_dashboard via dispatch returns the success envelope"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_delete_dashboard', [id: '412', confirm: true])

        then:
        mcpDriver.parseInner(resp).success == true
    }

    def "hub_clone_dashboard via dispatch returns the success envelope"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_clone_dashboard', [id: '412'])

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
        def r = script.handleGateway('hub_manage_dashboards', 'hub_delete_dashboard', [id: '412', confirm: true])

        then:
        r.success == true
    }
}
