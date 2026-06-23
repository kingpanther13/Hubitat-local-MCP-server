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
}
