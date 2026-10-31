package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.TestLocation
import support.TestDevice
import support.ToolSpecBase

/**
 * The native source of device inventories: hub_list_devices and both context resources read the
 * hub's Devices page (/hub2/devicesList) and, when capabilities are needed, the capability feed
 * (/hub2/vrb/devices). The Groovy device objects supply identity only: any SDK metadata read fails
 * these specs.
 */
class ToolNativeDeviceInventorySpec extends ToolSpecBase {
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    private TestDevice sdkIdentity(Integer id) {
        def sdk = new TestDevice(id: id)
        ['getName', 'getLabel', 'getRoomName', 'getCapabilities', 'getSupportedAttributes',
         'getSupportedCommands', 'getCurrentStates', 'getLastActivity', 'getDeviceNetworkId', 'getDisabled'].each { method ->
            sdk.metaClass."${method}" = { -> throw new AssertionError("SDK metadata read: ${method}") }
        }
        sdk.metaClass.currentValue = { String attr -> throw new AssertionError('SDK state read') }
        return sdk
    }

    // Three devices on the page: 1 selected, 2 an MCP child, 3 neither.
    private void nativeFixture(Map extra = [:]) {
        settingsMap.selectedDevices = [sdkIdentity(1)]
        childDevicesList << sdkIdentity(2)
        hubGet.register('/hub2/devicesList') {
            JsonOutput.toJson([devices: (1..3).collect { id ->
                [key: "DEV-${id}", data: [id: id, name: "Native ${id}", roomName: 'Den', type: 'Virtual Switch', isVirtual: true,
                    lastActivity: '2026-09-10T10:00:00+0000', disabled: false,
                    currentStates: [[key: 'switch', value: id == 2 ? 'off' : 'on'], [key: 'level', value: '42']]] + (extra[id] ?: [:]),
                 children: []]
            }])
        }
        hubGet.register('/hub2/vrb/devices') { JsonOutput.toJson((1..3).collect { [id: it, label: "Native ${it}", capabilities: ['Switch']] }) }
        (1..3).each { id -> hubGet.register("/device/fullJson/${id}") { throw new AssertionError("per-device read of ${id}") } }
    }

    @Unroll
    def 'the tool lists from the page with bypass #bypass and no SDK or per-device read'() {
        given:
        nativeFixture()
        settingsMap.bypassDeviceAllowlist = bypass

        when:
        def summary = script.toolListDevices([:])
        def context = script.toolListDevices([format: 'context', roomFilter: 'den', stateFilter: [switch: 'on']])

        then:
        summary.devices*.id == expected
        summary.devices[0].currentStates == [switch: 'on', level: '42']
        summary.devices[1].mcpManaged == true
        context.summary.contains('- Native 1 (1, Den) - Switch; switch=on, level=42')
        context.summary.contains('Native 3') == bypass
        !context.summary.contains('Native 2')
        hubGet.calls*.path.unique().sort() == ['/hub2/devicesList', '/hub2/vrb/devices']

        where:
        bypass | expected
        false  | ['1', '2']
        true   | ['1', '2', '3']
    }

    def 'summary, ids and filtered listings read the page once and the capability feed only when needed'() {
        given:
        nativeFixture()

        when:
        script.toolListDevices([fields: ['id', 'label']])
        script.toolListDevices([format: 'ids', staleHours: 1, disabled: false])
        def withoutFeed = hubGet.calls*.path
        hubGet.calls.clear()
        script.toolListDevices([capabilityFilter: 'Switch', format: 'ids'])

        then:
        withoutFeed == ['/hub2/devicesList', '/hub2/devicesList']
        hubGet.calls*.path == ['/hub2/devicesList', '/hub2/vrb/devices']
    }

    def 'both context resources read the selected and child population from the page'() {
        given:
        nativeFixture()

        when:
        def structured = script._buildContextJson()
        def summary = script._buildContextSummaryText()

        then:
        structured.devices*.id == ['1', '2']
        structured.devices[0] == [id: '1', label: 'Native 1', room: 'Den', capabilities: ['Switch'], attributes: [switch: 'on', level: '42']]
        structured.rooms == [[name: 'Den', deviceIds: ['1', '2']]]
        structured.partial != true
        summary.contains('- Native 1 (1, Den) - Switch; switch=on, level=42')
        !summary.contains('Native 3')
        hubGet.calls*.path == ['/hub2/devicesList', '/hub2/vrb/devices', '/hub2/devicesList', '/hub2/vrb/devices']
    }

    def 'the context resources follow the allowlist bypass to every page device'() {
        given:
        nativeFixture()
        settingsMap.bypassDeviceAllowlist = true

        expect:
        script._buildContextJson().devices*.id == ['1', '2', '3']
        script._buildContextSummaryText().contains('Native 3 (3, Den)')
    }

    def 'context json groups devices by the page room, with no room as No room'() {
        given:
        nativeFixture([3: [roomName: '']])
        settingsMap.bypassDeviceAllowlist = true

        when:
        def structured = script._buildContextJson()

        then:
        structured.rooms == [[name: 'Den', deviceIds: ['1', '2']], [name: 'No room', deviceIds: ['3']]]
        structured.devices.find { it.id == '3' }.room == null
    }

    def 'an unreadable Devices page is reported by both resources, not served as an empty house'() {
        given:
        nativeFixture()
        hubGet.register('/hub2/devicesList') { throw new IOException('offline') }

        when:
        def structured = script._buildContextJson()
        def summary = script._buildContextSummaryText()

        then:
        structured.isError == true
        structured.error == "The hub's device list (/hub2/devicesList) could not be read: offline"
        summary == "Context unavailable: the hub's device list (/hub2/devicesList) could not be read: offline"
    }

    def 'an unreadable capability feed keeps the resources served and says capabilities are missing'() {
        given:
        nativeFixture()
        hubGet.register('/hub2/vrb/devices') { throw new IOException('feed down') }

        when:
        def structured = script._buildContextJson()
        def summary = script._buildContextSummaryText()

        then:
        structured.partial == true
        structured.devices.every { it.capabilitiesUnavailable == true && it.capabilities == [] }
        summary.contains('Capabilities unavailable')
        summary.contains('- Native 1 (1, Den); switch=on, level=42')
    }

    @Unroll
    def 'the resources truncate at a #byteBudget-byte budget with the rooms index capped first'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/hub2/devicesList') {
            JsonOutput.toJson([devices: (1..10).collect { [data: [id: it, name: 'L' * 1000, roomName: "Room ${it}"]] }])
        }
        hubGet.register('/hub2/vrb/devices') { JsonOutput.toJson((1..10).collect { [id: it, capabilities: ['Switch']] }) }
        script.metaClass._contextResourceByteBudget = { -> byteBudget }

        when:
        def structured = script._buildContextJson()
        def summary = script._buildContextSummaryText()

        then:
        structured.truncated == true
        structured.totalDevices == 10
        structured.deviceCount == structured.devices.size()
        structured.note.contains('hub_list_devices')
        structured.containsKey('roomsTruncated') == roomsCut
        summary.contains('truncated at')

        where:
        byteBudget | roomsCut
        5000       | false
        120        | true
    }

    def 'selectedDevices validation checks ids against the page and refuses an unknown one'() {
        given:
        nativeFixture()
        settingsMap.enableDeveloperMode = true

        when:
        script._validateMcpDeviceScope([mode: 'add', ids: ['3', '99']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Unknown device id(s): 99')
        hubGet.calls*.path == ['/hub2/devicesList']
    }
}
