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
            capabilities: p.capabilities ?: [], supportedAttributes: [], supportedCommands: [], attributeValues: [:])
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
        result.devices.find { it.id == "80" }.mcpAuthorized == true
        result.devices.find { it.id == "99" }.mcpAuthorized == false
        result.devices.find { it.id == "99" }.capabilities == ["MotionSensor"]
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
        result.devices*.id == ["2"]
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

    // ---- added coverage (PR #289 review gaps) ----------------------------

    def "scope='all' returns a structured error when BOTH inventory endpoints fail"() {
        given:
        settingsMap.selectedDevices = []
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("boom") }
        hubGet.register('/hub2/devicesList') { params -> throw new RuntimeException("boom too") }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then:
        result.success == false
        result.note?.toLowerCase()?.contains("authorized")
    }

    def "scope='all' returns a structured error when the fallback endpoint returns a non-array"() {
        given:
        settingsMap.selectedDevices = []
        hubGet.register('/device/listWithCapabilities/json') { params -> JsonOutput.toJson([unexpected: "object"]) }
        hubGet.register('/hub2/devicesList') { params -> JsonOutput.toJson([devices: "not-a-list"]) }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: "a non-array primary falls through to the fallback, so the error names the fallback"
        result.success == false
        result.error?.contains("/hub2/devicesList")
    }

    // Platform 2.5.1.173 and later removed /device/listWithCapabilities/json (404; confirmed on .173/.174). /hub2/devicesList is still
    // a superset of the authorized set, so scope='all' keeps working -- minus capabilities, which
    // that endpoint does not carry and which are unknowable for a device the app cannot see.

    def "scope='all' falls back when the capabilities endpoint answers EMPTY (#body) -- never zero devices with success"() {
        given: "hubInternalGet returns null on an empty/204 body; parseText(txt ?: '[]') made that a real empty list"
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> body }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [[key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then:
        result.source == "/hub2/devicesList"
        result.devices.size() == 1

        where:
        body << [null, '', '[]']
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

    def "the ids shape carries the same fallback metadata as the summary shape"() {
        given: "the capabilities endpoint is gone, so capabilityFilter can only match authorized devices"
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [
                [key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: []],
                [key: "DEV-99", data: [id: 99, name: "Unauthorized Motion"], children: []]
            ]])
        }

        when: "the ids shape -- the one a caller pages through, where a silent partial is invisible"
        def result = script.toolListDevices(false, 0, 0, null, null, null, "ids", null, null, "all")

        then: "source and the partial-capabilities warning ride it, not just the summary shape"
        result.deviceIds.sort() == [80, 99]
        result.source == "/hub2/devicesList"
        result.capabilitiesPartial == true
        result.capabilitiesNote?.contains("capabilityFilter therefore matches authorized devices only")
    }

    def "scope='all' falls back to hub2 devicesList when the capabilities endpoint is gone"() {
        given:
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([suggestBackup: false, devices: [
                [key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: [
                    [key: "DEV-81", data: [id: 81, name: "Child Of 80"], children: []]
                ]],
                [key: "DEV-99", data: [id: 99, name: "Unauthorized Motion"], children: []]
            ]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: "every device is listed, children flattened, and the source + partial flag are explicit"
        result.scope == "all"
        result.source == "/hub2/devicesList"
        result.capabilitiesPartial == true
        result.total == 3
        result.devices*.id.sort() == ["80", "81", "99"]
        result.devices.find { it.id == "80" }.label == "Authorized Switch"

        and: "authorization is still tagged from the app's own device list"
        result.devices.find { it.id == "80" }.mcpAuthorized == true
        result.devices.find { it.id == "99" }.mcpAuthorized == false
    }

    def "scope='all' fallback leaves an unauthorized device's capabilities empty rather than guessing"() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 80, name: "D80", label: "Authorized Switch",
            roomName: null, capabilities: ["Switch", "Actuator"], supportedAttributes: [],
            supportedCommands: [], attributeValues: [:])]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [
                [key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: []],
                [key: "DEV-99", data: [id: 99, name: "Unauthorized Motion"], children: []]
            ]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then:
        result.devices.find { it.id == "80" }.capabilities == ["Switch", "Actuator"]
        result.devices.find { it.id == "99" }.capabilities == []
        result.capabilitiesNote?.contains("unauthorized")
    }

    def "scope='all' fallback reports capabilities for an MCP child device, not just selected ones"() {
        given: "the app's own child device is authorized via getChildDevices, not selectedDevices"
        settingsMap.selectedDevices = []
        // Label deliberately shares no substring with the capability, so a filter applied to
        // the wrong field cannot make this pass.
        // Capability entries as the Groovy device model hands them over -- objects with a `name`,
        // not bare strings. A reader that falls back to toString() passes on strings and fails
        // here, which is the whole point of reading `.name`.
        childDevicesList << new TestDevice(id: 77, name: "D77", label: "Porch Lamp",
            roomName: null, capabilities: [[name: "Switch"]], supportedAttributes: [],
            supportedCommands: [], attributeValues: [:])
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [[key: "DEV-77", data: [id: 77, name: "Porch Lamp"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, "Switch", null, null, null, "all")

        then: "it is both authorized and matchable by capabilityFilter"
        result.devices.size() == 1
        result.devices[0].mcpAuthorized == true
        result.devices[0].capabilities == ["Switch"]
    }

    // The middle tier: /hub2/vrb/devices, the VRB 2.0 device picker feed, carries the same
    // {id, label, capabilities} triple over the same full inventory, so capabilities survive the
    // removal of /device/listWithCapabilities/json on 2.5.1.173+.

    def "scope='all' keeps devices the vrb feed omits from /hub2/devicesList, in tree order, and flags the result partial"() {
        given: 'a picker feed that filtered one device out; the tree lists that device FIRST'
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params ->
            JsonOutput.toJson([[id: 80, label: "Authorized Switch", capabilities: ["Switch"]]])
        }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [
                [key: "DEV-77", data: [id: 77, name: "Filtered Out"], children: []],
                [key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: 'the omitted device is still an inventory member, without capabilities, and the caller is told why'
        result.source == "/hub2/vrb/devices"
        result.total == 2
        result.devices*.id == ["77", "80"]   // tree order (an append-at-the-end join would give 80, 77)
        result.devices.find { it.id == "77" }.label == "Filtered Out"
        result.devices.find { it.id == "77" }.capabilities == []
        result.capabilitiesPartial == true
        result.capabilitiesNote.contains("/hub2/vrb/devices")
        result.capabilitiesNote.contains("omitted 1 of 2")
    }

    def "an AUTHORIZED device the vrb feed omits still gets its capabilities from the Groovy model"() {
        given: 'the picker feed skipped the authorized device; the model knows its capabilities'
        settingsMap.selectedDevices = [dev(id: 80, capabilities: ["Switch", "Refresh"])]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 99, label: "Hall", capabilities: ["MotionSensor"]]]) }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [[key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: []],
                                         [key: "DEV-99", data: [id: 99, name: "Hall"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: 'no capabilities key on the omitted record, so the fill-in ran instead of an empty list winning'
        result.capabilitiesPartial == true
        result.devices.find { it.id == "80" }.capabilities.containsAll(["Switch", "Refresh"])
        result.devices.find { it.id == "99" }.capabilities == ["MotionSensor"]
    }

    def "when the devicesList spine cannot be read the vrb feed alone is returned, flagged partial with the reason"() {
        given:
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 80, label: "Authorized Switch", capabilities: ["Switch"]]]) }
        hubGet.register('/hub2/devicesList') { params -> throw new RuntimeException("status code: 504") }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: 'never a complete, capability-bearing answer while the cross-check could not run'
        result.source == "/hub2/vrb/devices"
        result.devices*.id == ["80"]
        result.devices[0].capabilities == ["Switch"]
        result.capabilitiesPartial == true
        result.capabilitiesNote.contains("could not be read")
        result.capabilitiesNote.contains("504")
    }

    def "an EMPTY /hub2/devicesList beside a populated vrb feed is the feed alone, flagged partial -- never zero devices as the truth"() {
        given: 'the tree answers no devices while the feed lists two'
        settingsMap.selectedDevices = [dev(id: 80, capabilities: ["Switch"])]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params ->
            JsonOutput.toJson([[id: 80, label: "Authorized Switch", capabilities: ["Switch"]],
                               [id: 99, label: "Hall", capabilities: ["MotionSensor"]]])
        }
        hubGet.register('/hub2/devicesList') { params -> JsonOutput.toJson([devices: []]) }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: 'contradictory data: the feed is the honest answer, and nothing vouches for its completeness'
        result.source == "/hub2/vrb/devices"
        result.devices*.id == ["80", "99"]
        result.devices.find { it.id == "99" }.capabilities == ["MotionSensor"]
        result.capabilitiesPartial == true
        result.capabilitiesNote.contains("answered no devices")
        result.capabilitiesNote.contains("listed 2")
    }

    def "a device only the vrb feed lists is kept after the tree's devices and the tree's omission is counted"() {
        given: 'the tree lacks a device the feed carries'
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params ->
            JsonOutput.toJson([[id: 99, label: "Feed Only", capabilities: ["MotionSensor"]],
                               [id: 80, label: "Authorized Switch", capabilities: ["Switch"]]])
        }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [[key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: 'the union: tree devices first, then the feed-only device with its own capabilities; partial says which source fell short'
        result.source == "/hub2/vrb/devices"
        result.devices*.id == ["80", "99"]
        result.devices.find { it.id == "99" }.capabilities == ["MotionSensor"]
        result.devices.find { it.id == "99" }.mcpAuthorized == false
        result.capabilitiesPartial == true
        result.capabilitiesNote.contains("tree (/hub2/devicesList) omitted 1")
        !result.capabilitiesNote.contains("feed (/hub2/vrb/devices) omitted")
    }

    def "a tree node without a data.id fails the read as a shape error rather than shrinking the inventory"() {
        given: 'no feed to fall back on, and a tree with an id-less node'
        settingsMap.selectedDevices = []
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [[key: "DEV-80", data: [id: 80, name: "A"], children: []],
                                         [key: "DEV-?", data: [name: "No id"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then:
        result.success == false
        result.error?.contains("/hub2/devicesList")
    }

    def "scope='all' falls past a vrb feed whose entries carry no capabilities list"() {
        given:
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params -> JsonOutput.toJson([[id: 80, label: "Authorized Switch"]]) }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [[key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: 'a feed that cannot vouch for capabilities is not adopted as the capability source'
        result.source == "/hub2/devicesList"
        result.capabilitiesPartial == true
    }

    def "scope='all' takes the vrb tier when the capabilities endpoint is gone"() {
        given:
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params ->
            // A FLAT array -- child devices ride it as their own entries, no tree to walk -- with
            // the picker-only fields the projection drops.
            JsonOutput.toJson([
                [id: 80, label: "Authorized Switch", capabilities: ["Switch", "Actuator"], temperature: null, lightEffects: null, supportedFanSpeeds: null, buttonCount: null],
                [id: 81, label: "Child Of 80", capabilities: ["Bulb", "Switch"], temperature: null, lightEffects: "{}", supportedFanSpeeds: null, buttonCount: null],
                [id: 99, label: "Unauthorized Motion", capabilities: ["MotionSensor"], temperature: 71, lightEffects: null, supportedFanSpeeds: null, buttonCount: null]
            ])
        }
        // The tier cross-checks the feed against the full tree; here they agree.
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [
                [key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: [[key: "DEV-81", data: [id: 81, name: "Child Of 80"], children: []]]],
                [key: "DEV-99", data: [id: 99, name: "Unauthorized Motion"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: "capabilities survive, so nothing is reported as partial"
        result.source == "/hub2/vrb/devices"
        result.capabilitiesPartial == null
        !result.containsKey("capabilitiesNote")

        and: "every hub device is listed, the child device among them"
        result.total == 3
        result.devices*.id.sort() == ["80", "81", "99"]
        result.devices.find { it.id == "81" }.label == "Child Of 80"
        result.devices.find { it.id == "81" }.capabilities == ["Bulb", "Switch"]

        and: "an UNAUTHORIZED device carries its real capabilities -- the point of the tier"
        result.devices.find { it.id == "99" }.mcpAuthorized == false
        result.devices.find { it.id == "99" }.capabilities == ["MotionSensor"]
        result.devices.find { it.id == "80" }.mcpAuthorized == true
    }

    def "the vrb tier matches an UNAUTHORIZED device by capabilityFilter"() {
        given: "nothing is authorized, so the capability-less last resort would match nothing"
        settingsMap.selectedDevices = []
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params ->
            JsonOutput.toJson([
                [id: 98, label: "Porch", capabilities: ["Switch"]],
                [id: 99, label: "Hall", capabilities: ["MotionSensor"]]
            ])
        }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [[key: "DEV-98", data: [id: 98, name: "Porch"], children: []],
                                         [key: "DEV-99", data: [id: 99, name: "Hall"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, "MotionSensor", null, null, null, "all")

        then:
        result.source == "/hub2/vrb/devices"
        result.devices*.id == ["99"]
        result.devices[0].mcpAuthorized == false
        result.unfilteredTotal == 2
        result.capabilitiesPartial == null
    }

    def "a vrb tier answering #desc still falls through to /hub2/devicesList"() {
        given:
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException("status code: 404") }
        hubGet.register('/hub2/vrb/devices') { params -> body }
        hubGet.register('/hub2/devicesList') { params ->
            JsonOutput.toJson([devices: [[key: "DEV-80", data: [id: 80, name: "Authorized Switch"], children: []]]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then: "the capability-less last resort answers, and says so"
        result.source == "/hub2/devicesList"
        result.capabilitiesPartial == true
        result.capabilitiesNote?.contains("/hub2/vrb/devices")
        result.devices*.id == ["80"]

        where:
        desc                    | body
        "no body"               | null
        "an empty body"         | ''
        "an empty array"        | '[]'
        "a JSON object"         | JsonOutput.toJson([success: false])
        "an array of non-maps"  | JsonOutput.toJson(["nope"])
        "an id-less array"      | JsonOutput.toJson([[label: "No id", capabilities: ["Switch"]]])
    }

    def "scope='all' keeps the capabilities endpoint as the primary source when it still answers"() {
        given:
        settingsMap.selectedDevices = [dev(id: 80)]
        hubGet.register('/device/listWithCapabilities/json') { params ->
            JsonOutput.toJson([[id: 80, label: "A", capabilities: ["Switch"]], [id: 99, label: "B", capabilities: ["MotionSensor"]]])
        }
        hubGet.register('/hub2/devicesList') { params -> throw new RuntimeException("must not be reached") }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then:
        result.source == "/device/listWithCapabilities/json"
        result.capabilitiesPartial == null
        result.devices.find { it.id == "99" }.capabilities == ["MotionSensor"]
    }

    def "scope='all' format='ids' returns integer ids (strings cast back) and no devices key"() {
        given:
        settingsMap.selectedDevices = [dev(id: 1)]
        hubGet.register('/device/listWithCapabilities/json') { params ->
            JsonOutput.toJson([[id: 1, label: "A", capabilities: ["Switch"]], [id: 2, label: "B", capabilities: []]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, "ids", null, null, "all")

        then:
        result.scope == "all"
        result.deviceIds == [1, 2]
        result.count == 2
        result.total == 2
        !result.containsKey("devices")
    }

    def "scope='all' paginates with hasMore / nextOffset over the filtered set"() {
        given:
        settingsMap.selectedDevices = []
        hubGet.register('/device/listWithCapabilities/json') { params ->
            JsonOutput.toJson([[id: 1, label: "A", capabilities: []], [id: 2, label: "B", capabilities: []], [id: 3, label: "C", capabilities: []]])
        }

        when: "first page of 2"
        def page1 = script.toolListDevices(false, 0, 2, null, null, null, null, null, null, "all")

        then:
        page1.devices.size() == 2
        page1.total == 3
        page1.count == 2
        page1.hasMore == true
        page1.nextOffset == 2

        when: "second page"
        def page2 = script.toolListDevices(false, 2, 2, null, null, null, null, null, null, "all")

        then:
        page2.devices.size() == 1
        page2.hasMore == false
        !page2.containsKey("nextOffset")
    }

    def "scope='all' tags an MCP child (virtual) device mcpAuthorized=true"() {
        given:
        childDevicesList << dev(id: 50)
        hubGet.register('/device/listWithCapabilities/json') { params ->
            JsonOutput.toJson([[id: 50, label: "Virtual", capabilities: ["Switch"]], [id: 51, label: "Foreign", capabilities: []]])
        }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then:
        result.devices.find { it.id == "50" }.mcpAuthorized == true
        result.devices.find { it.id == "51" }.mcpAuthorized == false
    }

    def "scope='all' devices carry id as a String (matches schema + scope='authorized')"() {
        given:
        settingsMap.selectedDevices = []
        hubGet.register('/device/listWithCapabilities/json') { params -> JsonOutput.toJson([[id: 7, label: "X", capabilities: []]]) }

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null, null, "all")

        then:
        result.devices[0].id == "7"
        result.devices[0].id instanceof String
    }

    def "scope='all' rejects detailed=true"() {
        when:
        script.toolListDevices(true, 0, 0, null, null, null, null, null, null, "all")

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
        hubGet.register('/device/listWithCapabilities/json') { params -> JsonOutput.toJson([[id: 1, label: "A", capabilities: []]]) }
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
