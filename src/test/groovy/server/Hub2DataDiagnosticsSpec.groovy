package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Contract spec for the /hub2/hubData surfacing (pending platform/firmware update + hub health
 * alerts + safeMode). Asserts the three tools that fold it in behave correctly against the real
 * endpoint shape captured from a C-8 on 2.5.0.143:
 *   - hub_get_info        -> platformUpdate + safeMode always; healthAlerts + appUpdate (the
 *                            folded-in MCP-app version check) only with their opt-in args
 *   - hub_get_metrics     -> the full healthAlerts block alongside the trend metrics
 * And the defensive path: when /hub2/hubData is unreadable, callers degrade (available=null, no
 * safeMode, healthAlerts=null) rather than throwing.
 *
 * hubGet.register('/hub2/hubData') stubs hubInternalGet; an unregistered path makes hubInternalGet
 * THROW, which _getHub2HubData catches and degrades to null (so the "unreadable" specs exercise the
 * real catch arm, not a benign no-op).
 */
class Hub2DataDiagnosticsSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    // Captured shape: pending update available + an active low-memory alert.
    @Shared String HUB2_UPDATE_AND_ALERT = JsonOutput.toJson([
        hubId: '811e21ba', version: '2.5.0.143', model: 'C-8', name: 'CrameHub', safeMode: false,
        alerts: [
            platformUpdateAvailable: true, platformUpdateVersion: '2.5.0.153',
            hubLowMemory: true, hubHighLoad: false, hubLoadSevere: false,
            zwaveOffline: false, zigbeeOffline: false, hubLargeDatabase: false,
            localBackupFailed: false, cloudBackupFailed: false, weakZigbee: false,
            databaseSize: 15,
            headerMessages: ['Platform update 2.5.0.153 available', 'Hub is running low on memory.']
        ]
    ])

    // No pending update, no firing alerts, hub in Safe Mode.
    @Shared String HUB2_NO_UPDATE_SAFEMODE = JsonOutput.toJson([
        version: '2.5.0.143', safeMode: true,
        alerts: [platformUpdateAvailable: false, hubLowMemory: false, zwaveOffline: false]
    ])

    // Safe Mode AND a pending update at once -- proves the two are read from independent fields.
    @Shared String HUB2_UPDATE_AND_SAFEMODE = JsonOutput.toJson([
        version: '2.5.0.143', safeMode: true,
        alerts: [platformUpdateAvailable: true, platformUpdateVersion: '2.5.0.153', hubLowMemory: false]
    ])

    // Valid JSON object but the alerts block is the wrong shape (a list) -- a firmware shape change.
    @Shared String HUB2_ALERTS_MALFORMED = JsonOutput.toJson([
        version: '2.5.0.143', safeMode: false, alerts: ['not', 'a', 'map']
    ])

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanup() {
        sharedLocation.hub = null
    }

    private TestHub hubOnFirmware(String fw) {
        def h = new TestHub()
        h.firmwareVersionString = fw
        return h
    }

    // -------- #12: pending platform/firmware update --------

    def "hub_get_info surfaces platformUpdate when /hub2/hubData reports one available"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }

        when:
        def result = script.toolGetHubInfo()

        then:
        result.platformUpdate.available == true
        result.platformUpdate.currentVersion == '2.5.0.143'
        result.platformUpdate.availableVersion == '2.5.0.153'
    }

    def "hub_get_info platformUpdate.available is false (no availableVersion) when none pending"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_NO_UPDATE_SAFEMODE }

        when:
        def result = script.toolGetHubInfo()

        then:
        result.platformUpdate.available == false
        result.platformUpdate.currentVersion == '2.5.0.143'
        !result.platformUpdate.containsKey('availableVersion')
        // the readable path must NOT carry the "unreadable" note
        !result.platformUpdate.containsKey('note')
    }

    def "hub_get_info platformUpdate.available is null (not false) when /hub2/hubData has an unrecognized alerts shape"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_ALERTS_MALFORMED }

        when:
        def result = script.toolGetHubInfo()

        then: 'a present-but-malformed alerts block must NOT masquerade as a confident "no update"'
        result.platformUpdate.available == null
        result.platformUpdate.note?.toLowerCase()?.contains('unrecognized')
    }

    def "hub_get_info reads safeMode and platformUpdate from independent fields (Safe Mode WITH a pending update)"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_SAFEMODE }

        when:
        def result = script.toolGetHubInfo()

        then:
        result.safeMode == true
        result.platformUpdate.available == true
        result.platformUpdate.availableVersion == '2.5.0.153'
    }

    def "hub_get_info platformUpdate.currentVersion falls back to /hub2/hubData version when the hub firmware string is null"() {
        given:
        def h = new TestHub()
        h.firmwareVersionString = null
        sharedLocation.hub = h
        hubGet.register('/hub2/hubData') { params -> HUB2_NO_UPDATE_SAFEMODE }   // carries version: '2.5.0.143'

        when:
        def result = script.toolGetHubInfo()

        then: 'firmwareVersionString null -> currentVersion comes from hub2.version'
        result.platformUpdate.currentVersion == '2.5.0.143'
    }

    def "hub_get_info degrades gracefully when /hub2/hubData is unreadable"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        // /hub2/hubData not registered -> hubInternalGet returns null.

        when:
        def result = script.toolGetHubInfo()

        then:
        noExceptionThrown()
        result.platformUpdate.available == null
        result.platformUpdate.currentVersion == '2.5.0.143'   // still from the hub firmware string
        // note is always set on the unreadable path -- assert it plainly (no safe-nav: a null note
        // here would itself be a regression we want to fail on).
        result.platformUpdate.note.toLowerCase().contains('unreadable')
        !result.containsKey('safeMode')
        !result.containsKey('healthAlerts')
    }

    def "hub_get_info surfaces platformUpdate always, plus the MCP-app version check under appUpdate when includeAppUpdate=true"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }

        when:
        def result = script.toolGetHubInfo([includeAppUpdate: true])

        then:
        result.platformUpdate.available == true             // pending HUB firmware
        result.platformUpdate.availableVersion == '2.5.0.153'
        result.appUpdate != null                            // folded-in MCP server app check
        result.appUpdate.containsKey('installedVersion')
        result.appUpdate.containsKey('updateAvailable')
    }

    def "hub_get_info omits appUpdate unless includeAppUpdate=true"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }

        when:
        def result = script.toolGetHubInfo([:])

        then:
        result.platformUpdate.available == true             // firmware read always present
        !result.containsKey('appUpdate')                    // app-version GitHub check is opt-in
    }

    // -------- #13: health alerts + safeMode --------

    def "hub_get_info surfaces safeMode but NOT the full alerts block by default"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_NO_UPDATE_SAFEMODE }

        when:
        def result = script.toolGetHubInfo()

        then:
        result.safeMode == true
        !result.containsKey('healthAlerts')
    }

    def "hub_get_info includes the full healthAlerts block only when includeHealthAlerts=true"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }

        when:
        def result = script.toolGetHubInfo([includeHealthAlerts: true])

        then:
        result.healthAlerts != null
        result.healthAlerts.safeMode == false
        // only the firing boolean flags, sorted
        result.healthAlerts.active == ['hubLowMemory']
        // details carries the FULL alert map -- firing AND non-firing flags + messages (unlike
        // `active`, which is filtered to the true ones)...
        result.healthAlerts.details.hubLowMemory == true
        result.healthAlerts.details.hubHighLoad == false
        result.healthAlerts.details.headerMessages instanceof List
        // ...but the platform-update fields are surfaced separately, not duplicated here
        !result.healthAlerts.details.containsKey('platformUpdateAvailable')
        !result.healthAlerts.details.containsKey('platformUpdateVersion')
    }

    def "hub_get_metrics folds in the full healthAlerts block alongside the trend metrics"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }

        when:
        def result = script.toolGetHubPerformance([:])

        then:
        result.containsKey('current')
        result.healthAlerts != null
        result.healthAlerts.active == ['hubLowMemory']
        result.healthAlerts.safeMode == false
    }

    def "hub_get_metrics healthAlerts is null when /hub2/hubData is unreadable"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        // /hub2/hubData not registered.

        when:
        def result = script.toolGetHubPerformance([:])

        then:
        noExceptionThrown()
        result.containsKey('healthAlerts')
        result.healthAlerts == null
    }
}
