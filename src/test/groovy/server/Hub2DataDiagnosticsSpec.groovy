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

    // asynchttpGet is an AppExecutor API method: metaClass stubbing silently no-ops for it, and the
    // shared per-spec-class mock only honors interactions declared in setupSpec (the additive-stub
    // pattern; see HarnessSpec's buildAppExecutorMock note). The toggle lets one test drive the real
    // doUpdateCheck's throw-before-scheduling arm without touching siblings that stub doUpdateCheck.
    @Shared boolean appCheckThrows = false

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
        appExecutor.asynchttpGet(*_) >> { if (appCheckThrows) throw new RuntimeException('github down') }
    }

    def cleanup() {
        sharedLocation.hub = null
        appCheckThrows = false
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
        // Seed a prior completed check + stub the async refresh as started so appUpdate carries the snapshot.
        stateMap.updateCheck = [latestVersion: '9.9.9', updateAvailable: true, checkedAt: 1700000000000L]
        script.metaClass.doUpdateCheck = { -> true }        // refresh scheduled

        when:
        def result = script.toolGetHubInfo([includeAppUpdate: true])

        then:
        result.platformUpdate.available == true             // pending HUB firmware
        result.platformUpdate.availableVersion == '2.5.0.153'
        result.appUpdate.latestVersion == '9.9.9'           // folded-in MCP server app check
        result.appUpdate.updateAvailable == true            // derived: 9.9.9 is newer than the installed version
        result.appUpdate.checkInProgress == true            // a fresh async check was kicked off for next time
        (result.appUpdate.installedVersion as String) ==~ /\d+\.\d+\.\d+.*/
    }

    def "hub_get_info appUpdate derives updateAvailable from the versions, so a stale flag can't survive an upgrade"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        // Reproduce the post-HPM-upgrade state: the last check recorded updateAvailable:true against an
        // older installed version, and still says true even though latest now equals the installed version.
        def installed = script.currentVersion()
        stateMap.updateCheck = [latestVersion: installed, updateAvailable: true, checkedAt: 1700000000000L]
        script.metaClass.doUpdateCheck = { -> true }

        when:
        def result = script.toolGetHubInfo([includeAppUpdate: true])

        then:
        result.appUpdate.latestVersion == installed
        result.appUpdate.updateAvailable == false           // latest == installed -> no update; the stale true is ignored
    }

    def "hub_get_info appUpdate reports in-progress when no prior check has completed"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        stateMap.updateCheck = null
        script.metaClass.doUpdateCheck = { -> true }

        when:
        def result = script.toolGetHubInfo([includeAppUpdate: true])

        then:
        result.appUpdate.latestVersion == 'unknown (check in progress)'
        result.appUpdate.updateAvailable == false
        result.appUpdate.lastChecked == 'never'
        result.appUpdate.checkInProgress == true
    }

    def "hub_get_info appUpdate reports checkInProgress from doUpdateCheck (false when the refresh did not start)"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        stateMap.updateCheck = [latestVersion: '9.9.9', updateAvailable: true, checkedAt: 1700000000000L]
        script.metaClass.doUpdateCheck = { -> false }       // refresh failed to start

        when:
        def result = script.toolGetHubInfo([includeAppUpdate: true])

        then: "we don't claim a refresh is pending when it never started"
        result.appUpdate.checkInProgress == false
        result.appUpdate.latestVersion == '9.9.9'           // the prior snapshot still surfaces
    }

    def "hub_get_info appUpdate leaves the stored checkedAt untouched (the read never rewrites it)"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        def seeded = 1700000000000L
        stateMap.updateCheck = [latestVersion: '9.9.9', checkedAt: seeded]
        script.metaClass.doUpdateCheck = { -> true }        // async write lands in a LATER execution

        when:
        def result = script.toolGetHubInfo([includeAppUpdate: true])

        then: "the snapshot reports the seeded timestamp and the read does not null or rewrite it"
        result.appUpdate.lastChecked == script.formatTimestamp(seeded)
        stateMap.updateCheck.checkedAt == seeded
    }

    def "hub_get_info appUpdate surfaces lastCheckError when the last check failed"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        // A failed check advances checkedAt + lastError but keeps the older latestVersion.
        stateMap.updateCheck = [latestVersion: '9.9.9', checkedAt: 1700000000000L, lastError: 'http 503']
        script.metaClass.doUpdateCheck = { -> true }

        when:
        def result = script.toolGetHubInfo([includeAppUpdate: true])

        then: "the error is surfaced so lastChecked doesn't imply a fresher version than we have"
        result.appUpdate.lastCheckError == 'http 503'
        result.appUpdate.latestVersion == '9.9.9'
    }

    def "hub_get_info appUpdate degrades to the normal snapshot (not an error map) when the async check throws"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        // Real doUpdateCheck runs: asynchttpGet throws before scheduling -> doUpdateCheck catches it and
        // returns false, so appUpdate is the ordinary prior-check view rather than an error map.
        appCheckThrows = true
        stateMap.updateCheck = [latestVersion: '9.9.9', checkedAt: 1700000000000L]

        when:
        def result = script.toolGetHubInfo([includeAppUpdate: true])

        then:
        !result.appUpdate.containsKey('error')          // the throw is swallowed, not surfaced as an error map
        result.appUpdate.checkInProgress == false       // and we don't claim a refresh that never started
        result.appUpdate.latestVersion == '9.9.9'       // the prior snapshot still surfaces
        result.platformUpdate.available == true         // the firmware read still survives
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

    // -------- includeNetwork: the folded hub_get_network_settings read --------
    // The network-config read (GET /hub2/networkConfiguration) is folded into hub_get_info as the opt-in
    // includeNetwork flag. Field names RE'd from resources/hub2-source/vue-hub2.min.js. The planted
    // psk/wifiPassword keys below are NOT sent by the real endpoint; they prove the allowlist drops them.
    // The endpoint returns the SSID (wifiNetwork) but NEVER the password. currentLanAddress is
    // deliberately absent from the block -- hub_get_info already reports it as localIP.
    private static final String NET_JSON = '''{
        "hubVersion": 10,
        "usingStaticIP": true,
        "lanAddr": "192.168.1.50",
        "wlanAddr": "192.168.1.51",
        "wifiNetwork": "HomeNet",
        "dnsServers": ["8.8.8.8", "1.1.1.1"],
        "useDNSFallover": true,
        "dhcpNameServers": ["9.9.9.9"],
        "staticIP": "192.168.1.50",
        "staticGateway": "192.168.1.1",
        "staticSubnetMask": "255.255.255.0",
        "staticNameServers": "208.67.222.222, 208.67.220.220",
        "lanAutoneg": false,
        "wifiDriversInstalled": true,
        "hasEthernet": true,
        "hasWiFi": true,
        "restartBonjourOnSchedule": false,
        "psk": "hunter2-should-never-leak",
        "wifiPassword": "also-secret"
    }'''

    def "hub_get_info omits the network block unless includeNetwork=true"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        hubGet.register('/hub2/networkConfiguration') { params -> NET_JSON }

        when:
        def result = script.toolGetHubInfo([:])

        then:
        !result.containsKey('network')
    }

    def "hub_get_info includeNetwork projects the networkConfiguration into a network block"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        hubGet.register('/hub2/networkConfiguration') { params -> NET_JSON }

        when:
        def result = script.toolGetHubInfo([includeNetwork: true])

        then: 'ip mode is derived from usingStaticIP'
        def net = result.network
        net.success == true
        net.ipMode == 'static'

        and: 'the current LAN address is NOT duplicated here (it is top-level localIP)'
        !net.containsKey('currentLanAddress')
        net.currentWifiAddress == '192.168.1.51'

        and: 'DNS lists normalize whether the hub sent an array or a comma-joined string'
        net.activeDnsServers == ['8.8.8.8', '1.1.1.1']
        net.dhcpNameServers == ['9.9.9.9']
        net.staticNameServers == ['208.67.222.222', '208.67.220.220']

        and: 'static + dhcp config and the capability/ethernet flags'
        net.staticIp == '192.168.1.50'
        net.staticGateway == '192.168.1.1'
        net.staticSubnetMask == '255.255.255.0'
        net.useDNSFallover == true
        net.ethernetAutoneg == false
        net.hasEthernet == true
        net.hasWiFi == true
        net.wifiDriversInstalled == true
        net.hubVersion == 10
        net.wifiSsid == 'HomeNet'
    }

    def "hub_get_info includeNetwork NEVER returns the Wi-Fi password, even when the wire carries one"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        hubGet.register('/hub2/networkConfiguration') { params -> NET_JSON }

        when:
        def result = script.toolGetHubInfo([includeNetwork: true])

        then: 'no secret-shaped key, and no secret value, anywhere in the projected block'
        result.network.keySet().every { !it.toLowerCase().contains('psk') }
        result.network.keySet().every { !it.toLowerCase().contains('password') }
        def serialized = JsonOutput.toJson(result.network)
        !serialized.contains('hunter2-should-never-leak')
        !serialized.contains('also-secret')
        result.network.note.toLowerCase().contains('password')
    }

    def "hub_get_info includeNetwork reports ipMode=unknown when usingStaticIP is not a Boolean"() {
        given: 'the hub omits usingStaticIP -- must NOT be silently read as dhcp'
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        hubGet.register('/hub2/networkConfiguration') { params -> '{"lanAddr": "10.0.0.5"}' }

        when:
        def result = script.toolGetHubInfo([includeNetwork: true])

        then:
        result.network.success == true
        result.network.ipMode == 'unknown'
        result.network.activeDnsServers == []
    }

    def "hub_get_info includeNetwork derives ipMode from a real Boolean usingStaticIP"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        hubGet.register('/hub2/networkConfiguration') { params -> '{"usingStaticIP": false, "lanAddr": "10.0.0.5"}' }

        when:
        def result = script.toolGetHubInfo([includeNetwork: true])

        then:
        result.network.ipMode == 'dhcp'
        !result.network.containsKey('currentLanAddress')
    }

    @spock.lang.Unroll
    def "hub_get_info includeNetwork returns a structured error block (never a throw) when the endpoint answers #label"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        hubGet.register('/hub2/networkConfiguration') { params -> response }

        when:
        def result = script.toolGetHubInfo([includeNetwork: true])

        then:
        noExceptionThrown()
        result.network.success == false
        result.network.error
        result.network.note.contains('hub_admin_write_system')

        where:
        label                  | response
        'an empty body'        | ''
        'a JSON array'         | '[1, 2, 3]'
        'a non-JSON HTML page' | '<html>Not Found</html>'
    }

    def "hub_get_info includeNetwork via dispatch returns the network block (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        sharedLocation.hub = hubOnFirmware('2.5.0.143')
        hubGet.register('/hub2/hubData') { params -> HUB2_UPDATE_AND_ALERT }
        hubGet.register('/hub2/networkConfiguration') { params -> NET_JSON }

        when:
        def response = mcpDriver.callTool('hub_get_info', [includeNetwork: true])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.network.success == true
        inner.network.ipMode == 'static'
        inner.network.wifiSsid == 'HomeNet'
        !JsonOutput.toJson(inner.network).contains('hunter2-should-never-leak')

        where:
        useGateways << [true, false]
    }
}
