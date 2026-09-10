package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppScript
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolNativeVirtualDevicesSpec extends ToolSpecBase {
    Map models = [:]
    List writes = []
    List deletions = []
    int creations = 0
    boolean acceptWrite = true
    boolean persistWrite = true
    boolean readable = true
    boolean failInfoAfterData = false

    def setup() {
        stateMap.lastBackupTimestamp = 1234567890000L
        def self = this
        wireLifecycle({ Object... args ->
            if (args.length == 1 && args[0] == 'list') return self.childDevicesList
            if (args[0] == 'delete') {
                self.deletions << args[2]
                self.childDevicesList.removeAll { it.deviceNetworkId == args[2] }
                return null
            }
            throw new IllegalStateException('Unexpected child lifecycle call')
        })
        script.metaClass.hubInternalPostJson = { String path, String body ->
            assert path == '/device/runmethod'
            def payload = new JsonSlurper().parseText(body)
            writes << payload
            if (acceptWrite && persistWrite) {
                models[payload.id.toString()].device.data[payload.args[0].value] = payload.args[1].value
            }
            [success: acceptWrite]
        }
    }

    private void wireLifecycle(Closure handler) {
        try {
            def factory = HubitatAppScript.getDeclaredField('childDeviceFactory')
            factory.accessible = true
            factory.set(script, handler)
        } catch (NoSuchFieldException ignored) {
            // The Groovy 2.5 harness delegates lifecycle methods to AppExecutor.
            mockChildDeviceLifecycle = handler
        }
    }

    private void model(String id, String namespace = 'native-ns') {
        models[id] = [device: [id: id.toInteger(), name: 'Native name', label: 'Native label',
            deviceNetworkId: "mcp-${id}", deviceTypeName: 'Native Driver', deviceTypeNamespace: namespace,
            data: [mcpDriverNamespace: 'persisted-ns'], capabilities: ['Switch', 'TemperatureMeasurement'],
            currentStates: [switch: [value: 'on', dataType: 'ENUM'],
                temperature: [value: '21.50', numberValue: 21.50, dataType: 'NUMBER']]],
            commands: [[name: 'on', parameters: []]], settings: [], inputValues: [:]]
        int reads = 0
        hubGet.register("/device/fullJson/${id}") {
            reads++
            readable && !(failInfoAfterData && reads > 1) ? JsonOutput.toJson(models[id]) : null
        }
    }

    private void owned(String id) {
        def sdk = new TestDevice(id: id.toInteger(), name: 'Stale SDK name', deviceNetworkId: "mcp-${id}")
        sdk.metaClass.getCapabilities = { throw new AssertionError('SDK capabilities read') }
        sdk.metaClass.getSupportedAttributes = { throw new AssertionError('SDK attributes read') }
        sdk.metaClass.getSupportedCommands = { throw new AssertionError('SDK commands read') }
        sdk.metaClass.currentValue = { String attr -> throw new AssertionError('SDK state read') }
        sdk.metaClass.getDataValue = { String key -> throw new AssertionError('SDK data read') }
        sdk.metaClass.getDriverType = { throw new AssertionError('SDK driver read') }
        childDevicesList << sdk
        model(id)
    }

    private void creator() {
        model('77')
        models['77'].device.data = [:]
        def child = Mock(ChildDeviceWrapper) {
            getId() >> '77'
            getIdAsLong() >> 77L
            getDeviceNetworkId() >> 'mcp-77'
            getCapabilities() >> { throw new AssertionError('SDK capabilities read') }
            getSupportedAttributes() >> { throw new AssertionError('SDK attributes read') }
            getSupportedCommands() >> { throw new AssertionError('SDK commands read') }
            currentValue(_) >> { throw new AssertionError('SDK state read') }
            updateDataValue(_, _) >> { throw new AssertionError('SDK data write') }
        }
        def self = this
        wireLifecycle({ Object... args ->
            if (args.length == 1 && args[0] == 'list') return self.childDevicesList
            self.creations++
            self.childDevicesList << child
            child
        })
    }

    @Unroll
    def 'virtual inventory uses native detail and keeps ownership pagination with bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        owned('77')
        owned('78')
        settingsMap.selectedDevices = [new TestDevice(id: 99, name: 'Not MCP owned')]

        when:
        def result = script.toolListVirtualDevices([cursor: '', limit: 1])

        then:
        result.total == 2
        result.count == 1
        result.nextCursor == '1'
        result.devices*.id == ['77']
        result.devices[0].name == 'Native name'
        result.devices[0].driverNamespace == 'persisted-ns'
        result.devices[0].driverType == 'Native Driver'
        result.devices[0].typeName == 'Native Driver'
        result.devices[0].capabilities == ['Switch', 'TemperatureMeasurement']
        result.devices[0].commands == ['on']
        result.devices[0].currentStates == [switch: 'on', temperature: 21.50]
        !hubGet.calls.any { it.path == '/device/fullJson/99' }
        !hubGet.calls.any { it.path == '/device/fullJson/78' }

        where:
        bypass << [false, true]
    }

    def 'virtual inventory derives missing namespace from native driver metadata'() {
        given:
        owned('77')
        models['77'].device.data = [:]

        when:
        def response = mcpDriver.callTool('hub_list_devices', [filter: 'virtual'])

        then:
        response.error == null
        !response.result.isError
        mcpDriver.parseInner(response).devices[0].driverNamespace == 'native-ns'
    }

    def 'virtual inventory preserves failed device id and counts instead of claiming empty native metadata'() {
        given:
        owned('77')
        readable = false

        when:
        def result = script.toolListVirtualDevices([:])

        then:
        result.success == false
        result.count == 1
        result.total == 1
        result.devices[0].id == '77'
        result.devices[0].success == false
        result.devices[0].error.contains('fullJson')
        !result.devices[0].containsKey('capabilities')
    }

    def 'virtual inventory does not invent hubitat namespace when native namespace metadata is missing'() {
        given:
        owned('77')
        models['77'].device.data = [:]
        models['77'].device.remove('deviceTypeNamespace')

        when:
        def result = script.toolListVirtualDevices([:])

        then:
        result.partialSuccess == true
        result.devices[0].driverNamespace == null
        result.devices[0].warnings
        result.devices[0].driverType == 'Native Driver'
    }

    def 'create persists namespace through fixed native writer with readback and returns native reported attributes'() {
        given:
        creator()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [action: 'create',
            customDriver: [namespace: 'custom-ns', name: 'Native Driver'], deviceLabel: 'Native label',
            deviceNetworkId: 'mcp-77', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def result = mcpDriver.parseInner(response)
        result.success == true
        result.device.id == '77'
        result.device.name == 'Native name'
        result.device.driverNamespace == 'custom-ns'
        result.device.attributes == [[name: 'switch', value: 'on'], [name: 'temperature', value: 21.50]]
        writes == [[id: 77, method: 'updateDataValue', args: [[type: 'STRING', value: 'mcpDriverNamespace'],
            [type: 'STRING', value: 'custom-ns']]]]
        hubGet.calls.count { it.path == '/device/fullJson/77' } >= 2
        creations == 1
    }

    def 'virtual creation retains owned identity when native readback identifies another device'() {
        given:
        creator()
        models['77'].device.id = 78

        when:
        def result = script.toolCreateVirtualDevice([customDriver: [namespace: 'custom-ns', name: 'Custom Driver'],
            deviceLabel: 'Created child', deviceNetworkId: 'mcp-77', confirm: true])

        then:
        result.success == true
        result.partialSuccess == true
        result.device.id == '77'
        !result.device.containsKey('name')
        result.note.contains('Do not recreate')
        creations == 1
    }

    def 'virtual delete captures native label and preserves child lifecycle deletion'() {
        given:
        owned('77')

        when:
        def result = script.toolDeleteVirtualDevice([deviceNetworkId: 'mcp-77', confirm: true])

        then:
        deletions == ['mcp-77']
        result.success == true
        result.deviceId == '77'
        result.deviceLabel == 'Native label'
        hubGet.calls.any { it.path == '/device/fullJson/77' }
        writes.empty
    }

    def 'virtual delete stops before lifecycle mutation when native identity is unavailable'() {
        given:
        owned('77')
        readable = false

        when:
        def result = script.toolDeleteVirtualDevice([deviceNetworkId: 'mcp-77', confirm: true])

        then:
        deletions.empty
        result.success == false
        result.deviceId == '77'
        result.error.contains('fullJson')
        result.note
        childDevicesList.size() == 1
    }

    @Unroll
    def 'created device remains identifiable when #failure fails'() {
        given:
        creator()
        acceptWrite = failure != 'write'
        persistWrite = failure != 'data verification'
        readable = failure != 'all reads'
        failInfoAfterData = failure == 'info read'

        when:
        def result = script.toolCreateVirtualDevice([deviceType: 'Virtual Switch',
            deviceLabel: 'Native label', deviceNetworkId: 'mcp-77', confirm: true])

        then:
        result.success == true
        result.partialSuccess == true
        result.device.id == '77'
        result.device.deviceNetworkId == 'mcp-77'
        result.warnings
        result.note.toLowerCase().contains('recreat')
        creations == 1

        where:
        failure << ['write', 'data verification', 'all reads', 'info read']
    }
}
