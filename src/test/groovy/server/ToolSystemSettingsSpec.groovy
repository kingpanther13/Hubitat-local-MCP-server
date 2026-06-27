package server

import support.TestHub
import support.TestLocation
import support.ToolSpecBase
import spock.lang.Shared
import groovy.json.JsonSlurper

/**
 * Spec for hub_set_system_settings in libraries/mcp-system-lib.groovy (issue #259):
 *   toolSetSystemSettings -> hub_set_system_settings (hub-GLOBAL location/identity settings write)
 *
 * Wire format (verified live against a real hub):
 *   READ current state: GET /hub/details/json -> {hubName,timeZone,latitude,longitude,zipCode,
 *                       tempScale,dateFormat,timeFormat,ttsCurrent,mdnsName,...}
 *   WRITE (wholesale):  POST /location/update {name,timeZone,latitude,longitude,clock,dateFormat,
 *                       zipCode,temperatureScale,voice,mdnsName} -- read-merge: build from the current
 *                       values, override only the provided args; hubName is the `name` field, so every
 *                       setting goes through this ONE atomic POST.
 *
 * The GET is stubbed via hubGet.register; the POST is captured via script.metaClass.hubInternalPostJson.
 * A timeZone change reboots the hub, so it is confirm-gated (enableWrite()+lastBackupTimestamp satisfy it).
 */
class ToolSystemSettingsSpec extends ToolSpecBase {

    // The current-state JSON the hub returns from /hub/details/json (the read-merge source).
    private static final String DETAILS = '{"hubName":"CrameHub","timeZone":"US/Eastern","latitude":28.3078,' +
        '"longitude":-81.3681,"zipCode":"34744","tempScale":"F","dateFormat":"MDY","timeFormat":"12",' +
        '"ttsCurrent":"Nicole","mdnsName":"hubitat"}'

    @Shared private TestLocation sharedLocation = new TestLocation()

    private Map posted   // captured POST: [path: ..., body: <parsed JSON map>]

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hub = new TestHub()
        posted = [:]
        hubGet.register('/hub/details/json') { params -> DETAILS }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            posted.path = path
            posted.body = new JsonSlurper().parseText(body)
            return [success: true]
        }
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L   // matches the harness fixed now()
    }

    // ---------- no-args validation ----------

    def "no settable field provided throws IllegalArgumentException listing the fields"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('hubName')
        ex.message.contains('temperatureScale')
        ex.message.contains('timeZone')
        posted.isEmpty()
    }

    def "confirm-only (no settable field) still throws -- confirm is not a settable field"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    // ---------- read-merge POST /location/update ----------

    def "temperatureScale-only read-merges the FULL payload from /hub/details/json into one POST"() {
        given:
        enableWrite()

        when:
        def result = script.toolSetSystemSettings([temperatureScale: 'C'])

        then:
        result.success == true
        result.applied == ['temperatureScale']
        posted.path == '/location/update'
        posted.body.temperatureScale == 'C'      // changed
        // every other field preserved from the current /hub/details/json (no blanking)
        posted.body.name == 'CrameHub'
        posted.body.timeZone == 'US/Eastern'
        posted.body.latitude == 28.3078
        posted.body.longitude == -81.3681
        posted.body.zipCode == '34744'
        posted.body.clock == '12'                // timeFormat -> clock, carried through
        posted.body.dateFormat == 'MDY'
        posted.body.voice == 'Nicole'            // ttsCurrent -> voice, carried through
        posted.body.mdnsName == 'hubitat'
    }

    def "hubName maps to the payload's name field; others preserved"() {
        given:
        enableWrite()

        when:
        def result = script.toolSetSystemSettings([hubName: 'Loft'])

        then:
        result.success == true
        result.applied == ['hubName']
        posted.body.name == 'Loft'
        posted.body.temperatureScale == 'F'   // preserved from cur.tempScale
        posted.body.timeZone == 'US/Eastern'
    }

    def "latitude/longitude override only those fields; the rest come from the current state"() {
        given:
        enableWrite()

        when:
        def result = script.toolSetSystemSettings([latitude: 51.5074, longitude: -0.1278])

        then:
        result.success == true
        result.applied as Set == ['latitude', 'longitude'] as Set
        posted.body.latitude == 51.5074
        posted.body.longitude == -0.1278
        posted.body.timeZone == 'US/Eastern'
        posted.body.temperatureScale == 'F'
    }

    def "zipCode overrides the payload zipCode"() {
        given:
        enableWrite()

        when:
        def result = script.toolSetSystemSettings([zipCode: 'SW1A 1AA'])

        then:
        result.success == true
        result.applied == ['zipCode']
        posted.body.zipCode == 'SW1A 1AA'
    }

    // ---------- temperatureScale validation ----------

    def "temperatureScale must be F or C (#bad rejected)"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([temperatureScale: bad])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('temperatureScale')
        posted.isEmpty()

        where:
        bad << ['f', 'c', 'Fahrenheit', 'K', '']
    }

    // ---------- lat/long range validation ----------

    def "out-of-range / non-numeric #key=#val is rejected (-> -32602) with no POST"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([(key): val])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(key)
        posted.isEmpty()

        where:
        key         | val
        'latitude'  | 91
        'latitude'  | -91
        'longitude' | 181
        'longitude' | -181
        'latitude'  | 'notanumber'
    }

    def "string coordinates coerce to numbers in the payload"() {
        given:
        enableWrite()

        when:
        def result = script.toolSetSystemSettings([latitude: '45.0', longitude: '-120.5'])

        then:
        result.success == true
        posted.body.latitude == 45.0       // coerced to a number, not the string "45.0"
        posted.body.longitude == -120.5
    }

    // ---------- confirm gate (timeZone only) ----------

    def "timeZone change WITHOUT confirm throws the destructive gate and makes no POST"() {
        given:
        settingsMap.enableWrite = true   // no backup, no confirm

        when:
        script.toolSetSystemSettings([timeZone: 'America/Chicago'])

        then:
        thrown(IllegalArgumentException)
        posted.isEmpty()
    }

    def "timeZone change WITH confirm + recent backup is allowed and warns about the reboot"() {
        given:
        enableWrite()

        when:
        def result = script.toolSetSystemSettings([timeZone: 'America/Chicago', confirm: true])

        then:
        result.success == true
        result.applied == ['timeZone']
        posted.body.timeZone == 'America/Chicago'
        result.note.contains('reboot')
    }

    def "confirm is NOT required for non-timeZone fields (no backup present)"() {
        given:
        settingsMap.enableWrite = true   // deliberately NO backup timestamp

        when:
        def result = script.toolSetSystemSettings([hubName: 'Loft'])

        then:
        result.success == true
        result.applied == ['hubName']
    }

    // ---------- darkMode (independent setter: GET /hub/applyDarkMode/<bool>) ----------

    def "darkMode=true fires GET /hub/applyDarkMode/true and reports it applied"() {
        given:
        enableWrite()
        hubGet.register('/hub/applyDarkMode/true') { params -> "" }   // HTTP 200 empty body

        when:
        def result = script.toolSetSystemSettings([darkMode: true])

        then:
        result.success == true
        result.applied == ['darkMode']
        hubGet.calls.any { it.path == '/hub/applyDarkMode/true' }
        // darkMode-ONLY: no location read-merge, no /location/update POST
        hubGet.calls.every { it.path != '/hub/details/json' }
        posted.isEmpty()
    }

    def "darkMode=false fires GET /hub/applyDarkMode/false"() {
        given:
        enableWrite()
        hubGet.register('/hub/applyDarkMode/false') { params -> "" }

        when:
        def result = script.toolSetSystemSettings([darkMode: false])

        then:
        result.success == true
        result.applied == ['darkMode']
        hubGet.calls.any { it.path == '/hub/applyDarkMode/false' }
        posted.isEmpty()
    }

    def "a darkMode-only call is valid and makes NO /location/update POST"() {
        given:
        enableWrite()
        hubGet.register('/hub/applyDarkMode/true') { params -> "" }

        when:
        def result = script.toolSetSystemSettings([darkMode: true])

        then:
        result.success == true
        result.applied == ['darkMode']
        posted.isEmpty()                                            // proves no POST /location/update
        hubGet.calls.every { it.path != '/hub/details/json' }       // proves no read-merge GET
    }

    def "combined hubName + darkMode applies BOTH (location POST + dark-mode setter)"() {
        given:
        enableWrite()
        hubGet.register('/hub/applyDarkMode/true') { params -> "" }

        when:
        def result = script.toolSetSystemSettings([hubName: 'Loft', darkMode: true])

        then:
        result.success == true
        result.applied as Set == ['hubName', 'darkMode'] as Set
        posted.path == '/location/update'
        posted.body.name == 'Loft'
        hubGet.calls.any { it.path == '/hub/applyDarkMode/true' }
    }

    def "darkMode-only with the STRING 'false' applies light mode (not coerced truthy)"() {
        given:
        enableWrite()
        hubGet.register('/hub/applyDarkMode/false') { params -> "" }

        when:
        // Groovy treats the String "false" as truthy; the tool coerces via equalsIgnoreCase, so the
        // wire value must be /false (a bare `args.darkMode ?` would wrongly send /true).
        def result = script.toolSetSystemSettings([darkMode: 'false'])

        then:
        result.success == true
        result.applied == ['darkMode']
        hubGet.calls.any { it.path == '/hub/applyDarkMode/false' }
    }

    def "a darkMode endpoint failure returns the structured error mentioning dark mode; prior location change is in applied"() {
        given:
        enableWrite()
        // The location leg succeeds (POST stubbed in setup), then the dark-mode endpoint 404s/throws
        // (e.g. older firmware without /hub/applyDarkMode).
        hubGet.register('/hub/applyDarkMode/true') { params -> throw new RuntimeException('404 Not Found') }

        when:
        def result = script.toolSetSystemSettings([hubName: 'Loft', darkMode: true])

        then:
        result.success == false
        result.error.toLowerCase().contains('dark mode')
        result.applied == ['hubName']          // the location change committed before the dark-mode leg
        result.note != null
        posted.path == '/location/update'      // the location leg DID run and succeed
    }

    def "a darkMode-only endpoint failure returns success=false with empty applied"() {
        given:
        enableWrite()
        hubGet.register('/hub/applyDarkMode/true') { params -> throw new RuntimeException('404 Not Found') }

        when:
        def result = script.toolSetSystemSettings([darkMode: true])

        then:
        result.success == false
        result.error.toLowerCase().contains('dark mode')
        result.applied == []
        posted.isEmpty()
    }

    // ---------- error envelopes (no false-green) ----------

    def "a failing POST returns the structured error envelope (does NOT throw)"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            throw new RuntimeException('relay down')
        }

        when:
        def result = script.toolSetSystemSettings([hubName: 'Boom'])

        then:
        result.success == false
        result.error.contains('relay down')
        result.applied == []
        result.note != null
    }

    def "a non-success POST body surfaces success=false (no false-green)"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            [success: false, message: 'bad value']
        }

        when:
        def result = script.toolSetSystemSettings([temperatureScale: 'C'])

        then:
        result.success == false
        result.error.contains('bad value')
        result.applied == []
    }

    def "an unreadable /hub/details/json surfaces a structured error and makes NO POST"() {
        given:
        enableWrite()
        hubGet.register('/hub/details/json') { params -> throw new RuntimeException('details down') }

        when:
        def result = script.toolSetSystemSettings([temperatureScale: 'C'])

        then:
        result.success == false
        result.error.contains('current hub settings')
        posted.isEmpty()
    }

    // ---------- dispatch envelope ----------

    def "hub_set_system_settings via dispatch returns the success envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_set_system_settings', [hubName: 'Dispatch'])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.applied == ['hubName']

        where:
        useGateways << [true, false]
    }

    def "hub_set_system_settings darkMode via dispatch returns the success envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/hub/applyDarkMode/true') { params -> "" }

        when:
        def response = mcpDriver.callTool('hub_set_system_settings', [darkMode: true])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.applied == ['darkMode']
        hubGet.calls.any { it.path == '/hub/applyDarkMode/true' }

        where:
        useGateways << [true, false]
    }

    // ---------- network config (independent confirm-gated legs; param names RE'd from vue-hub2.min.js) ----------

    def "network DHCP fires GET /hub/advanced/switchToDhcp with nameserver+useDNSFallover and no /location/update POST"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/switchToDhcp?nameserver=8.8.8.8&useDNSFallover=true') { params -> "ok" }

        when:
        def result = script.toolSetSystemSettings([network: [ipMode: 'dhcp', nameserver: '8.8.8.8', useDNSFallover: true], confirm: true])

        then:
        result.success == true
        result.applied == ['network.dhcp']
        hubGet.calls.any { it.path == '/hub/advanced/switchToDhcp?nameserver=8.8.8.8&useDNSFallover=true' }
        posted.isEmpty()   // network is its own leg, never /location/update
    }

    def "network static IP fires GET /hub/advanced/switchToStaticIp with address+netmask+gateway+nameserver"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/switchToStaticIp?address=192.168.1.50&netmask=255.255.255.0&gateway=192.168.1.1&nameserver=1.1.1.1') { params -> "ok" }

        when:
        def result = script.toolSetSystemSettings([network: [ipMode: 'static', address: '192.168.1.50',
            netmask: '255.255.255.0', gateway: '192.168.1.1', nameserver: '1.1.1.1'], confirm: true])

        then:
        result.success == true
        result.applied == ['network.staticIp']
        hubGet.calls.any { it.path == '/hub/advanced/switchToStaticIp?address=192.168.1.50&netmask=255.255.255.0&gateway=192.168.1.1&nameserver=1.1.1.1' }
    }

    def "network ethernetAutoneg fires GET /hub/advanced/network/ethernetMode/<bool>"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/network/ethernetMode/false') { params -> "ok" }

        when:
        def result = script.toolSetSystemSettings([network: [ethernetAutoneg: false], confirm: true])

        then:
        result.success == true
        result.applied == ['network.ethernetAutoneg']
        hubGet.calls.any { it.path == '/hub/advanced/network/ethernetMode/false' }
    }

    def "network WiFi fires GET /hub/advanced/setWiFiNetworkInfo with ssid+psk (psk = wifiPassword)"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/setWiFiNetworkInfo?ssid=MyNet&psk=secret123') { params -> "ok" }

        when:
        def result = script.toolSetSystemSettings([network: [wifiSsid: 'MyNet', wifiPassword: 'secret123'], confirm: true])

        then:
        result.success == true
        result.applied == ['network.wifi']
        hubGet.calls.any { it.path == '/hub/advanced/setWiFiNetworkInfo?ssid=MyNet&psk=secret123' }
    }

    def "a network-only call makes NO /location/update POST"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/switchToDhcp?nameserver=&useDNSFallover=false') { params -> "ok" }

        when:
        def result = script.toolSetSystemSettings([network: [ipMode: 'dhcp'], confirm: true])

        then:
        result.success == true
        result.applied == ['network.dhcp']
        posted.isEmpty()
        hubGet.calls.every { it.path != '/hub/details/json' }   // no location read-merge
    }

    def "network requires confirm (can disconnect the hub) and makes no hub call without it"() {
        given:
        settingsMap.enableWrite = true   // Write master on, but no backup/confirm

        when:
        script.toolSetSystemSettings([network: [ipMode: 'dhcp']])

        then:
        thrown(IllegalArgumentException)
        posted.isEmpty()
        hubGet.calls.every { !it.path.startsWith('/hub/advanced/') }
    }

    def "network static IP missing #missing is rejected (-> -32602) before any hub call"() {
        given:
        enableWrite()
        def net = [ipMode: 'static', address: '192.168.1.50', netmask: '255.255.255.0', gateway: '192.168.1.1']
        net.remove(missing)

        when:
        script.toolSetSystemSettings([network: net, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(missing)
        hubGet.calls.every { !it.path.startsWith('/hub/advanced/') }

        where:
        missing << ['address', 'netmask', 'gateway']
    }

    def "network with an invalid ipMode is rejected (-> -32602)"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([network: [ipMode: 'bogus'], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('ipMode')
    }

    def "network with an unknown sub-field is rejected (-> -32602)"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([network: [bogusKey: 'x'], confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    def "a network is not an object is rejected (-> -32602)"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([network: 'not-a-map', confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    def "a network sub-op failure returns success=false with applied listing what succeeded (partial apply)"() {
        given:
        enableWrite()
        // DHCP leg succeeds; the ethernet leg then throws -> partial apply.
        hubGet.register('/hub/advanced/switchToDhcp?nameserver=&useDNSFallover=false') { params -> "ok" }
        hubGet.register('/hub/advanced/network/ethernetMode/true') { params -> throw new RuntimeException('link down') }

        when:
        def result = script.toolSetSystemSettings([network: [ipMode: 'dhcp', ethernetAutoneg: true], confirm: true])

        then:
        result.success == false
        result.error.toLowerCase().contains('autoneg')
        result.applied == ['network.dhcp']   // the DHCP leg committed before the ethernet leg failed
        result.note != null
    }

    def "combined location + network applies BOTH (one /location/update POST + the network leg)"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/switchToDhcp?nameserver=&useDNSFallover=false') { params -> "ok" }

        when:
        def result = script.toolSetSystemSettings([hubName: 'Loft', network: [ipMode: 'dhcp'], confirm: true])

        then:
        result.success == true
        result.applied as Set == ['hubName', 'network.dhcp'] as Set
        posted.path == '/location/update'
        posted.body.name == 'Loft'
        hubGet.calls.any { it.path == '/hub/advanced/switchToDhcp?nameserver=&useDNSFallover=false' }
    }

    def "network via dispatch returns the success envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/hub/advanced/switchToDhcp?nameserver=&useDNSFallover=false') { params -> "ok" }

        when:
        def response = mcpDriver.callTool('hub_set_system_settings', [network: [ipMode: 'dhcp'], confirm: true])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.applied == ['network.dhcp']

        where:
        useGateways << [true, false]
    }

    // ---------- WiFi URL-encoding ----------

    def "WiFi ssid+psk are URL-encoded (space -> '+', symbols percent-encoded)"() {
        given:
        enableWrite()
        // URLEncoder encodes a space as '+'; @ -> %40, & -> %26, = -> %3D.
        hubGet.register('/hub/advanced/setWiFiNetworkInfo?ssid=My+Net&psk=p%40ss%26w%3Drd') { params -> "ok" }

        when:
        def result = script.toolSetSystemSettings([network: [wifiSsid: 'My Net', wifiPassword: 'p@ss&w=rd'], confirm: true])

        then:
        result.success == true
        result.applied == ['network.wifi']
        hubGet.calls.any { it.path == '/hub/advanced/setWiFiNetworkInfo?ssid=My+Net&psk=p%40ss%26w%3Drd' }
    }

    // ---------- ordered multi-leg apply ----------

    def "an ordered multi-leg network apply records the legs in apply order"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/switchToDhcp?nameserver=&useDNSFallover=false') { params -> "ok" }
        hubGet.register('/hub/advanced/network/ethernetMode/true') { params -> "ok" }
        hubGet.register('/hub/advanced/setWiFiNetworkInfo?ssid=x&psk=') { params -> "ok" }

        when:
        def result = script.toolSetSystemSettings([network: [ipMode: 'dhcp', ethernetAutoneg: true, wifiSsid: 'x'], confirm: true])

        then:
        result.success == true
        // exact order: IP mode -> Ethernet autoneg -> WiFi
        result.applied == ['network.dhcp', 'network.ethernetAutoneg', 'network.wifi']
    }

    // ---------- truthy-string trap (mirrors the darkMode string test) ----------

    def "useDNSFallover as the STRING 'false' wires useDNSFallover=false (not coerced truthy)"() {
        given:
        enableWrite()
        // Groovy treats the String "false" as truthy; the tool coerces via equalsIgnoreCase, so the
        // query must end useDNSFallover=false (a bare `network.useDNSFallover ?` would send true).
        hubGet.register('/hub/advanced/switchToDhcp?nameserver=&useDNSFallover=false') { params -> "ok" }

        when:
        def result = script.toolSetSystemSettings([network: [ipMode: 'dhcp', useDNSFallover: 'false'], confirm: true])

        then:
        result.success == true
        result.applied == ['network.dhcp']
        hubGet.calls.any { it.path.endsWith('useDNSFallover=false') }
    }

    // ---------- network-only first-leg failure ----------

    def "a network-only first-leg failure returns success=false with applied == []"() {
        given:
        enableWrite()
        // The very first leg (IP mode) throws -> nothing committed.
        hubGet.register('/hub/advanced/switchToDhcp?nameserver=&useDNSFallover=false') { params -> throw new RuntimeException('link down') }

        when:
        def result = script.toolSetSystemSettings([network: [ipMode: 'dhcp'], confirm: true])

        then:
        result.success == false
        result.applied == []
        result.error.toLowerCase().contains('dhcp')
    }

    // ---------- FIX 2: reject network shapes that apply zero legs ----------

    def "network with wifiPassword but no wifiSsid is rejected (-> -32602) before any hub call"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([network: [wifiPassword: 'secret'], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('wifiSsid')
        hubGet.calls.every { !it.path.startsWith('/hub/advanced/') }
    }

    def "network with #field but no ipMode is rejected (-> -32602) before any hub call"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([network: [(field): value], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('ipMode')
        hubGet.calls.every { !it.path.startsWith('/hub/advanced/') }

        where:
        field            | value
        'nameserver'     | '8.8.8.8'
        'useDNSFallover' | true
    }

    def "network with only static fields (no ipMode) forms no leg and is rejected (-> -32602)"() {
        given:
        enableWrite()

        when:
        // address/netmask/gateway are consumed ONLY by ipMode='static'; without ipMode this no-ops.
        script.toolSetSystemSettings([network: [address: '192.168.1.50', netmask: '255.255.255.0', gateway: '192.168.1.1'], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('ipMode')
        hubGet.calls.every { !it.path.startsWith('/hub/advanced/') }
    }

    // ---------- P1: WiFi PSK is redacted from the hub debug log ----------

    def "the debug-log path redaction masks the WiFi psk value (no plaintext password in logs)"() {
        expect:
        // _redactSecretsInPath is the single seam every internal-HTTP log line passes the path through.
        script._redactSecretsInPath('/hub/advanced/setWiFiNetworkInfo?ssid=My+Net&psk=secret123') ==
            '/hub/advanced/setWiFiNetworkInfo?ssid=My+Net&psk=***'
        // ssid (not secret) is preserved; the psk value is gone.
        !script._redactSecretsInPath('/hub/advanced/setWiFiNetworkInfo?ssid=My+Net&psk=secret123').contains('secret123')
        // also masks password=/psw= variants, leaves non-secret query values intact, null-safe.
        script._redactSecretsInPath('/x?password=hunter2&keep=1') == '/x?password=***&keep=1'
        script._redactSecretsInPath('/x?a=1&b=2') == '/x?a=1&b=2'
        script._redactSecretsInPath(null) == null
    }
}
