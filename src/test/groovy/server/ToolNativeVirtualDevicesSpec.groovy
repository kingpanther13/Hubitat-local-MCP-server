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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
            // A delete reaching this slot (create-then-roll-back) must be recorded as one, or a
            // deletions.isEmpty() assertion passes vacuously.
            if (args[0] == 'delete') {
                self.deletions << args[2]
                self.childDevicesList.removeAll { it.deviceNetworkId == args[2] }
                return null
            }
            self.creations++
            self.childDevicesList << child
            child
        })
    }

    // The Devices page and capability feed for these ids, built from their fullJson models.
    private void page(List ids) {
        hubGet.register('/hub2/devicesList') {
            JsonOutput.toJson([devices: ids.collect { id ->
                def d = models[id]?.device ?: [id: id.toInteger(), name: "Device ${id}"]
                [key: "DEV-${id}", data: [id: d.id, name: d.label ?: d.name, secondaryName: d.label ? d.name : '',
                    type: d.deviceTypeName, dni: d.deviceNetworkId, isVirtual: true,
                    currentStates: (d.currentStates ?: [:]).collect { k, v -> [key: k, value: v.value] }], children: []]
            }])
        }
        hubGet.register('/hub2/vrb/devices') {
            JsonOutput.toJson(ids.collect { id -> [id: id.toInteger(), capabilities: models[id]?.device?.capabilities ?: []] })
        }
    }

    private List fullJsonReads() { hubGet.calls.findAll { it.path.startsWith('/device/fullJson/') }*.path }

    @Unroll
    def 'owned inventory lists only the app children from the page, with bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        owned('77')
        owned('78')
        settingsMap.selectedDevices = [new TestDevice(id: 99, name: 'Not MCP owned')]
        page(['77', '78', '99'])

        when:
        def result = script.toolListDevices([scope: 'owned', cursor: '', limit: 1])

        then:
        result.total == 2
        result.count == 1
        result.nextCursor == '1'
        result.devices == [[id: '77', label: 'Native label', name: 'Native name', driver: 'Native Driver', room: null,
            disabled: false, deviceNetworkId: 'mcp-77', lastActivity: null, protocol: 'virtual', mcpManaged: true,
            currentStates: [switch: 'on', temperature: '21.50']]]
        fullJsonReads().isEmpty()

        where:
        bypass << [false, true]
    }

    def 'owned detailed reads only the listed device for its saved driver namespace and typed values'() {
        given:
        owned('77')
        owned('78')
        page(['77', '78'])

        when:
        def result = script.toolListDevices([scope: 'owned', format: 'detailed', limit: 1])

        then:
        with(result.devices[0]) {
            driverNamespace == 'persisted-ns'
            capabilities == ['Switch', 'TemperatureMeasurement']
            commands == [[name: 'on', arguments: null]]
            attributes.find { it.name == 'temperature' }.value == 21.50
        }
        fullJsonReads() == ['/device/fullJson/77']
    }

    def 'owned detailed falls back to the native driver namespace when none was saved'() {
        given:
        owned('77')
        models['77'].device.data = [:]
        page(['77'])

        when:
        def response = mcpDriver.callTool('hub_list_devices', [scope: 'owned', format: 'detailed'])

        then:
        response.error == null
        !response.result.isError
        mcpDriver.parseInner(response).devices[0].driverNamespace == 'native-ns'
    }

    @Unroll
    def 'owned filters intersect before pagination through #surface with bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        ['77', '78', '79', '80', '81'].each { owned(it) }
        models['77'].device.label = 'Unrelated first'
        models['78'].device.label = 'BAT Native First'
        models['79'].device.label = 'BAT Native Wrong Capability'
        models['79'].device.capabilities = ['TemperatureMeasurement']
        models['80'].device.label = null
        models['80'].device.name = 'BAT Native Fallback'
        models['81'].device.label = 'BAT Native Last'
        settingsMap.selectedDevices = [new TestDevice(id: 99, name: 'BAT Native not owned')]
        page(['77', '78', '79', '80', '81', '99'])
        def args = [scope: 'owned', labelFilter: 'bat NATIVE', capabilityFilter: 'sWITCH', cursor: '1', limit: 1]

        when:
        def result = surface == 'direct' ? script.toolListDevices(args) :
            mcpDriver.parseInner(mcpDriver.callTool('hub_list_devices', args))

        then:
        result.devices*.id == ['80']
        result.devices[0].label == 'BAT Native Fallback'
        result.total == 3
        result.unfilteredTotal == 5
        result.count == 1
        result.offset == 1
        result.hasMore == true
        result.nextCursor == '2'
        fullJsonReads().isEmpty()
        writes.empty

        where:
        [surface, bypass] << ['direct', 'dispatch'].collectMany { surface -> [false, true].collect { [surface, it] } }
    }

    def 'owned cursor bounds use the filtered total'() {
        given:
        ['77', '78', '79'].each { owned(it) }
        models['77'].device.label = 'Only match'
        page(['77', '78', '79'])

        when:
        script.toolListDevices([scope: 'owned', labelFilter: 'Only', cursor: '2', limit: 1])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('out of range')
    }

    @Unroll
    def 'owned #field rejects non-string #value with no children and no hub read'() {
        when:
        script.toolListDevices([scope: 'owned', (field): value])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains(field)
        hubGet.calls.empty

        where:
        [field, value] << ['labelFilter', 'capabilityFilter'].collectMany { field -> [7, false, [], [:]].collect { [field, it] } }
    }

    def 'owned with no children says how to create one without a hub read'() {
        when:
        def result = script.toolListDevices([scope: 'owned', labelFilter: 'Absent'])

        then:
        result.devices == []
        result.total == 0
        result.message.contains('hub_manage_virtual_device')
        hubGet.calls.empty
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

    @Unroll
    def 'virtual delete uses owned identity when native metadata is #failure with bypass #bypass'() {
        given:
        owned('77')
        settingsMap.bypassDeviceAllowlist = bypass
        childDevicesList[0].metaClass.getLabel = { throw new AssertionError('SDK label read') }
        childDevicesList[0].metaClass.getName = { throw new AssertionError('SDK name read') }
        hubGet.register('/device/fullJson/77') {
            if (failure == 'transport failure') throw new IOException('offline')
            if (failure == 'wrong identity') return '{"device":{"id":78,"label":"Wrong device"}}'
            null
        }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device',
            [action: 'delete', deviceNetworkId: 'mcp-77', confirm: true])

        then:
        response.error == null
        response.result.isError != true
        def result = mcpDriver.parseInner(response)
        deletions == ['mcp-77']
        result.success == true
        result.deviceId == '77'
        result.deviceLabel == 'MCP-managed virtual device'
        childDevicesList.empty

        where:
        [failure, bypass] << ['unavailable', 'transport failure', 'wrong identity'].collectMany { failure ->
            [false, true].collect { [failure, it] }
        }
    }

    @Unroll
    def 'duplicate DNI preserves validation error when native label read fails through #surface'() {
        given:
        owned('77')
        childDevicesList[0].metaClass.getLabel = { throw new AssertionError('SDK label read') }
        childDevicesList[0].metaClass.getName = { throw new AssertionError('SDK name read') }
        hubGet.register('/device/fullJson/77') { throw new IOException('offline') }
        def args = [action: 'create', deviceType: 'Virtual Switch', deviceLabel: 'Duplicate',
            deviceNetworkId: 'mcp-77', confirm: true]

        when:
        String message
        if (surface == 'direct') {
            try {
                script.toolManageVirtualDevice(args)
                assert false: 'Duplicate DNI must fail validation'
            } catch (IllegalArgumentException expected) {
                message = expected.message
            }
        } else {
            def response = mcpDriver.callTool('hub_manage_virtual_device', args)
            assert response.result.isError == true
            message = mcpDriver.parseInner(response).error
        }

        then:
        message.contains("network ID 'mcp-77' already exists")
        message.contains('MCP-managed virtual device')
        message.contains('ID: 77')
        writes.empty
        deletions.empty
        creations == 0

        where:
        surface << ['direct', 'dispatch']
    }

    def 'an owned device whose detail read fails stays listed and the result is partial, through dispatch'() {
        given:
        owned('77')
        owned('78')
        page(['77', '78'])
        hubGet.register('/device/fullJson/78') { null }

        when:
        def response = mcpDriver.callTool('hub_list_devices', [scope: 'owned', format: 'detailed'])

        then:
        response.error == null
        !response.result.isError
        def result = mcpDriver.parseInner(response)
        result.devices*.id == ['77', '78']
        result.devices[1].metadataUnavailable == true
        result.devices[1].deviceNetworkId == 'mcp-78'
        result.partial == true
        result.count == 2
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
