package server

import support.TestDevice
import support.TestLocation
import support.ToolSpecBase
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Issue #257 hub2-source follow-ups (combined PR):
 *  - hub_list_devices scope='all'            -> toolListDevices over the whole Devices page
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
        new TestDevice(id: p.id, name: "D${p.id}", label: p.label ?: "Device ${p.id}")
    }

    // The hub's Devices page and capability feed: [id, label, capabilities] per device.
    private void hubPage(List devices) {
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: devices.collect { [key: "DEV-${it.id}", data: [id: it.id, name: it.label, type: 'Generic'], children: []] }])
        }
        hubGet.register('/hub2/vrb/devices') { params ->
            JsonOutput.toJson(devices.collect { [id: it.id, label: it.label, capabilities: it.capabilities ?: []] })
        }
    }

    // ---- Item 1: hub_list_devices scope='all' ----------------------------

    def "scope='all' lists every hub device tagged mcpAuthorized true/false"() {
        given:
        settingsMap.selectedDevices = [dev(id: 80)]
        hubPage([[id: 80, label: "Authorized Switch"], [id: 99, label: "Unauthorized Motion"]])

        when:
        def result = script.toolListDevices([scope: "all"])

        then:
        result.scope == "all"
        result.total == 2
        result.mcpAuthorizedCount == 1
        result.unauthorizedCount == 1
        result.devices.find { it.id == "80" }.mcpAuthorized == true
        result.devices.find { it.id == "99" }.mcpAuthorized == false
        result.devices.find { it.id == "99" }.label == "Unauthorized Motion"
    }

    def "scope='all' matches capabilityFilter on unauthorized devices too, and shows their capabilities"() {
        given:
        hubPage([[id: 1, label: "A", capabilities: ["Switch"]], [id: 2, label: "B", capabilities: ["MotionSensor"]]])

        when:
        def result = script.toolListDevices([scope: "all", capabilityFilter: "motionsensor"])

        then:
        result.total == 1
        result.devices*.id == ["2"]
        result.devices[0].capabilities == ["MotionSensor"]
        result.devices[0].mcpAuthorized == false
        result.unfilteredTotal == 2
    }

    def "invalid scope throws"() {
        when:
        script.toolListDevices([scope: "bogus"])

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
        def result = script.executeTool("hub_set_app_disabled", [appId: 5, disabled: true])

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
        def result = script.executeTool("hub_set_app_disabled", [appId: 7, disabled: true])

        then:
        result.success == false
        result.disabled == false
    }

    def "hub_set_app_disabled rejects a non-positive appId"() {
        given:
        settingsMap.enableWrite = true

        when:
        script.executeTool("hub_set_app_disabled", [appId: 0, disabled: true])

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

    def "hub_set_app_disabled(disabled=false) still works on a DISABLED app -- the remedy the edit refusal names"() {
        given: "the re-enable must never route through the edit engine, or a disabled rule is permanently stuck"
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        def state5 = [id: 5, name: "Notifier", disabled: true]
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            if (path == "/installedapp/disable" && jsonBody.contains('"disable":false')) state5.disabled = false
            [status: 200]
        }
        hubGet.register('/installedapp/json/5') { params -> JsonOutput.toJson(state5) }

        when:
        def result = script.executeTool("hub_set_app_disabled", [appId: 5, disabled: false])

        then:
        result.success == true
        result.disabled == false
    }

    def "scope='all' returns a structured error when the Devices page cannot be read"() {
        given:
        hubGet.register('/hub2/devicesList') { params -> throw new RuntimeException("boom") }

        when:
        def result = script.toolListDevices([scope: "all"])

        then:
        result.success == false
        result.isError == true
        result.error.contains("/hub2/devicesList")
        result.error.contains("boom")
    }

    def "scope='all' format='ids' returns integer ids and no devices key"() {
        given:
        settingsMap.selectedDevices = [dev(id: 1)]
        hubPage([[id: 1, label: "A"], [id: 2, label: "B"]])

        when:
        def result = script.toolListDevices([scope: "all", format: "ids"])

        then:
        result.scope == "all"
        result.deviceIds == [1, 2]
        result.count == 2
        result.total == 2
        result.mcpAuthorizedCount == 1
        !result.containsKey("devices")
    }

    def "scope='all' pages with hasMore / nextCursor over the filtered set"() {
        given:
        hubPage([[id: 1, label: "A"], [id: 2, label: "B"], [id: 3, label: "C"]])

        when: "first page of 2"
        def page1 = script.toolListDevices([scope: "all", limit: 2])

        then:
        page1.devices.size() == 2
        page1.total == 3
        page1.count == 2
        page1.hasMore == true
        page1.nextCursor == "2"

        when: "second page"
        def page2 = script.toolListDevices([scope: "all", limit: 2, cursor: page1.nextCursor])

        then:
        page2.devices*.id == ["3"]
        page2.hasMore == false
        !page2.containsKey("nextCursor")
    }

    def "scope='all' tags an MCP child (virtual) device mcpAuthorized=true"() {
        given:
        childDevicesList << dev(id: 50)
        hubPage([[id: 50, label: "Virtual"], [id: 51, label: "Foreign"]])

        when:
        def result = script.toolListDevices([scope: "all"])

        then:
        result.devices.find { it.id == "50" }.mcpAuthorized == true
        result.devices.find { it.id == "51" }.mcpAuthorized == false
    }

    def "scope='all' devices carry id as a String (matches scope='authorized')"() {
        given:
        hubPage([[id: 7, label: "X"]])

        when:
        def result = script.toolListDevices([scope: "all"])

        then:
        result.devices[0].id == "7"
        result.devices[0].id instanceof String
    }

    def "scope='all' rejects format='detailed'"() {
        when:
        script.toolListDevices([scope: "all", format: "detailed"])

        then:
        thrown(IllegalArgumentException)
    }

    def "include_topology on Zigbee hits the zigbee route endpoint and omits the Z-Wave-only table"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zigbeeDetails/json') { params -> JsonOutput.toJson([enabled: true]) }
        hubGet.register('/hub/zigbee/getChildAndRouteInfoJson') { params ->
            JsonOutput.toJson([children: [[id: "FD0A"]], neighbors: [], routes: [[id: "867A", nextHopId: "CC3E"]]])
        }

        when:
        def result = script.toolGetZigbeeDetails([include_topology: true])

        then:
        result.topology.endpoint.contains("zigbee")
        result.topology.routes != null
        !result.topology.containsKey("zwaveTopologyTable")
    }

    def "hub_set_app_disabled reports a structured error when the POST throws"() {
        given:
        settingsMap.enableWrite = true
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false -> throw new RuntimeException("net down") }

        when:
        def result = script.executeTool("hub_set_app_disabled", [appId: 5, disabled: true])

        then:
        result.success == false
        result.error?.contains("failed")
    }

    def "hub_set_app_disabled flags an unconfirmable write when the read-back throws"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false -> [status: 200] }
        hubGet.register('/installedapp/json/9') { params -> throw new RuntimeException("404") }

        when:
        def result = script.executeTool("hub_set_app_disabled", [appId: 9, disabled: true])

        then:
        result.success == false
        result.error?.toLowerCase()?.contains("could not be confirmed")
        result.note?.toLowerCase()?.contains("re-check")
    }

    def "hub_set_app_disabled requires the disabled arg"() {
        given:
        settingsMap.enableWrite = true

        when:
        script.executeTool("hub_set_app_disabled", [appId: 5])

        then:
        thrown(IllegalArgumentException)
    }

    def "dispatch: hub_list_devices scope='all' and hub_get_radio_details include_topology route through executeTool"() {
        given:
        settingsMap.enableRead = true
        settingsMap.selectedDevices = []
        hubPage([[id: 1, label: "A"]])
        hubGet.register('/hub/zwaveDetails/json') { params -> JsonOutput.toJson([enabled: true]) }
        hubGet.register('/hub/zwave/getChildAndRouteInfoJson') { params -> JsonOutput.toJson([nodes: [], connectors: []]) }
        hubGet.register('/hub/zwaveTopology') { params -> "table" }

        when:
        def devs = script.executeTool("hub_list_devices", [scope: "all"])
        def radio = script.executeTool("hub_get_radio_details", [radio: "zwave", include_topology: true])

        then:
        devs.scope == "all"
        devs.devices*.id == ["1"]
        radio.topology != null
        radio.topology.endpoint.contains("getChildAndRouteInfoJson")
    }
}
