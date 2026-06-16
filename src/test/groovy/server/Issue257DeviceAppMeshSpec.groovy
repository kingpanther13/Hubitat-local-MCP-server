package server

import support.TestDevice
import support.TestLocation
import support.ToolSpecBase
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Issue #257 hub2-source follow-ups (combined PR):
 *  - hub_list_devices scope='all'            -> toolListDevices all-hub branch (_listAllHubDevices)
 *  - hub_set_app_disabled                    -> toolSetAppDisabled (POST /installedapp/disable + read-back)
 *  - hub_get_radio_details include_topology  -> _fetchRadioTopology
 *
 * Mocking: hubInternalGet -> hubGet.register(path) closures; hubInternalPostJson -> script.metaClass.
 */
class Issue257DeviceAppMeshSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        // toolGetZwaveDetails reads location.hub; wire a non-null location stub.
        appExecutor.getLocation() >> sharedLocation
    }

    private TestDevice dev(Map p) {
        new TestDevice(id: p.id, name: "D${p.id}", label: p.label ?: "Device ${p.id}", roomName: null,
            capabilities: [], supportedAttributes: [], supportedCommands: [], attributeValues: [:])
    }

    // ---- Item 1: hub_list_devices scope='all' ----------------------------

    def "scope='all' lists every hub device tagged mcpAuthorized true/false"() {
        given:
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params ->
            JsonOutput.toJson([
                [id: 80, label: "Authorized Switch", capabilities: ["Switch", "Actuator"]],
                [id: 99, label: "Unauthorized Motion", capabilities: ["MotionSensor"]]
            ])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then:
        result.scope == "all"
        result.total == 2
        result.mcpAuthorizedCount == 1
        result.unauthorizedCount == 1
        result.devices.find { it.id == 80 }.mcpAuthorized == true
        result.devices.find { it.id == 99 }.mcpAuthorized == false
        result.devices.find { it.id == 99 }.capabilities == ["MotionSensor"]
    }

    def "scope='all' applies capabilityFilter across all-hub devices"() {
        given:
        settingsMap.selectedDevices = []
        hubGet.register('/device/listWithCapabilities/json') { params ->
            JsonOutput.toJson([
                [id: 1, label: "A", capabilities: ["Switch"]],
                [id: 2, label: "B", capabilities: ["MotionSensor"]]
            ])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, "MotionSensor", null, null, null, "all")

        then:
        result.total == 1
        result.devices*.id == [2]
        result.unfilteredTotal == 2
    }

    def "invalid scope throws"() {
        when:
        script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "bogus")

        then:
        thrown(IllegalArgumentException)
    }

    // ---- Item 2: hub_set_app_disabled ------------------------------------

    def "hub_set_app_disabled posts /installedapp/disable and verifies via read-back"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        def posted = [:]
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            posted.path = path; posted.body = jsonBody; return [status: 200]
        }
        hubGet.register('/installedapp/json/5') { params -> JsonOutput.toJson([id: 5, name: "Notifier", disabled: true]) }

        when:
        def result = script.executeTool("hub_set_app_disabled", [app_id: 5, disabled: true])

        then:
        posted.path == "/installedapp/disable"
        posted.body.contains('"id":5')
        posted.body.contains('"disable":true')
        result.success == true
        result.appId == 5
        result.disabled == true
    }

    def "hub_set_app_disabled reports failure when read-back disagrees"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false -> [status: 200] }
        hubGet.register('/installedapp/json/7') { params -> JsonOutput.toJson([id: 7, disabled: false]) }

        when:
        def result = script.executeTool("hub_set_app_disabled", [app_id: 7, disabled: true])

        then:
        result.success == false
        result.disabled == false
    }

    def "hub_set_app_disabled rejects a non-positive app_id"() {
        given:
        settingsMap.enableWrite = true

        when:
        script.executeTool("hub_set_app_disabled", [app_id: 0, disabled: true])

        then:
        thrown(IllegalArgumentException)
    }

    // ---- Item 3: hub_get_radio_details include_topology -------------------

    def "include_topology adds the Z-Wave mesh route map"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { params -> JsonOutput.toJson([enabled: true]) }
        hubGet.register('/hub/zwave/getChildAndRouteInfoJson') { params ->
            JsonOutput.toJson([nodes: [[id: 0, name: "Hubitat"]], connectors: [[source: "01", target: "06"]]])
        }
        hubGet.register('/hub/zwaveTopology') { params -> "01: 06 07" }

        when:
        def result = script.toolGetZwaveDetails([include_topology: true])

        then:
        result.topology != null
        result.topology.routes.nodes[0].name == "Hubitat"
        result.topology.zwaveTopologyTable == "01: 06 07"
    }

    def "omitting include_topology leaves topology absent"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { params -> JsonOutput.toJson([enabled: true]) }

        when:
        def result = script.toolGetZwaveDetails([:])

        then:
        result.topology == null
    }
}
