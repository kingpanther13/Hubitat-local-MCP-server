package server

import support.TestHub
import support.TestLocation
import support.ToolSpecBase
import spock.lang.Shared

/**
 * Spec for hub_set_system_settings in libraries/mcp-system-lib.groovy (issue #259):
 *   toolSetSystemSettings -> hub_set_system_settings (hub-GLOBAL location settings write)
 *
 * Wire format (verified against resources/hub2-source/vue-hub2.min.js):
 *   hubName                                       -> GET /hub/updateName?name=<urlenc>
 *   latitude/longitude/timeZone/temperatureScale/zipCode -> ONE GET /hub/updateLatLongTimezone
 *        (granular wholesale endpoint -> read-merge current location.* values; zipCode -> postalCode)
 *   identifyHub==true                             -> GET /hub/advanced/blinkLED
 *
 * The /hub/* GETs are stubbed via hubGet.register (the harness routes hubInternalGet through it);
 * the SDK location.* read-merge baseline comes from a shared TestLocation seeded with realistic
 * coordinates/scale/zip. A timeZone change reboots the hub, so that leg is confirm-gated
 * (enableWrite()+lastBackupTimestamp satisfy the gate); the other fields are Write-master-only.
 */
class ToolSystemSettingsSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        // Deterministic read-merge baseline (TestLocation defaults, restated so a prior test's
        // mutation can't leak): UTC tz, NYC coordinates, F scale, 10001 zip.
        sharedLocation.timeZone = TimeZone.getTimeZone('UTC')
        sharedLocation.latitude = 40.7128
        sharedLocation.longitude = -74.006
        sharedLocation.temperatureScale = 'F'
        sharedLocation.zipCode = '10001'
        sharedLocation.hub = new TestHub()
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L   // matches the harness fixed now()
    }

    private String latLongCall() {
        // The single /hub/updateLatLongTimezone GET this run made (path includes the query string).
        return hubGet.calls.collect { it.path }.find { it.startsWith('/hub/updateLatLongTimezone') }
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
    }

    def "confirm-only (no settable field) still throws -- confirm is not a settable field"() {
        given:
        enableWrite()

        when:
        script.toolSetSystemSettings([confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    // ---------- hubName ----------

    def "hubName URL-encodes the value and GETs /hub/updateName"() {
        given:
        enableWrite()
        hubGet.register('/hub/updateName?name=My+New+Hub') { params -> 'OK' }

        when:
        def result = script.toolSetSystemSettings([hubName: 'My New Hub'])

        then:
        result.success == true
        result.applied == ['hubName']
        hubGet.calls.any { it.path == '/hub/updateName?name=My+New+Hub' }
        // hubName alone must NOT touch the lat/long/tz endpoint
        latLongCall() == null
    }

    // ---------- updateLatLongTimezone read-merge ----------

    def "temperatureScale-only read-merges the other fields from location.* into one updateLatLongTimezone GET"() {
        given:
        enableWrite()
        // Wholesale endpoint: changing only temperatureScale must still carry the CURRENT
        // lat/long/tz/zip (read-merged) so they aren't blanked.
        def expected = '/hub/updateLatLongTimezone?latitude=40.7128&longitude=-74.006&timeZone=UTC&temperatureScale=C&postalCode=10001'
        hubGet.register(expected) { params -> 'OK' }

        when:
        def result = script.toolSetSystemSettings([temperatureScale: 'C'])

        then:
        result.success == true
        result.applied == ['temperatureScale']
        latLongCall() == expected
        !hubGet.calls.any { it.path.startsWith('/hub/updateName') }
    }

    def "latitude/longitude override only those fields; timeZone/scale/zip come from location.*"() {
        given:
        enableWrite()
        def expected = '/hub/updateLatLongTimezone?latitude=51.5074&longitude=-0.1278&timeZone=UTC&temperatureScale=F&postalCode=10001'
        hubGet.register(expected) { params -> 'OK' }

        when:
        def result = script.toolSetSystemSettings([latitude: 51.5074, longitude: -0.1278])

        then:
        result.success == true
        result.applied as Set == ['latitude', 'longitude'] as Set
        latLongCall() == expected
    }

    def "zipCode maps to the postalCode query param (URL-encoded)"() {
        given:
        enableWrite()
        hubGet.register('/hub/updateLatLongTimezone?latitude=40.7128&longitude=-74.006&timeZone=UTC&temperatureScale=F&postalCode=SW1A+1AA') { params -> 'OK' }

        when:
        def result = script.toolSetSystemSettings([zipCode: 'SW1A 1AA'])

        then:
        result.success == true
        latLongCall().contains('postalCode=SW1A+1AA')
        !latLongCall().contains('zipCode=')
    }

    def "timeZone value is URL-encoded (slash -> %2F) in the merged GET"() {
        given:
        enableWrite()
        def expected = '/hub/updateLatLongTimezone?latitude=40.7128&longitude=-74.006&timeZone=America%2FNew_York&temperatureScale=F&postalCode=10001'
        hubGet.register(expected) { params -> 'OK' }

        when:
        def result = script.toolSetSystemSettings([timeZone: 'America/New_York', confirm: true])

        then:
        result.success == true
        result.applied == ['timeZone']
        latLongCall() == expected
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

        where:
        bad << ['f', 'c', 'Fahrenheit', 'K', '']
    }

    // ---------- confirm gate (timeZone only) ----------

    def "timeZone change WITHOUT confirm throws the destructive gate (and makes no hub call)"() {
        given:
        settingsMap.enableWrite = true   // no backup, no confirm

        when:
        script.toolSetSystemSettings([timeZone: 'America/Chicago'])

        then:
        thrown(IllegalArgumentException)
        // the gate fires before any hub write
        latLongCall() == null
    }

    def "timeZone change WITH confirm + recent backup is allowed"() {
        given:
        enableWrite()   // sets a backup timestamp matching now() -> within 24h
        def expected = '/hub/updateLatLongTimezone?latitude=40.7128&longitude=-74.006&timeZone=America%2FChicago&temperatureScale=F&postalCode=10001'
        hubGet.register(expected) { params -> 'OK' }

        when:
        def result = script.toolSetSystemSettings([timeZone: 'America/Chicago', confirm: true])

        then:
        result.success == true
        result.applied == ['timeZone']
        latLongCall() == expected
    }

    def "confirm is NOT required for non-timeZone fields (no backup present)"() {
        given:
        settingsMap.enableWrite = true   // deliberately NO backup timestamp
        hubGet.register('/hub/updateName?name=Loft') { params -> 'OK' }

        when:
        def result = script.toolSetSystemSettings([hubName: 'Loft'])

        then:
        result.success == true
        result.applied == ['hubName']
    }

    // ---------- hubName + lat/long/tz in one call ----------

    def "hubName + latitude in one call: both legs fire (updateName + updateLatLongTimezone)"() {
        given:
        enableWrite()
        hubGet.register('/hub/updateName?name=Den') { params -> 'OK' }
        def expectedLL = '/hub/updateLatLongTimezone?latitude=12.34&longitude=-74.006&timeZone=UTC&temperatureScale=F&postalCode=10001'
        hubGet.register(expectedLL) { params -> 'OK' }

        when:
        def result = script.toolSetSystemSettings([hubName: 'Den', latitude: 12.34])

        then:
        result.success == true
        result.applied as Set == ['hubName', 'latitude'] as Set
        hubGet.calls.any { it.path == '/hub/updateName?name=Den' }
        latLongCall() == expectedLL
    }

    // ---------- runtime-error envelope (hub call fails) ----------

    def "a failing hub call returns the structured error envelope (does NOT throw)"() {
        given:
        enableWrite()
        hubGet.register('/hub/updateName?name=Boom') { params -> throw new RuntimeException('relay down') }

        when:
        def result = script.toolSetSystemSettings([hubName: 'Boom'])

        then:
        result.success == false
        result.error.contains('relay down')
        result.note != null
        result.applied == []   // failed before appending hubName
    }

    // ---------- dispatch envelope ----------

    def "hub_set_system_settings via dispatch returns the success envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/hub/updateName?name=Dispatch') { params -> 'OK' }

        when:
        def response = mcpDriver.callTool('hub_set_system_settings', [hubName: 'Dispatch'])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.applied == ['hubName']

        where:
        useGateways << [true, false]
    }
}
