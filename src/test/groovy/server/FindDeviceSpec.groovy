package server

import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for hubitat-mcp-server.groovy::findDevice.
 *
 * Search order: settings.selectedDevices first, then getChildDevices().
 * Returns null on miss. Both String and Integer ids are accepted
 * (internally coerces via .toString() equality).
 */
class FindDeviceSpec extends ToolSpecBase {

    def "finds device in settings.selectedDevices by integer id"() {
        given:
        def device = new TestDevice(id: 10, label: 'In Settings')
        settingsMap.selectedDevices = [device]

        expect:
        script.findDevice(10)?.is(device)
    }

    def "finds device in settings.selectedDevices by string id"() {
        given:
        def device = new TestDevice(id: 10, label: 'In Settings')
        settingsMap.selectedDevices = [device]

        expect:
        script.findDevice('10')?.is(device)
    }

    def "falls through to getChildDevices when not in settings.selectedDevices"() {
        given:
        settingsMap.selectedDevices = []
        def virtual = new TestDevice(id: 20, label: 'Virtual Child')
        childDevicesList << virtual

        expect:
        script.findDevice('20')?.is(virtual)
    }

    def "finds device in getChildDevices by integer id (fallthrough branch coercion)"() {
        given:
        settingsMap.selectedDevices = []
        def virtual = new TestDevice(id: 20, label: 'Virtual Child')
        childDevicesList << virtual

        expect:
        script.findDevice(20)?.is(virtual)
    }

    def "settings.selectedDevices takes priority over getChildDevices on id collision"() {
        given:
        def fromSettings = new TestDevice(id: 30, label: 'From Settings')
        def fromChildren = new TestDevice(id: 30, label: 'From Children')
        settingsMap.selectedDevices = [fromSettings]
        childDevicesList << fromChildren

        expect:
        script.findDevice('30')?.is(fromSettings)
    }

    def "returns null when id is not found anywhere"() {
        given:
        settingsMap.selectedDevices = []
        childDevicesList.clear()

        expect:
        script.findDevice('404') == null
    }

    def "returns null for null id without throwing"() {
        expect:
        script.findDevice(null) == null
    }
}
