package server

import spock.lang.Shared
import support.TestDevice
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * hub_get_info readings (#511): numbers as numbers, the database size in the hub's own unit (MB),
 * a device count covering every device the tools reach, and no hub-security field once the
 * firmware has retired the credentials it describes.
 */
class HubInfoDataQualitySpec extends ToolSpecBase {
    @Shared private TestLocation infoLocation = new TestLocation(hub: new TestHub())

    def setupSpec() {
        appExecutor.getLocation() >> infoLocation
    }

    def setup() {
        hubGet.register('/hub/advanced/freeOSMemory') { p -> '456232\n' }
        hubGet.register('/hub/advanced/internalTempCelsius') { p -> '42.0' }
        hubGet.register('/hub/advanced/databaseSize') { p -> '25' }
        hubGet.register('/logs/past/json') { p -> '[]' }
        hubGet.register('/hub2/hubData') { p -> '{}' }
    }

    def cleanup() {
        childDevicesList.clear()
    }

    def "readings are numbers and the database size is reported in MB"() {
        when:
        def info = script.toolGetHubInfo([:])

        then:
        info.freeMemoryKB == 456232L
        info.internalTempCelsius == 42.0
        info.databaseSizeMB == 25L
        !info.containsKey('databaseSizeKB')
        !info.containsKey('databaseWarning')
    }

    def "a database over 500 MB carries the warning"() {
        given:
        hubGet.register('/hub/advanced/databaseSize') { p -> '612' }

        expect:
        script.toolGetHubInfo([:]).databaseWarning.contains('612 MB')
    }

    def "mcpDeviceCount counts the selected devices and the MCP-managed virtual devices once each"() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 1), new TestDevice(id: 2)]
        childDevicesList << new TestDevice(id: 2) << new TestDevice(id: 30)

        expect:
        script.toolGetHubInfo([:]).mcpDeviceCount == 3
    }

    def "with the allowlist bypassed every device in the hub's tree counts, and the bypass is flagged"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        settingsMap.selectedDevices = [new TestDevice(id: 1)]
        hubGet.register('/hub2/devicesList') { p ->
            groovy.json.JsonOutput.toJson([devices: [[data: [id: 1], children: [[data: [id: 2]]]], [data: [id: 3]]]])
        }

        when:
        def info = script.toolGetHubInfo([:])

        then:
        info.deviceAllowlistBypassed == true
        info.mcpDeviceCount == 3
        !info.containsKey('mcpDeviceCountNote')
    }

    def "an unreadable device tree under the bypass keeps the selected count and says why"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        settingsMap.selectedDevices = [new TestDevice(id: 1)]
        hubGet.register('/hub2/devicesList') { p -> throw new IOException('tree down') }

        when:
        def info = script.toolGetHubInfo([:])

        then:
        info.mcpDeviceCount == 1
        info.mcpDeviceCountNote.contains('tree down')
    }

    def "without the bypass no device tree is read and the bypass is not flagged"() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 1)]

        when:
        def info = script.toolGetHubInfo([:])

        then:
        !info.containsKey('deviceAllowlistBypassed')
        !hubGet.calls.any { it.path == '/hub2/devicesList' }
    }

    def "hubSecurityConfigured is listed only while the firmware still uses the credentials: retired=#retired"() {
        given:
        stateMap.hubSecurityRetired = retired

        when:
        def info = script.toolGetHubInfo([:])

        then:
        info.hubSecurityRetired == retired
        info.containsKey('hubSecurityConfigured') == !retired

        where:
        retired << [true, false]
    }
}
