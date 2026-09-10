package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolNativeMetadataBoundarySpec extends ToolSpecBase {
    Map model
    List writes = []

    def setup() {
        model = [device: [id: 10, name: 'Native fixture', label: 'Native fixture',
            deviceNetworkId: 'mcp-10', capabilities: ['Switch'],
            currentStates: [switch: [value: 'on']]], commands: [[name: 'on', parameters: []]]]
        childDevicesList << new TestDevice(id: 10, deviceNetworkId: 'mcp-10')
        stateMap.lastBackupTimestamp = 1234567890000L
        hubGet.register('/device/fullJson/10') { JsonOutput.toJson(model) }
        hubGet.register('/device/eventsJson/10') { '[]' }
        hubGet.register('/logs/past/json') { '[]' }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            writes << [path: path, body: body]
            [success: true]
        }
    }

    private def read(String operation) {
        switch (operation) {
            case 'summary': return script.toolGetDevice('10')
            case 'attribute': return script.toolGetAttribute('10', 'switch')
            case 'poll': return script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch', expectedValue: 'on', timeoutMs: 100, pollIntervalMs: 100])
            case 'events': return script.toolGetDeviceEvents('10', 10)
            case 'history': return script.toolGetDeviceHistory([deviceId: '10', hoursBack: 1])
            case 'command': return script.toolSendCommand('10', 'on', [])
            case 'update': return script.toolUpdateDevice([deviceId: '10', label: 'Changed'])
            case 'logs': return script.toolGetHubLogs([deviceId: '10'])
            case 'virtual': return script.toolListVirtualDevices([:]).devices[0]
            case 'deleteVirtual': return script.toolDeleteVirtualDevice([deviceNetworkId: 'mcp-10', confirm: true])
        }
        throw new AssertionError("Unhandled operation: ${operation}")
    }

    @Unroll
    def '#operation rejects #identity native identity with bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        if (identity == 'empty') model.device = [:]
        if (identity == 'missing') model.device.remove('id')
        if (identity == 'mismatched') model.device.id = 11

        when:
        def result = read(operation)

        then:
        result.success == false
        if (operation == 'poll') {
            assert result.readError == true
            assert result.timedOut != true
            assert result.error.toString().contains('10')
            assert result.finalValue == null
        } else {
            assert result.error
        }
        writes.empty
        !hubGet.calls.any { it.path in ['/device/eventsJson/10', '/logs/past/json'] }

        where:
        [operation, identity, bypass] << ['summary', 'attribute', 'poll', 'events', 'history', 'command', 'update', 'logs', 'virtual'].collectMany { op ->
            ['empty', 'missing', 'mismatched'].collectMany { identity -> [false, true].collect { [op, identity, it] } }
        }
    }

    @Unroll
    def 'unverified native identity is a #tool tool execution error'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        model.device.id = 11

        when:
        def response = mcpDriver.callTool(tool, args)

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).success == false
        writes.empty

        where:
        tool                       | args
        'hub_get_device'            | [deviceId: '10']
        'hub_get_logs'              | [deviceId: '10']
        'hub_call_device_command'   | [deviceId: '10', command: 'on']
        'hub_list_devices'          | [filter: 'virtual']
        'hub_get_device_attribute'  | [deviceId: '10', attribute: 'switch']
        'hub_list_device_events'    | [deviceId: '10']
        'hub_list_device_events'    | [deviceId: '10', hoursBack: 1]
        'hub_update_device'         | [deviceId: '10', label: 'Changed']
    }

    @Unroll
    def 'summary rejects #field collection #shape without manufacturing an empty value'() {
        given:
        def source = field == 'commands' ? model : model.device
        if (shape == 'absent') source.remove(field)
        else source[field] = shape == 'null' ? null : 'unavailable'

        when:
        def result = read('summary')

        then:
        result.success == false
        result.error.toString().contains(field)
        !result.containsKey('attributes')

        where:
        [field, shape] << ['commands', 'capabilities', 'currentStates'].collectMany { f ->
            ['absent', 'null', 'malformed'].collect { [f, it] }
        }
    }

    @Unroll
    def 'unavailable command collection #commands is a runtime failure before dispatch'() {
        given:
        model.commands = commands

        when:
        def result = read('command')

        then:
        result.success == false
        result.error.toString().contains('command')
        result.note.toString().contains('no command was sent')
        writes.empty

        where:
        commands << [null, 'unavailable', [error: 'unavailable']]
    }

    def 'genuine empty native collections remain valid and reject an unsupported command'() {
        given:
        model.device.currentStates = [:]
        model.device.capabilities = []
        model.commands = []

        expect:
        read('summary').attributes == []
        read('summary').commands == []
        read('summary').capabilities == []
        read('attribute').neverReported == true

        when:
        read('command')

        then:
        thrown(IllegalArgumentException)
        writes.empty
    }

    def 'created native device keeps its ID without accepting another devices readback'() {
        given:
        hubGet.register('/device/drivers') { '{"drivers":[{"id":500,"type":"sys","name":"Fixture Driver"}]}' }
        hubGet.register('/device/sysDriverByIdJson/500') { '{"success":true,"deviceId":10}' }
        model.device.id = 11

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', confirm: true])

        then:
        result.success == true
        result.deviceId == '10'
        result.name == null
        result.label == null
        result.warnings.any { it.contains('could not read it back') }
    }

    def 'configuration projection does not depend on summary state or command collections'() {
        given:
        model.remove('commands')
        model.device.remove('capabilities')
        model.device.remove('currentStates')
        model.settings = []
        model.inputValues = []

        when:
        def result = script.toolGetDevice('10', 'configuration')

        then:
        result.preferenceRead.status == 'complete'
        result.id == '10'
    }

    @Unroll
    def '#operation rejects malformed event rows #rows instead of losing activity'() {
        given:
        hubGet.register('/device/eventsJson/10') { JsonOutput.toJson(rows) }

        when:
        def result = read(operation)

        then:
        result.success == false
        result.error.toString().contains('eventsJson')
        !result.containsKey('events')

        where:
        [operation, rows] << ['events', 'history'].collectMany { op ->
            [[null, 'unavailable'], [[name: 'switch', value: 'on'], null]].collect { [op, it] }
        }
    }

    @Unroll
    def 'command acknowledgement #ack reports unknown outcome and prevents unsafe replay advice'() {
        given:
        int calls = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            calls++
            if (ack == 'exception') throw new IOException('response lost')
            ack
        }

        when:
        def result = read('command')

        then:
        result.success == false
        result.outcomeUnknown == true
        result.isError == true
        result.note.toString().contains('Inspect')
        result.note.toString().contains('state')
        result.note.toString().contains('event')
        !result.note.toString().toLowerCase().contains('retry')
        calls == 1

        where:
        ack << ['exception', null, [_unparseable: true, message: 'not JSON'], [:], [], 'OK']
    }

    @Unroll
    def 'room clear cannot be confirmed by #room readback'() {
        given:
        model.device.roomName = 'Den'
        model.device.putAll([version: 0, controllerType: null, zigbeeId: null,
            maxEvents: 10, maxStates: 10, spammyThreshold: 100, deviceTypeId: 100,
            deviceTypeReadableType: 'System', roomId: 7, meshEnabled: false,
            retryEnabled: false, meshFullSync: false, locationId: 1, hubId: 1,
            groupId: null, tags: '', defaultIcon: null, notes: null])
        model.homeKitEnabled = false
        model.dashboards = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int timeout = 420, boolean retry = false ->
            writes << [path: path, body: body]
            if (room == 'absent') model.device.remove('roomName')
            else model.device.roomName = room
            [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', room: 'none'])

        then:
        result.success == false
        result.isError == true
        result.errors.any { it.property == 'room' && it.error }
        !result.changes.any { it.property == 'room' }
        writes.size() == 1

        where:
        room << ['absent', false, 0, [:], []]
    }
    @Unroll
    def 'missing native metadata is an MCP execution error in #mode mode'() {
        given:
        model.device.id = 11

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '10', mode: mode])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).success == false

        where:
        mode << ['summary', 'configuration', 'details']
    }

    def 'poll preflight rejects a missing identity without waiting or re-fetching'() {
        given:
        model.device = null
        def pauses = []
        script.metaClass.pauseExecution = { Long millis -> pauses << millis }

        when:
        def result = read('poll')

        then:
        result.success == false
        result.isError == true
        result.readError == true
        result.error.toString().contains('10')
        result.timedOut != true
        pauses.empty
        hubGet.calls.count { it.path == '/device/fullJson/10' } == 1
    }

    def 'an unreported attribute includes observed names without claiming driver declarations'() {
        when:
        def result = script.toolGetAttribute('10', 'swich')

        then:
        result.value == null
        result.neverReported == true
        result.reportedAttributes == ['switch']
        result.note.toString().contains('never reported')
    }

}
