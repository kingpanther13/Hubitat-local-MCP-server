package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolListHpmPackages and toolGetHpmDrift.
 * Gateway: manage_hpm -> list_hpm_packages / get_hpm_drift.
 *
 * HPM stores its tracked packages in state.manifests. The /installedapp/statusJson
 * endpoint returns this as appState[].value, which is typically a Map on live hubs
 * (JsonSlurper recursively parses the inner JSON) but may be a JSON-encoded String
 * on older firmware. Both shapes are handled and tested.
 *
 * Both tools share private helpers (_hpmDiscoverAppId, _hpmAssertAppIsHpm,
 * _hpmFetchManifests) tested indirectly through the public tool methods.
 */
class ToolHpmReadonlySpec extends ToolSpecBase {

    // -------------------------------------------------------------------------
    // Fixture builders
    // -------------------------------------------------------------------------

    /**
     * Minimal appsList response with HPM as a user app.
     * Discovery matches on data.type == "Hubitat Package Manager" in the apps[] tree.
     * userAppTypes[] does NOT carry a namespace field on real hubs -- the fixture
     * reflects real hub data shape (id + name only, no namespace).
     * The app-type-catalog id (101) differs from the installed-instance id (hpmAppId)
     * to ensure the implementation returns the instance id, not the type-catalog id.
     */
    private static String makeAppsListWithHpm(String hpmAppId = "37") {
        JsonOutput.toJson([
            systemAppTypes: [],
            userAppTypes  : [
                [id: "101", name: "Hubitat Package Manager"]
            ],
            apps: [
                [
                    data    : [id: hpmAppId, name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true, disabled: false, hidden: false, appTypeId: "101"],
                    children: []
                ]
            ]
        ])
    }

    /**
     * Minimal appsList response confirming a given id is HPM, with no other user apps.
     * Used by explicit-hpmAppId tests that only need the type-assertion to pass
     * and do not require userAppTypes[] entries for orphan-detection.
     */
    private static String makeAppsListWithHpmOnly(String hpmAppId = "37") {
        JsonOutput.toJson([
            systemAppTypes: [],
            userAppTypes  : [],
            apps: [
                [
                    data    : [id: hpmAppId, name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true, disabled: false, hidden: false],
                    children: []
                ]
            ]
        ])
    }

    /**
     * /hub2/userAppTypes response: flat JSON array of code definitions.
     * Each entry has at minimum an id field. This is the Apps Code registry
     * (what list_hub_apps uses), not the installed-instances tree in appsList.
     * Child-app templates appear here even with zero running instances.
     */
    private static String makeUserAppTypes(List<String> ids = []) {
        JsonOutput.toJson(ids.collect { id -> [id: id, name: "App ${id}", namespace: "test"] })
    }

    /**
     * /hub2/userDeviceTypes response: flat JSON array of driver code definitions.
     * Despite the name, this is the Drivers Code registry (parallel role to /hub2/userAppTypes
     * for apps). Used by get_hpm_drift for orphan-driver detection.
     */
    private static String makeUserDriverTypes(List<String> ids = []) {
        JsonOutput.toJson(ids.collect { id -> [id: id, name: "Driver ${id}", namespace: "test"] })
    }

    /** appsList with no HPM entry. */
    private static String makeAppsListNoHpm() {
        JsonOutput.toJson([
            systemAppTypes: [],
            userAppTypes  : [[id: "200", name: "Some Other App"]],
            apps: [
                [
                    data    : [id: "50", name: "Some Other App", type: "Other", user: true, disabled: false, hidden: false, appTypeId: "200"],
                    children: []
                ]
            ]
        ])
    }

    /**
     * Build a statusJson response where appState[].value is a Map (already-parsed shape).
     * This matches the live-hub shape: the outer JsonSlurper recursively parses the
     * inner JSON string, so entry.value arrives as a Map, not a String.
     * Use this fixture for happy-path tests.
     */
    private static String makeHpmStatusJson(String hpmAppId, Map manifests) {
        // value is the Map directly -- JsonOutput will serialize it as a nested JSON object,
        // and the outer JsonSlurper will parse it back to a Map, matching real hub behavior.
        JsonOutput.toJson([
            id      : hpmAppId as Integer,
            appState: [
                [name: "manifests", value: manifests]
            ],
            appSettings: []
        ])
    }

    /**
     * Build a statusJson response where appState[].value is a JSON-encoded String
     * (double-encoded / fallback shape). Exercises the String branch of the type-aware decode.
     */
    private static String makeHpmStatusJsonStringValue(String hpmAppId, Map manifests) {
        def manifestsJson = JsonOutput.toJson(manifests)
        JsonOutput.toJson([
            id      : hpmAppId as Integer,
            appState: [
                [name: "manifests", value: manifestsJson]
            ],
            appSettings: []
        ])
    }

    /** statusJson with no manifests entry (HPM installed but no packages tracked). */
    private static String makeHpmStatusJsonEmpty(String hpmAppId) {
        JsonOutput.toJson([
            id      : hpmAppId as Integer,
            appState: [],
            appSettings: []
        ])
    }

    /** One fully-populated package with one app, one driver, one file. */
    private static Map onePackageManifests() {
        [
            "https://raw.githubusercontent.com/foo/bar/main/packageManifest.json": [
                packageName: "BOND Home Integration",
                version    : "1.1.2",
                beta       : false,
                author     : "Dominick Meglio",
                apps: [
                    [id: "bf7c20fb-0001-0001-0001-aabbccddeeff", name: "BOND Home App", namespace: "mavrrick", location: "https://raw.githubusercontent.com/foo/bar/main/bondApp.groovy", required: true, version: null, heID: "142"]
                ],
                drivers: [
                    [id: "163f6821-0001-0001-0001-aabbccddeeff", name: "BOND Fan", namespace: "mavrrick", location: "https://raw.githubusercontent.com/foo/bar/main/bondDriver.groovy", required: true, version: "1.0.3", heID: "89"]
                ],
                files: [
                    [id: "3f7ffb28-0001-0001-0001-aabbccddeeff", name: "bond.js"]
                ]
            ]
        ]
    }

    // -------------------------------------------------------------------------
    // list_hpm_packages: Hub Admin Read gate
    // -------------------------------------------------------------------------

    def "list_hpm_packages throws when Hub Admin Read is disabled"() {
        given:
        settingsMap.enableHubAdminRead = false

        when:
        script.toolListHpmPackages([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    // -------------------------------------------------------------------------
    // list_hpm_packages: explicit hpmAppId validation
    // -------------------------------------------------------------------------

    def "list_hpm_packages throws when hpmAppId is non-numeric"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolListHpmPackages([hpmAppId: "not-a-number"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("hpmAppId must be numeric")
    }

    def "list_hpm_packages throws when explicit hpmAppId exists but belongs to a different app type"() {
        given:
        settingsMap.enableHubAdminRead = true
        // App 999 exists but is type "Simple Automation Rules", not HPM
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "999", name: "Simple Automations", type: "Simple Automation Rules", user: true], children: []]
                ]
            ])
        }

        when:
        script.toolListHpmPackages([hpmAppId: "999"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("999")
        // Must name the actual type so the caller knows what they hit
        ex.message.contains("actual type: Simple Automation Rules")
    }

    def "list_hpm_packages throws when explicit hpmAppId does not exist in installed apps"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Installed apps list has no entry with id "998"
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }

        when:
        script.toolListHpmPackages([hpmAppId: "998"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("998")
        ex.message.contains("not found in installed apps -- verify the ID or omit hpmAppId to use auto-discovery")
    }

    // -------------------------------------------------------------------------
    // list_hpm_packages: auto-discovery
    // -------------------------------------------------------------------------

    def "list_hpm_packages auto-discovers hpmAppId when not supplied"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpm("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }

        when:
        def result = script.toolListHpmPackages([:])

        then:
        result.success == true
        result.hpmAppId == "37"
    }

    /**
     * Regression: discovery must return the installed-instance id from apps[].data.id ("37"),
     * NOT the app-type-catalog id from userAppTypes[].id ("101"). Both are present in the
     * fixture. The broken implementation incorrectly matched via userAppTypes[] and would have
     * returned "101" or failed outright (real userAppTypes[] has no namespace field).
     */
    def "list_hpm_packages discovery returns installed-instance id, not app-type-catalog id"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Fixture: type-catalog id = "101", installed-instance id = "37"
        hubGet.register('/hub2/appsList') { makeAppsListWithHpm("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }

        when:
        def result = script.toolListHpmPackages([:])

        then: 'returns the installed-instance id (37), not the type-catalog id (101)'
        result.success == true
        result.hpmAppId == "37"
        result.hpmAppId != "101"
    }

    def "list_hpm_packages throws when HPM is not installed"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListNoHpm() }

        when:
        script.toolListHpmPackages([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Hubitat Package Manager does not appear to be installed")
    }

    // -------------------------------------------------------------------------
    // list_hpm_packages: golden path with explicit hpmAppId
    // -------------------------------------------------------------------------

    def "list_hpm_packages golden path: returns single package with apps, drivers, and files"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'top-level success and count'
        result.success == true
        result.hpmAppId == "37"
        result.count == 1
        result.packages.size() == 1

        and: 'package-level fields'
        def pkg = result.packages[0]
        pkg.packageName == "BOND Home Integration"
        pkg.version == "1.1.2"
        pkg.beta == false
        pkg.author == "Dominick Meglio"
        pkg.manifestUrl == "https://raw.githubusercontent.com/foo/bar/main/packageManifest.json"

        and: 'app component'
        pkg.apps.size() == 1
        pkg.apps[0].name == "BOND Home App"
        pkg.apps[0].heID == "142"
        pkg.apps[0].required == true
        pkg.apps[0].version == null
        // Canonical heID must not emit _warning -- a regression that always-fires the warning branch is silent without this pin
        !pkg.apps[0].containsKey('_warning')

        and: 'driver component with per-component version'
        pkg.drivers.size() == 1
        pkg.drivers[0].name == "BOND Fan"
        pkg.drivers[0].heID == "89"
        pkg.drivers[0].version == "1.0.3"
        !pkg.drivers[0].containsKey('_warning')

        and: 'file component (no heID field)'
        pkg.files.size() == 1
        pkg.files[0].name == "bond.js"
        !pkg.files[0].containsKey('heID')
    }

    /**
     * Regression: _hpmFetchManifests must handle appState[].value as a JSON-encoded String
     * (double-encoded / fallback path). Real hubs return a Map (outer JsonSlurper parses it);
     * the String path exists for any hub variant that leaves the inner value as a String.
     * Both paths must produce the same package list.
     */
    def "list_hpm_packages handles appState value as JSON-encoded String (double-encoded fallback path)"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        // makeHpmStatusJsonStringValue encodes manifests as a JSON string inside the outer JSON
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJsonStringValue("37", onePackageManifests()) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'String-value path produces the same output as the Map-value path'
        result.success == true
        result.count == 1
        result.packages[0].packageName == "BOND Home Integration"
        result.packages[0].apps[0].heID == "142"
    }

    def "list_hpm_packages handles multiple packages and returns correct count"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = onePackageManifests() + [
            "https://raw.githubusercontent.com/baz/qux/main/packageManifest.json": [
                packageName: "Second Package",
                version    : "2.0.0",
                beta       : true,
                author     : "Someone Else",
                apps: [], drivers: [], files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        result.count == 2
        result.packages.size() == 2
        result.packages.any { it.packageName == "BOND Home Integration" }
        result.packages.any { it.packageName == "Second Package" }
    }

    def "list_hpm_packages handles package with no apps or drivers or files"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/empty/packageManifest.json": [
                packageName: "Empty Package",
                version    : "0.1.0",
                beta       : false,
                author     : "Test",
                apps: [], drivers: [], files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        result.count == 1
        def pkg = result.packages[0]
        pkg.apps == []
        pkg.drivers == []
        pkg.files == []
    }

    // -------------------------------------------------------------------------
    // list_hpm_packages: no packages tracked (empty appState)
    // -------------------------------------------------------------------------

    def "list_hpm_packages returns count=0 when no manifests entry in appState"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJsonEmpty("37") }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        result.count == 0
        result.packages == []
    }

    // -------------------------------------------------------------------------
    // list_hpm_packages: error paths
    // -------------------------------------------------------------------------

    def "list_hpm_packages returns success=false when statusJson body is empty"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { '' }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == false
        result.error != null
        result.error.contains("Empty response from /installedapp/statusJson")
    }

    def "list_hpm_packages returns success=false when manifests value is not parseable JSON"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') {
            JsonOutput.toJson([
                id: 37,
                appState: [[name: "manifests", value: "{not valid json"]],
                appSettings: []
            ])
        }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == false
        result.error != null
        // Pin the specific parse-path tail to distinguish from the statusJson outer-parse branch
        result.error.contains("Failed to parse HPM manifests value")
    }

    def "list_hpm_packages returns success=false with type disclosure when statusJson outer parse yields non-Map"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        // Return a JSON array -- parseText succeeds but outer is a List, not a Map
        hubGet.register('/installedapp/statusJson/37') { '[{"x":1}]' }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == false
        result.error != null
        result.error.contains("Unexpected HPM statusJson shape")
        result.error.contains("got List")
    }

    def "list_hpm_packages returns success=false with type disclosure when manifests value parses to non-Map"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        // manifests value is a JSON array string -- parseText succeeds but yields List, not Map
        hubGet.register('/installedapp/statusJson/37') {
            JsonOutput.toJson([
                id: 37,
                appState: [[name: "manifests", value: '[{"url":"https://example.com"}]']],
                appSettings: []
            ])
        }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == false
        result.error != null
        result.error.contains("Unexpected HPM manifests shape")
        result.error.contains("got List")
    }

    // -------------------------------------------------------------------------
    // list_hpm_packages: heID type coercion
    // -------------------------------------------------------------------------

    def "list_hpm_packages coerces Integer heID to String"() {
        given:
        settingsMap.enableHubAdminRead = true
        // HPM sometimes stores heID as an Integer (from Location header parsing).
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "IntID Package",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // heID as a raw Integer -- not a String
                apps: [[id: "uuid-1", name: "Some App", namespace: "ns", location: "loc", required: true, version: null, heID: 999]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        def app = result.packages[0].apps[0]
        app.heID instanceof String
        app.heID == "999"
        // Integer heID is a valid scalar -- must NOT trigger the non-scalar _warning path
        !app.containsKey('_warning')
    }

    def "list_hpm_packages coerces Integer heID to String on a driver component"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "IntID Driver Package",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // heID as a raw Integer on a driver entry
                drivers: [[id: "uuid-d1", name: "Some Driver", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: 123]],
                files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        def driver = result.packages[0].drivers[0]
        driver.heID instanceof String
        driver.heID == "123"
    }

    def "list_hpm_packages represents null heID as null (not string 'null')"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Optional Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [[id: "uuid-2", name: "Optional App", namespace: "ns", location: "loc", required: false, version: null, heID: null]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        result.packages[0].apps[0].heID == null
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: Hub Admin Read gate
    // -------------------------------------------------------------------------

    def "get_hpm_drift throws when Hub Admin Read is disabled"() {
        given:
        settingsMap.enableHubAdminRead = false

        when:
        script.toolGetHpmDrift([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "get_hpm_drift throws when hpmAppId is non-numeric"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHpmDrift([hpmAppId: "not-a-number"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("hpmAppId must be numeric")
    }

    def "get_hpm_drift throws when explicit hpmAppId does not exist in installed apps"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Installed apps list has no entry with id "998"
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }

        when:
        script.toolGetHpmDrift([hpmAppId: "998"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("998")
        ex.message.contains("not found in installed apps -- verify the ID or omit hpmAppId to use auto-discovery")
    }

    def "get_hpm_drift throws when explicit hpmAppId belongs to a different app type"() {
        given:
        settingsMap.enableHubAdminRead = true
        // App 999 exists but is type "Simple Automation Rules", not HPM
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "999", name: "Simple Automations", type: "Simple Automation Rules", user: true], children: []]
                ]
            ])
        }

        when:
        script.toolGetHpmDrift([hpmAppId: "999"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("999")
        // Must name the actual type so the caller knows what they hit
        ex.message.contains("actual type: Simple Automation Rules")
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: HPM not installed
    // -------------------------------------------------------------------------

    def "get_hpm_drift throws when HPM is not installed (auto-discovery fails)"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListNoHpm() }

        when:
        script.toolGetHpmDrift([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Hubitat Package Manager does not appear to be installed")
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: no drift (clean state)
    // -------------------------------------------------------------------------

    def "get_hpm_drift happy path: no drift when all required components have heID and all heIDs present in Apps Code"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        // apps[] includes HPM so the explicit-id assert (_hpmAssertAppIsHpm) passes.
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // /hub2/userAppTypes (Apps Code registry): heID 142 is present -- no orphan-app signal.
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        // /hub2/userDeviceTypes (Drivers Code registry): heID 89 is present -- no orphan-driver signal.
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["89"]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then:
        result.success == true
        result.hpmAppId == "37"
        result.packagesChecked == 1
        result.packagesWithActionableDrift == 0
        result.totalDriftSignals == 0
        result.drift == []
        result.summary.contains("No drift detected")
        // Singular noun form: "1 tracked package." (not "packages") -- ternary uses '' for singular, 's' for plural
        result.summary.contains("across 1 tracked package.")
        result.orphanDetection?.enabled == true
        result.orphanDriverDetection?.enabled == true
    }

    def "get_hpm_drift summary uses plural 'tracked packages' when multiple packages are all clean"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Three packages, all with heIDs present in registry -- no drift expected
        def manifests = [
            "https://example.com/pkg1/packageManifest.json": [
                packageName: "Package Alpha", version: "1.0.0", beta: false, author: "Tester",
                apps: [[id: "uuid-a1", name: "App A", namespace: "ns", location: "loc", required: true, version: null, heID: "10"]],
                drivers: [], files: []
            ],
            "https://example.com/pkg2/packageManifest.json": [
                packageName: "Package Beta", version: "2.0.0", beta: false, author: "Tester",
                apps: [], drivers: [[id: "uuid-d1", name: "Driver B", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: "20"]],
                files: []
            ],
            "https://example.com/pkg3/packageManifest.json": [
                packageName: "Package Gamma", version: "3.0.0", beta: false, author: "Tester",
                apps: [[id: "uuid-a2", name: "App C", namespace: "ns", location: "loc", required: false, version: null, heID: "30"]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["10", "30"]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["20"]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'three clean packages -- plural noun form and No drift detected'
        result.success == true
        result.packagesChecked == 3
        result.packagesWithActionableDrift == 0
        result.totalDriftSignals == 0
        result.drift == []
        result.summary.contains("No drift detected across 3 tracked packages.")
        // Plural noun form: "tracked packages" (not "tracked package ")
        result.summary.contains("tracked packages")
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: missing-required signal
    // -------------------------------------------------------------------------

    def "get_hpm_drift surfaces missing-required signal when required app has null heID"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Incomplete Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [
                    [id: "app-uuid-1", name: "Required App", namespace: "ns", location: "loc", required: true, version: null, heID: null]
                ],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps          : [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // All heIDs are null so orphan check never fires; empty registries are fine.
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then:
        result.success == true
        result.packagesWithActionableDrift == 1
        result.totalDriftSignals == 1
        result.orphanDetection?.enabled == true
        result.orphanDriverDetection?.enabled == true
        result.drift.size() == 1
        def entry = result.drift[0]
        entry.packageName == "Incomplete Pkg"
        entry.version == "1.0.0"
        entry.signals.size() == 1
        def sig = entry.signals[0]
        sig.type == "missing-required"
        sig.componentType == "app"
        sig.componentName == "Required App"
        sig.note.contains("Component is required but heID is null/absent")

        and: 'summary reflects 1 of 1 tracked packages with 1 total signal'
        result.summary.contains("1 of 1")
        result.summary.contains("1 total signal")
        result.summary.contains("shows")   // singular subject-verb agreement

        and: 'orphan detection enabled (sanity check)'
        result.orphanDetection?.enabled == true
        result.orphanDriverDetection?.enabled == true
    }

    def "get_hpm_drift summary uses plural 'packages show drift' when multiple packages have actionable signals"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/pkg1/packageManifest.json": [
                packageName: "Pkg With Missing Required App",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [[id: "app-uuid-1", name: "Required App", namespace: "ns", location: "loc", required: true, version: null, heID: null]],
                drivers: [], files: []
            ],
            "https://example.com/pkg2/packageManifest.json": [
                packageName: "Pkg With Missing Required Driver",
                version    : "2.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                drivers: [[id: "drv-uuid-1", name: "Required Driver", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: null]],
                files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'two packages with actionable drift -- summary uses plural "packages show drift"'
        result.success == true
        result.packagesWithActionableDrift == 2
        result.totalDriftSignals == 2
        result.summary.contains("packages show drift")
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: orphan-app signal
    // -------------------------------------------------------------------------

    def "get_hpm_drift surfaces orphan-app signal when heID not in Apps Code registry"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Orphaned Pkg",
                version    : "2.0.0",
                beta       : false,
                author     : "Tester",
                apps: [
                    [id: "app-uuid-orphan", name: "Orphan App", namespace: "ns", location: "loc", required: true, version: null, heID: "999"]
                ],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        // apps[] includes HPM so the explicit-id assert passes.
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Apps Code registry has a different app (100) but NOT 999 -- orphan-app signal fires.
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["100"]) }
        // Drivers Code registry -- no driver heIDs in this package so orphan-driver is irrelevant.
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then:
        result.success == true
        result.totalDriftSignals == 1
        result.orphanDetection?.enabled == true
        result.orphanDriverDetection?.enabled == true
        def sig = result.drift[0].signals[0]
        sig.type == "orphan-app"
        sig.heID == "999"
        sig.componentName == "Orphan App"
        sig.note.contains("no longer in Apps Code")
        result.drift[0].version == "2.0.0"
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: both signals on same package
    // -------------------------------------------------------------------------

    def "get_hpm_drift surfaces both missing-required and orphan-app signals on same package"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Mixed Signals",
                version    : "3.0.0",
                beta       : false,
                author     : "Tester",
                apps: [
                    [id: "app-req-null", name: "Missing Required", namespace: "ns", location: "loc", required: true, version: null, heID: null],
                    [id: "app-orphan", name: "Orphan App", namespace: "ns", location: "loc", required: false, version: null, heID: "888"]
                ],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        // apps[] includes HPM so the explicit-id assert passes.
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Apps Code registry has a different code def (999) but NOT 888 -- orphan-app signal fires.
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["999"]) }
        // No drivers in this package -- Drivers Code registry is irrelevant.
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then:
        result.success == true
        result.totalDriftSignals == 2
        result.orphanDetection?.enabled == true
        result.orphanDriverDetection?.enabled == true
        result.drift.size() == 1
        result.drift[0].version == "3.0.0"
        def signals = result.drift[0].signals
        signals.any { it.type == "missing-required" }
        signals.any { it.type == "orphan-app" }
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: packageFilter
    // -------------------------------------------------------------------------

    def "get_hpm_drift with packageFilter narrows to matching packages only"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = onePackageManifests() + [
            "https://example.com/other/packageManifest.json": [
                packageName: "Totally Different",
                version    : "1.0.0",
                beta       : false,
                author     : "Other",
                apps: [[id: "uuid-x", name: "Other App", namespace: "ns", location: "loc", required: true, version: null, heID: null]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // heID 142 (BOND app) and 89 (BOND driver) are in the registries -- no orphan signals.
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["89"]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37", packageFilter: "BOND"])

        then:
        result.success == true
        // Only the BOND package matches the filter
        result.packagesChecked == 1
        // BOND package has no drift (heIDs present in both registries)
        result.totalDriftSignals == 0
    }

    def "get_hpm_drift with packageFilter that matches nothing returns packagesChecked=0"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps          : [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37", packageFilter: "NoSuchPackageXYZ"])

        then:
        result.success == true
        result.packagesChecked == 0
        result.drift == []
        result.totalDriftSignals == 0
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: limitations note is always present
    // -------------------------------------------------------------------------

    def "get_hpm_drift always includes limitations note in response"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJsonEmpty("37") }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps          : [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then:
        result.success == true
        result.limitations != null
        result.limitations.contains("heID-presence-only")
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: regression -- child-app template false-positive
    // -------------------------------------------------------------------------

    /**
     * Regression pin: a component whose heID exists in Apps Code (/hub2/userAppTypes)
     * but has NO running installed instance in the apps[] tree must NOT be flagged
     * orphan-app. This was the live bug: "MCP Rule" (a child-app template) has a code
     * definition in Apps Code (heID 179) but is never installed as a standalone instance
     * -- it is instantiated dynamically by its parent. The original implementation used
     * /hub2/appsList .userAppTypes[] (installed instances) instead of /hub2/userAppTypes
     * (code definitions), which caused every child-app template to appear orphaned.
     */
    def "get_hpm_drift does not flag orphan-app for child-app template present in Apps Code but with no running instance"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Package has a child-app template component (heID 179) with no running instance.
        def manifests = [
            "https://raw.githubusercontent.com/example/main/packageManifest.json": [
                packageName: "Parent + Child App Package",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [
                    [id: "app-uuid-parent", name: "Parent App",    namespace: "ns", location: "loc", required: true, version: null, heID: "178"],
                    [id: "app-uuid-child",  name: "Child App Rule", namespace: "ns", location: "loc", required: true, version: null, heID: "179"]
                ],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        // apps[] has only HPM (37) and the parent (178) as running instances.
        // The child-app template (179) has NO entry in apps[] -- it is not a running instance.
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37",  name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: [
                        [data: [id: "178", name: "Parent App",    type: "Parent App",    user: true], children: []]
                    ]]
                ]
            ])
        }
        // Apps Code registry has BOTH the parent (178) and the child-app template (179).
        // This is what the corrected implementation checks -- not installed instances.
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["178", "179"]) }
        // No drivers in this package.
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'no orphan-app signal for the child-app template'
        result.success == true
        result.totalDriftSignals == 0
        result.drift == []
        result.summary.contains("No drift detected")
    }

    // -------------------------------------------------------------------------
    // Auto-discovery: BFS into children[] (I7)
    // -------------------------------------------------------------------------

    def "list_hpm_packages auto-discovery finds HPM nested under a parent app in the apps tree"() {
        given:
        settingsMap.enableHubAdminRead = true
        // HPM (id "37") is a child of some parent app (id "10") in the instance tree.
        // Discovery must walk children[] to find it, not just the top-level apps[].
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "10", name: "Some Parent App", type: "Some Parent", user: true], children: [
                        [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                    ]]
                ]
            ])
        }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }

        when:
        def result = script.toolListHpmPackages([:])

        then: 'found HPM nested under a parent app'
        result.success == true
        result.hpmAppId == "37"
    }

    // -------------------------------------------------------------------------
    // Auto-discovery: multiple HPM instances throws (C2)
    // -------------------------------------------------------------------------

    def "list_hpm_packages auto-discovery throws when multiple HPM instances are found"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []],
                    [data: [id: "99", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }

        when:
        script.toolListHpmPackages([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("[37, 99]")
        ex.message.contains("Multiple HPM instances found")
    }

    // -------------------------------------------------------------------------
    // _hpmDiscoverAppId: HPM entry found but id field is null (B-Grid-1)
    // -------------------------------------------------------------------------

    def "list_hpm_packages auto-discovery throws when HPM entry exists but has no id field"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Simulate a rare edge case: an entry whose type matches HPM but data.id is null.
        // _hpmDiscoverAppId must throw rather than return null, which would silently pass an
        // invalid id to downstream calls.
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    // HPM type but no id field set (null)
                    [data: [id: null, name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }

        when:
        script.toolListHpmPackages([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("HPM entry found but has no id field")
    }

    // -------------------------------------------------------------------------
    // packageFilter case-insensitivity (I7)
    // -------------------------------------------------------------------------

    def "get_hpm_drift packageFilter is case-insensitive (lowercase filter matches mixed-case packageName)"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["89"]) }

        when:
        // "bond" (lowercase) must match "BOND Home Integration" (mixed case)
        def result = script.toolGetHpmDrift([hpmAppId: "37", packageFilter: "bond"])

        then:
        result.success == true
        result.packagesChecked == 1
        result.totalDriftSignals == 0
    }

    // -------------------------------------------------------------------------
    // /hub2/userAppTypes fetch failure -- structured orphanDetection disclosure (C1 / I7)
    // -------------------------------------------------------------------------

    def "get_hpm_drift surfaces orphanDetection.enabled=false when /hub2/userAppTypes fetch fails"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // /hub2/userAppTypes throws -- simulates transport failure
        hubGet.register('/hub2/userAppTypes') { throw new RuntimeException("connection refused") }
        // /hub2/userDeviceTypes succeeds normally
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["89"]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'tool succeeds but discloses that orphan-app detection was skipped'
        result.success == true
        result.orphanDetection?.enabled == false
        result.orphanDetection?.reason != null
        // totalDriftSignals stays 0 -- no orphan signals without the apps registry
        result.totalDriftSignals == 0
        // summary must flag partial detection so a consumer surfacing only the string isn't misled
        result.summary.contains("(partial: orphanDetection disabled this call -- see orphanDetection reason)")
    }

    // -------------------------------------------------------------------------
    // filterMatchedZero: packageFilter with no matching packages (I1)
    // -------------------------------------------------------------------------

    def "get_hpm_drift with packageFilter that matches nothing includes filterMatchedZero and availablePackages"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37", packageFilter: "NoSuchPackageXYZ"])

        then:
        result.success == true
        result.packagesChecked == 0
        result.filterMatchedZero == true
        result.availablePackages != null
        result.availablePackages.contains("BOND Home Integration")
    }

    // -------------------------------------------------------------------------
    // Regression pin: orphan-app fires on empty registry (enabled=true path)
    // -------------------------------------------------------------------------

    /**
     * Regression pin: when orphanDetection.enabled=true AND installedAppCodeIds is an
     * empty Set (fresh hub, or Apps Code registry returns []), every HPM-tracked heID
     * that is non-null must be flagged as orphan-app.
     * Guard must be `if (!heIdNull && orphanDetection.enabled)`, NOT a Groovy-truthy
     * check on the Set itself (empty collection is falsy -- silently drops orphan signals).
     */
    def "get_hpm_drift reports orphan-app when orphanDetection.enabled=true and Apps Code registry is empty (fresh hub)"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Orphaned On Fresh Hub",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [
                    [id: "app-uuid-1", name: "Orphan App", namespace: "ns", location: "loc", required: false, version: null, heID: "777"]
                ],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Empty Apps Code registry -- successful fetch, just no entries.
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        // Empty Drivers Code registry -- no driver heIDs in this package.
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'orphanDetection.enabled=true AND heID 777 not in empty registry -> orphan-app signal'
        result.success == true
        result.orphanDetection?.enabled == true
        result.orphanDriverDetection?.enabled == true
        result.totalDriftSignals == 1
        result.drift.size() == 1
        result.drift[0].signals[0].type == "orphan-app"
        result.drift[0].signals[0].heID == "777"
    }

    // -------------------------------------------------------------------------
    // list_hpm_packages: non-scalar heID handling on app and driver entries
    // -------------------------------------------------------------------------

    def "list_hpm_packages adds _warning to app entry when heID is a non-scalar type (List)"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Bad HeID Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // heID is a List -- non-scalar, should be cleared and flagged
                apps: [[id: "uuid-1", name: "Weird App", namespace: "ns", location: "loc", required: true, version: null, heID: [1, 2, 3]]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        def app = result.packages[0].apps[0]
        app.heID == null
        app._warning != null
        // list_hpm_packages clears the heID and keeps the component entry (heID cleared disposition)
        app._warning.contains("non-scalar heID (not Number or String) -- heID cleared")
    }

    def "list_hpm_packages adds _warning to driver entry when heID is a non-scalar type (Map)"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Bad Driver HeID Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // heID is a Map -- non-scalar
                drivers: [[id: "uuid-d1", name: "Weird Driver", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: [nested: "map"]]],
                files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        def driver = result.packages[0].drivers[0]
        driver.heID == null
        driver._warning != null
        // list_hpm_packages clears the heID and keeps the component entry (heID cleared disposition)
        driver._warning.contains("non-scalar heID (not Number or String) -- heID cleared")
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: non-scalar heID lands in dataQualityWarnings, not signals
    // -------------------------------------------------------------------------

    /**
     * Regression pin: a component with non-scalar heID must land in dataQualityWarnings[]
     * on the drift entry and in the top-level dataQualityWarnings[], NOT in signals[],
     * and must NOT inflate totalDriftSignals.
     * Data-quality entries (heid-non-scalar-dropped type) are classified separately from
     * actionable drift signals and must not roll up into totalDriftSignals.
     */
    def "get_hpm_drift places non-scalar heID app component in dataQualityWarnings, not signals, and does not inflate totalDriftSignals"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Malformed HeID Pkg",
                version    : "2.0.0",
                beta       : false,
                author     : "Tester",
                // heID is a List -- non-scalar; must go to dataQualityWarnings
                apps: [[id: "app-uuid-bad", name: "Bad HeID App", namespace: "ns", location: "loc", required: false, version: null, heID: [99, 100]]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["100"]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'non-scalar heID goes to dataQualityWarnings, not signals'
        result.success == true
        result.totalDriftSignals == 0
        result.drift.size() == 1
        result.drift[0].signals == []
        result.drift[0].dataQualityWarnings?.size() == 1
        result.drift[0].dataQualityWarnings[0].type == "heid-non-scalar-dropped"
        // get_hpm_drift drops the component entirely (component skipped disposition -- different from list_hpm_packages which clears heID)
        result.drift[0].dataQualityWarnings[0]._warning.contains("non-scalar heID (not Number or String) -- component skipped")
        result.dataQualityWarnings != null
        result.dataQualityWarnings.size() == 1
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: data-quality-only package does not inflate summary drift count
    // -------------------------------------------------------------------------

    /**
     * Regression pin: a package with only dataQualityWarnings and no actionable signals
     * must produce summary "No drift detected" (not "1 package shows drift") and must
     * leave totalDriftSignals == 0. The drift[] entry is still present for visibility.
     */
    def "get_hpm_drift summary says No drift detected when only data-quality warnings exist (no actionable signals)"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Data Quality Only Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // heID is a List -- goes to dataQualityWarnings, not signals
                apps: [[id: "app-uuid-dq", name: "DQ App", namespace: "ns", location: "loc", required: false, version: null, heID: [42, 43]]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["100"]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'summary reflects no actionable drift; dataQualityWarnings entry still present'
        result.success == true
        result.totalDriftSignals == 0
        result.summary.contains("No drift detected")
        result.drift.size() == 1
        result.drift[0].signals == []
        result.drift[0].dataQualityWarnings?.size() == 1
        result.dataQualityWarnings?.size() == 1
    }

    // -------------------------------------------------------------------------
    // list_hpm_packages: per-entry malformed manifest entries land in skippedMalformed[]
    // -------------------------------------------------------------------------

    def "list_hpm_packages skips non-Map manifest entry into skippedMalformed and keeps well-formed neighbors"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/good/packageManifest.json": [
                packageName: "Good Package",
                version    : "1.0.0",
                beta       : false,
                author     : "Author",
                apps: [], drivers: [], files: []
            ],
            // Malformed entry: value is a String, not a Map
            "https://example.com/bad/packageManifest.json": "this is not a map"
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'malformed entry listed in skippedMalformed; well-formed neighbor present in packages'
        result.success == true
        result.count == 1
        result.packages.size() == 1
        result.packages[0].packageName == "Good Package"
        result.skippedMalformed != null
        result.skippedMalformed.contains("https://example.com/bad/packageManifest.json")
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: skippedMalformed mirror for per-entry malformed manifests
    // -------------------------------------------------------------------------

    def "get_hpm_drift includes skippedMalformed for non-Map manifest entries"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/good/packageManifest.json": [
                packageName: "Good Drift Package",
                version    : "1.0.0",
                beta       : false,
                author     : "Author",
                apps: [[id: "app-ok", name: "OK App", namespace: "ns", location: "loc", required: true, version: null, heID: "50"]],
                drivers: [], files: []
            ],
            // Malformed entry: String value
            "https://example.com/bad/packageManifest.json": "not a map"
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // heID 50 (app) is present -- no orphan-app signal for the good package.
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["50"]) }
        // No driver heIDs in this package.
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'malformed URL in top-level skippedMalformed; good package analyzed without drift'
        result.success == true
        result.packagesChecked == 2   // filteredManifests includes both entries (skipping happens inside the loop)
        result.totalDriftSignals == 0
        result.skippedMalformed != null
        result.skippedMalformed.contains("https://example.com/bad/packageManifest.json")
    }

    // -------------------------------------------------------------------------
    // _hpmDiscoverAppId: nested-under-children[] duplicate detected (BFS walks unconditionally)
    // -------------------------------------------------------------------------

    def "list_hpm_packages auto-discovery throws when duplicate HPM app is nested under a parent"() {
        given:
        settingsMap.enableHubAdminRead = true
        // One HPM at top level (37), one nested under a parent (88).
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []],
                    [data: [id: "10", name: "Some Parent", type: "Some Parent", user: true], children: [
                        [data: [id: "88", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                    ]]
                ]
            ])
        }

        when:
        script.toolListHpmPackages([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("[37, 88]")
        ex.message.contains("Multiple HPM instances found")
    }

    // -------------------------------------------------------------------------
    // Empty/whitespace heID normalization in list_hpm_packages
    // -------------------------------------------------------------------------

    def "list_hpm_packages emits _warning and sets heID=null for empty-string heID on app component"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Empty HeID App Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // heID is an empty string -- must be normalized to null + _warning
                apps: [[id: "uuid-1", name: "Empty HeID App", namespace: "ns", location: "loc", required: true, version: null, heID: ""]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'empty heID normalized to null and _warning added to the entry'
        result.success == true
        def app = result.packages[0].apps[0]
        app.heID == null
        app._warning != null
        app._warning.contains("empty heID")
        app._warning.contains("normalized to null")
        app._warning.contains("''")
    }

    def "list_hpm_packages emits _warning and sets heID=null for whitespace-only heID on driver component"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Whitespace HeID Driver Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // heID is whitespace-only -- must be normalized to null + _warning
                drivers: [[id: "uuid-d1", name: "Whitespace HeID Driver", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: "  "]],
                files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'whitespace heID normalized to null and _warning added to the driver entry'
        result.success == true
        def driver = result.packages[0].drivers[0]
        driver.heID == null
        driver._warning != null
        driver._warning.contains("empty heID")
        driver._warning.contains("normalized to null")
        driver._warning.contains("'  '")
    }

    // -------------------------------------------------------------------------
    // Empty/whitespace heID normalization in get_hpm_drift
    // -------------------------------------------------------------------------

    def "get_hpm_drift places empty-string heID app component in dataQualityWarnings and normalizes to null (missing-required fires)"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Empty HeID Required Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // required=true + empty heID: empty heID normalizes to null, triggering missing-required
                apps: [[id: "uuid-1", name: "Empty HeID Required App", namespace: "ns", location: "loc", required: true, version: null, heID: "  "]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'empty heID emits empty-heid data-quality warning AND missing-required signal (heID normalized to null)'
        result.success == true
        def entry = result.drift[0]
        def dqw = entry.dataQualityWarnings?.find { it.type == "empty-heid" && it.componentType == "app" }
        dqw != null
        dqw._warning.contains("empty heID string '  ' normalized to null")
        entry.signals.any { it.type == "missing-required" && it.componentName == "Empty HeID Required App" }
    }

    // -------------------------------------------------------------------------
    // Orphan-driver detection via Drivers Code registry
    // -------------------------------------------------------------------------

    def "get_hpm_drift surfaces orphan-driver signal when driver heID not in Drivers Code registry"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Orphan Driver Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                drivers: [[id: "drv-uuid-1", name: "Orphan Driver", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: "555"]],
                files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Apps Code registry irrelevant (no apps in this package)
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        // Drivers Code registry has a different driver (999) but NOT 555 -- orphan-driver signal fires.
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["999"]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then:
        result.success == true
        result.totalDriftSignals == 1
        result.orphanDriverDetection?.enabled == true
        result.drift.size() == 1
        def sig = result.drift[0].signals[0]
        sig.type == "orphan-driver"
        sig.componentType == "driver"
        sig.heID == "555"
        sig.componentName == "Orphan Driver"
        sig.note.contains("no longer in Drivers Code")
        result.drift[0].version == "1.0.0"
    }

    def "get_hpm_drift surfaces missing-required signal for required driver with null heID"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Missing Required Driver Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                drivers: [[id: "drv-uuid-1", name: "Missing Driver", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: null]],
                files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then:
        result.success == true
        result.totalDriftSignals == 1
        result.drift[0].version == "1.0.0"
        def sig = result.drift[0].signals[0]
        sig.type == "missing-required"
        sig.componentType == "driver"
        sig.componentName == "Missing Driver"
    }

    def "get_hpm_drift places non-scalar heID driver component in dataQualityWarnings, not signals"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Bad Driver HeID Drift Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // non-scalar heID on driver -- goes to dataQualityWarnings
                drivers: [[id: "drv-uuid-1", name: "Bad HeID Driver", namespace: "ns", location: "loc", required: false, version: "1.0.0", heID: [7, 8]]],
                files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'non-scalar driver heID goes to dataQualityWarnings with heid-non-scalar-dropped type'
        result.success == true
        result.totalDriftSignals == 0
        result.drift.size() == 1
        result.drift[0].signals == []
        def dqw = result.drift[0].dataQualityWarnings?.find { it.type == "heid-non-scalar-dropped" && it.componentType == "driver" }
        dqw != null
        // get_hpm_drift drops the component entirely (component skipped disposition -- different from list_hpm_packages which clears heID)
        dqw._warning.contains("non-scalar heID (not Number or String) -- component skipped")
    }

    def "get_hpm_drift regression pin: orphanDriverDetection enabled=true with empty Drivers Code registry fires orphan-driver signal"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Orphan Driver Fresh Hub Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                drivers: [[id: "drv-uuid-1", name: "Orphan Driver", namespace: "ns", location: "loc", required: false, version: "1.0.0", heID: "888"]],
                files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        // Empty Drivers Code registry -- enabled=true, heID 888 not in empty set -> orphan-driver
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'orphanDriverDetection.enabled=true AND heID 888 not in empty registry -> orphan-driver signal'
        result.success == true
        result.orphanDriverDetection?.enabled == true
        result.totalDriftSignals == 1
        result.drift[0].signals[0].type == "orphan-driver"
        result.drift[0].signals[0].heID == "888"
    }

    def "get_hpm_drift surfaces orphanDriverDetection.enabled=false when /hub2/userDeviceTypes fetch fails"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Apps Code registry succeeds with the app's heID (142) present -- no orphan-app signal
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        // Drivers Code registry throws -- simulates transport failure
        hubGet.register('/hub2/userDeviceTypes') { throw new RuntimeException("connection refused") }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'tool succeeds; driver detection disabled with reason; apps-side detection unaffected'
        result.success == true
        result.orphanDriverDetection?.enabled == false
        result.orphanDriverDetection?.reason != null
        // apps-side orphan detection is unaffected by the driver fetch failure
        result.orphanDetection?.enabled == true
        // no actionable signals: apps-side passes (142 in registry), drivers-side detection disabled
        result.totalDriftSignals == 0
        // summary must flag partial detection so a consumer surfacing only the string isn't misled
        result.summary.contains("(partial: orphanDriverDetection disabled this call -- see orphanDriverDetection reason)")
    }

    // -------------------------------------------------------------------------
    // Non-Map component skip tracking in drift entries
    // -------------------------------------------------------------------------

    def "get_hpm_drift tracks skippedAppCount and emits skipped-malformed-component warning for non-Map app entries"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Malformed App Component Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // First entry is a String (not a Map) -- should be counted and warned
                apps: ["not-a-map", [id: "uuid-ok", name: "OK App", namespace: "ns", location: "loc", required: false, version: null, heID: null]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'skippedAppCount present on drift entry; skipped-malformed-component in dataQualityWarnings'
        result.success == true
        result.drift.size() == 1
        result.drift[0].skippedAppCount == 1
        !result.drift[0].containsKey('skippedDriverCount')
        result.drift[0].dataQualityWarnings?.any { it.type == "skipped-malformed-component" && it.componentType == "app" }
    }

    def "get_hpm_drift tracks skippedDriverCount and emits skipped-malformed-component warning for non-Map driver entries"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Malformed Driver Component Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // First entry is a String (not a Map) -- should be counted and warned
                drivers: ["not-a-map", [id: "drv-uuid-ok", name: "OK Driver", namespace: "ns", location: "loc", required: false, version: "1.0.0", heID: null]],
                files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'skippedDriverCount present on drift entry; skippedAppCount absent; skipped-malformed-component (driver) in dataQualityWarnings'
        result.success == true
        result.drift.size() == 1
        result.drift[0].skippedDriverCount == 1
        !result.drift[0].containsKey('skippedAppCount')
        result.drift[0].dataQualityWarnings?.any { it.type == "skipped-malformed-component" && it.componentType == "driver" }
    }

    // -------------------------------------------------------------------------
    // Multi-instance HPM auto-discovery: truncated error message format
    // -------------------------------------------------------------------------

    def "list_hpm_packages auto-discovery throws with truncated id list when more than 10 HPM instances found"() {
        given:
        settingsMap.enableHubAdminRead = true
        // 15 HPM instances -- discovery must truncate at 10 and report "and 5 more (total 15)"
        def ids = (1..15).collect { it.toString() }
        def apps = ids.collect { id ->
            [data: [id: id, name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
        }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([systemAppTypes: [], userAppTypes: [], apps: apps])
        }

        when:
        script.toolListHpmPackages([:])

        then:
        def ex = thrown(IllegalArgumentException)
        // The first 10 ids must appear as a literal bracketed list -- prevents id "1" substring
        // matching "10" or "11" from masking a regression that outputs the wrong id set.
        ex.message.contains("[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]")
        // Truncation suffix must name the delta (5) and total (15)
        ex.message.contains("and 5 more (total 15)")
    }

    // -------------------------------------------------------------------------
    // Wrong-shape /hub2/userAppTypes: actualType and preview disclosed in orphanDetection.reason
    // -------------------------------------------------------------------------

    def "get_hpm_drift discloses actualType and preview when /hub2/userAppTypes returns a Map instead of List"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Return a JSON object (Map) instead of array -- triggers the non-List branch.
        // The Map value must exceed 200 characters when .toString() is called so the (truncated)
        // marker appended by actualPreview logic fires and can be asserted below.
        hubGet.register('/hub2/userAppTypes') {
            JsonOutput.toJson([
                error  : "unexpected",
                code   : 500,
                detail : "a" * 210,
                context: "This is a deliberately long error payload to push the preview past the 200-character truncation threshold in orphanDetection.reason"
            ])
        }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'orphanDetection disabled with reason disclosing actualType=Map and truncated preview'
        result.success == true
        result.orphanDetection?.enabled == false
        result.orphanDetection?.reason != null
        result.orphanDetection.reason.contains("expected JSON array, got Map")
        result.orphanDetection.reason.contains("(truncated)")
        // summary must flag partial detection so a consumer surfacing only the string isn't misled
        result.summary.contains("(partial: orphanDetection disabled this call -- see orphanDetection reason)")
    }

    // -------------------------------------------------------------------------
    // skippedAppCount: emitted when > 0, omitted when all entries are well-formed
    // -------------------------------------------------------------------------

    def "list_hpm_packages includes skippedAppCount on package entry when > 0"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Skipped App Entry Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // One well-formed app, one non-Map entry that should be counted
                apps: [
                    [id: "uuid-good", name: "Good App", namespace: "ns", location: "loc", required: true, version: null, heID: "10"],
                    "not-a-map"
                ],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'skippedAppCount == 1 and omitted fields are absent'
        result.success == true
        def pkg = result.packages[0]
        pkg.skippedAppCount == 1
        !pkg.containsKey('skippedDriverCount')
        !pkg.containsKey('skippedFileCount')
        pkg.apps.size() == 1
        pkg.apps[0].name == "Good App"
    }

    def "list_hpm_packages includes skippedDriverCount on package entry when > 0 and omits skippedAppCount"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Skipped Driver Entry Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // One well-formed driver, one non-Map entry that should be counted
                drivers: [
                    "not-a-map",
                    [id: "uuid-drv-good", name: "Good Driver", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: "20"]
                ],
                files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'skippedDriverCount == 1 and skippedAppCount is absent'
        result.success == true
        def pkg = result.packages[0]
        pkg.skippedDriverCount == 1
        !pkg.containsKey('skippedAppCount')
        pkg.drivers.size() == 1
        pkg.drivers[0].name == "Good Driver"
    }

    def "list_hpm_packages omits skippedAppCount when all app entries are well-formed"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'no skippedAppCount, skippedDriverCount, or skippedFileCount when all entries are Maps'
        result.success == true
        def pkg = result.packages[0]
        !pkg.containsKey('skippedAppCount')
        !pkg.containsKey('skippedDriverCount')
        !pkg.containsKey('skippedFileCount')
    }

    // -------------------------------------------------------------------------
    // Non-scalar heID _warning contains literal "non-scalar" (List and Map variants)
    // -------------------------------------------------------------------------

    def "list_hpm_packages _warning for non-scalar app heID contains literal 'non-scalar heID'"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Non-Scalar App HeID Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [[id: "uuid-1", name: "Weird App", namespace: "ns", location: "loc", required: true, version: null, heID: [1, 2, 3]]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        def app = result.packages[0].apps[0]
        app.heID == null
        app._warning != null
        // list_hpm_packages clears the heID and keeps the component entry (heID cleared disposition)
        app._warning.contains("non-scalar heID (not Number or String) -- heID cleared")
    }

    // -------------------------------------------------------------------------
    // Whitespace-padded heID normalization in get_hpm_drift (apps + drivers)
    // -------------------------------------------------------------------------

    def "get_hpm_drift normalizes whitespace-padded app heID and emits heid-whitespace-normalized warning without orphan-app signal"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Whitespace HeID App Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // " 142 " has surrounding whitespace -- would miss the registry lookup verbatim.
                // After normalization to "142" it must match and NOT fire an orphan-app signal.
                apps: [[id: "uuid-pad", name: "Padded App", namespace: "ns", location: "loc", required: false, version: null, heID: " 142 "]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Registry contains "142" (no padding) -- must match after normalization
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'whitespace-padded heID normalizes to trimmed value -- no orphan-app signal, heid-whitespace-normalized warning emitted'
        result.success == true
        result.totalDriftSignals == 0
        def entry = result.drift[0]
        def dqw = entry.dataQualityWarnings?.find { it.type == "heid-whitespace-normalized" && it.componentType == "app" }
        dqw != null
        dqw._warning.contains("whitespace-padded heID ' 142 ' normalized to '142'")
    }

    def "get_hpm_drift normalizes whitespace-padded driver heID and emits heid-whitespace-normalized warning without orphan-driver signal"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Whitespace HeID Driver Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // " 89 " has surrounding whitespace -- would miss the registry lookup verbatim.
                // After normalization to "89" it must match and NOT fire an orphan-driver signal.
                drivers: [[id: "drv-uuid-pad", name: "Padded Driver", namespace: "ns", location: "loc", required: false, version: "1.0.0", heID: " 89 "]],
                files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        // Registry contains "89" (no padding) -- must match after normalization
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["89"]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'whitespace-padded heID normalizes to trimmed value -- no orphan-driver signal, heid-whitespace-normalized warning emitted'
        result.success == true
        result.totalDriftSignals == 0
        def entry = result.drift[0]
        def dqw = entry.dataQualityWarnings?.find { it.type == "heid-whitespace-normalized" && it.componentType == "driver" }
        dqw != null
        dqw._warning.contains("whitespace-padded heID ' 89 ' normalized to '89'")
    }

    // -------------------------------------------------------------------------
    // Driver-side empty-heid data-quality warning in get_hpm_drift
    // -------------------------------------------------------------------------

    def "get_hpm_drift places empty-string heID driver component in dataQualityWarnings and normalizes to null"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Empty HeID Driver Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // required=true + empty heID: empty heID normalizes to null, triggering missing-required
                drivers: [[id: "drv-uuid-1", name: "Empty HeID Driver", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: "  "]],
                files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'empty heID emits empty-heid data-quality warning AND missing-required signal (heID normalized to null)'
        result.success == true
        def entry = result.drift[0]
        def dqw = entry.dataQualityWarnings?.find { it.type == "empty-heid" && it.componentType == "driver" }
        dqw != null
        dqw._warning.contains("empty heID string '  ' normalized to null")
        entry.signals.any { it.type == "missing-required" && it.componentName == "Empty HeID Driver" }
    }

    // -------------------------------------------------------------------------
    // orphanDriverDetection.enabled=false: empty-body and non-List branches
    // -------------------------------------------------------------------------

    def "get_hpm_drift surfaces orphanDriverDetection.enabled=false when /hub2/userDeviceTypes returns empty body"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Apps Code registry succeeds normally
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        // Empty response body -- triggers the !userDeviceTypesText branch
        hubGet.register('/hub2/userDeviceTypes') { "" }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'tool succeeds; orphanDriverDetection disabled with reason indicating empty response'
        result.success == true
        result.orphanDriverDetection?.enabled == false
        result.orphanDriverDetection?.reason != null
        result.orphanDriverDetection.reason.contains("Empty response from /hub2/userDeviceTypes")
        result.summary.contains("(partial: orphanDriverDetection disabled this call -- see orphanDriverDetection reason)")
    }

    def "get_hpm_drift surfaces orphanDriverDetection.enabled=false when /hub2/userDeviceTypes returns non-List JSON"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Apps Code registry succeeds normally
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        // Return a JSON object (Map) instead of array -- triggers the non-List branch for drivers
        hubGet.register('/hub2/userDeviceTypes') {
            JsonOutput.toJson([error: "unexpected shape", code: 500])
        }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'tool succeeds; orphanDriverDetection disabled with reason disclosing actualType=Map'
        result.success == true
        result.orphanDriverDetection?.enabled == false
        result.orphanDriverDetection?.reason != null
        result.orphanDriverDetection.reason.contains("expected JSON array, got Map")
        result.summary.contains("(partial: orphanDriverDetection disabled this call -- see orphanDriverDetection reason)")
    }

    def "get_hpm_drift surfaces orphanDetection.enabled=false when /hub2/userAppTypes returns JSON null root"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Return JSON literal null -- exercises the actualTypeName == "null" branch at line 12198
        hubGet.register('/hub2/userAppTypes') { 'null' }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'orphanDetection disabled; reason contains "got null"'
        result.success == true
        result.orphanDetection?.enabled == false
        result.orphanDetection?.reason != null
        result.orphanDetection.reason.contains("got null")
    }

    def "get_hpm_drift surfaces orphanDriverDetection.enabled=false when /hub2/userDeviceTypes returns JSON null root"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        // Return JSON literal null -- exercises the actualTypeName == "null" branch at line 12230
        hubGet.register('/hub2/userDeviceTypes') { 'null' }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'orphanDriverDetection disabled; reason contains "got null"'
        result.success == true
        result.orphanDriverDetection?.enabled == false
        result.orphanDriverDetection?.reason != null
        result.orphanDriverDetection.reason.contains("got null")
    }

    def "list_hpm_packages _warning for non-scalar driver heID contains literal 'non-scalar heID'"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Non-Scalar Driver HeID Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                drivers: [[id: "uuid-d1", name: "Weird Driver", namespace: "ns", location: "loc", required: true, version: "1.0.0", heID: [nested: "map"]]],
                files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then:
        result.success == true
        def driver = result.packages[0].drivers[0]
        driver.heID == null
        driver._warning != null
        // list_hpm_packages clears the heID and keeps the component entry (heID cleared disposition)
        driver._warning.contains("non-scalar heID (not Number or String) -- heID cleared")
    }

    // -------------------------------------------------------------------------
    // get_hpm_drift: Integer heID is a valid scalar -- no data-quality warning (BLOCKING #7)
    // -------------------------------------------------------------------------

    /**
     * Regression pin: an Integer heID (e.g. 142 raw) must be treated as a valid scalar.
     * The production guard is `instanceof Number || instanceof String`. If the Number check
     * is dropped, Integer heIDs route to the heid-non-scalar-dropped path and silently
     * disappear from drift checking, producing false-negatives for orphan detection.
     */
    def "get_hpm_drift treats Integer heID as valid scalar -- no heid-non-scalar-dropped warning and no orphan-app signal when heID matches registry"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Integer HeID Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // heID as raw Integer -- must be treated as valid scalar and matched against registry
                apps: [[id: "uuid-int", name: "Integer HeID App", namespace: "ns", location: "loc", required: false, version: null, heID: 142]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Registry contains "142" -- Integer 142 must match via .toString() coercion
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes([]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'Integer heID 142 matches registry "142" -- no orphan signal and no data-quality warning'
        result.success == true
        result.totalDriftSignals == 0
        // No drift entry because no signals and no dataQualityWarnings
        result.drift == []
        // No top-level data-quality warnings either
        !result.containsKey('dataQualityWarnings')
    }

    def "get_hpm_drift treats Integer heID on driver as valid scalar -- no heid-non-scalar-dropped warning when heID matches registry"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Integer HeID Driver Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // heID as raw Integer on a driver entry -- must be treated as valid scalar
                drivers: [[id: "drv-uuid-int", name: "Integer HeID Driver", namespace: "ns", location: "loc", required: false, version: "1.0.0", heID: 55]],
                files: []
            ]
        ]
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes([]) }
        // Registry contains "55" -- Integer 55 must match via .toString() coercion
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["55"]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'Integer driver heID 55 matches registry "55" -- no orphan signal and no heid-non-scalar-dropped warning'
        result.success == true
        result.totalDriftSignals == 0
        result.drift == []
        !result.containsKey('dataQualityWarnings')
    }

    // -------------------------------------------------------------------------
    // Coverage gap: list_hpm_packages _warning text for whitespace-padded heID
    // -------------------------------------------------------------------------

    def "list_hpm_packages _warning for whitespace-padded app heID contains normalization text with trimmed value"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Padded HeID App Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                // " 142 " has surrounding whitespace -- must normalize and record _warning with trimmed value
                apps: [[id: "uuid-pad", name: "Padded HeID App", namespace: "ns", location: "loc", required: false, version: null, heID: " 142 "]],
                drivers: [], files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'heID normalized to trimmed value; _warning records both raw and trimmed forms'
        result.success == true
        def app = result.packages[0].apps[0]
        app.heID == "142"
        app._warning != null
        app._warning.contains("whitespace-padded heID ' 142 ' normalized to '142'")
    }

    def "list_hpm_packages _warning for whitespace-padded driver heID contains normalization text with trimmed value"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Padded HeID Driver Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                // " 89 " has surrounding whitespace -- must normalize and record _warning with trimmed value
                drivers: [[id: "drv-uuid-pad", name: "Padded HeID Driver", namespace: "ns", location: "loc", required: false, version: "1.0.0", heID: " 89 "]],
                files: []
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'driver heID normalized to trimmed value; _warning records both raw and trimmed forms'
        result.success == true
        def driver = result.packages[0].drivers[0]
        driver.heID == "89"
        driver._warning != null
        driver._warning.contains("whitespace-padded heID ' 89 ' normalized to '89'")
    }

    // -------------------------------------------------------------------------
    // Coverage gap: both orphanDetection and orphanDriverDetection disabled
    // -------------------------------------------------------------------------

    def "get_hpm_drift summary uses 'reasons' plural when both orphanDetection and orphanDriverDetection are disabled"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Both registry fetches fail -- both detection systems disabled this call
        hubGet.register('/hub2/userAppTypes') { throw new RuntimeException("simulated failure") }
        hubGet.register('/hub2/userDeviceTypes') { throw new RuntimeException("simulated failure") }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'both disabled -> summary contains both names joined with / and uses plural "reasons"'
        result.success == true
        result.orphanDetection?.enabled == false
        result.orphanDriverDetection?.enabled == false
        result.summary.contains("(partial: orphanDetection/orphanDriverDetection disabled this call -- see orphanDetection/orphanDriverDetection reasons)")
    }

    // -------------------------------------------------------------------------
    // Coverage gap: driver-side (truncated) in orphanDriverDetection.reason
    // -------------------------------------------------------------------------

    def "get_hpm_drift orphanDriverDetection.reason contains '(truncated)' when /hub2/userDeviceTypes non-List payload exceeds 200 chars"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Apps Code registry succeeds
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        // Return a JSON Map whose toString() length exceeds 200 chars -- triggers (truncated) in reason
        hubGet.register('/hub2/userDeviceTypes') {
            JsonOutput.toJson([
                error  : "unexpected",
                code   : 500,
                detail : "b" * 210,
                context: "Long error payload to push the preview past the 200-character truncation threshold"
            ])
        }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'orphanDriverDetection disabled; reason contains (truncated) because payload exceeded 200 chars'
        result.success == true
        result.orphanDriverDetection?.enabled == false
        result.orphanDriverDetection?.reason != null
        result.orphanDriverDetection.reason.contains("expected JSON array, got Map")
        result.orphanDriverDetection.reason.contains("(truncated)")
    }

    // -------------------------------------------------------------------------
    // Coverage gap: app-side orphanDetection.enabled=false on empty body
    // -------------------------------------------------------------------------

    def "get_hpm_drift surfaces orphanDetection.enabled=false when /hub2/userAppTypes returns empty body"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        // Empty body -- triggers the !userAppTypesText branch
        hubGet.register('/hub2/userAppTypes') { "" }
        // Driver registry succeeds normally
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["89"]) }

        when:
        def result = script.toolGetHpmDrift([hpmAppId: "37"])

        then: 'tool succeeds; orphanDetection disabled with reason indicating empty response from /hub2/userAppTypes'
        result.success == true
        result.orphanDetection?.enabled == false
        result.orphanDetection?.reason != null
        result.orphanDetection.reason.contains("Empty response from /hub2/userAppTypes")
        result.summary.contains("(partial: orphanDetection disabled this call -- see orphanDetection reason)")
    }

    // -------------------------------------------------------------------------
    // Coverage gap: skippedFileCount > 0 in list_hpm_packages
    // -------------------------------------------------------------------------

    def "list_hpm_packages emits skippedFileCount when files list contains a non-Map entry"() {
        given:
        settingsMap.enableHubAdminRead = true
        def manifests = [
            "https://example.com/packageManifest.json": [
                packageName: "Skipped File Entry Pkg",
                version    : "1.0.0",
                beta       : false,
                author     : "Tester",
                apps: [],
                drivers: [],
                // First file entry is an Integer (not a Map) -- should increment skippedFileCount
                files: [42, [id: "file-uuid-ok", name: "good.js"]]
            ]
        ]
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", manifests) }

        when:
        def result = script.toolListHpmPackages([hpmAppId: "37"])

        then: 'skippedFileCount == 1; well-formed file entry still present; skippedAppCount and skippedDriverCount absent'
        result.success == true
        def pkg = result.packages[0]
        pkg.skippedFileCount == 1
        !pkg.containsKey('skippedAppCount')
        !pkg.containsKey('skippedDriverCount')
        pkg.files.size() == 1
        pkg.files[0].name == "good.js"
    }

    // -------------------------------------------------------------------------
    // Coverage gap: gateway dispatch regression
    // -------------------------------------------------------------------------

    def "gateway dispatch: manage_hpm(tool='list_hpm_packages') produces same shape as direct toolListHpmPackages call"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub2/appsList') { makeAppsListWithHpmOnly("37") }
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }

        when:
        def direct  = script.toolListHpmPackages([hpmAppId: "37"])
        def gateway = script.executeTool('manage_hpm', [tool: 'list_hpm_packages', args: [hpmAppId: "37"]])

        then: 'both paths return success=true with deep structural equality'
        direct.success == true
        gateway.success == true
        gateway == direct
    }

    def "gateway dispatch: manage_hpm(tool='get_hpm_drift') produces same shape as direct toolGetHpmDrift call"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/installedapp/statusJson/37') { makeHpmStatusJson("37", onePackageManifests()) }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([
                systemAppTypes: [],
                userAppTypes  : [],
                apps: [
                    [data: [id: "37", name: "Hubitat Package Manager", type: "Hubitat Package Manager", user: true], children: []]
                ]
            ])
        }
        hubGet.register('/hub2/userAppTypes') { makeUserAppTypes(["142"]) }
        hubGet.register('/hub2/userDeviceTypes') { makeUserDriverTypes(["89"]) }

        when:
        def direct  = script.toolGetHpmDrift([hpmAppId: "37"])
        def gateway = script.executeTool('manage_hpm', [tool: 'get_hpm_drift', args: [hpmAppId: "37"]])

        then: 'both paths return success=true with deep structural equality'
        direct.success == true
        gateway.success == true
        gateway == direct
    }
}
