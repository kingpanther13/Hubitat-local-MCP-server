package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolListAppPages (hubitat-mcp-server.groovy).
 * Gateway tool under hub_read_apps_code -- executeTool() dispatches via case "hub_list_app_pages".
 *
 * Covers:
 *  - Read master gate (throws when disabled)
 *  - Missing appId validation (throws before HTTP)
 *  - Blank appId validation (throws before HTTP)
 *  - Non-numeric appId validation (throws before HTTP)
 *  - Golden path HPM: pages list contains all curated HPM pages
 *  - Golden path RM rule: the sub-pages its mainPage links to (selectTriggers/selectActions)
 *  - Room Lighting: the live sub-page links replace the old hard-coded "single mainPage" note
 *  - Unknown app type / Mode Manager: no links -> primary page only, "links no sub-pages" note
 *  - Duplicate links and links back to the primary page are listed once / not at all
 *  - Empty hub response: returns success=false
 *  - Unknown appId (hub returns {app:null}): 'missing app' fingerprint, success=false
 *  - Not-found 404/410 throw (deleted / mid-delete / install shell whose config page
 *    can't render): clean 'app not found (404)' degrade at warn, not an opaque error
 *
 * Each direct-call feature has a parallel "via dispatch" feature that fires
 * the same tool through {@code mcpDriver.callTool} so the production
 * envelope path (JSON-RPC parse → tools/call → executeTool routing → error
 * mapping → response wrapping) is covered alongside the unit-level tool
 * internals. Dispatch features are @Unroll'd across useGateways true/false.
 */
class ToolListAppPagesSpec extends ToolSpecBase {

    // An exception that carries an HTTP status the way HttpResponseException does
    // (duck-typed via .response.status). hubInternalGet's mock responder can throw
    // this to drive the catch-block status-detection path. Mirrors the shape used in
    // HubInternalRetrySpec.FakeHttpException.
    private static class FakeHttpException extends RuntimeException {
        final def response
        FakeHttpException(int status) {
            super("HTTP ${status}")
            this.response = [status: status]
        }
    }

    // Helper: build a minimal hub response for /installedapp/configure/json/<id>
    // `hrefs` = [[page, title], ...] rendered as body href elements, the only place the hub puts page links.
    private static String makeAppJson(String appTypeName, String primaryPageName = 'mainPage', String pageTitle = 'Settings', List hrefs = []) {
        def data = [
            app: [
                id       : 35,
                trueLabel: appTypeName + ' Instance',
                label    : appTypeName + ' Instance',
                name     : appTypeName,
                disabled : false,
                installed: true,
                parentAppId: null,
                appType: [
                    name     : appTypeName,
                    namespace: 'hubitat',
                    author   : 'Hubitat',
                    category : 'Utility',
                    classLocation: 'builtin',
                    deprecated: false,
                    system   : true,
                    documentationLink: null
                ]
            ],
            configPage: [
                name   : primaryPageName,
                title  : pageTitle,
                install: true,
                refreshInterval: null,
                sections: hrefs ? [[input: [], body: hrefs.collect { [element: 'href', page: it[0], title: it[1]] }]] : []
            ],
            settings : [:],
            childApps: []
        ]
        return JsonOutput.toJson(data)
    }

    // Live link sets (titles as the hub renders them, HTML included), fw 2.5.1.181.
    private static final List RM_HREFS = [['selectTriggers', 'Select Trigger Events'], ['selectActions', 'Select <b>Actions</b> to Run']]
    private static final List RL_HREFS = [['onDevicesPage', 'Set Up Lighting Periods or Re-Capture Devices'],
                                          ['onMeansPage', 'Select Means to Activate Lights'],
                                          ['offMeansPage', 'Select Means to Turn Off Lights']]

    // -------------------------------------------------------------------------
    // Gate enforcement
    // -------------------------------------------------------------------------

    def "throws when Read master is disabled"() {
        given:
        settingsMap.enableRead = false

        when:
        script.executeTool("hub_list_app_pages", [appId: 35])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Read tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns an isError validation result when Read disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = false

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Read tools are disabled')

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // Input validation (all must throw before any HTTP call)
    // -------------------------------------------------------------------------

    def "throws when appId is missing from args"() {
        given:
        settingsMap.enableRead = true

        when:
        script.toolListAppPages([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('appid is required')
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns isError validation result envelope when appId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [:])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('appid is required')

        where:
        useGateways << [true, false]
    }

    def "throws when appId is blank string"() {
        given:
        settingsMap.enableRead = true

        when:
        script.toolListAppPages([appId: '   '])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('appid is required')
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns isError validation result envelope when appId is blank (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: '   '])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('appid is required')

        where:
        useGateways << [true, false]
    }

    def "throws when appId is non-numeric (no HTTP call made)"() {
        given:
        settingsMap.enableRead = true
        // Do NOT register the hub endpoint -- if validation fails to fire,
        // HubInternalGetMock will throw an unregistered-path error, which
        // surfaces as a different failure than the expected IAE.

        when:
        script.toolListAppPages([appId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns isError validation result envelope when appId is non-numeric (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 'not-a-number'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('numeric')

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // Golden path -- HPM (curated multi-page directory)
    // -------------------------------------------------------------------------

    def "golden path HPM: returns all curated HPM page names"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Hubitat Package Manager', 'prefOptions', 'Main Menu')
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then: 'top-level success'
        result.success == true

        and: 'app identity present'
        result.app.id == 35
        result.app.appTypeName == 'Hubitat Package Manager'

        and: 'primary page introspected from hub'
        result.primaryPage.name == 'prefOptions'

        and: 'all curated HPM pages present'
        def pageNames = result.pages*.name
        pageNames.contains('prefOptions')
        pageNames.contains('prefPkgUninstall')
        pageNames.contains('prefPkgModify')
        pageNames.contains('prefPkgInstall')
        pageNames.contains('prefPkgMatchUp')
        result.pages.size() == 5

        and: 'prefPkgUninstall is marked as full_package_list'
        def uninstallPage = result.pages.find { it.name == 'prefPkgUninstall' }
        uninstallPage.role == 'full_package_list'

        and: 'no limiting note for curated HPM type'
        result.note == null
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns all curated HPM page names (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Hubitat Package Manager', 'prefOptions', 'Main Menu')
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.app.id == 35
        inner.app.appTypeName == 'Hubitat Package Manager'
        inner.primaryPage.name == 'prefOptions'
        def pageNames = inner.pages*.name
        pageNames.contains('prefOptions')
        pageNames.contains('prefPkgUninstall')
        pageNames.contains('prefPkgModify')
        pageNames.contains('prefPkgInstall')
        pageNames.contains('prefPkgMatchUp')
        inner.pages.size() == 5
        inner.note == null

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // Golden path -- Rule Machine rule: the sub-pages its mainPage links to
    // -------------------------------------------------------------------------

    def "golden path RM rule: lists the selectTriggers/selectActions sub-pages its mainPage links to"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Rule-5.1', 'mainPage', 'My Rule', RM_HREFS)
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
        result.pages*.name == ['mainPage', 'selectTriggers', 'selectActions']
        result.pages*.role == ['primary', 'sub-page', 'sub-page']
        // Link titles arrive as HTML; the listing carries plain text.
        result.pages[2].title == 'Select Actions to Run'
        result.note.contains('hub_set_rule')
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch lists an RM rule's linked sub-pages (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Rule-5.1', 'mainPage', 'My Rule', RM_HREFS)
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.pages*.name == ['mainPage', 'selectTriggers', 'selectActions']
        inner.pages*.role == ['primary', 'sub-page', 'sub-page']
        inner.note.contains('hub_set_rule')

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // Unknown app type with no links -- primary page only
    // -------------------------------------------------------------------------

    def "unknown app type with no page links: returns the primary page only"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('MyCustomCommunityApp', 'mainPage', 'Custom Settings')
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
        result.pages.size() == 1
        result.pages[0].name == 'mainPage'
        result.note == 'The primary page links no sub-pages.'
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns the primary page only when it links nothing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('MyCustomCommunityApp', 'mainPage', 'Custom Settings')
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.pages.size() == 1
        inner.pages[0].name == 'mainPage'
        inner.note == 'The primary page links no sub-pages.'

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    def "returns success=false when hub returns empty body"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/99') { params -> '' }

        when:
        def result = script.toolListAppPages([appId: 99])

        then:
        result.success == false
        result.error != null
        result.error.toLowerCase().contains('empty')
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns success=false envelope when hub returns empty body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/99') { params -> '' }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 99])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error != null
        inner.error.toLowerCase().contains('empty')

        where:
        useGateways << [true, false]
    }

    def "returns success=false with fingerprint when hub returns app=null (unknown appId)"() {
        given:
        settingsMap.enableRead = true
        // Hub returns a 200 body whose top-level object has app=null -- the configure page
        // rendered but found no app (fingerprint 'missing app'), distinct from the 404/410-THROW
        // not-found below (fingerprint 'app not found (404)') where the page never renders at all.
        hubGet.register('/installedapp/configure/json/9999') { params ->
            JsonOutput.toJson([
                app       : null,
                configPage: [:],
                settings  : [:],
                childApps : []
            ])
        }

        when:
        def result = script.toolListAppPages([appId: 9999])

        then:
        result.success == false
        result.error != null
        result.fingerprint == 'missing app'
    }

    def "returns a clean not-found (warn, not error) when the configure page fetch throws a 404"() {
        given:
        settingsMap.enableRead = true
        // A deleted / mid-delete / not-yet-committed install shell: the configure page
        // can't render, so hubInternalGet raises an HttpResponseException-shaped 404.
        // The tool must degrade gracefully -- structured not-found, not an opaque error.
        hubGet.register('/installedapp/configure/json/9999') { params ->
            throw new FakeHttpException(404)
        }

        when:
        def result = script.toolListAppPages([appId: 9999])

        then:
        result.success == false
        result.fingerprint == 'app not found (404)'
        result.status == 404
        result.appId == 9999
        result.error.toLowerCase().contains('not found')
        // Steers the agent to the cheap identity probe instead of dead-end retries
        result.error.contains('summary')
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns a clean 404 not-found envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/9999') { params ->
            throw new FakeHttpException(404)
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 9999])

        then: 'a graceful not-found is a normal tool result, not a transport error'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.fingerprint == 'app not found (404)'
        inner.status == 404
        inner.error.toLowerCase().contains('not found')
        inner.error.contains('summary')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns success=false envelope for unknown appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/9999') { params ->
            JsonOutput.toJson([
                app       : null,
                configPage: [:],
                settings  : [:],
                childApps : []
            ])
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 9999])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error != null
        inner.fingerprint == 'missing app'

        where:
        useGateways << [true, false]
    }

    def "returns success=false with fingerprint when response is not a JSON object"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '"just a string"' }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == false
        result.fingerprint == 'top-level not a Map'
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns success=false envelope when response is not a JSON object (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '"just a string"' }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.fingerprint == 'top-level not a Map'

        where:
        useGateways << [true, false]
    }

    def "returns success=false with fingerprint when configPage is missing"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            JsonOutput.toJson([
                app      : [id: 35, label: 'My Rule', name: 'Rule-5.1', disabled: false,
                            appType: [name: 'Rule-5.1']],
                childApps: []
                // configPage intentionally absent
            ])
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == false
        result.fingerprint == 'missing configPage'
        result.error?.toLowerCase()?.contains('configpage') == true
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns success=false envelope when configPage is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            JsonOutput.toJson([
                app      : [id: 35, label: 'My Rule', name: 'Rule-5.1', disabled: false,
                            appType: [name: 'Rule-5.1']],
                childApps: []
            ])
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.fingerprint == 'missing configPage'
        inner.error?.toLowerCase()?.contains('configpage') == true

        where:
        useGateways << [true, false]
    }

    def "returns success=false when hub response is unparseable JSON"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '{not valid json' }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == false
        result.error?.toLowerCase()?.contains('parse') == true
        result.error?.toLowerCase()?.contains('firmware') == true
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch returns success=false envelope when hub returns unparseable JSON (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '{not valid json' }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error?.toLowerCase()?.contains('parse') == true
        inner.error?.toLowerCase()?.contains('firmware') == true

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // Curated dispatch -- parametric coverage for known appType variants
    // -------------------------------------------------------------------------

    // Issue #461: this used to return a hard-coded "Room Lighting instances use a single mainPage"
    // note while the live page linked three sub-pages holding the motion/illuminance inputs.
    def "Room Lights: lists the onDevicesPage/onMeansPage/offMeansPage sub-pages its mainPage links to"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Room Lights', 'mainPage', 'Kitchen Lights', RL_HREFS)
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
        result.pages*.name == ['mainPage', 'onDevicesPage', 'onMeansPage', 'offMeansPage']
        result.pages[2] == [name: 'onMeansPage', title: 'Select Means to Activate Lights', role: 'sub-page']
        result.note.contains('walkStep')
        !result.note.toLowerCase().contains('single')
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch lists a Room Lights instance's linked sub-pages (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Room Lights', 'mainPage', 'Kitchen Lights', RL_HREFS)
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.pages*.name == ['mainPage', 'onDevicesPage', 'onMeansPage', 'offMeansPage']
        inner.pages*.role == ['primary', 'sub-page', 'sub-page', 'sub-page']

        where:
        useGateways << [true, false]
    }

    def "a link repeated on the page is listed once, and a link back to the primary page is not listed"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Room Lights', 'mainPage', 'Kitchen Lights',
                [['onMeansPage', 'Means'], ['mainPage', 'Back'], ['onMeansPage', 'Means again']])
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.pages*.name == ['mainPage', 'onMeansPage']
        result.pages[1].title == 'Means'
    }

    def "Mode Manager (no page links) returns its mainPage only"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Mode Manager', 'mainPage', 'Manage Setting of Modes')
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
        result.pages.size() == 1
        result.pages[0].name == 'mainPage'
        result.note == 'The primary page links no sub-pages.'
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch Mode Manager (no page links) returns its mainPage only (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Mode Manager', 'mainPage', 'Manage Setting of Modes')
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.pages.size() == 1
        inner.pages[0].name == 'mainPage'
        inner.note == 'The primary page links no sub-pages.'

        where:
        useGateways << [true, false]
    }

    def "Rule Machine parent app with no page links returns its mainPage only"() {
        given:
        settingsMap.enableRead = true
        // "Rule Machine" is the parent app container (not an individual rule).
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Rule Machine', 'mainPage', 'Rule Machine')
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
        result.pages.size() == 1
        result.pages[0].name == 'mainPage'
        result.note == 'The primary page links no sub-pages.'
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch Rule Machine parent app returns mainPage (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Rule Machine', 'mainPage', 'Rule Machine')
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.pages.size() == 1
        inner.pages[0].name == 'mainPage'
        inner.note != null

        where:
        useGateways << [true, false]
    }

    def "HPM curated dispatch is case-insensitive: '#appTypeName'"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson(appTypeName, 'prefOptions', 'Main Menu')
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
        result.pages.size() == 5
        result.pages*.name.contains('prefOptions')
        result.pages*.name.contains('prefPkgUninstall')
        result.note == null

        where:
        appTypeName << ['hubitat package manager', 'HUBITAT PACKAGE MANAGER', 'Hubitat Package Manager']
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch HPM curated dispatch is case-insensitive '#appTypeName' (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson(appTypeName, 'prefOptions', 'Main Menu')
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.pages.size() == 5
        inner.pages*.name.contains('prefOptions')
        inner.pages*.name.contains('prefPkgUninstall')
        inner.note == null

        where:
        useGateways | appTypeName
        true        | 'hubitat package manager'
        false       | 'hubitat package manager'
        true        | 'HUBITAT PACKAGE MANAGER'
        false       | 'HUBITAT PACKAGE MANAGER'
        true        | 'Hubitat Package Manager'
        false       | 'Hubitat Package Manager'
    }

    // -------------------------------------------------------------------------
    // appId type coercion (integer arg passes isInteger check)
    // -------------------------------------------------------------------------

    def "integer appId is accepted (passes isInteger after toString)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Hubitat Package Manager', 'prefOptions', 'Main Menu')
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
    }

    @spock.lang.Unroll
    def "hub_list_app_pages via dispatch accepts integer appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Hubitat Package Manager', 'prefOptions', 'Main Menu')
        }

        when:
        def response = mcpDriver.callTool('hub_list_app_pages', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true

        where:
        useGateways << [true, false]
    }
}
