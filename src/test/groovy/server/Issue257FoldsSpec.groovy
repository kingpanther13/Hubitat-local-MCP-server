package server

import support.TestDevice
import support.TestLocation
import support.ToolSpecBase
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Issue #257 capability folds (no new tools):
 *  - hub_get_radio_details radio='matter'  -> toolGetMatterDetails (/hub/matterDetails/json)
 *  - hub_get_device_health traceroute      -> /hub/networkTest/traceroute/<ipv4>
 *  - hub_get_device_health speedtest       -> /hub/networkTest/speedtest
 *
 * Mocking: hubInternalGet -> hubGet.register(path){...}. radio reads location.hub,
 * so wire a non-null @Shared TestLocation in setupSpec (same pattern as
 * Issue257DeviceAppMeshSpec).
 */
class Issue257FoldsSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        // toolGetMatterDetails does not read location.hub, but the radio-details
        // dispatch and the sibling Z-Wave/Zigbee helpers do; wire a stub so any
        // shared-script path that touches location.hub resolves.
        appExecutor.getLocation() >> sharedLocation
    }

    // ---- FOLD 1: Matter into hub_get_radio_details -----------------------

    def "radio='matter' returns parsed matterData from /hub/matterDetails/json"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/matterDetails/json') { params ->
            JsonOutput.toJson([
                enabled: true, installed: true, networkState: "Online",
                fabricId: "3E4FEE8CED4BBA2C",
                devices: [[nodeId: 3001, online: true, name: "HW1", manufacturer: "Leedarson"]]
            ])
        }

        when:
        def result = script.toolGetMatterDetails([:])

        then:
        result.source == "hub_api"
        result.endpoint == "/hub/matterDetails/json"
        result.matterData.fabricId == "3E4FEE8CED4BBA2C"
        result.matterData.networkState == "Online"
        result.matterData.devices[0].name == "HW1"
    }

    def "radio='matter' falls back to sdk_only with a C-8 note when the endpoint is empty (benign: no error field)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/matterDetails/json') { params -> null }

        when:
        def result = script.toolGetMatterDetails([:])

        then:
        result.source == "sdk_only"
        result.note?.toLowerCase()?.contains("c-8")
        !result.containsKey("matterData")
        // Empty 2xx is the benign "no Matter radio" signal -- NOT a fault, so no error field.
        !result.containsKey("error")
    }

    def "radio='matter' captures a raw body when the response is not JSON"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/matterDetails/json') { params -> "<html>not json</html>" }

        when:
        def result = script.toolGetMatterDetails([:])

        then:
        result.source == "hub_api_raw"
        result.rawResponse?.contains("not json")
        result.note == "Response was not JSON format"
    }

    def "radio='matter' surfaces a structured error when the fetch throws (fault != absent hardware)"() {
        given:
        settingsMap.enableRead = true
        // No handler registered -> HubInternalGetMock throws, exercising the catch path.

        when:
        def result = script.toolGetMatterDetails([:])

        then:
        result.source == "sdk_only"
        // A thrown request is a genuine fault, distinguished from the benign empty-2xx
        // "no Matter radio" case by the error field (silent-failure-hunter finding).
        result.error?.toLowerCase()?.contains("matter query failed")
        result.note?.toLowerCase()?.contains("matter")
    }

    def "invalid radio value throws (matter is the only added member)"() {
        when:
        script.toolGetRadioDetails([radio: "thread"])

        then:
        thrown(IllegalArgumentException)
    }

    def "radio is case-normalized so a capitalized 'Matter' still dispatches"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/matterDetails/json') { params ->
            JsonOutput.toJson([enabled: true, fabricId: "CAFE", devices: []])
        }

        when:
        def result = script.toolGetRadioDetails([radio: "Matter"])

        then:
        result.source == "hub_api"
        result.matterData.fabricId == "CAFE"
    }

    def "radio case-normalization also covers Z-Wave ('ZWAVE' dispatches, does not throw)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { params -> JsonOutput.toJson([enabled: true]) }

        when:
        def result = script.toolGetRadioDetails([radio: "ZWAVE"])

        then:
        // Routed to the Z-Wave helper (single-radio shape), not the both-shape or a throw.
        !result.containsKey("zigbee")
        result.source == "hub_api"
    }

    def "omitting radio keeps the Z-Wave + Zigbee both-shape (Matter not added to OMIT)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { params -> JsonOutput.toJson([enabled: true]) }
        hubGet.register('/hub/zigbeeDetails/json') { params -> JsonOutput.toJson([enabled: true]) }

        when:
        def result = script.toolGetRadioDetails([:])

        then:
        result.containsKey("zwave")
        result.containsKey("zigbee")
        !result.containsKey("matter")
        !result.containsKey("matterData")
    }

    // ---- FOLD 2: traceroute into hub_get_device_health -------------------

    def "traceroute happy path returns the route table under result.traceroute"() {
        given:
        settingsMap.enableRead = true
        settingsMap.selectedDevices = []
        hubGet.register('/hub/networkTest/traceroute/1.2.3.4') { params ->
            "traceroute to 1.2.3.4 (1.2.3.4), 30 hops max\n 1  router (192.168.1.1)  0.8 ms\n 2  1.2.3.4  10 ms"
        }

        when:
        def result = script.toolDeviceHealthCheck([traceroute: "1.2.3.4"])

        then:
        result.traceroute.host == "1.2.3.4"
        result.traceroute.endpoint == "/hub/networkTest/traceroute/1.2.3.4"
        result.traceroute.output.contains("hops max")
        !result.traceroute.containsKey("error")
    }

    def "traceroute rejects a non-IPv4 host with IllegalArgumentException"() {
        given:
        settingsMap.enableRead = true

        when:
        script.toolDeviceHealthCheck([traceroute: "example.com"])

        then:
        thrown(IllegalArgumentException)
    }

    def "traceroute rejects malformed/dangerous host '#bad' (IPv4 gate guards the path interpolation)"() {
        given:
        settingsMap.enableRead = true

        when:
        script.toolDeviceHealthCheck([traceroute: bad])

        then:
        thrown(IllegalArgumentException)

        where:
        // Out-of-range octet, wrong arity, empty (post-trim), and a shell/path-injection
        // attempt -- all must be rejected before the host reaches the endpoint path.
        bad << ["256.1.1.1", "1.2.3.999", "1.2.3", "1.2.3.4.5", "", "8.8.8.8/../../hub/reboot", "8.8.8.8; ls"]
    }

    def "traceroute fetch failure sets result.traceroute.error instead of throwing"() {
        given:
        settingsMap.enableRead = true
        settingsMap.selectedDevices = []
        // No handler registered for the traceroute path -> fetch throws -> caught.

        when:
        def result = script.toolDeviceHealthCheck([traceroute: "9.9.9.9"])

        then:
        result.traceroute.host == "9.9.9.9"
        result.traceroute.error?.toLowerCase()?.contains("traceroute failed")
        !result.traceroute.containsKey("output")
    }

    // ---- FOLD 2: speedtest into hub_get_device_health -------------------

    def "speedtest happy path returns the wget log under result.speedtest"() {
        given:
        settingsMap.enableRead = true
        settingsMap.selectedDevices = []
        hubGet.register('/hub/networkTest/speedtest') { params ->
            "--2026-06-16--  https://hubitat-public-files.s3.us-east-2.amazonaws.com/speedtest.bin\n" +
            "2026-06-16 (2.76 MB/s) - '/dev/null' saved [10485760/10485760]"
        }

        when:
        def result = script.toolDeviceHealthCheck([speedtest: true])

        then:
        result.speedtest.endpoint == "/hub/networkTest/speedtest"
        result.speedtest.output.contains("MB/s")
        !result.speedtest.containsKey("error")
    }

    def "speedtest fetch failure sets result.speedtest.error instead of throwing"() {
        given:
        settingsMap.enableRead = true
        settingsMap.selectedDevices = []
        // No handler registered -> fetch throws -> caught.

        when:
        def result = script.toolDeviceHealthCheck([speedtest: true])

        then:
        result.speedtest.error?.toLowerCase()?.contains("speedtest failed")
        !result.speedtest.containsKey("output")
    }

    def "speedtest=false (or omitted) does not run the speedtest"() {
        given:
        settingsMap.enableRead = true
        settingsMap.selectedDevices = []

        when:
        def result = script.toolDeviceHealthCheck([speedtest: false])

        then:
        result.speedtest == null
        // Would have thrown an Unstubbed hubInternalGet if it had fired.
    }

    def "traceroute + speedtest also attach on the no-devices-selected early return"() {
        given:
        settingsMap.enableRead = true
        settingsMap.selectedDevices = null
        hubGet.register('/hub/networkTest/traceroute/8.8.8.8') { params -> "route ok" }
        hubGet.register('/hub/networkTest/speedtest') { params -> "1.5 MB/s" }

        when:
        def result = script.toolDeviceHealthCheck([traceroute: "8.8.8.8", speedtest: true])

        then:
        result.message == "No devices selected for MCP access"
        result.traceroute.output == "route ok"
        result.speedtest.output == "1.5 MB/s"
    }

    def "traceroute + speedtest attach on the normal (devices-present) return path too"() {
        given:
        settingsMap.enableRead = true
        // A populated selection skips the no-devices early return and reaches the
        // bottom-of-function attach. The device has no lastActivity -> 'unknown',
        // which is fine: we only need the populated-result path exercised.
        settingsMap.selectedDevices = [new TestDevice(id: 1, name: "d1", label: "Living Room Light")]
        hubGet.register('/hub/networkTest/traceroute/8.8.8.8') { params -> "1  router  0.5 ms\n2  8.8.8.8  12 ms" }
        hubGet.register('/hub/networkTest/speedtest') { params -> "(2.7 MB/s) saved [10485760/10485760]" }

        when:
        def result = script.toolDeviceHealthCheck([traceroute: "8.8.8.8", speedtest: true])

        then:
        // Reached the populated-result path, not the early "No devices selected" return.
        !result.containsKey("message")
        result.summary.totalDevices == 1
        result.traceroute.output.contains("8.8.8.8")
        result.speedtest.output.contains("MB/s")
    }

    // ---- Dispatch via executeTool ---------------------------------------

    def "dispatch: hub_get_radio_details radio='matter' routes through executeTool"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/matterDetails/json') { params ->
            JsonOutput.toJson([enabled: true, fabricId: "ABCD", devices: []])
        }

        when:
        def result = script.executeTool("hub_get_radio_details", [radio: "matter"])

        then:
        result.source == "hub_api"
        result.matterData.fabricId == "ABCD"
    }

    def "dispatch: hub_get_device_health traceroute routes through executeTool"() {
        given:
        settingsMap.enableRead = true
        settingsMap.selectedDevices = []
        hubGet.register('/hub/networkTest/traceroute/1.2.3.4') { params -> "hops here" }

        when:
        def result = script.executeTool("hub_get_device_health", [traceroute: "1.2.3.4"])

        then:
        result.traceroute.host == "1.2.3.4"
        result.traceroute.output == "hops here"
    }

    def "dispatch: case-normalized radio='Matter' survives the executeTool layer"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/matterDetails/json') { params ->
            JsonOutput.toJson([enabled: true, fabricId: "BEEF", devices: []])
        }

        when:
        // executeTool does not enum-validate args (only master/override gating), so a
        // capitalized value reaches the handler -- this proves the fold works end-to-end
        // for a client that capitalizes the enum.
        def result = script.executeTool("hub_get_radio_details", [radio: "Matter"])

        then:
        result.source == "hub_api"
        result.matterData.fabricId == "BEEF"
    }
}
