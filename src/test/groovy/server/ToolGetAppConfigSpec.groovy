package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolGetAppConfig (hubitat-mcp-server.groovy approx line 6232).
 * Gateway tool under manage_installed_apps — executeTool() dispatches via case "get_app_config".
 *
 * Covers:
 *  - Hub Admin Read gate (throws when disabled)
 *  - Missing appId validation (throws before HTTP)
 *  - Non-numeric appId validation (throws before HTTP)
 *  - pageName sanitization (throws on path-separator characters)
 *  - Golden path: single-page app with sections, inputs, child apps
 *  - pageName arg: URL includes the page segment
 *  - includeSettings=false (default): settings key absent, settingsNote present
 *  - includeSettings=true: settings key populated
 *  - Empty hub response: returns success=false
 *  - Unknown appId (hub returns {app:null}): fingerprint check fires, success=false
 *  - HTML stripping: span tags removed from labels
 */
class ToolGetAppConfigSpec extends ToolSpecBase {

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
        return JsonOutput.toJson(base)
    }

    // -------------------------------------------------------------------------
    // Gate enforcement
    // -------------------------------------------------------------------------

    def "throws when Hub Admin Read is disabled"() {
        given:
        settingsMap.enableHubAdminRead = false

        when:
        script.toolGetAppConfig([appId: 35])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    // -------------------------------------------------------------------------
    // Input validation (all must throw before any HTTP call)
    // -------------------------------------------------------------------------

    def "throws when appId is missing from args"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Do NOT register the endpoint; if HTTP fires the HubInternalGetMock throws,
        // exposing a different failure mode than the expected validation exception.

        when:
        script.toolGetAppConfig([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('appid is required')
    }

    def "throws when appId is blank string"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetAppConfig([appId: '   '])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('appid is required')
    }

    def "throws when appId is non-numeric (no HTTP call made)"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Do NOT register the hub endpoint — if the code reaches HTTP before
        // validation, HubInternalGetMock will throw an unregistered-path error,
        // which would surface as a different failure than the expected IAE.

        when:
        script.toolGetAppConfig([appId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    def "throws when pageName contains a path separator"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetAppConfig([appId: 35, pageName: '../etc/passwd'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pagename')
    }

    def "throws when pageName contains a space"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetAppConfig([appId: 35, pageName: 'page name'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pagename')
    }

    // -------------------------------------------------------------------------
    // Golden path — single-page app
    // -------------------------------------------------------------------------

    def "golden path: returns app identity, sections, inputs, and child apps"() {
        given:
        settingsMap.enableHubAdminRead = true

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

        and: 'endpoint field present for debugging'
        result.endpoint == '/installedapp/configure/json/35'
    }

    def "golden path: hub endpoint includes pageName segment when supplied"() {
        given:
        settingsMap.enableHubAdminRead = true

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

    // -------------------------------------------------------------------------
    // HTML stripping
    // -------------------------------------------------------------------------

    def "HTML span tags in app label are stripped from the response"() {
        given:
        settingsMap.enableHubAdminRead = true

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

    // -------------------------------------------------------------------------
    // includeSettings flag
    // -------------------------------------------------------------------------

    def "includeSettings=false (default): settings key absent, settingsNote present when count > 0"() {
        given:
        settingsMap.enableHubAdminRead = true
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
    }

    def "includeSettings=true: settings map present and populated"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson()
        }

        when:
        def result = script.toolGetAppConfig([appId: 35, includeSettings: true])

        then:
        result.success == true
        result.containsKey('settings')
        result.settings instanceof Map
        result.settings.size() == 2
        result.settings.triggerDevice == 'Kitchen Switch'
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    def "returns success=false when hub returns empty body"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/configure/json/99') { params -> '' }

        when:
        def result = script.toolGetAppConfig([appId: 99])

        then:
        result.success == false
        result.error != null
        result.error.toLowerCase().contains('empty')
    }

    def "returns success=false with fingerprint when app.app is null (unknown appId)"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Hub returns top-level object but with app=null — matches what Hubitat
        // returns for an unknown/deleted appId.
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

    def "returns success=false with fingerprint when response is not a JSON object"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '"just a string"' }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == false
        result.error != null
        // Production code's fingerprint for "top-level not a Map"
        result.fingerprint == 'top-level not a Map'
    }

    def "returns success=false when hub response is unparseable JSON"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/configure/json/35') { params -> '{not valid json' }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == false
        result.error?.toLowerCase()?.contains('parse') == true
    }

    def "returns success=false with fingerprint when configPage is missing (app exists but no configPage)"() {
        given:
        settingsMap.enableHubAdminRead = true
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

    def "returns success=false with fingerprint and list_app_pages hint when configPage.sections is not a list"() {
        given:
        settingsMap.enableHubAdminRead = true
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
        // Note must guide the agent toward list_app_pages and away from dead-end retries
        result.error.contains('list_app_pages')
    }

    // -------------------------------------------------------------------------
    // stripOptionsHtml -- List and Map option shapes
    // -------------------------------------------------------------------------

    def "enum input with List-shape options has HTML stripped from option labels"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Room Lighting scenes and RM rule references use List<Map> options --
        // each entry is a single-key map {value: label} where label may have HTML badges.
        def json = JsonOutput.toJson([
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
        ])
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        def modeInput = result.page.sections[0].inputs.find { it.name == 'modeSelect' }
        modeInput != null
        modeInput.options instanceof List
        // HTML stripped from 'Day' label
        def dayEntry = modeInput.options.find { it.containsKey('day') }
        dayEntry != null
        dayEntry['day'] == 'Day'
        !dayEntry['day'].contains('<span')
    }

    def "enum input with Map-shape options has HTML stripped from option values"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Some capability inputs encode options as a plain Map {value: label}.
        def json = JsonOutput.toJson([
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
        ])
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        def sceneInput = result.page.sections[0].inputs.find { it.name == 'sceneSelect' }
        sceneInput != null
        sceneInput.options instanceof Map
        // HTML stripped from 'On' label
        sceneInput.options['on'] == 'On'
        !sceneInput.options['on'].contains('<span')
    }

    // -------------------------------------------------------------------------
    // appId type coercion (integer arg passes isInteger check)
    // -------------------------------------------------------------------------

    def "integer appId arg is accepted (passes isInteger after toString)"() {
        given:
        settingsMap.enableHubAdminRead = true
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

    def "string numeric appId arg '35' is accepted"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppConfigJson()
        }

        when:
        def result = script.toolGetAppConfig([appId: '35'])

        then:
        result.success == true
    }

    // -------------------------------------------------------------------------
    // bool input sentinel fix (value==true must not be filtered for type="bool")
    // -------------------------------------------------------------------------

    def "bool input with value=true is preserved (not filtered by sentinel)"() {
        given:
        settingsMap.enableHubAdminRead = true
        // i.value==true is the legitimate "checkbox enabled" state for type="bool".
        // Before the fix, this would be filtered out (sentinel path), making the
        // AI believe the setting was unconfigured.
        def json = JsonOutput.toJson([
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
        ])
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

    def "bool input with value=false is preserved"() {
        given:
        settingsMap.enableHubAdminRead = true
        def json = JsonOutput.toJson([
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
        ])
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

    def "non-bool input with defaultValue=true is still filtered (sentinel preserved)"() {
        given:
        settingsMap.enableHubAdminRead = true
        // capability.* inputs use defaultValue=true as a "has configured value" sentinel --
        // NOT as the actual selection. The output's value field should be absent for these.
        def json = JsonOutput.toJson([
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
        ])
        hubGet.register('/installedapp/configure/json/35') { params -> json }

        when:
        def result = script.toolGetAppConfig([appId: 35])

        then:
        result.success == true
        def sensorInput = result.page.sections[0].inputs.find { it.name == 'mySensor' }
        sensorInput != null
        // Sentinel: value must NOT be emitted as bare 'true' for non-bool capability types
        !sensorInput.containsKey('value')
    }
}
