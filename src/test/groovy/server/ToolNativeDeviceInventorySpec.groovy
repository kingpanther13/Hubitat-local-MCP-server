package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import spock.lang.Shared
import support.TestLocation
import support.TestDevice
import support.ToolSpecBase

class ToolNativeDeviceInventorySpec extends ToolSpecBase {
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    private Map models

    private void nativeFixture() {
        models = (1..3).collectEntries { id ->
            [(id.toString()): [device: [id: id, name: "Native ${id}", label: "Native ${id}",
                roomName: 'Den', capabilities: ['Switch'], disabled: false,
                lastActivityTime: '2026-09-10T10:00:00Z',
                currentStates: [switch: [value: id == 2 ? 'off' : 'on']]],
                commands: [[name: 'on', arguments: []]]]]
        }
        def selected = sdkIdentity(1)
        settingsMap.selectedDevices = [selected]
        childDevicesList << sdkIdentity(2)
        hubGet.register('/device/listWithCapabilities/json') {
            JsonOutput.toJson(models.values().collect { [id: it.device.id, label: it.device.label, capabilities: ['Switch']] })
        }
        models.keySet().each { id -> hubGet.register("/device/fullJson/${id}") { JsonOutput.toJson(models[id]) } }
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

    @Unroll
    def 'ordinary inventory uses native records with bypass #bypass and honors page state filters'() {
        given:
        nativeFixture()
        settingsMap.bypassDeviceAllowlist = bypass

        when:
        def result = script.toolListDevices(false, 0, 1, null, 'native', 'switch', 'context', null, '',
            null, 'den', true, '2026-09-10T09:00:00Z', ['switch'])

        then:
        result.total == (bypass ? 2 : 1)
        result.count == 1
        result.summary.contains('Native 1 (1, Den) - Switch; switch=on')
        result.hasMore == bypass
        (result.nextCursor == '1') == bypass
        !hubGet.calls.any { it.path == '/device/fullJson/3' } || bypass

        where:
        bypass << [false, true]
    }

    def 'selected and child inventory reports native state and child ownership with no SDK reads'() {
        given:
        nativeFixture()

        when:
        def result = script.toolListDevices(true, 0, 0)

        then:
        result.devices*.id == ['1', '2']
        result.devices[0].attributes == [[name: 'switch', value: 'on']]
        result.devices[0].commands == ['on']
        result.devices[1].mcpManaged == true
        !result.devices[0].containsKey('mcpManaged')
    }

    def 'ids projection pages native bypass inventory without fetching per-device state'() {
        given:
        nativeFixture()
        settingsMap.bypassDeviceAllowlist = true

        when:
        def result = script.toolListDevices(false, 0, 1, null, null, null, 'ids', null, '')

        then:
        result.deviceIds == [1]
        result.total == 3
        result.nextCursor == '1'
        !hubGet.calls.any { it.path.startsWith('/device/fullJson/') }
    }

    def 'native fullJson outage is an explicit inventory error'() {
        given:
        nativeFixture()
        hubGet.register('/device/fullJson/1') { throw new IOException('offline') }

        when:
        def result = script.toolListDevices(false, 0, 10)

        then:
        result.success == false
        result.error.toString().contains('1')
        !result.containsKey('devices')
    }

    def 'both context resources read the native selected and child population'() {
        given:
        nativeFixture()

        when:
        def structured = script._buildContextJson()
        def summary = script._buildContextSummaryText()

        then:
        structured.devices*.id == ['1', '2']
        structured.devices[0].attributes.switch == 'on'
        summary.contains('Native 1 (1, Den) - Switch; switch=on')
        !summary.contains('Native 3')
    }

    def 'scope all fills missing selected capabilities from native fullJson only'() {
        given:
        nativeFixture()
        hubGet.register('/device/listWithCapabilities/json') { throw new IOException('removed') }
        hubGet.register('/hub2/devicesList') { JsonOutput.toJson([devices: models.values().collect { [data: [id: it.device.id, name: it.device.label]] }]) }
        hubGet.register('/hub2/vrb/devices') { throw new IOException('unavailable') }

        when:
        def result = script.toolListDevices(false, 0, 10, null, null, 'Switch', 'summary', null, null, 'all')

        then:
        result.devices*.id == ['1', '2']
        result.devices.every { it.mcpAuthorized && it.capabilities == ['Switch'] }
        result.capabilitiesPartial == true
    }
    def 'unfiltered summary hydrates only the requested page and preserves numeric states'() {
        given:
        nativeFixture()
        models['2'].device.currentStates.temperature = [value: '72.5', dataType: 'NUMBER', numberValue: 72.5]

        when:
        def result = script.toolListDevices(false, 1, 1)

        then:
        result.devices*.id == ['2']
        result.devices[0].currentStates.temperature == 72.5
        hubGet.calls.findAll { it.path.startsWith('/device/fullJson/') }*.path == ['/device/fullJson/2']
    }

    def 'scope all tags every native device accessible when bypass is enabled'() {
        given:
        nativeFixture()
        settingsMap.bypassDeviceAllowlist = true

        when:
        def result = script.toolListDevices(false, 0, 10, null, null, null, 'summary', null, null, 'all')

        then:
        result.total == 3
        result.mcpAuthorizedCount == 3
        result.unauthorizedCount == 0
        result.devices.every { it.mcpAuthorized }
    }

    def 'a bypass inventory with an unconfirmed id set cannot look complete'() {
        given:
        nativeFixture()
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/device/listWithCapabilities/json') { throw new IOException('removed') }
        hubGet.register('/hub2/devicesList') { throw new IOException('offline') }
        hubGet.register('/hub2/vrb/devices') { JsonOutput.toJson([[id: 3, label: 'Native 3', capabilities: ['Switch']]]) }

        when:
        def result = script.toolListDevices(false, 0, 10, null, null, null, 'ids')

        then:
        result.success == false
        result.error.toString().toLowerCase().contains('inventory')
        !result.containsKey('deviceIds')
    }

    @Unroll
    def 'inventory #fields rejects unavailable required #field metadata'() {
        given:
        nativeFixture()
        def source = field == 'commands' ? models['1'] : models['1'].device
        source.remove(field)

        when:
        def result = script.toolListDevices(false, 0, 10, null, null, null, 'summary', fields)

        then:
        result.success == false
        result.error.toString().contains(field)
        !result.containsKey('devices')

        where:
        field           | fields
        'commands'      | ['commands']
        'capabilities'  | ['capabilities']
        'currentStates' | ['currentStates']
        'currentStates' | ['attributes']
    }

    @Unroll
    def 'inventory #fields does not require unrelated missing collections'() {
        given:
        nativeFixture()
        models.values().each { full ->
            full.remove('commands')
            full.device.remove('capabilities')
            full.device.remove('currentStates')
        }

        when:
        def result = script.toolListDevices(false, 0, 10, null, 'Native', null, 'summary', fields)

        then:
        result.devices*.id == ['1', '2']
        result.devices*.label == ['Native 1', 'Native 2']

        where:
        fields << [['label'], ['id', 'label']]
    }

    def 'scope all rejects malformed inventory records rather than omitting them'() {
        given:
        nativeFixture()
        hubGet.register('/device/listWithCapabilities/json') { JsonOutput.toJson([[id: 1, label: 'Native 1'], null]) }

        when:
        def result = script.toolListDevices(false, 0, 10, null, null, null, 'summary', null, null, 'all')

        then:
        result.success == false
        !result.containsKey('devices')
    }

}
