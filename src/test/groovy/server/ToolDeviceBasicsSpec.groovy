package server

import support.TestDevice
import support.ToolSpecBase

/**
 * Device-tool primitives: findDevice (the resolver used by every
 * device-targeting tool), toolGetDevice (summary response shape), and
 * toolSendCommand (command dispatch).
 *
 * Consolidated from FindDeviceSpec + ToolGetDeviceSpec + ToolSendCommandSpec —
 * same harness fixture, same support imports, three thematically adjacent
 * slices of the device-tool surface. Sandbox compile is amortised across
 * all 11 features instead of three separate spec-class compiles.
 *
 * findDevice search order: settings.selectedDevices first, then
 * getChildDevices(). Returns null on miss. Both String and Integer ids
 * are accepted (internally coerces via .toString() equality).
 */
class ToolDeviceBasicsSpec extends ToolSpecBase {

    // ---- findDevice resolver -------------------------------------------------

    def "findDevice finds device in settings.selectedDevices by integer id"() {
        given:
        def device = new TestDevice(id: 10, label: 'In Settings')
        settingsMap.selectedDevices = [device]

        expect:
        script.findDevice(10)?.is(device)
    }

    def "findDevice finds device in settings.selectedDevices by string id"() {
        given:
        def device = new TestDevice(id: 10, label: 'In Settings')
        settingsMap.selectedDevices = [device]

        expect:
        script.findDevice('10')?.is(device)
    }

    def "findDevice falls through to getChildDevices when not in settings.selectedDevices"() {
        given:
        settingsMap.selectedDevices = []
        def virtual = new TestDevice(id: 20, label: 'Virtual Child')
        childDevicesList << virtual

        expect:
        script.findDevice('20')?.is(virtual)
    }

    def "findDevice finds device in getChildDevices by integer id (fallthrough branch coercion)"() {
        given:
        settingsMap.selectedDevices = []
        def virtual = new TestDevice(id: 20, label: 'Virtual Child')
        childDevicesList << virtual

        expect:
        script.findDevice(20)?.is(virtual)
    }

    def "findDevice gives settings.selectedDevices priority over getChildDevices on id collision"() {
        given:
        def fromSettings = new TestDevice(id: 30, label: 'From Settings')
        def fromChildren = new TestDevice(id: 30, label: 'From Children')
        settingsMap.selectedDevices = [fromSettings]
        childDevicesList << fromChildren

        expect:
        script.findDevice('30')?.is(fromSettings)
    }

    def "findDevice returns null when id is not found anywhere"() {
        given:
        settingsMap.selectedDevices = []
        childDevicesList.clear()

        expect:
        script.findDevice('404') == null
    }

    def "findDevice returns null for null id without throwing"() {
        expect:
        script.findDevice(null) == null
    }

    // no dispatch counterparts for findDevice: helper, not a tool

    // ---- toolGetDevice response shape ---------------------------------------

    def "toolGetDevice returns device summary shape for an existing device"() {
        given:
        def device = new TestDevice(
            id: 10,
            name: 'TestSwitch',
            label: 'Test Switch',
            roomName: 'Living Room',
            capabilities: [[name: 'Switch']],
            supportedAttributes: [[name: 'switch', dataType: 'ENUM']],
            supportedCommands: [[name: 'on', arguments: null], [name: 'off', arguments: null]],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        when:
        def result = script.toolGetDevice('10')

        then:
        result.id == '10'
        result.label == 'Test Switch'
        result.room == 'Living Room'
        result.capabilities == ['Switch']
        result.attributes.size() == 1
        result.attributes[0].name == 'switch'
        result.attributes[0].value == 'off'
    }

    @spock.lang.Unroll
    def "via dispatch: get_device returns device summary shape for an existing device (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(
            id: 10,
            name: 'TestSwitch',
            label: 'Test Switch',
            roomName: 'Living Room',
            capabilities: [[name: 'Switch']],
            supportedAttributes: [[name: 'switch', dataType: 'ENUM']],
            supportedCommands: [[name: 'on', arguments: null], [name: 'off', arguments: null]],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('get_device', [deviceId: '10'])

        then:
        response.error == null
        !response.result.isError
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.id == '10'
        inner.label == 'Test Switch'
        inner.room == 'Living Room'
        inner.capabilities == ['Switch']
        inner.attributes.size() == 1
        inner.attributes[0].name == 'switch'
        inner.attributes[0].value == 'off'

        where:
        useGateways << [true, false]
    }

    def "toolGetDevice throws when device is not found"() {
        given:
        childDevicesList.clear()
        settingsMap.selectedDevices = []

        when:
        script.toolGetDevice('999')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Device not found: 999'
    }

    @spock.lang.Unroll
    def "via dispatch: get_device returns -32602 when device is not found (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        childDevicesList.clear()
        settingsMap.selectedDevices = []

        when:
        def response = mcpDriver.callTool('get_device', [deviceId: '999'])

        then:
        response.error.code == -32602
        response.error.message.contains('Device not found: 999')

        where:
        useGateways << [true, false]
    }

    // ---- toolSendCommand dispatch -------------------------------------------

    def "toolSendCommand dispatches command to device and returns success"() {
        given: 'a TestDevice that supports on/off'
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'the device method was invoked exactly once'
        1 * device.on()

        and: 'the result shape reflects success'
        result.success == true
        result.command == 'on'
        result.device == 'Test Switch'
    }

    @spock.lang.Unroll
    def "via dispatch: send_command dispatches command to device and returns success (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
        }
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('send_command', [deviceId: '10', command: 'on', parameters: []])

        then: 'the device method was invoked exactly once'
        1 * device.on()

        and: 'the dispatch envelope carries the success result'
        response.error == null
        !response.result.isError
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.success == true
        inner.command == 'on'
        inner.device == 'Test Switch'

        where:
        useGateways << [true, false]
    }

    def "toolSendCommand throws when device is not found"() {
        given:
        childDevicesList.clear()

        when:
        script.toolSendCommand('999', 'on', [])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Device not found: 999'
    }

    @spock.lang.Unroll
    def "via dispatch: send_command returns -32602 when device is not found (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        childDevicesList.clear()

        when:
        def response = mcpDriver.callTool('send_command', [deviceId: '999', command: 'on', parameters: []])

        then:
        response.error.code == -32602
        response.error.message.contains('Device not found: 999')

        where:
        useGateways << [true, false]
    }
}
