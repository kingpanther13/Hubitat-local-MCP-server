package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolGetAppConfig (libraries/mcp-code-management-lib.groovy).
 * Gateway tool under hub_read_apps_code — executeTool() dispatches via case "hub_get_app_config".
 *
 * Covers:
 *  - Read master central gate (throws when disabled)
 *  - Missing appId validation (throws before HTTP)
 *  - Non-numeric appId validation (throws before HTTP)
 *  - pageName sanitization (throws on path-separator characters)
 *  - Golden path: single-page app with sections, inputs, child apps
 *  - pageName arg: URL includes the page segment
 *  - includeSettings=false (default): settings key absent, settingsNote present
 *  - includeSettings=true: settings key populated
 *  - includeStatus=true: appState, eventSubscriptions and scheduledJobs from the one App Status read,
 *    credential-named appState values masked; a failed status read leaves the page read standing
 *  - summary=true: identity-only fast path via /installedapp/json (pass-through + namespace + error paths)
 *  - Empty hub response: returns success=false
 *  - Unknown appId (hub returns {app:null}): 'missing app' fingerprint, success=false
 *  - Not-found 404/410 throw (deleted / mid-delete / install shell whose config page
 *    can't render): clean 'app not found (404)' degrade at warn, full + summary modes
 *  - HTML stripping: span tags removed from labels
 *  - controls: the form engine's controls for the page (button titles, control_ids, navigation)
 *  - RM disabled actions: a paragraph with RM's red italic spans reads {text, disabled: [row text, ...]};
 *    the legend, *BROKEN* spans and non-RM apps stay out
 *  - mode inputs read their stored setting as value; pageNote on a page-name mismatch
 *
 * Each direct-call feature has a parallel "via dispatch" feature that fires
 * the same tool through {@code mcpDriver.callTool} so the production
 * envelope path (JSON-RPC parse → tools/call → executeTool routing → error
 * mapping → response wrapping) is covered alongside the unit-level tool
 * internals. Dispatch features are @Unroll'd across useGateways true/false.
 */
class ToolGetAppConfigSpec extends ToolSpecBase {

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

    // Canonical hub response for a simple single-page app.
    // Shape mirrors what /installedapp/configure/json/<id> actually returns
    // for legacy SmartApps (Rule Machine, Basic Rules, etc.).
    private static String makeAppConfigJson(Map overrides = [:]) {
        def base = [
            app: [
                id        : 35,
                trueLabel : 'My Rule',
                label     : 'My Rule',
                name      : 'Rule-5.1',
                disabled  : false,
                installed : true,
                parentAppId: null,
                appType: [
                    name     : 'Rule Machine',
                    namespace: 'hubitat',
                    author   : 'Hubitat',
                    category : 'Utility',
                    classLocation: 'builtin',
                    deprecated: false,
                    system   : true,
                    menu     : 'Automations',
                    documentationLink: null
                ]
            ],
            configPage: [
                name    : 'mainPage',
                title   : 'Rule Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Triggers',
                        input: [
                            [
                                name        : 'triggerDevice',
                                type        : 'capability.*',
                                title       : 'Which device triggers this rule?',
                                description : null,
                                multiple    : true,
                                required    : false,
                                defaultValue: 'Kitchen Switch',
                                options     : null
                            ]
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [
                triggerDevice: 'Kitchen Switch',
                ruleEnable   : true
            ],
            childApps : [
                [id: 101, label: 'Sub Rule', name: 'Rule-5.1']
            ]
        ]
        // Merge overrides at top level
        overrides.each { k, v -> base[k] = v }
        return JsonOutput.toJson(withElements(base))
    }

    // The hub tags every page element: section inputs are element 'input', and a fixture body entry
    // without a tag is a paragraph.
    private static Map withElements(Map page) {
        (page.configPage instanceof Map ? page.configPage.sections : null)?.each { section ->
            if (!(section instanceof Map)) return
            (section.input ?: []).each { if (it instanceof Map && !it.element) it.element = 'input' }
            (section.body ?: []).each { if (it instanceof Map && !it.element) it.element = 'paragraph' }
        }
        page
    }

    // /installedapp/statusJson/<id>: appSettings types every setting of the app, whichever page
    // declares it. hub_get_app_config reads it for includeSettings=true redaction.
    private void registerStatusJson(Map<String, String> types, int appId = 35) {
        hubGet.register("/installedapp/statusJson/${appId}".toString()) { params ->
            JsonOutput.toJson([installedApp: [id: appId],
                               appSettings: types.collect { n, t -> [name: n, type: t, value: null, multiple: false] },
                               appState: []])
        }
    }

    // -------------------------------------------------------------------------
    // Gate enforcement
    // -------------------------------------------------------------------------

    def "throws when Read tools are disabled"() {
        given:
        settingsMap.enableRead = false

        when:
        script.executeTool('hub_get_app_config', [appId: 35])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Read tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns an isError validation result when Read tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = false

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

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
        // Do NOT register the endpoint; if HTTP fires the HubInternalGetMock throws,
        // exposing a different failure mode than the expected validation exception.

        when:
        script.toolGetAppConfig([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('appid is required')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns isError validation result envelope when appId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [:])

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
        script.toolGetAppConfig([appId: '   '])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('appid is required')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns isError validation result envelope when appId is blank (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: '   '])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('appid is required')

        where:
        useGateways << [true, false]
    }

    def "throws when appId is non-numeric (no HTTP call made)"() {
        given:
        settingsMap.enableRead = true
        // Do NOT register the hub endpoint — if the code reaches HTTP before
        // validation, HubInternalGetMock will throw an unregistered-path error,
        // which would surface as a different failure than the expected IAE.

        when:
        script.toolGetAppConfig([appId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns isError validation result envelope when appId is non-numeric (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 'not-a-number'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('numeric')

        where:
        useGateways << [true, false]
    }

    def "throws when pageName contains a path separator"() {
        given:
        settingsMap.enableRead = true

        when:
        script.toolGetAppConfig([appId: 35, pageName: '../etc/passwd'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pagename')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns isError validation result envelope when pageName contains a path separator (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, pageName: '../etc/passwd'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('pagename')

        where:
        useGateways << [true, false]
    }

    def "throws when pageName contains a space"() {
        given:
        settingsMap.enableRead = true

        when:
        script.toolGetAppConfig([appId: 35, pageName: 'page name'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pagename')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns isError validation result envelope when pageName contains a space (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, pageName: 'page name'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('pagename')

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // Sub-page links (issue #461)
    // -------------------------------------------------------------------------

    // Room Lighting's motion/illuminance inputs live on sub-pages; without the links in the
    // result an AI had no way to know those pages exist.
    def "page.hrefs lists the page's sub-page and external links with their summaries; a page with no links omits it"() {
        given:
        settingsMap.enableRead = true
        def sections = [[title: '', input: [], body: [
            [element: 'paragraph', description: 'Set up Automation for the Lights in a Room'],
            [element: 'href', page: 'onMeansPage', title: 'Select Means to Activate Lights', description: 'Motion <b>active</b>'],
            [element: 'href', page: 'offMeansPage', title: '<b>Select Means to Turn Off Lights</b>', description: 'Click to set'],
            [element: 'href', url: 'https://docs.example/lights', title: 'Documentation'],
            [element: 'href', page: 'helpPage', url: 'https://docs.example/help', title: 'Help', description: 'Click to show']
        ]]]
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'Kitchen Lights', install: true, refreshInterval: null, sections: sections]])
        }
        hubGet.register('/installedapp/configure/json/36') { params -> makeAppConfigJson() }

        when:
        def linked = script.toolGetAppConfig([appId: 35])
        def unlinked = script.toolGetAppConfig([appId: 36])

        then:
        linked.page.hrefs == [[page: 'onMeansPage', title: 'Select Means to Activate Lights', description: 'Motion active'],
                              [page: 'offMeansPage', title: 'Select Means to Turn Off Lights'],
                              [url: 'https://docs.example/lights', title: 'Documentation'],
                              [url: 'https://docs.example/help', title: 'Help']]
        linked.page.sections[0].paragraphs == ['Set up Automation for the Lights in a Room']
        !unlinked.page.containsKey('hrefs')
    }

    // -------------------------------------------------------------------------
    // Golden path — single-page app
    // -------------------------------------------------------------------------

    def "golden path: returns app identity, sections, inputs, and child apps"() {
        given:
        settingsMap.enableRead = true

        and: 'hub returns a well-formed config page for app 35'
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson()
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then: 'top-level success'
        result.success == true

        and: 'app identity fields populated'
        result.app.id == 35
        result.app.label == 'My Rule'
        result.app.name == 'Rule-5.1'
        result.app.disabled == false

        and: 'page structure correct'
        result.page.name == 'mainPage'
        result.page.title == 'Rule Settings'
        result.page.sections.size() == 1
        result.page.sections[0].title == 'Triggers'
        result.page.sections[0].inputs.size() == 1
        result.page.sections[0].inputs[0].name == 'triggerDevice'
        result.page.sections[0].inputs[0].value == 'Kitchen Switch'

        and: 'child apps listed'
        result.childApps.size() == 1
        result.childApps[0].id == 101
        result.childApps[0].label == 'Sub Rule'

        and: 'appType carries the namespace and name of the type, built-in flag and declared menu tab, and nothing else (#5/#6, #575)'
        result.app.appType == [namespace: 'hubitat', name: 'Rule Machine', system: true, menu: 'Automations']

        and: 'endpoint field present for debugging'
        result.endpoint == '/installedapp/configure/json/35'
    }

    def "inputs read as form fields, with the page's child devices, mode restriction and components (#575)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([
                app: [id: 35, trueLabel: 'My Rule', label: 'My Rule', name: 'Rule-5.1', disabled: false, installed: true, parentAppId: null,
                      restrictedModes: ['1', '3'], appType: [name: 'Rule Machine', namespace: 'hubitat', system: true, menu: 'Automations']],
                modeMap: ['1': 'Day', '2': 'Evening', '3': 'Night'],
                childDevices: [[id: 7, name: 'Child <b>one</b>']],
                settings: [lights: ['7': 'Kitchen'], onDate: '2026-11-10'],
                configPage: [name: 'mainPage', title: 'Rule Settings', install: true, refreshInterval: null, sections: [[title: 'S', input: [], body: [
                    [element: 'input', name: 'lights', type: 'capability.switch', title: 'Lights', multiple: true, submitOnChange: true, description: 'Kitchen'],
                    [element: 'input', name: 'onDate', type: 'date', title: 'Date'],
                    [element: 'input', name: 'level', type: 'number', title: 'Level', range: '1..100', defaultValue: 50],
                    [element: 'input', name: 'export', type: 'download-text', title: 'Export', filename: 'rule.json', filecontent: '{"a":1}'],
                    [element: 'component', name: 'schedule-grid', title: 'Schedule', description: '<i>grid</i>']
                ]]]]
            ])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])
        def inputs = result.page.sections[0].inputs.collectEntries { [(it.name): it] }

        then:
        result.success
        inputs.lights.value == ['7'] && inputs.lights.submit_on_change && inputs.lights.selected == [[value: '7', label: 'Kitchen']]
        inputs.onDate.value == '2026-11-10' && inputs.onDate.value_source == 'stored'
        inputs.level.value == 50 && inputs.level.value_source == 'default' && inputs.level.range == '1..100'
        inputs.export.filename == 'rule.json' && inputs.export.value == '{"a":1}' && inputs.export.supported == false
        result.page.sections[0].components == [[name: 'schedule-grid', title: 'Schedule', description: 'grid']]
        result.childDevices == [[id: 7, name: 'Child one']]
        result.app.restrictedModes == [[value: '1', label: 'Day'], [value: '3', label: 'Night']]
    }

    def "a page with more inputs than a form workflow caches still reads every value"() {
        given:
        settingsMap.enableRead = true
        def inputs = (1..260).collect { [name: "f${it}".toString(), type: 'text', title: "Field ${it}".toString()] }
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([settings: [f1: 'one', f260: 'last'], configPage: [name: 'mainPage', title: 'Big', install: true, refreshInterval: null,
                sections: [[title: 'S', input: inputs, body: []]]]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])
        def read = result.page.sections[0].inputs.collectEntries { [(it.name): it] }

        then:
        !result.containsKey('formError')
        read.size() == 260
        read.f1.value == 'one' && read.f260.value == 'last'
    }

    def "label and mode page elements read as the app's own name and mode fields; an input in body and input reads once"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([
                app: [id: 35, trueLabel: 'My App', label: 'My App', name: 'Community App', disabled: false, installed: true, parentAppId: null,
                      restrictedModes: ['2'], appType: [name: 'Community App', namespace: 'acme', system: false]],
                modeMap: ['1': 'Day', '2': 'Night'],
                settings: [note: 'kept'],
                configPage: [name: 'mainPage', title: 'Main', install: true, refreshInterval: null, sections: [[title: 'S',
                    input: [[element: 'input', name: 'note', type: 'text', title: 'Note']],
                    body: [[element: 'input', name: 'note', type: 'text', title: 'Note'],
                           [element: 'label', title: 'Name this app'], [element: 'mode', title: 'Only in modes']]]]]
            ])
        }

        when:
        def inputs = script.toolGetAppConfig([appId: 35]).page.sections[0].inputs

        then:
        inputs*.name == ['note', 'label', 'mode[mode]']
        inputs[0].value == 'kept'
        inputs[1].value == 'My App' && inputs[1].type == 'text'
        inputs[2].value == ['2'] && inputs[2].options == [[value: '1', label: 'Day'], [value: '2', label: 'Night']]
        inputs.every { !it.containsKey('app_field') && !it.containsKey('sidecars') }
    }

    def "a community parent's create button is listed in unsupported, not dropped"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'Main', install: true, refreshInterval: null, sections: [[title: 'S', input: [], body: [
                [element: 'app', name: 'childRules', appName: 'Community Rule', namespace: 'acme', title: '+ Add New Rule']]]]]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.unsupported == ['Native element: app']
        !result.controls.any { it.kind == 'create_child' }
    }

    def "appType.menu is null when the app type declares no menu"() {
        given:
        settingsMap.enableRead = true

        and: 'hub returns an app whose appType omits the menu key'
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson(app: [
                id        : 35,
                trueLabel : 'My Rule',
                label     : 'My Rule',
                name      : 'Rule-5.1',
                disabled  : false,
                installed : true,
                parentAppId: null,
                appType: [
                    name     : 'Community App',
                    namespace: 'acme',
                    author   : 'Third Party',
                    system   : false
                ]
            ])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then: 'menu surfaces as null (no invented default) and system reflects community'
        result.success == true
        result.app.appType.system == false
        result.app.appType.menu == null
    }

    def "a mode input reads its stored setting as its value, as the form engine does"() {
        given:
        settingsMap.enableRead = true

        and: 'a type="mode" input renders no value; the page JSON stores it in settings[name]'
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([
                settings: [mode1Override: ['1', '3']],
                configPage: [name: 'ModeOptions', title: 'Mode Options', install: false, refreshInterval: null, sections: [
                    [title: 'Override 1', input: [
                        [name: 'mode1Override', type: 'mode', title: 'Mode', description: null, multiple: true, required: false],
                        [name: 'mode2Override', type: 'mode', title: 'Mode', description: null, multiple: true, required: false]
                    ], body: []]
                ]]
            ])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])
        def inputs = result.page.sections[0].inputs

        then: 'the stored list is the value; an unset mode input reads null from its default; no side list or note'
        result.success == true
        inputs.find { it.name == 'mode1Override' }.value == ['1', '3']
        def unsetMode = inputs.find { it.name == 'mode2Override' }
        unsetMode.value == null
        unsetMode.value_source == 'default'
        !result.containsKey('modeInputs')
        !result.containsKey('modeInputsNote')
    }

    def "a page input reads its stored setting over the code default; a device input reads its stored ids and labels"() {
        given:
        settingsMap.enableRead = true

        and: 'the page JSON carries only the code defaultValue; the stored values are in settings[name]'
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([
                settings: [debugLogging: 'true', flagOn: 'false', logLevel: 'debug', origLabel: 'Kitchen', lights: ['12': 'Lamp']],
                configPage: [name: 'mainPage', title: 'Main', install: true, refreshInterval: null, sections: [
                    [title: 'S', input: [
                        [name: 'debugLogging', type: 'bool', title: 'Debug', defaultValue: false],
                        [name: 'flagOn', type: 'bool', title: 'On', defaultValue: true],
                        [name: 'logLevel', type: 'enum', title: 'Level', defaultValue: 'error', options: ['error', 'debug']],
                        [name: 'origLabel', type: 'text', title: 'Label'],
                        [name: 'retries', type: 'number', title: 'Retries', defaultValue: 3],
                        [name: 'lights', type: 'capability.switch', title: 'Lights', defaultValue: true, description: 'Lamp']
                    ], body: []]
                ]]
            ])
        }

        when:
        def inputs = script.toolGetAppConfig([appId: 35]).page.sections[0].inputs.collectEntries { [(it.name): it] }

        then: 'stored values win; an unset input shows its default; the device input reads its ids with their labels'
        inputs.debugLogging.value == 'true'
        inputs.flagOn.value == 'false'
        inputs.logLevel.value == 'debug'
        inputs.origLabel.value == 'Kitchen'
        inputs.retries.value == 3
        inputs.lights.value == '12'
        inputs.lights.selected == [[value: '12', label: 'Lamp']]
        inputs.lights.description == 'Lamp'
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns app identity, sections, inputs, child apps (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.app.id == 35
        inner.app.label == 'My Rule'
        inner.app.name == 'Rule-5.1'
        inner.app.disabled == false
        inner.page.name == 'mainPage'
        inner.page.title == 'Rule Settings'
        inner.page.sections.size() == 1
        inner.page.sections[0].title == 'Triggers'
        inner.page.sections[0].inputs.size() == 1
        inner.page.sections[0].inputs[0].name == 'triggerDevice'
        inner.page.sections[0].inputs[0].value == 'Kitchen Switch'
        inner.childApps.size() == 1
        inner.childApps[0].id == 101
        inner.childApps[0].label == 'Sub Rule'
        inner.endpoint == '/installedapp/configure/json/35'

        where:
        useGateways << [true, false]
    }

    def "golden path: hub endpoint includes pageName segment when supplied"() {
        given:
        settingsMap.enableRead = true

        and: 'hub endpoint must include the pageName path segment'
        boolean correctPathCalled = false
        hubGet.register('/installedapp/configure/json/35/prefPkgModify') { params ->
            correctPathCalled = true
            makeAppConfigJson()
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, pageName: 'prefPkgModify'])

        then:
        result.success == true
        correctPathCalled == true
        result.endpoint == '/installedapp/configure/json/35/prefPkgModify'
    }

    // HPM's prefSettings renders only through its href; a direct read returns prefOptions (live, C-7).
    def "pageNote names the page the hub rendered instead of pageName and the form tool that reaches it"() {
        given:
        settingsMap.enableRead = true
        def page = { String name -> [name: name, title: 'Package Manager', install: true, refreshInterval: null, sections: []] }
        hubGet.register('/installedapp/configure/json/35/prefSettings') { params ->
            makeAppConfigJson([app: [id: 35, label: 'HPM', name: 'Hubitat Package Manager', disabled: false, installed: true,
                                     appType: [name: 'Hubitat Package Manager', namespace: 'dcm.hpm']], configPage: page('prefOptions')])
        }
        hubGet.register('/installedapp/configure/json/35/selectActions') { params -> makeAppConfigJson([configPage: page('mainPage')]) }
        hubGet.register('/installedapp/configure/json/35/prefOptions') { params -> makeAppConfigJson([configPage: page('prefOptions')]) }

        when:
        def hpm = script.toolGetAppConfig([appId: 35, pageName: 'prefSettings'])
        def rule = script.toolGetAppConfig([appId: 35, pageName: 'selectActions'])
        def same = script.toolGetAppConfig([appId: 35, pageName: 'prefOptions'])

        then:
        hpm.pageNote == "The hub rendered 'prefOptions' instead of 'prefSettings'; this app reaches that page only through its own link: open it with hub_set_native_app(operation:'form') and activate the href."
        rule.pageNote.startsWith("The hub rendered 'mainPage' instead of 'selectActions';")
        rule.pageNote.contains("hub_set_rule(operation:'form')")
        !same.containsKey('pageNote')
    }

    def "configPageError passes through the page's own error"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'Rule', install: true, error: 'For input string: "updateRule"', sections: []]])
        }
        hubGet.register('/installedapp/configure/json/36') { params -> makeAppConfigJson() }

        expect:
        script.toolGetAppConfig([appId: 35]).configPageError == 'For input string: "updateRule"'
        !script.toolGetAppConfig([appId: 36]).containsKey('configPageError')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch includes pageName segment in endpoint (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        boolean correctPathCalled = false
        hubGet.register('/installedapp/configure/json/35/prefPkgModify') { params ->
            correctPathCalled = true
            makeAppConfigJson()
        }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, pageName: 'prefPkgModify'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        correctPathCalled == true
        inner.endpoint == '/installedapp/configure/json/35/prefPkgModify'

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // HTML stripping
    // -------------------------------------------------------------------------

    def "HTML span tags in app label are stripped from the response"() {
        given:
        settingsMap.enableRead = true

        def jsonWithHtml = makeAppConfigJson([
            app: [
                id       : 42,
                trueLabel: '<span style="color:green">Colored Rule</span>',
                label    : 'Colored Rule (raw)',
                name     : 'Rule-5.1',
                disabled : false,
                installed: true,
                parentAppId: null,
                appType  : null
            ]
        ])
        hubGet.register('/installedapp/configure/json/42') { params -> jsonWithHtml }

        when:
        def result = script.toolGetAppConfig([appId: 42])

        then:
        result.success == true
        result.app.label == 'Colored Rule'
        !result.app.label.contains('<span')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch strips HTML span tags from app label (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        def jsonWithHtml = makeAppConfigJson([
            app: [
                id       : 42,
                trueLabel: '<span style="color:green">Colored Rule</span>',
                label    : 'Colored Rule (raw)',
                name     : 'Rule-5.1',
                disabled : false,
                installed: true,
                parentAppId: null,
                appType  : null
            ]
        ])
        hubGet.register('/installedapp/configure/json/42') { params -> jsonWithHtml }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 42])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.app.label == 'Colored Rule'
        !inner.app.label.contains('<span')

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // includeSettings flag
    // -------------------------------------------------------------------------

    def "includeSettings=false (default): settings key absent, settingsNote present when count > 0"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson()
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        result.containsKey('settingsKeyCount')
        result.settingsKeyCount > 0
        !result.containsKey('settings')
        result.settingsNote != null
        result.settingsNote.contains('includeSettings=true')

        and: 'no settings are returned, so the setting-type read is skipped'
        !hubGet.calls.any { it.path.startsWith('/installedapp/statusJson/') }
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch with includeSettings=false omits settings key (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.containsKey('settingsKeyCount')
        inner.settingsKeyCount > 0
        !inner.containsKey('settings')
        inner.settingsNote != null
        inner.settingsNote.contains('includeSettings=true')

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // includeStatus flag
    // -------------------------------------------------------------------------

    // statusJson as the hub serves it: appState is a LIST of {name, value} rows.
    private void registerAppStatus(List appState, int appId = 35) {
        hubGet.register("/installedapp/statusJson/${appId}".toString()) { params ->
            JsonOutput.toJson([installedApp: [id: appId], appSettings: [],
                               eventSubscriptions: [[deviceId: 12, name: 'switch', handler: 'handler']],
                               scheduledJobs: [[handler: 'poll', nextRunTime: '2026-11-20T10:00:00+0000']],
                               appState: appState])
        }
    }

    def "includeStatus=true returns appState, eventSubscriptions and scheduledJobs, credential-named values hidden at any depth"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'paused', value: false],
                           [name: 'apiKey', value: 'secret-api-key'],
                           [name: 'allLocalVars', value: [counter: [type: 'integer', value: 3], doorCode: [type: 'string', value: '4321']]],
                           [name: 'lv_doorCode', value: [type: 'string', value: '4321']],
                           [name: 'history', value: [[when: 1, alarmPin: '9999'], 'plain']],
                           [name: 'batches', value: [[[apiKey: 'nested-api-key']], 'plain']]])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.success == true
        result.eventSubscriptions == [[deviceId: 12, name: 'switch', handler: 'handler']]
        result.scheduledJobs == [[handler: 'poll', nextRunTime: '2026-11-20T10:00:00+0000']]
        result.appState.find { it.name == 'paused' }.value == false
        result.appState.find { it.name == 'apiKey' }.value == '***redacted (password)***'
        result.appState.find { it.name == 'allLocalVars' }.value.counter == [type: 'integer', value: 3]
        result.appState.find { it.name == 'allLocalVars' }.value.doorCode == '***redacted (password)***'
        result.appState.find { it.name == 'lv_doorCode' }.value == '***redacted (password)***'
        result.appState.find { it.name == 'history' }.value == [[when: 1, alarmPin: '***redacted (password)***'], 'plain']
        result.appState.find { it.name == 'batches' }.value == [[[apiKey: '***redacted (password)***']], 'plain']
        result.statusRedactionNote.contains('***redacted (password)***')
        !JsonOutput.toJson(result).contains('4321') && !JsonOutput.toJson(result).contains('secret-api-key')
        !JsonOutput.toJson(result).contains('nested-api-key')
        hubGet.calls.count { it.path == '/installedapp/statusJson/35' } == 1
    }

    def "includeStatus masks session cookies, bearer strings and psw names, and judges nested keys and rows by their names"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'hubSecurityCookie', value: 'JSESSIONID=abc123'],
                           [name: 'hubSecurityCookieExpiry', value: 1700000000000L],
                           [name: 'sessionId', value: 'sess-1'],
                           [name: 'bearerAuth', value: 'Bearer xyz'],
                           [name: 'mqttPsw', value: 'psw-1'],
                           [name: 'lock', value: [code: '2468', battery: 90]],
                           [name: 'users', value: [[name: 'apiKey', value: 'row-api-key'], [name: 'mode', value: 'Home']]],
                           [name: 'lockCodes', value: ['1': [name: 'Guest', code: '1357']]]])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])
        Map state = result.appState.collectEntries { [(it.name): it.value] }

        then: 'a live login is masked; metadata about it is not'
        ['hubSecurityCookie', 'sessionId', 'bearerAuth', 'mqttPsw'].every { state[it] == '***redacted (password)***' }
        state.hubSecurityCookieExpiry == 1700000000000L

        and: "a bare code is judged after its parent's name, a {name, value} row by its name, and a secret-named map whole"
        state.lock == [code: '***redacted (password)***', battery: 90]
        state.users == [[name: 'apiKey', value: '***redacted (password)***'], [name: 'mode', value: 'Home']]
        state.lockCodes == '***redacted (password)***'
        !JsonOutput.toJson(result).contains('2468') && !JsonOutput.toJson(result).contains('1357') && !JsonOutput.toJson(result).contains('row-api-key')
    }

    // Live (C-7): a rule's page prints its local variables as a table whose text runs together, so a local masked in
    // appState still came back in clear in page.sections[].paragraphs.
    def "a value masked in appState is masked in the page text too, even run together with other words"() {
        given:
        settingsMap.enableRead = true
        def sections = [[title: '', input: [], body: [
            [element: 'paragraph', description: 'NameTypeValuePrivate BooleanBooleantrue%value%Value0e2eSessionCookieStringe2e-cookie-value']]]]
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'Rule', install: true, refreshInterval: null, sections: sections]])
        }
        registerAppStatus([[name: 'allLocalVars', value: [value: [type: 'integer', value: 0], e2eSessionCookie: [type: 'string', value: 'e2e-cookie-value']]]])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.appState.find { it.name == 'allLocalVars' }.value.e2eSessionCookie == '***redacted (password)***'
        result.page.sections[0].paragraphs == ['NameTypeValuePrivate BooleanBooleantrue%value%Value0e2eSessionCookieString***redacted (password)***']
        !JsonOutput.toJson(result).contains('e2e-cookie-value')
        hubGet.calls.count { it.path == '/installedapp/statusJson/35' } == 1
    }

    // Live (C-7): the rule page's locals table also rides in a control's title as HTML, and the page prints it whether
    // or not includeStatus read the app's state.
    @spock.lang.Unroll
    def "a Rule Machine rule's credential-named local is masked in page text and control titles (includeStatus=#withStatus)"() {
        given:
        settingsMap.enableRead = true
        String table = '<style>.mdl-data-table tbody tr:hover{background-color:inherit}</style><table class="mdl-data-table"><tr><td>e2eSessionCookie</td><td>String</td><td>e2e-cookie-value</td></tr></table>'
        def sections = [[title: '', input: [[element: 'input', name: 'localVars', type: 'button', title: table]], body: [
            [element: 'paragraph', description: 'NameTypeValuePrivate BooleanBooleantrue%value%Value0e2eSessionCookieStringe2e-cookie-value']]]]
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'Rule', install: true, refreshInterval: null, sections: sections]])
        }
        registerAppStatus([[name: 'paused', value: false]])
        script.metaClass._nrState = { Long id -> [:] }
        script.metaClass._nrHandle = { Long id -> [getAllLocalVars: { -> [e2eSessionCookie: [type: 'string', value: 'e2e-cookie-value'], count: [type: 'integer', value: 12345]] }] as Expando }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: withStatus])
        String text = JsonOutput.toJson(result)

        then:
        !text.contains('e2e-cookie-value')
        result.page.sections[0].paragraphs == ['NameTypeValuePrivate BooleanBooleantrue%value%Value0e2eSessionCookieString***redacted (password)***']
        result.controls.find { it.name == 'localVars' }.title.endsWith('e2eSessionCookieString***redacted (password)***')

        where:
        withStatus << [false, true]
    }

    def "a value masked in settings is masked in the page text too"() {
        given:
        settingsMap.enableRead = true
        def sections = [[title: '', input: [], body: [[element: 'paragraph', description: 'Broker login: brokerPwd=hunter22 (saved)']]]]
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'App', install: true, refreshInterval: null, sections: sections], settings: [brokerPassword: 'hunter22']])
        }
        registerStatusJson([brokerPassword: 'text'])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.settings.brokerPassword == '***redacted (password)***'
        result.page.sections[0].paragraphs == ['Broker login: brokerPwd=***redacted (password)*** (saved)']
    }

    def "includeStatus runs eventSubscriptions and scheduledJobs through the same masking before any truncation"() {
        given: 'the live subscription shape, plus one whose data an app filled with a credential-named map'
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        Map live = [filter: 'true', handler: 'switchHandler', name: 'switch', subscriptionData: '3', type: 'DEVICE', typeId: 12, typeName: 'Kitchen']
        Map url = live + [subscriptionData: 'http://x.test/hook?token=SUBTOK&room=den', type: 'LOCATION', typeId: null, typeName: null]
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], appSettings: [], appState: [],
                               eventSubscriptions: [live, live + [subscriptionData: [apiKey: 'sub-api-key']], url],
                               scheduledJobs: [[handler: 'poll', data: [token: 'job-token']]]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.eventSubscriptions[0] == live
        result.eventSubscriptions[1].subscriptionData == [apiKey: '***redacted (password)***']
        result.eventSubscriptions[2].subscriptionData == 'http://x.test/hook?token=***redacted (password)***&room=den'
        !JsonOutput.toJson(result).contains('SUBTOK')
        result.scheduledJobs == [[handler: 'poll', data: [token: '***redacted (password)***']]]
        !JsonOutput.toJson(result).contains('sub-api-key') && !JsonOutput.toJson(result).contains('job-token')
    }

    // hub_list_rules and the toggles need only the lifecycle flags, so nothing else of the app's state is copied or masked.
    def "the flag-only status read keeps paused, stopped and private and nothing else"() {
        given:
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], appSettings: [], eventSubscriptions: [], scheduledJobs: [],
                               appState: [[name: 'paused', value: true], [name: 'stopped', value: false], [name: 'private', value: 'true'],
                                          [name: 'apiKey', value: 'k'], [name: 'allLocalVars', value: [a: 1]]]])
        }
        hubGet.register('/installedapp/statusJson/36') { params -> JsonOutput.toJson([installedApp: [id: 36]]) }

        expect:
        script._appStatusFlagsRead(35) == [appStateMissing: false, appState: [[name: 'paused', value: true], [name: 'stopped', value: false], [name: 'private', value: 'true']]]
        script._appStatusFlagsRead(36) == [appStateMissing: true, appState: []]
    }

    def "a password-typed App Status setting is masked whatever its name"() {
        given:
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], appState: [], eventSubscriptions: [], scheduledJobs: [],
                               appSettings: [[name: 'loginField', type: 'password', value: 'pw-1', deviceIdsForDeviceList: []],
                                             [name: 'greeting', type: 'text', value: 'hello', deviceIdsForDeviceList: []]]])
        }

        when:
        List rows = script._appStatusRead(35).appSettings

        then:
        rows.find { it.name == 'loginField' }.value == '***redacted (password)***'
        rows.find { it.name == 'greeting' }.value == 'hello'
    }

    def "a state value masked in an oversized status is still masked in the page text after the status is trimmed"() {
        given:
        settingsMap.enableRead = true
        script.metaClass._responseSizeLimitBytes = { -> 12000 }
        def sections = [[title: '', input: [], body: [[element: 'paragraph', description: 'Broker login: hunter22secret (saved)']]]]
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'App', install: true, refreshInterval: null, sections: sections]])
        }
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], appSettings: [], eventSubscriptions: [], scheduledJobs: [],
                               appState: [[name: 'piston', value: 'x' * 20000], [name: 'mqttPassword', value: 'hunter22secret']]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.statusTruncated == true
        result.appState == [[name: 'piston'], [name: 'mqttPassword']]
        result.page.sections[0].paragraphs == ['Broker login: ***redacted (password)*** (saved)']
        !JsonOutput.toJson(result).contains('hunter22secret')
    }

    def "a state value is still masked in the page text when fitting the status drops appState entirely"() {
        given:
        settingsMap.enableRead = true
        script.metaClass._responseSizeLimitBytes = { -> 12000 }
        def sections = [[title: '', input: [], body: [[element: 'paragraph', description: 'Broker login: hunter22secret (saved)']]]]
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'App', install: true, refreshInterval: null, sections: sections]])
        }
        List rows = (1..400).collect { [name: "pistonVariable${it}".padRight(48, 'x'), value: 'v'] } + [[name: 'mqttPassword', value: 'hunter22secret']]
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], appSettings: [], eventSubscriptions: [], scheduledJobs: [], appState: rows])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        !result.containsKey('appState')
        result.appStateTotal == 401
        result.page.sections[0].paragraphs == ['Broker login: ***redacted (password)*** (saved)']
        !JsonOutput.toJson(result).contains('hunter22secret')
    }

    @spock.lang.Unroll
    def "an access token masked in appState is masked in the page text too (redactAccessTokens=#tokens, redactCredentials=#credentials)"() {
        given:
        settingsMap.enableRead = true
        settingsMap.redactAccessTokens = tokens
        settingsMap.redactCredentials = credentials
        def sections = [[title: '', input: [], body: [[element: 'paragraph', description: 'Endpoint key tok-abc-12345 (copy it)']]]]
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'App', install: true, refreshInterval: null, sections: sections]])
        }
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], appSettings: [], eventSubscriptions: [], scheduledJobs: [],
                               appState: [[name: 'accessToken', value: 'tok-abc-12345']]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.page.sections[0].paragraphs == [tokens ? 'Endpoint key ***redacted (access token)*** (copy it)' : 'Endpoint key tok-abc-12345 (copy it)']

        where:
        tokens | credentials
        true   | true
        true   | false
        false  | true
    }

    def "a failed status read is not repeated in the same call, and its note is unchanged"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        hubGet.register('/installedapp/statusJson/35') { params -> throw new IOException('status timeout') }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true, includeStatus: true])

        then:
        result.success == true
        hubGet.calls.count { it.path == '/installedapp/statusJson/35' } == 1
        !result.containsKey('appState')
        result.statusNote == "The app's status page could not be read (status timeout), so appState, eventSubscriptions and scheduledJobs are omitted. Retry; the rest of this read is unaffected."
        result.settingsNote.contains('withheld')
    }

    def "a sub-page link's page name is kept when a masked value appears in it"() {
        given:
        settingsMap.enableRead = true
        def sections = [[title: '', input: [], body: [[element: 'paragraph', description: 'Broker login: hunter22 (saved)'],
                                                     [element: 'href', page: 'hunter22Page', title: 'Open hunter22']]]]
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'App', install: true, refreshInterval: null, sections: sections], settings: [brokerPassword: 'hunter22']])
        }
        registerStatusJson([brokerPassword: 'text'])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.page.sections[0].paragraphs == ['Broker login: ***redacted (password)*** (saved)']
        result.page.hrefs[0].page == 'hunter22Page'
        result.page.hrefs[0].title == 'Open ***redacted (password)***'
    }

    def "includeSettings and includeStatus together read the status page once"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], eventSubscriptions: [], scheduledJobs: [], appState: [[name: 'paused', value: false]],
                               appSettings: [[name: 'triggerDevice', type: 'capability.*', value: null], [name: 'ruleEnable', type: 'bool', value: true]]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true, includeStatus: true])

        then:
        result.settings == [triggerDevice: 'Kitchen Switch', ruleEnable: true]
        result.appState == [[name: 'paused', value: false]]
        hubGet.calls.count { it.path == '/installedapp/statusJson/35' } == 1
    }

    // Live (C-7): an HTTP Get action keeps its URL in httper.<n>, and a Run Custom Action's setCode keeps the PIN in
    // cpVal2.<n>; both came back in clear from the settings and from the page's link summaries.
    def "a secret URL parameter and a lock-code action's parameters are masked in settings and in the page's text"() {
        given:
        settingsMap.enableRead = true
        String url = 'http://x.test/api?apikey=KEY123&mode=on&token=TOK456&access_token=AT789'
        def sections = [[title: '', input: [], body: [
            [element: 'href', page: 'doActPage', title: 'Edit Actions', description: "GET ${url}<br>Lock: setCode(1, 482913, Guest)".toString()],
            [element: 'paragraph', description: "Run: setCode(1, 482913, Guest) then GET ${url}".toString()]]]]
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'Rule', install: true, refreshInterval: null, sections: sections],
                               settings: ['httper.1': url, 'cCmd.2': 'setCode', 'cpVal1.2': '1', 'cpVal2.2': '482913', 'cpVal3.2': 'Guest', 'cpVal2.3': '482913']])
        }
        registerStatusJson(['httper.1': 'text', 'cCmd.2': 'enum', 'cpVal1.2': 'text', 'cpVal2.2': 'text', 'cpVal3.2': 'text', 'cpVal2.3': 'text'])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])
        String text = JsonOutput.toJson(result)

        then: 'the secret query values and every setCode parameter read the marker; access_token follows its own setting'
        result.settings['httper.1'] == 'http://x.test/api?apikey=***redacted (password)***&mode=on&token=***redacted (password)***&access_token=AT789'
        ['cpVal1.2', 'cpVal2.2', 'cpVal3.2'].every { result.settings[it] == '***redacted (password)***' }
        result.settings['cpVal2.3'] == '482913'
        result.page.hrefs[0].description == 'GET http://x.test/api?apikey=***redacted (password)***&mode=on&token=***redacted (password)***&access_token=AT789Lock: setCode(1, ***redacted (password)***, ***redacted (password)***)'
        !text.contains('KEY123') && !text.contains('TOK456')
        // Guest is a setCode parameter masked in settings, so the page masks it too.
        result.page.sections[0].paragraphs[0].contains('setCode(1, ***redacted (password)***, ***redacted (password)***)')
        result.settingsRedactionNote.contains('lock-code custom action (setCode)')
    }

    // A secret value is masked whole, '?' included; a nested URL carried in another value has its own parameters judged.
    def "a secret URL value is masked whole and a nested URL's secret is masked: #url"() {
        expect:
        script._ruleSecretsMasked(url, [names: [], codes: []]) == expected

        where:
        url                                                     | expected
        'http://hub/cb?next=https://api.x.com/v1?apikey=SECRET' | 'http://hub/cb?next=https://api.x.com/v1?apikey=***redacted (password)***'
        'http://h/a?url=http://h/?password=pw'                  | 'http://h/a?url=http://h/?password=***redacted (password)***'
        'http://h/x?psw=ab?cd&room=den'                         | 'http://h/x?psw=***redacted (password)***&room=den'
        'http://h/y?psw=?abcd&room=den'                         | 'http://h/y?psw=***redacted (password)***&room=den'
        'http://h/z?next=http://i/?a=1&psw=pw?x#frag'           | 'http://h/z?next=http://i/?a=1&psw=***redacted (password)***#frag'
        'http://h/w?q=a?b&room=den'                             | 'http://h/w?q=a?b&room=den'
    }

    // The secret word starts the parameter name or follows a separator, so a word that merely ends in one is kept.
    def "a URL query parameter named #param is #outcome"() {
        expect:
        script._ruleSecretsMasked("http://x.test/a?${param}=V123&room=den".toString(), [names: [], codes: []]) ==
            "http://x.test/a?${param}=${masked ? '***redacted (password)***' : 'V123'}&room=den".toString()

        where:
        param          | masked
        'apikey'       | true
        'api_key'      | true
        'x-api-key'    | true
        'auth_token'   | true
        'token'        | true
        'Password'     | true
        // Named as credential settings are: the setting classifier covers them.
        'psw'          | true
        'authToken'    | true
        'clientSecret' | true
        'monkey'       | false
        'hotkey'       | false
        'oauth'        | false
        'access_token' | false
        'accessToken'  | false
        outcome = masked ? 'masked' : 'kept'
    }

    def "with Hide passwords and credentials off, URL parameters and lock-code parameters read as stored"() {
        given:
        settingsMap.enableRead = true
        settingsMap.redactCredentials = false
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([settings: ['httper.1': 'http://x.test/?apikey=KEY123', 'cCmd.2': 'setCode', 'cpVal2.2': '482913']])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.settings == ['httper.1': 'http://x.test/?apikey=KEY123', 'cCmd.2': 'setCode', 'cpVal2.2': '482913']
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch masks a lock-code action's PIN in settings (useGateways=#useGateways)"() {
        given:
        settingsMap.enableRead = true
        settingsMap.useGateways = useGateways
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson([settings: ['cCmd.2': 'setCode', 'cpVal2.2': '482913']]) }
        registerStatusJson(['cCmd.2': 'enum', 'cpVal2.2': 'text'])

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, includeSettings: true])

        then:
        !response.result.isError
        mcpDriver.parseInner(response).settings['cpVal2.2'] == '***redacted (password)***'
        !JsonOutput.toJson(response).contains('482913')

        where:
        useGateways << [true, false]
    }

    def "includeStatus=true reads values as stored with Hide passwords and credentials off"() {
        given:
        settingsMap.enableRead = true
        settingsMap.redactCredentials = false
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'apiKey', value: 'secret-api-key']])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.appState == [[name: 'apiKey', value: 'secret-api-key']]
        !result.containsKey('statusRedactionNote')
    }

    def "statusRedactionNote says whether an app's access token is shown"() {
        given:
        settingsMap.enableRead = true
        settingsMap.redactCredentials = false
        settingsMap.redactAccessTokens = redactTokens
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'accessToken', value: 'tok-123']])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.statusRedactionNote.contains(expected)

        where:
        redactTokens | expected
        true         | 'read as redacted'
        false        | "The app's accessToken is shown, because Hide access tokens is off"
    }

    // clientTokens is also credential-named, so Hide passwords and credentials masks it whatever Hide access tokens says.
    @spock.lang.Unroll
    def "statusRedactionNote names clientTokens as redacted while Hide passwords and credentials is on (redactCredentials=#redactCreds)"() {
        given:
        settingsMap.enableRead = true
        settingsMap.redactCredentials = redactCreds
        settingsMap.redactAccessTokens = false
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'accessToken', value: 'tok-123'], [name: 'clientTokens', value: [c1: [label: 'Desk', token: 'client-tok']]]])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.statusRedactionNote.contains(expected)
        JsonOutput.toJson(result).contains('client-tok') == !redactCreds

        where:
        redactCreds | expected
        true        | "The app's accessToken is shown, because Hide access tokens is off in the MCP app's settings. Its clientTokens read as redacted under Hide passwords and credentials."
        false       | "The app's accessToken and clientTokens are shown, because Hide access tokens is off in the MCP app's settings."
    }

    def "for a per-client token, statusRedactionNote says both are always hidden and offers no settings advice"() {
        given: 'both settings are off, which a client token overrides'
        settingsMap.enableRead = true
        settingsMap.redactCredentials = false
        settingsMap.redactAccessTokens = false
        script.callerClient = [id: 'c1']
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'accessToken', value: 'tok-123'], [name: 'apiKey', value: 'secret-api-key']])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.statusRedactionNote == "This client's token always reads credential-named appState values and access tokens as redacted, whatever the MCP app's settings say."
        !JsonOutput.toJson(result).contains('tok-123') && !JsonOutput.toJson(result).contains('secret-api-key')

        cleanup:
        script.callerClient = null
    }

    def "a status page with no app state is marked appStateMissing, unlike an app whose state is empty"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], appSettings: [], eventSubscriptions: [], scheduledJobs: []] + (empty ? [appState: []] : [:]))
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.appState == []
        result.containsKey('appStateMissing') == !empty
        if (!empty) assert result.appStateMissing == true

        where:
        empty << [true, false]
    }

    def "without includeStatus no status lists are returned and the status page is not read"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        !result.containsKey('appState') && !result.containsKey('eventSubscriptions') && !result.containsKey('scheduledJobs')
        !hubGet.calls.any { it.path.startsWith('/installedapp/statusJson/') }
    }

    def "a failed status read leaves the page read standing and says why the lists are missing"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        hubGet.register('/installedapp/statusJson/35') { params -> throw new IOException('status timeout') }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.success == true
        result.page != null
        !result.containsKey('appState')
        result.statusNote.contains('status timeout')
    }

    def "includeStatus works with summary=true and renders no page"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/json/35') { params -> JsonOutput.toJson([id: 35, name: 'webCoRE Piston', type: 'webCoRE Piston', disabled: false, user: true]) }
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'ady624', name: 'webCoRE Piston']] }
        registerAppStatus([[name: 'piston', value: [o: [], s: [[t: 'action']]]]])

        when:
        def result = script.toolGetAppConfig([appId: 35, summary: true, includeStatus: true])

        then:
        result.success == true
        result.appState == [[name: 'piston', value: [o: [], s: [[t: 'action']]]]]
        result.scheduledJobs.size() == 1
        !hubGet.calls.any { it.path.startsWith('/installedapp/configure/json/') }
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns the status lists with includeStatus (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'stopped', value: true]])

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, includeStatus: true])
        def inner = mcpDriver.parseInner(response)

        then:
        !response.result.isError
        inner.appState == [[name: 'stopped', value: true]]
        inner.eventSubscriptions.size() == 1

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "includeStatus masks an accessToken state row for #who"() {
        given:
        settingsMap.enableRead = true
        settingsMap.redactAccessTokens = setting
        if (client) script.callerClient = [id: 'c1']
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'accessToken', value: 'a1b2c3d4-live-token'], [name: 'paused', value: false]])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.appState.find { it.name == 'accessToken' }.value == shown
        result.appState.find { it.name == 'paused' }.value == false

        cleanup:
        script.callerClient = null

        where:
        who                               | setting | client | shown
        'the main token, setting on'      | true    | false  | '***redacted (access token)***'
        'a per-client token, setting off' | false   | true   | '***redacted (access token)***'
        'the main token, setting off'     | false   | false  | 'a1b2c3d4-live-token'
    }

    def "includeStatus masks each per-client token inside a clientTokens state row"() {
        given: "credentials shown, so only the access-token setting can hide the tokens"
        settingsMap.enableRead = true
        settingsMap.redactCredentials = false
        settingsMap.redactAccessTokens = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'clientTokens', value: [c1: [label: 'Desk', token: 'client-token-one'], c2: [label: 'Phone', token: 'client-token-two']]]])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.appState.find { it.name == 'clientTokens' }.value == [c1: [label: 'Desk', token: '***redacted (access token)***'],
                                                                     c2: [label: 'Phone', token: '***redacted (access token)***']]
        !JsonOutput.toJson(result).contains('client-token-')
    }

    def "includeStatus too large for one response lists appState names only, then cuts subscriptions and jobs with their totals"() {
        given:
        settingsMap.enableRead = true
        script.metaClass._responseSizeLimitBytes = { -> limit }
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], appSettings: [],
                               eventSubscriptions: (1..subs).collect { [deviceId: it, name: 'switch', handler: 'handler'] },
                               scheduledJobs: [[handler: 'poll', nextRunTime: '2026-11-20T10:00:00+0000']],
                               appState: [[name: 'piston', value: 'x' * 20000], [name: 'paused', value: false]]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.success == true
        result.statusTruncated == true
        result.appState == [[name: 'piston'], [name: 'paused']]
        result.statusTruncatedNote.contains('values omitted')
        result.statusTruncatedNote.contains("hub_get_tool_guide(section='builtin_app_tools_apps')")
        (result.eventSubscriptions.size() < subs) == cut
        result.containsKey('eventSubscriptionsTotal') == cut
        !cut || (result.eventSubscriptionsTotal == subs && result.statusTruncatedNote.contains("eventSubscriptions lists the first ${result.eventSubscriptions.size()} of ${subs}"))
        result.scheduledJobs.size() == 1
        !result.containsKey('scheduledJobsTotal')
        script._appConfigResultBytes(result) <= limit - 1024

        where:
        limit | subs | cut
        12000 | 3    | false
        12000 | 400  | true
    }

    def "includeStatus whose appState names alone are too large leaves appState out with its count"() {
        given:
        settingsMap.enableRead = true
        script.metaClass._responseSizeLimitBytes = { -> 12000 }
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        hubGet.register('/installedapp/statusJson/35') { params ->
            JsonOutput.toJson([installedApp: [id: 35], appSettings: [], eventSubscriptions: [], scheduledJobs: [],
                               appState: (1..2000).collect { [name: "key${it}x".toString(), value: it] }])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.success == true
        result.statusTruncated == true
        !result.containsKey('appState')
        result.appStateTotal == 2000
        result.statusTruncatedNote.contains('appState is omitted (2000 entries)')
        script._appConfigResultBytes(result) <= 12000 - 1024
    }

    def "includeStatus within the size budget is returned whole"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerAppStatus([[name: 'piston', value: 'x' * 20000]])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeStatus: true])

        then:
        result.appState[0].value.size() == 20000
        !result.containsKey('statusTruncated')
    }

    def "includeSettings=true: settings map present and populated"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson()
        }
        registerStatusJson([triggerDevice: 'capability.*', ruleEnable: 'bool'])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.success == true
        result.containsKey('settings')
        result.settings instanceof Map
        result.settings.size() == 2
        result.settings.triggerDevice == 'Kitchen Switch'
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch with includeSettings=true exposes settings (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }
        registerStatusJson([triggerDevice: 'capability.*', ruleEnable: 'bool'])

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, includeSettings: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.containsKey('settings')
        inner.settings instanceof Map
        inner.settings.size() == 2
        inner.settings.triggerDevice == 'Kitchen Switch'

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // summary=true — identity-only fast path via /installedapp/json/<id>
    // -------------------------------------------------------------------------

    def "summary=true passes the thin identity record through with the type's namespace and never renders the config page"() {
        given:
        settingsMap.enableRead = true
        // configure/json deliberately NOT registered -- if summary mode rendered the
        // config page, HubInternalGetMock would throw on the unregistered path.
        // extraField proves unknown hub fields pass through untouched.
        hubGet.register('/installedapp/json/35') { params ->
            JsonOutput.toJson([id: 35, name: 'My Rule', type: 'Rule-5.1', disabled: false, user: false, extraField: 'future-firmware-key'])
        }
        script.metaClass.getAppByAppId = { Long id -> id == 35L ? [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']] : null }

        when:
        def result = script.toolGetAppConfig([appId: 35, summary: true])

        then: 'identity fields pass through as-is, with the namespace the installed app carries'
        result == [id: 35, name: 'My Rule', type: 'Rule-5.1', disabled: false, user: false, extraField: 'future-firmware-key',
                   namespace: 'hubitat', success: true, endpoint: '/installedapp/json/35']

        and: 'only the thin endpoint was hit'
        hubGet.calls.every { it.path == '/installedapp/json/35' }
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch summary=true returns the thin identity record (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/json/35') { params ->
            JsonOutput.toJson([id: 35, name: 'My Rule', type: 'Rule-5.1', disabled: false, user: false])
        }
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, summary: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.id == 35
        inner.name == 'My Rule'
        inner.type == 'Rule-5.1'
        inner.namespace == 'hubitat'
        inner.disabled == false
        inner.user == false
        inner.endpoint == '/installedapp/json/35'
        !inner.containsKey('page')

        where:
        useGateways << [true, false]
    }

    def "summary=true still answers when the installed app cannot be looked up, without a namespace"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/json/35') { params -> JsonOutput.toJson([id: 35, name: 'My Rule', type: 'Rule-5.1']) }
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup refused') }

        when:
        def result = script.toolGetAppConfig([appId: 35, summary: true])

        then:
        result.success == true
        result.type == 'Rule-5.1'
        result.namespace == null
    }

    def "summary=true returns success=false when hub returns empty body"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/json/99') { params -> '' }

        when:
        def result = script.toolGetAppConfig([appId: 99, summary: true])

        then:
        result.success == false
        result.error.toLowerCase().contains('empty')
        result.appId == 99
    }

    def "summary=true returns success=false with fingerprint when response is not a JSON object"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/json/35') { params -> '"just a string"' }

        when:
        def result = script.toolGetAppConfig([appId: 35, summary: true])

        then:
        result.success == false
        result.fingerprint == 'top-level not a Map'
    }

    def "summary=true still validates appId before any HTTP call"() {
        given:
        settingsMap.enableRead = true
        // No endpoint registered: reaching HTTP would throw the unstubbed-path
        // error instead of the expected validation exception.

        when:
        script.toolGetAppConfig([appId: 'not-a-number', summary: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    // -------------------------------------------------------------------------
    // Password redaction (issue #105 PR2a #3): type="password" input values must
    // never surface raw, on EITHER the structured inputs path (always returned)
    // or the raw settings map (includeSettings=true). The Hubitat UI masks these
    // inputs; this tool must not de-mask the stored secret into the MCP response.
    // -------------------------------------------------------------------------

    private static String makePasswordConfigJson() {
        makeAppConfigJson([
            configPage: [
                name: 'mainPage', title: 'Credentials', install: true, refreshInterval: null,
                sections: [[
                    title: 'API',
                    input: [
                        [name: 'apiKey', type: 'password', title: 'API Key', defaultValue: 'hunter2-SECRET', required: true],
                        [name: 'username', type: 'text', title: 'Username', defaultValue: 'alice']
                    ],
                    body: []
                ]]
            ],
            settings: [apiKey: 'hunter2-SECRET', username: 'alice']
        ])
    }

    def "redacts password-type input values on the structured inputs path even without includeSettings"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makePasswordConfigJson() }

        when: 'includeSettings is NOT set (default false) -- the structured inputs path still runs'
        def result = script.toolGetAppConfig([appId: 35])

        then: 'the password input is still listed (so the AI knows it is set) but carries no value, as the form engine reads it'
        def pw = result.page.sections[0].inputs.find { it.name == 'apiKey' }
        pw != null
        pw.redacted == true && pw.has_value == true && !pw.containsKey('value')

        and: 'non-password inputs keep their real values'
        result.page.sections[0].inputs.find { it.name == 'username' }.value == 'alice'

        and: 'the secret never appears anywhere in the serialized result'
        !JsonOutput.toJson(result).contains('hunter2-SECRET')
    }

    def "with Hide passwords and credentials off, inputs and settings read back as stored"() {
        given:
        settingsMap.enableRead = true
        settingsMap.redactCredentials = false
        hubGet.register('/installedapp/configure/json/35') { params -> makePasswordConfigJson() }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.page.sections[0].inputs.find { it.name == 'apiKey' }.value == 'hunter2-SECRET'
        result.settings.apiKey == 'hunter2-SECRET'
        !result.containsKey('settingsRedactionNote')
    }

    def "a per-client token sees credentials hidden even with Hide passwords and credentials off"() {
        given:
        settingsMap.enableRead = true
        settingsMap.redactCredentials = false
        script.callerClient = [id: 'c1']
        hubGet.register('/installedapp/configure/json/35') { params -> makePasswordConfigJson() }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        def apiKeyInput = result.page.sections[0].inputs.find { it.name == 'apiKey' }
        apiKeyInput.redacted == true
        !apiKeyInput.containsKey('value')
        !JsonOutput.toJson(result).contains('hunter2-SECRET')

        cleanup:
        script.callerClient = null
    }

    def "redacts password-type keys in the raw settings map when includeSettings=true"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makePasswordConfigJson() }
        registerStatusJson([apiKey: 'password', username: 'text'])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then: 'the password key is present (so the AI knows it is configured) but its value is redacted'
        result.settings.containsKey('apiKey')
        result.settings.apiKey == '***redacted (password)***'

        and: 'non-secret settings are unchanged'
        result.settings.username == 'alice'

        and: 'no secret in the serialized result'
        !JsonOutput.toJson(result).contains('hunter2-SECRET')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch redacts password values on both paths (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makePasswordConfigJson() }
        registerStatusJson([apiKey: 'password', username: 'text'])

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, includeSettings: true])

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.settings.apiKey == '***redacted (password)***'
        def apiKeyInput = inner.page.sections[0].inputs.find { it.name == 'apiKey' }
        apiKeyInput.redacted == true
        !apiKeyInput.containsKey('value')
        !JsonOutput.toJson(inner).contains('hunter2-SECRET')

        where:
        useGateways << [true, false]
    }

    def "redacts a password that appears only in the settings map when the input echoes no value (the common masked case)"() {
        given:
        settingsMap.enableRead = true
        // Hubitat masks type=password fields and often does NOT echo them into the config-page
        // input, yet the secret IS present in the flat settings map. The redaction must still
        // fire (password names are collected regardless of whether the input carries a value).
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson([
            configPage: [
                name: 'mainPage', title: 'Credentials', install: true, refreshInterval: null,
                sections: [[title: 'API', input: [[name: 'apiKey', type: 'password', title: 'API Key', required: true]], body: []]]
            ],
            settings: [apiKey: 'hunter2-SECRET']
        ]) }
        // statusJson knows nothing about the key: the page's own password input still redacts it.
        registerStatusJson([:])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then: 'the settings map redacts the password key even though the input carried no value'
        result.settings.apiKey == '***redacted (password)***'

        and: 'the structured input reports a stored value without reading it'
        def apiKeyInput = result.page.sections[0].inputs.find { it.name == 'apiKey' }
        apiKeyInput.redacted == true
        apiKeyInput.has_value == true
        !apiKeyInput.containsKey('value')

        and: 'no secret anywhere in the result'
        !JsonOutput.toJson(result).contains('hunter2-SECRET')
    }

    // -------------------------------------------------------------------------
    // Cross-page password redaction: a multi-page app declares its passwords on pages the
    // fetched configPage never renders, so the settings map is redacted from statusJson's
    // per-setting types (every page) plus a credential-name fallback.
    // -------------------------------------------------------------------------

    // Hubitat Package Manager's shape: the default page prefOptions is navigation only (no
    // inputs), while hpmPassword -- often the hub admin password -- lives on prefSettings.
    private static String makeHpmConfigJson() {
        makeAppConfigJson([
            app: [id: 35, trueLabel: 'Hubitat Package Manager', label: 'Hubitat Package Manager',
                  name: 'Hubitat Package Manager', disabled: false, installed: true, parentAppId: null,
                  appType: [name: 'Hubitat Package Manager', namespace: 'dcm.hpm', author: 'Dominick Meglio']],
            configPage: [
                name: 'prefOptions', title: 'Package Manager', install: true, refreshInterval: null,
                sections: [[
                    title: '',
                    input: [],
                    body: [[description: 'What would you like to do?']]
                ]]
            ],
            settings: [hpmPassword: 'HubAdmin-S3cret!', hpmUsername: 'admin', hpmSecurity: true,
                       updateMismatchBehavior: 'ignore', linkPhrase: 'xpage-typed-secret'],
            childApps: []
        ])
    }

    private static final Map<String, String> HPM_TYPES = [hpmPassword: 'password', hpmUsername: 'text',
                                                          hpmSecurity: 'bool', updateMismatchBehavior: 'enum',
                                                          linkPhrase: 'password']

    def "a password declared on another page (HPM hpmPassword) is redacted in the settings map"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeHpmConfigJson() }
        registerStatusJson(HPM_TYPES)

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then: 'the fetched page has no inputs at all, yet both password-typed settings are redacted'
        result.success == true
        result.page.sections[0].inputs.isEmpty()
        result.settings.hpmPassword == '***redacted (password)***'

        and: 'a password-typed setting whose name looks harmless is redacted by its hub type alone'
        result.settings.linkPhrase == '***redacted (password)***'

        and: 'non-secret settings are untouched'
        result.settings.hpmUsername == 'admin'
        result.settings.hpmSecurity == true
        result.settings.updateMismatchBehavior == 'ignore'

        and: 'neither secret appears anywhere, and the note describes all-page redaction'
        def json = JsonOutput.toJson(result)
        !json.contains('HubAdmin-S3cret!')
        !json.contains('xpage-typed-secret')
        result.settingsRedactionNote.contains('any page')
        !result.settingsRedactionNote.contains('#')

        and: 'the setting types came from the one statusJson read'
        hubGet.calls.count { it.path == '/installedapp/statusJson/35' } == 1
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch redacts a password declared on another page (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeHpmConfigJson() }
        registerStatusJson(HPM_TYPES)

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, includeSettings: true])

        then:
        response.error == null
        !response.result.isError
        !response.result.content[0].text.contains('HubAdmin-S3cret!')
        !response.result.content[0].text.contains('xpage-typed-secret')
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.settings.hpmPassword == '***redacted (password)***'
        inner.settings.linkPhrase == '***redacted (password)***'
        inner.settings.hpmUsername == 'admin'

        where:
        useGateways << [true, false]
    }

    def "a credential-named setting is redacted even when statusJson does not type it"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson([
            settings: [mqttPassword: 'mqtt-pw-value', cloudApiKey: 'ak-123', authToken: 'tok-456',
                       tokenCount: 7, usePassword: true, passThrough: 'yes', bypassMode: 'strict',
                       emptyPassword: '', ruleEnable: true, disarmPin: '4321', lockCode: 1234, pinCode: '9', userCode: '77',
                       zipCode: '02134', pinnedBaseline: 'b', spinnerSpeed: 'fast', lockCodeLength: 4]
        ]) }
        // mqttPassword, cloudApiKey, authToken: missing from statusJson, so only the name decides.
        registerStatusJson([tokenCount: 'number', usePassword: 'bool', passThrough: 'text', bypassMode: 'enum',
                            emptyPassword: 'text', ruleEnable: 'bool', disarmPin: 'text', lockCode: 'number', pinCode: 'text',
                            userCode: 'text', zipCode: 'text', pinnedBaseline: 'text', spinnerSpeed: 'text', lockCodeLength: 'number'])

        when:
        def s = script.toolGetAppConfig([appId: 35, includeSettings: true]).settings

        then:
        s.mqttPassword == '***redacted (password)***'
        s.cloudApiKey == '***redacted (password)***'
        s.authToken == '***redacted (password)***'

        and: 'PINs and lock or user codes are secrets too'
        s.disarmPin == '***redacted (password)***'
        s.lockCode == '***redacted (password)***'
        s.pinCode == '***redacted (password)***'
        s.userCode == '***redacted (password)***'
        s.zipCode == '02134'
        s.pinnedBaseline == 'b'
        s.spinnerSpeed == 'fast'
        s.lockCodeLength == 4

        and: 'metadata, toggles, look-alike names, and empty values are left as they are'
        s.tokenCount == 7
        s.usePassword == true
        s.passThrough == 'yes'
        s.bypassMode == 'strict'
        s.emptyPassword == ''
        s.ruleEnable == true
    }

    def "a credential-named free-text input on the page is redacted without includeSettings"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson([
            configPage: [
                name: 'mainPage', title: 'Broker', install: true, refreshInterval: null,
                sections: [[
                    title: 'MQTT',
                    input: [
                        [name: 'brokerPassword', type: 'text', title: 'Password', defaultValue: 'plain-text-pw'],
                        [name: 'brokerUser', type: 'text', title: 'User', defaultValue: 'mqttuser'],
                        [name: 'tokenDevice', type: 'capability.switch', title: 'Device'],
                        [name: 'requirePassword', type: 'bool', title: 'Require password', defaultValue: true]
                    ],
                    body: []
                ]]
            ],
            settings: [brokerPassword: 'plain-text-pw', brokerUser: 'mqttuser', tokenDevice: ['5': 'Porch Light']]
        ]) }

        when:
        def result = script.toolGetAppConfig([appId: 35])
        def inputs = result.page.sections[0].inputs.collectEntries { [(it.name): it] }

        then:
        inputs.brokerPassword.value == '***redacted (password)***'
        inputs.brokerUser.value == 'mqttuser'
        inputs.tokenDevice.value == '5' && inputs.tokenDevice.selected == [[value: '5', label: 'Porch Light']]
        inputs.requirePassword.value == true
        !JsonOutput.toJson(result).contains('plain-text-pw')
    }

    @spock.lang.Unroll
    def "raw settings are withheld, not returned partly redacted, when the setting types cannot be read (#why)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeHpmConfigJson() }
        hubGet.register('/installedapp/statusJson/35') { params -> statusBody }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then: 'the config page still comes back'
        result.success == true
        result.page.name == 'prefOptions'
        result.settingsKeyCount == 5

        and: 'the settings map is absent and the note says why'
        !result.containsKey('settings')
        !result.containsKey('settingsRedactionNote')
        result.settingsNote.contains('withheld')
        !JsonOutput.toJson(result).contains('HubAdmin-S3cret!')

        where:
        why                | statusBody
        'empty body'       | ''
        'no appSettings'   | JsonOutput.toJson([installedApp: [id: 35], appState: []])
        'unparseable body' | 'not json'
    }

    def "raw settings are withheld when the setting types leave a non-empty neutral-named value uncovered"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson([
            settings: [customField: 'HubAdmin-S3cret!', ruleEnable: true, authToken: 'tok-456', spare: '', flag: false]
        ]) }
        // customField is missing from statusJson and nothing about its name says secret: the map is withheld.
        registerStatusJson([ruleEnable: 'bool'])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.success == true
        !result.containsKey('settings')
        result.settingsNote.contains('withheld')
        result.settingsNote.contains('customField')
        !result.settingsNote.contains('authToken')
        !JsonOutput.toJson(result).contains('HubAdmin-S3cret!')
        !JsonOutput.toJson(result).contains('tok-456')
    }

    def "a statusJson row with a name but no type does not count as typed, so the map is withheld"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson([
            settings: [customField: 'HubAdmin-S3cret!', ruleEnable: true]
        ]) }
        registerStatusJson([ruleEnable: 'bool', customField: null])

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.success == true
        !result.containsKey('settings')
        result.settingsNote.contains('carries no type for customField')
        !JsonOutput.toJson(result).contains('HubAdmin-S3cret!')
    }

    def "untyped settings that are empty, boolean or credential-named do not withhold the map"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson([
            settings: [authToken: 'tok-456', spare: '', flag: false, ruleEnable: true]
        ]) }
        registerStatusJson([ruleEnable: 'bool'])

        when:
        def s = script.toolGetAppConfig([appId: 35, includeSettings: true]).settings

        then:
        s.authToken == '***redacted (password)***'
        s.spare == ''
        s.flag == false
        s.ruleEnable == true
    }

    def "raw settings are withheld when the statusJson read throws"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeHpmConfigJson() }
        hubGet.register('/installedapp/statusJson/35') { params -> throw new RuntimeException('read timed out') }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.success == true
        !result.containsKey('settings')
        result.settingsNote.contains('withheld')
        !JsonOutput.toJson(result).contains('HubAdmin-S3cret!')
    }

    def "an app with no settings skips the setting-type read"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson([settings: [:]]) }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.settings == [:]
        !result.containsKey('settingsRedactionNote')
        !hubGet.calls.any { it.path.startsWith('/installedapp/statusJson/') }
    }

    @spock.lang.Unroll
    def "_appConfigSecretName('#name') == #expected"() {
        expect:
        script._appConfigSecretName(name) == expected

        where:
        name                    | expected
        // a live login: a session cookie, a session id, a bearer string, a psw
        'hubSecurityCookie'     | true
        'sessionCookies'        | true
        'sessionId'             | true
        'session'               | true
        'bearerToken'           | true
        'bearer'                | true
        'mqttPsw'               | true
        'hubSecurityCookieExpiry' | false
        'sessionCount'          | false
        'cookieTimeout'         | false
        // credential-shaped names
        'hpmPassword'           | true
        'HPMPassword'           | true
        'password'              | true
        'hubpassword'           | true
        'password2'             | true
        'smtp_passwd'           | true
        'routerPwd'             | true
        'mqttPass'              | true
        'MQTT_PASS'             | true
        'passWord'              | true
        'wifiPassphrase'        | true
        'lockPasscode'          | true
        'clientSecret'          | true
        'oauthClientSecret'     | true
        'refresh_token'         | true
        'authtoken'             | true
        'apiTokens'             | true
        'apiKey'                | true
        'API_KEY'               | true
        'apikey'                | true
        'openWeatherApiKey'     | true
        'privateKey'            | true
        'awsAccessKey'          | true
        'credentials'           | true
        'cloudCredential'       | true
        'psk'                   | true
        'accessTokenRefresh'    | true
        // look-alikes and metadata about a secret
        'bypass'                | false
        'bypassDeviceAllowlist' | false
        'byPass'                | false
        'passThrough'           | false
        'passMode'              | false
        'compass'               | false
        'passive'               | false
        'tokenizer'             | false
        'secretary'             | false
        'tokenCount'            | false
        'tokenExpiry'           | false
        'passwordSet'           | false
        'passwordLength'        | false
        'apiKeyCount'           | false
        'tokenUrl'              | false
        // OAuth access tokens are left to the redactAccessTokens setting
        'accessToken'           | false
        'access_token'          | false
        'ACCESS_TOKEN'          | false
        'keypadDevice'          | false
        'monkey'                | false
        'triggerDevice'         | false
        'dm~123~Day'            | false
        ''                      | false
        null                    | false
    }

    @spock.lang.Unroll
    def "_appConfigIsSecretSetting(#name, #type, #value) == #expected"() {
        expect:
        script._appConfigIsSecretSetting(name, type, value) == expected

        where:
        name           | type                | value      | expected
        'linkPhrase'   | 'password'          | 'abc'      | true
        'linkPhrase'   | 'password'          | ''         | false
        'linkPhrase'   | 'password'          | null       | false
        'linkPhrase'   | 'text'              | 'abc'      | false
        'hubPassword'  | null                | 'abc'      | true
        'hubPassword'  | 'text'              | 'abc'      | true
        'hubPassword'  | 'textarea'          | 'abc'      | true
        'lockPin'      | 'number'            | 1234       | true
        'lockCodes'    | 'text'              | '1111'     | true
        'userCodes'    | 'text'              | '2222'     | true
        'userPins'     | 'text'              | '3333'     | true
        'pin'          | 'text'              | '4444'     | true
        'ledPin'       | 'number'            | 2          | false
        'relayPin'     | 'number'            | 4          | false
        'gpioPin'      | 'number'            | 13         | false
        'pinCodeLength' | 'number'           | 4          | false
        'pinMode'      | 'text'              | 'output'   | false
        'sirenPin'     | 'text'              | '5555'     | true
        'keypadInputPin' | 'text'            | '1111'     | true
        'alarmResetPin' | 'number'           | 2222       | true
        'alarmResetCode' | 'text'            | '2323'     | true
        'userpin'      | 'text'              | '3333'     | true
        'alarmcode'    | 'text'              | '4444'     | true
        'HSMPIN'       | 'text'              | '5555'     | true
        'installerCode' | 'text'             | '6666'     | true
        'duressCode'   | 'text'              | '7777'     | true
        'gateCode'     | 'text'              | '8888'     | true
        'zipCode'      | 'text'              | '02139'    | false
        'opcode'       | 'text'              | 'x'        | false
        'doorLedPin'   | 'text'              | '13'       | false
        'garageRelayPin' | 'number'          | 5          | false
        'lockErrorCode' | 'text'             | 'E42'      | false
        'alarmStatusCode' | 'text'           | 'S7'       | false
        'lockPasscode' | 'number'            | 1234       | true
        'apiTokens'    | null                | ['a', 'b'] | true
        'apiTokens'    | null                | []         | false
        'usePassword'  | 'bool'              | true       | false
        'usePassword'  | null                | false      | false
        'passwordMode' | 'enum'              | 'strict'   | false
        'tokenDevice'  | 'capability.switch' | 'Porch'    | false
        'tokenDevice'  | 'device.Switch'     | 'Porch'    | false
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    def "returns success=false when hub returns empty body"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/99') { params -> '' }

        when:
        def result = script.toolGetAppConfig([appId: 99])

        then:
        result.success == false
        result.error != null
        result.error.toLowerCase().contains('empty')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns success=false envelope when hub returns empty body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/99') { params -> '' }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 99])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error != null
        inner.error.toLowerCase().contains('empty')

        where:
        useGateways << [true, false]
    }

    def "returns success=false with fingerprint when app.app is null (unknown appId)"() {
        given:
        settingsMap.enableRead = true
        // Hub returns a 200 body whose top-level object has app=null -- the configure page
        // rendered but found no app. This is the {app:null}-BODY not-found (fingerprint
        // 'missing app'), distinct from the 404/410-THROW not-found below (fingerprint
        // 'app not found (404)') where the page never renders at all.
        hubGet.register('/installedapp/configure/json/9999') { params ->
            JsonOutput.toJson([
                app       : null,
                configPage: [:],
                settings  : [:],
                childApps : []
            ])
        }

        when:
        def result = script.toolGetAppConfig([appId: 9999])

        then:
        result.success == false
        result.error != null
        result.error.toLowerCase().contains('app')
        // Fingerprint distinguishes "app not found" (missing app) from firmware contract drift
        result.fingerprint == 'missing app'
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns success=false envelope for unknown appId (useGateways=#useGateways)"() {
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
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 9999])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error != null
        inner.error.toLowerCase().contains('app')
        inner.fingerprint == 'missing app'

        where:
        useGateways << [true, false]
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
        def result = script.toolGetAppConfig([appId: 9999])

        then:
        result.success == false
        result.fingerprint == 'app not found (404)'
        result.status == 404
        result.appId == 9999
        result.error.toLowerCase().contains('not found')
        // Steers the agent to the cheap identity probe instead of dead-end retries
        result.note.contains('summary')
        result.note.contains("hub_get_tool_guide(section='builtin_app_tools_apps')")
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns a clean 404 not-found envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/9999') { params ->
            throw new FakeHttpException(404)
        }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 9999])

        then: 'a graceful not-found is a tool error result, not a transport error'
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.fingerprint == 'app not found (404)'
        inner.status == 404
        inner.error.toLowerCase().contains('not found')
        inner.note.contains('summary')

        where:
        useGateways << [true, false]
    }

    def "a configure page fetch that throws a 410 is the same clean not-found"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/9999') { params ->
            throw new FakeHttpException(410)
        }

        when:
        def result = script.toolGetAppConfig([appId: 9999])

        then:
        result.success == false
        result.fingerprint == 'app not found (404)'
        result.status == 410
        result.error.contains('not found (HTTP 410')
    }

    def "summary=true returns a clean not-found when the identity fetch throws a 404"() {
        given:
        settingsMap.enableRead = true
        // The thin /installedapp/json record 404s when the app is gone -- same graceful
        // degrade as the full-mode path, via the summary catch block.
        hubGet.register('/installedapp/json/9999') { params ->
            throw new FakeHttpException(404)
        }

        when:
        def result = script.toolGetAppConfig([appId: 9999, summary: true])

        then:
        result.success == false
        result.fingerprint == 'app not found (404)'
        result.status == 404
        result.appId == 9999
        result.error.toLowerCase().contains('not found')
        result.note.contains('summary')
    }

    def "returns success=false with fingerprint when response is not a JSON object"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '"just a string"' }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == false
        result.error != null
        // Production code's fingerprint for "top-level not a Map"
        result.fingerprint == 'top-level not a Map'
        result.note.contains("hub_get_tool_guide(section='builtin_app_tools_apps')")
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns success=false envelope when response is not a JSON object (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '"just a string"' }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error != null
        inner.fingerprint == 'top-level not a Map'

        where:
        useGateways << [true, false]
    }

    def "returns success=false when hub response is unparseable JSON"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '{not valid json' }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == false
        result.error?.toLowerCase()?.contains('parse') == true
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns success=false envelope when hub response is unparseable JSON (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '{not valid json' }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error?.toLowerCase()?.contains('parse') == true

        where:
        useGateways << [true, false]
    }

    def "returns success=false with fingerprint when configPage is missing (app exists but no configPage)"() {
        given:
        settingsMap.enableRead = true
        // Hub returns a well-formed app object but no configPage key -- can happen
        // for apps that have been partially uninstalled or on edge-case firmware builds.
        hubGet.register('/installedapp/configure/json/35') { params ->
            JsonOutput.toJson([
                app      : [id: 35, label: 'My Rule', name: 'Rule-5.1', disabled: false],
                childApps: []
                // configPage intentionally absent
            ])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == false
        result.fingerprint == 'missing configPage'
        result.error?.toLowerCase()?.contains('configpage') == true
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns success=false envelope when configPage is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            JsonOutput.toJson([
                app      : [id: 35, label: 'My Rule', name: 'Rule-5.1', disabled: false],
                childApps: []
            ])
        }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.fingerprint == 'missing configPage'
        inner.error?.toLowerCase()?.contains('configpage') == true

        where:
        useGateways << [true, false]
    }

    def "returns success=false with fingerprint and a page.hrefs note when configPage.sections is not a list"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            JsonOutput.toJson([
                app       : [id: 35, label: 'X', name: 'X', disabled: false],
                configPage: [name: 'mainPage', title: 'X', sections: 'not-a-list'],
                settings  : [:],
                childApps : []
            ])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == false
        result.fingerprint == 'sections not a list'
        // The note must point the agent at the default page's page.hrefs instead of dead-end retries
        result.note.contains('page.hrefs')
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns success=false envelope when configPage.sections is not a list (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            JsonOutput.toJson([
                app       : [id: 35, label: 'X', name: 'X', disabled: false],
                configPage: [name: 'mainPage', title: 'X', sections: 'not-a-list'],
                settings  : [:],
                childApps : []
            ])
        }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.fingerprint == 'sections not a list'
        inner.note.contains('page.hrefs')

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // stripOptionsHtml -- List and Map option shapes
    // -------------------------------------------------------------------------

    def "enum input with List-shape options has HTML stripped from option labels"() {
        given:
        settingsMap.enableRead = true
        // Room Lighting scenes and RM rule references use List<Map> options --
        // each entry is a single-key map {value: label} where label may have HTML badges.
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Mode',
                        input: [
                            [name: 'modeSelect', type: 'enum', title: 'Select mode',
                             description: null, multiple: false, required: false,
                             defaultValue: null, options: [
                                ['day': '<span style="color:blue">Day</span>'],
                                ['night': 'Night']
                             ], value: 'day']
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [modeSelect: 'day'],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        def modeInput = result.page.sections[0].inputs.find { it.name == 'modeSelect' }
        modeInput != null
        // Options read as the form engine lists them, labels without HTML
        modeInput.options == [[value: 'day', label: 'Day'], [value: 'night', label: 'Night']]
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch strips HTML from List-shape enum option labels including data keys (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Mode',
                        input: [
                            [name: 'modeSelect', type: 'enum', title: 'Select mode',
                             description: null, multiple: false, required: false,
                             defaultValue: null, options: [
                                ['day': '<span style="color:blue">Day</span>'],
                                ['night': 'Night'],
                                ['fields': '<b>Lowercase</b>'],
                                ['Fields': '<b>Uppercase</b>'],
                                ['class': '<b>Class data</b>'],
                                ['metaClass': '<b>Metaclass data</b>'],
                                ['getClass': '<b>Method name data</b>'],
                                ['disabled': false, 'count': 0, 'unset': null]
                             ], value: 'day']
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [modeSelect: 'day'],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        def modeInput = inner.page.sections[0].inputs.find { it.name == 'modeSelect' }
        modeInput != null
        and: 'Map keys stay data and every label reads as the text the page shows'
        modeInput.options == [[value: 'day', label: 'Day'], [value: 'night', label: 'Night'], [value: 'fields', label: 'Lowercase'],
                              [value: 'Fields', label: 'Uppercase'], [value: 'class', label: 'Class data'],
                              [value: 'metaClass', label: 'Metaclass data'], [value: 'getClass', label: 'Method name data'],
                              [value: 'disabled', label: 'false'], [value: 'count', label: '0'], [value: 'unset', label: '']]

        where:
        useGateways << [true, false]
    }

    def "enum input with Map-shape options has HTML stripped from option values"() {
        given:
        settingsMap.enableRead = true
        // Some capability inputs encode options as a plain Map {value: label}.
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Scene',
                        input: [
                            [name: 'sceneSelect', type: 'enum', title: 'Select scene',
                             description: null, multiple: false, required: false,
                             defaultValue: null,
                             options: [
                                on : '<span style="color:green">On</span>',
                                off: 'Off',
                                fields: '<b>Lowercase</b>',
                                metaClass: '<b>Metaclass data</b>',
                                disabled: false, count: 0, unset: null
                             ], value: 'on']
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [sceneSelect: 'on'],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        def sceneInput = result.page.sections[0].inputs.find { it.name == 'sceneSelect' }
        sceneInput != null
        // Map keys stay data; labels read without HTML, as the text the page shows
        sceneInput.options == [[value: 'on', label: 'On'], [value: 'off', label: 'Off'], [value: 'fields', label: 'Lowercase'],
                               [value: 'metaClass', label: 'Metaclass data'], [value: 'disabled', label: 'false'],
                               [value: 'count', label: '0'], [value: 'unset', label: '']]
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch strips HTML from Map-shape enum option values (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Scene',
                        input: [
                            [name: 'sceneSelect', type: 'enum', title: 'Select scene',
                             description: null, multiple: false, required: false,
                             defaultValue: null,
                             options: [
                                on : '<span style="color:green">On</span>',
                                off: 'Off'
                             ], value: 'on']
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [sceneSelect: 'on'],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        def sceneInput = inner.page.sections[0].inputs.find { it.name == 'sceneSelect' }
        sceneInput != null
        sceneInput.options == [[value: 'on', label: 'On'], [value: 'off', label: 'Off']]

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // appId type coercion (integer arg passes isInteger check)
    // -------------------------------------------------------------------------

    def "integer appId arg is accepted (passes isInteger after toString)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson()
        }

        when:
        // Pass as native integer rather than string — production does .toString().trim().isInteger()
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        result.app.id == 35
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch accepts integer appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.app.id == 35

        where:
        useGateways << [true, false]
    }

    def "string numeric appId arg '35' is accepted"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson()
        }

        when:
        def result = script.toolGetAppConfig([appId: '35'])

        then:
        result.success == true
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch accepts string numeric appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> makeAppConfigJson() }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: '35'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // bool input sentinel fix (value==true must not be filtered for type="bool")
    // -------------------------------------------------------------------------

    def "bool input with value=true is preserved (not filtered by sentinel)"() {
        given:
        settingsMap.enableRead = true
        // i.value==true is the legitimate "checkbox enabled" state for type="bool".
        // Before the fix, this would be filtered out (sentinel path), making the
        // AI believe the setting was unconfigured.
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Logging',
                        input: [
                            [name: 'logging', type: 'bool', title: 'Enable logging',
                             description: null, multiple: false, required: false,
                             defaultValue: null, options: null, value: true]
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [logging: true],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        def loggingInput = result.page.sections[0].inputs.find { it.name == 'logging' }
        loggingInput != null
        loggingInput.containsKey('value')
        loggingInput.value == true
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch preserves bool input with value=true (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Logging',
                        input: [
                            [name: 'logging', type: 'bool', title: 'Enable logging',
                             description: null, multiple: false, required: false,
                             defaultValue: null, options: null, value: true]
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [logging: true],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        def loggingInput = inner.page.sections[0].inputs.find { it.name == 'logging' }
        loggingInput != null
        loggingInput.containsKey('value')
        loggingInput.value == true

        where:
        useGateways << [true, false]
    }

    def "bool input with value=false is preserved"() {
        given:
        settingsMap.enableRead = true
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Logging',
                        input: [
                            [name: 'logging', type: 'bool', title: 'Enable logging',
                             description: null, multiple: false, required: false,
                             defaultValue: null, options: null, value: false]
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [logging: false],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        def loggingInput = result.page.sections[0].inputs.find { it.name == 'logging' }
        loggingInput != null
        loggingInput.containsKey('value')
        loggingInput.value == false
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch preserves bool input with value=false (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Logging',
                        input: [
                            [name: 'logging', type: 'bool', title: 'Enable logging',
                             description: null, multiple: false, required: false,
                             defaultValue: null, options: null, value: false]
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [logging: false],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        def loggingInput = inner.page.sections[0].inputs.find { it.name == 'logging' }
        loggingInput != null
        loggingInput.containsKey('value')
        loggingInput.value == false

        where:
        useGateways << [true, false]
    }

    def "non-bool input with defaultValue=true is still filtered (sentinel preserved)"() {
        given:
        settingsMap.enableRead = true
        // capability.* inputs use defaultValue=true as a "has configured value" sentinel -- NOT as the
        // selection: with nothing stored the picker reads null (value_source 'default'), never true.
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Devices',
                        input: [
                            [name: 'mySensor', type: 'capability.motionSensor',
                             title: 'Motion sensor', description: 'Kitchen Motion',
                             multiple: false, required: false,
                             defaultValue: true, options: null, value: null]
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [:],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        def sensorInput = result.page.sections[0].inputs.find { it.name == 'mySensor' }
        sensorInput != null
        // Sentinel: a device picker's defaultValue true is never its value; the page fills a picker only from settings
        sensorInput.value == null && sensorInput.value_source == 'default'
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch filters non-bool input with defaultValue=true sentinel (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        def json = JsonOutput.toJson(withElements([
            app       : [id: 35, trueLabel: 'My Rule', label: 'My Rule',
                         name: 'Rule-5.1', disabled: false, installed: true,
                         parentAppId: null, appType: null],
            configPage: [
                name    : 'mainPage',
                title   : 'Settings',
                install : true,
                refreshInterval: null,
                sections: [
                    [
                        title: 'Devices',
                        input: [
                            [name: 'mySensor', type: 'capability.motionSensor',
                             title: 'Motion sensor', description: 'Kitchen Motion',
                             multiple: false, required: false,
                             defaultValue: true, options: null, value: null]
                        ],
                        body: []
                    ]
                ]
            ],
            settings  : [:],
            childApps : []
        ]))
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        def sensorInput = inner.page.sections[0].inputs.find { it.name == 'mySensor' }
        sensorInput != null
        sensorInput.value == null && sensorInput.value_source == 'default'

        where:
        useGateways << [true, false]
    }

    // -------------------------------------------------------------------------
    // Page controls and Rule Machine disabled actions
    // -------------------------------------------------------------------------

    // RM 5.1's thisActionLabel wraps a disabled action in a red italic span (fw 2.5.2; the
    // selectActions header legend uses the same markup). Shape mirrors a live mainPage actions
    // paragraph, including a condition's green (T) span and a *BROKEN* marker, which is red but
    // NOT italic.
    private static final String RM_ACTIONS_PARAGRAPH =
        "IF (Switch on<span style='color:green'>(T)</span>) THEN\n" +
        "\t<span style='color:red'><i>Notify Pushover: 'Fixed mismatch'\n</i></span>" +
        "\tNotify Pushover: 'still cooling'\n" +
        "\tOn: Lamp <span style='color:red'>*BROKEN*</span>\n" +
        "END-IF"

    private static String rmActionsPageJson(String paragraphHtml, String pageName = 'mainPage') {
        makeAppConfigJson([configPage: [name: pageName, title: 'Rule', install: true, refreshInterval: null,
            sections: [[title: 'Actions', input: [], body: [[element: 'paragraph', description: paragraphHtml]]]]]])
    }

    // Verbatim selectActions row (fw 2.5.2 / RM 5.1; a disabled row's disable<N> button reads
    // 'Enable Action' with a checked icon). N is RM's action index, so after
    // removeAction(index:2) on a 3-action rule the rows are 1 and 3 and there is no disable2.
    private static String rmSelectActionsRow(int n, String rowText, String title) {
        "<tr><th><div class='form-group'><input type='hidden' name='chkBox${n}.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' style='color:purple;cursor:pointer;font-size:15px' title='Select for Copy Action'><i class='he-checkbox-unchecked'></i></div></div><input type='hidden' name='settings[chkBox${n}]' value=''></th>" +
        "<td style='text-align:left;min-width:250px' title='Edit Action'><div class='form-group'><input type='hidden' name='${n}.0.false.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' data-stateAttribute='doAct' style='color:purple;cursor:pointer;font-size:15px'>${rowText}</div></div><input type='hidden' name='settings[${n}.0.false]' value='' /></td>" +
        "<td><div class='form-group'><input type='hidden' name='disable${n}.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' style='color:purple;cursor:pointer;font-size:15px' title='${title}'><i class='${title == 'Enable Action' ? 'he-checkbox-checked' : 'he-checkbox-unchecked'}'></i></div></div><input type='hidden' name='settings[disable${n}]' value=''></td></tr>"
    }

    private static final String RM_SELECT_ACTIONS =
        "<table><tr><th>Actions for <b>R</b><span style='color:red'>           <i>Disabled Action</i></span></th></tr>" +
        rmSelectActionsRow(1, "Log: 'one'", 'Disable Action') +
        rmSelectActionsRow(3, "<span style='color:red'><i>Log: 'three'\n</i></span>", 'Enable Action') +
        "</table>"

    def "a Rule Machine paragraph holding a disabled action lists exactly that row's text; others stay plain text"() {
        given:
        settingsMap.enableRead = true
        def p2 = "\tLog: live4\n\tOn: Lamp <span style='color:red'>*BROKEN*</span>"
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'Rule', install: true, refreshInterval: null,
                sections: [
                    [title: 'Actions', input: [], body: [[element: 'paragraph', description: RM_ACTIONS_PARAGRAPH]]],
                    [title: 'More', input: [], body: [[element: 'paragraph', description: p2]]]
                ]]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])
        def para = result.page.sections[0].paragraphs[0]

        then: 'of three actions, only the one in the hub marker is listed; the text is the stripped paragraph, no mark of ours'
        para.disabled == ["Notify Pushover: 'Fixed mismatch'"]
        para.text.contains("\tNotify Pushover: 'Fixed mismatch'\n")
        para.text.contains("\tNotify Pushover: 'still cooling'")
        para.text.contains("On: Lamp *BROKEN*")
        !para.text.contains('[DISABLED]')
        !para.text.contains('<span')

        and: 'a red *BROKEN* span is not the disabled marker'
        result.page.sections[1].paragraphs == ["Log: live4\n\tOn: Lamp *BROKEN*"]

        and: 'the scraped lists are gone'
        !result.containsKey('disabledActions')
        !result.containsKey('disabledActionsNote')
        !result.page.sections[0].containsKey('embeddedActions')
    }

    def "disabled rows read the same when firmware classes the spans, and only while Rule Machine's state lists one"() {
        given: 'the 2.5.3 markup: theme classes instead of inline colors'
        settingsMap.enableRead = true
        String classed = RM_ACTIONS_PARAGRAPH.replace("style='color:red'", "class='text-hubitat-danger'").replace("style='color:green'", "class='text-hubitat-success'")
        hubGet.register('/installedapp/configure/json/35') { params -> rmActionsPageJson(classed) }
        script.metaClass._nrState = { Long id -> [disabledActions: disabled, disabledTriggers: []] }

        when:
        def para = script.toolGetAppConfig([appId: 35]).page.sections[0].paragraphs[0]
        def text = para instanceof Map ? para.text : para

        then:
        (para instanceof Map ? para.disabled : null) == listed
        text.contains("Notify Pushover: 'Fixed mismatch'") && !text.contains('<span')

        where:
        disabled | listed
        ['2']    | ["Notify Pushover: 'Fixed mismatch'"]
        []       | null
    }

    def "italic HTML in a live action's own text is not a disabled row"() {
        given:
        settingsMap.enableRead = true
        String paragraph = "\tLog: '<span style='font-size:20px'><i>Alert</i></span> live'\n" +
            "\t<span class='text-hubitat-danger'><i>Log: 'to disable'\n</i></span>"
        hubGet.register('/installedapp/configure/json/35') { params -> rmActionsPageJson(paragraph) }
        script.metaClass._nrState = { Long id -> [disabledActions: ['2'], disabledTriggers: []] }

        expect:
        script.toolGetAppConfig([appId: 35]).page.sections[0].paragraphs[0].disabled == ["Log: 'to disable'"]
    }

    def "a disabled trigger is listed from the trigger table, without the Disabled Trigger legend"() {
        given:
        settingsMap.enableRead = true
        String table = "<table><tr><th>Trigger Events<span class='text-hubitat-danger'>           <i>Disabled Trigger</i></span></th></tr>" +
            "<tr><td><span class='text-hubitat-danger'><i>Switch A turns on</i></span></td></tr><tr><td>Switch B turns off</td></tr></table>"
        hubGet.register('/installedapp/configure/json/35/selectTriggers') { params -> rmActionsPageJson(table, 'selectTriggers') }
        script.metaClass._nrState = { Long id -> [disabledActions: [], disabledTriggers: ['1']] }

        expect:
        script.toolGetAppConfig([appId: 35, pageName: 'selectTriggers']).page.sections[0].paragraphs[0].disabled == ['Switch A turns on']
    }

    def "disabled rows fall back to the page when the rule state cannot be read"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            def json = new groovy.json.JsonSlurper().parseText(rmActionsPageJson(RM_ACTIONS_PARAGRAPH))
            json.app.name = appName
            groovy.json.JsonOutput.toJson(json)
        }
        int stateReads = 0
        script.metaClass._nrState = { Long id -> stateReads++; throw new IllegalStateException('Native state is unavailable') }

        when:
        def para = script.toolGetAppConfig([appId: 35]).page.sections[0].paragraphs[0]

        then:
        para.disabled == ["Notify Pushover: 'Fixed mismatch'"]
        stateReads == 1

        where:
        appName << ['Rule-5.1', 'Button Rule-5.1']
    }

    def "a Rule Machine main page's action link lists the disabled rows its summary holds"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'Rule', install: true, refreshInterval: null,
                sections: [[title: '', input: [], body: [
                    [element: 'href', page: 'selectTriggers', title: 'Select Trigger Events', description: 'When time is 3:35 PM'],
                    [element: 'href', page: 'selectActions', title: 'Select Actions to Run', description: RM_ACTIONS_PARAGRAPH]
                ]]]]])
        }

        when:
        def hrefs = script.toolGetAppConfig([appId: 35]).page.hrefs

        then:
        hrefs[0] == [page: 'selectTriggers', title: 'Select Trigger Events', description: 'When time is 3:35 PM']
        hrefs[1].disabled == ["Notify Pushover: 'Fixed mismatch'"]
        hrefs[1].description.contains("Notify Pushover: 'still cooling'") && !hrefs[1].description.contains('<span')
    }

    def "selectActions controls name each row's buttons; a disabled action's disable<N> reads 'Enable Action'"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35/selectActions') { params -> rmActionsPageJson(RM_SELECT_ACTIONS, 'selectActions') }

        when:
        def result = script.toolGetAppConfig([appId: 35, pageName: 'selectActions'])
        def buttons = result.controls.findAll { it.kind == 'button' }
        def named = { String n -> buttons.find { it.name == n } }

        then: 'the form engine read the row buttons, keyed by action index (no disable2)'
        buttons*.name == ['chkBox1', '1.0.false', 'disable1', 'chkBox3', '3.0.false', 'disable3']
        buttons.every { it.control_id && it.disabled == false && it.submit_on_change == true }
        named('1.0.false').control_id == '1.0.false||doAct'

        and: 'which action is disabled, and the control that re-enables it'
        named('disable3').title == 'Enable Action'
        named('disable1').title == 'Disable Action'
        named('3.0.false').title.trim() == "Log: 'three'"
        named('1.0.false').title.trim() == "Log: 'one'"

        and: 'the paragraph lists the disabled row, not the legend shown while an action is disabled'
        result.page.sections[0].paragraphs[0].disabled == ["Log: 'three'"]
        !result.page.sections[0].paragraphs[0].text.contains('[DISABLED]')

        and: 'controls use the form tools public shape'
        buttons.every { !it.containsKey('stateAttribute') && !it.containsKey('params') }
    }

    def "a page with no disabled Rule Machine action has plain paragraphs and every disable<N> reads 'Disable Action'"() {
        given:
        settingsMap.enableRead = true
        def html = "<table>" + rmSelectActionsRow(1, "Log: 'one'", 'Disable Action') + "</table>"
        hubGet.register('/installedapp/configure/json/35/selectActions') { params -> rmActionsPageJson(html, 'selectActions') }

        when:
        def result = script.toolGetAppConfig([appId: 35, pageName: 'selectActions'])

        then:
        result.page.sections[0].paragraphs[0] instanceof String
        result.controls.find { it.name == 'disable1' }.title == 'Disable Action'
    }

    def "controls carry an HTML button's title, stateAttribute-keyed control_id and the page's navigation"() {
        given:
        settingsMap.enableRead = true
        def html = "<div class='form-group'><input type='hidden' name='928.type' value='button'></div>" +
            "<div><div class='submitOnChange app-button-link' title='' onclick='buttonClick(this); return false;' " +
            "data-stateAttribute='setManHeat' style='color:red;cursor:pointer;font-size:1em' " +
            "title='Set Heating Setpoint'>68°</div></div><input type='hidden' name='settings[928]' value=''>"
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([
                app: [id: 35, trueLabel: 'Tstat', label: 'Tstat', name: 'Thermostat Controller', disabled: false, installed: true,
                      appType: [name: 'Thermostat Controller', namespace: 'hubitat']],
                configPage: [name: 'mainPage', title: 'Tstat', install: true, refreshInterval: null,
                    sections: [[title: 'Main', input: [], body: [[element: 'paragraph', description: html]]]]]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.controls.find { it.name == '928' } == [name: '928', kind: 'button', title: 'Set Heating Setpoint', disabled: false,
                                                      submit_on_change: true, control_id: '928||setManHeat']
        result.controls.find { it.kind == 'navigation' }.control_id == '_action_update|Done|'
    }

    @spock.lang.Unroll
    def "RM's raw less-than comparator survives tag stripping (#cmp) in paragraphs and control titles"() {
        given:
        settingsMap.enableRead = true
        // RM renders the comparator as a raw `<` inside span markup (captured from a hub log line);
        // a bare `<[^>]+>` tag pattern swallowed "< 100<span ...>" as one tag (issue #508).
        def cond = "IF (Variable local<span style='color:black'>(0)</span> is ${cmp} 100<span style='color:green'>(T)</span><span style='color:green'> [TRUE]</span>) THEN"
        def html = "${cond}\n" +
            "<input type='hidden' name='1.0.true.type' value='button'>" +
            "<div class='submitOnChange' onclick='buttonClick(this)' data-stateAttribute='doAct' style='color:purple'>${cond}</div>" +
            "<input type='hidden' name='settings[1.0.true]' value=''>"
        hubGet.register('/installedapp/configure/json/35/selectActions') { params -> rmActionsPageJson(html, 'selectActions') }

        when:
        def result = script.toolGetAppConfig([appId: 35, pageName: 'selectActions'])

        then:
        result.page.sections[0].paragraphs[0].contains("IF (Variable local(0) is ${cmp} 100(T) [TRUE]) THEN")
        result.controls.find { it.name == '1.0.true' }.title == "IF (Variable local(0) is ${cmp} 100(T) [TRUE]) THEN"

        and: 'the direct helper agrees'
        script.stripAppConfigHtml(cond) == "IF (Variable local(0) is ${cmp} 100(T) [TRUE]) THEN"

        where:
        cmp << ['<', '<=']
    }

    def "a non-RM app's red italic span is ordinary emphasis, not a disabled flag"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([
                app: [id: 35, trueLabel: 'Hall', label: 'Hall', name: 'Room Lights', disabled: false, installed: true,
                      appType: [name: 'Room Lights', namespace: 'hubitat']],
                configPage: [name: 'mainPage', title: 'Hall', install: true, refreshInterval: null,
                    sections: [[title: 'Main', input: [], body: [[element: 'paragraph', description: "<span style='color:red'><i>Warning: battery low\n</i></span>"]]]]]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        result.page.sections[0].paragraphs == ['Warning: battery low']
    }

    def "a page the form engine cannot model still reads its inputs' identity, with formError instead of controls and no values"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson([configPage: [name: 'mainPage', title: 'Rule', install: true, refreshInterval: null, sections: [[title: 'S', input: [
                [element: 'input', name: 'dup', type: 'text', title: 'First'],
                [element: 'input', name: 'dup', type: 'text', title: 'Second']], body: []]]]])
        }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        result.page.sections[0].inputs == [[name: 'dup', type: 'text', title: 'First']]
        !result.containsKey('controls')
        result.formError == 'Native form contains ambiguous fields'
        result.note.contains('Input values and controls are withheld') && result.note.contains('includeSettings=true')
    }

    @spock.lang.Unroll
    def "_rmIsRuleAppType('#namespace', '#name') == #expected"() {
        expect:
        script._rmIsRuleAppType(namespace, name) == expected

        where:
        namespace | name               | expected
        'hubitat' | 'Rule-5.1'         | true
        'hubitat' | 'Button Rule-5.1'  | true
        'hubitat' | 'Rule-6.0'         | true
        'hubitat' | 'Rule Machine'     | false
        'hubitat' | 'Room Lights'      | false
        'hubitat' | 'My Rule-5.1'      | false
        'hubitat' | 'rule-5.1'         | false
        'hubitat' | null               | false
        'acme'    | 'Rule-5.1'         | false
        null      | 'Rule-5.1'         | false
    }

    @spock.lang.Unroll
    def "hub_get_app_config via dispatch returns controls and the disabled rows (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/35/selectActions') { params -> rmActionsPageJson(RM_SELECT_ACTIONS, 'selectActions') }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, pageName: 'selectActions'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.page.sections[0].paragraphs[0].disabled == ["Log: 'three'"]
        inner.controls.find { it.name == 'disable3' }.title == 'Enable Action'

        where:
        useGateways << [true, false]
    }
}
