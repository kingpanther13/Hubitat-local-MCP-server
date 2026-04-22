package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolListAppPages (hubitat-mcp-server.groovy).
 * Gateway tool under manage_installed_apps -- executeTool() dispatches via case "list_app_pages".
 *
 * Covers:
 *  - Hub Admin Read gate (throws when disabled)
 *  - Missing appId validation (throws before HTTP)
 *  - Blank appId validation (throws before HTTP)
 *  - Non-numeric appId validation (throws before HTTP)
 *  - Golden path HPM: pages list contains all curated HPM pages
 *  - Golden path RM rule: single-page result with note
 *  - Unknown app type: single-page result with uncurated note
 *  - Empty hub response: returns success=false
 *  - Unknown appId (hub returns {app:null}): fingerprint check fires, success=false
 */
class ToolListAppPagesSpec extends ToolSpecBase {

    // Helper: build a minimal hub response for /installedapp/configure/json/<id>
    private static String makeAppJson(String appTypeName, String primaryPageName = 'mainPage', String pageTitle = 'Settings') {
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
                sections: []
            ],
            settings : [:],
            childApps: []
        ]
        return JsonOutput.toJson(data)
    }

    // -------------------------------------------------------------------------
    // Gate enforcement
    // -------------------------------------------------------------------------

    def "throws when Hub Admin Read is disabled"() {
        given:
        settingsMap.enableHubAdminRead = false

        when:
        script.toolListAppPages([appId: 35])

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

        when:
        script.toolListAppPages([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('appid is required')
    }

    def "throws when appId is blank string"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolListAppPages([appId: '   '])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('appid is required')
    }

    def "throws when appId is non-numeric (no HTTP call made)"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Do NOT register the hub endpoint -- if validation fails to fire,
        // HubInternalGetMock will throw an unregistered-path error, which
        // surfaces as a different failure than the expected IAE.

        when:
        script.toolListAppPages([appId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    // -------------------------------------------------------------------------
    // Golden path -- HPM (curated multi-page directory)
    // -------------------------------------------------------------------------

    def "golden path HPM: returns all curated HPM page names"() {
        given:
        settingsMap.enableHubAdminRead = true
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

    // -------------------------------------------------------------------------
    // Golden path -- Rule Machine rule (single-page)
    // -------------------------------------------------------------------------

    def "golden path RM rule: returns single mainPage with single-page note"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Rule-5.1', 'mainPage', 'My Rule')
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
        result.pages.size() == 1
        result.pages[0].name == 'mainPage'
        result.pages[0].role == 'primary'
        result.note != null
        result.note.toLowerCase().contains('single')
    }

    // -------------------------------------------------------------------------
    // Unknown app type -- uncurated note
    // -------------------------------------------------------------------------

    def "unknown app type: returns primary page only with uncurated note"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('MyCustomCommunityApp', 'mainPage', 'Custom Settings')
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
        result.pages.size() == 1
        result.pages[0].name == 'mainPage'
        // Note must hint that directory is not curated
        result.note != null
        result.note.toLowerCase().contains('not curated') || result.note.toLowerCase().contains('uncurated') || result.note.toLowerCase().contains('not available') || result.note.contains('only primary page known')
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    def "returns success=false when hub returns empty body"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/configure/json/99') { params -> '' }

        when:
        def result = script.toolListAppPages([appId: 99])

        then:
        result.success == false
        result.error != null
        result.error.toLowerCase().contains('empty')
    }

    def "returns success=false when hub returns app=null (unknown appId)"() {
        given:
        settingsMap.enableHubAdminRead = true
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
    }

    // -------------------------------------------------------------------------
    // appId type coercion (integer arg passes isInteger check)
    // -------------------------------------------------------------------------

    def "integer appId is accepted (passes isInteger after toString)"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/configure/json/35') { params ->
            makeAppJson('Hubitat Package Manager', 'prefOptions', 'Main Menu')
        }

        when:
        def result = script.toolListAppPages([appId: 35])

        then:
        result.success == true
    }
}
