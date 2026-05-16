package server

import support.TestDevice
import support.ToolSpecBase

/**
 * Device-tool primitives: findDevice (the resolver used by every
 * device-targeting tool), toolGetDevice (summary response shape), and
 * toolSendCommand (command dispatch).
 *
 * Consolidated from FindDeviceSpec + ToolGetDeviceSpec + ToolSendCommandSpec
 * (#183) — same harness fixture, same support imports, three thematically
 * adjacent slices of the device-tool surface. Sandbox compile is amortised
 * across all 11 features instead of three separate spec-class compiles.
 *
 * Underlying source locations:
 *   findDevice         — hubitat-mcp-server.groovy
 *   toolGetDevice      — hubitat-mcp-server.groovy line 1937
 *   toolSendCommand    — hubitat-mcp-server.groovy line 1986
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

    def "toolSendCommand throws when device is not found"() {
        given:
        childDevicesList.clear()

        when:
        script.toolSendCommand('999', 'on', [])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Device not found: 999'
    }
}
