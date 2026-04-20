package server

import support.ToolSpecBase

/**
 * Spec for toolGetDevice (hubitat-mcp-server.groovy line 1937).
 *
 * Covers: golden-path response shape and the "Device not found" error path.
 */
class ToolGetDeviceSpec extends ToolSpecBase {

    def "returns device summary shape for an existing device"() {
        given:
        def device = Mock(Object) {
            _ * getId() >> 10
            _ * getName() >> 'TestSwitch'
            _ * getLabel() >> 'Test Switch'
            _ * getRoomName() >> 'Living Room'
            _ * getCapabilities() >> [[name: 'Switch']]
            _ * getSupportedAttributes() >> [[name: 'switch', dataType: 'ENUM']]
            _ * getSupportedCommands() >> [[name: 'on', arguments: null], [name: 'off', arguments: null]]
            _ * currentValue('switch') >> 'off'
        }
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

    def "throws when device is not found"() {
        given:
        childDevicesList.clear()
        settingsMap.selectedDevices = []

        when:
        script.toolGetDevice('999')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Device not found: 999'
    }
}
