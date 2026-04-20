package server

import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for toolSendCommand (hubitat-mcp-server.groovy line 1986).
 *
 * Covers: golden-path command dispatch to a device in childDevicesList,
 * and the "Device not found" error path.
 */
class ToolSendCommandSpec extends ToolSpecBase {

    def "dispatches command to device and returns success"() {
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

    def "throws when device is not found"() {
        given:
        childDevicesList.clear()

        when:
        script.toolSendCommand('999', 'on', [])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Device not found: 999'
    }
}
