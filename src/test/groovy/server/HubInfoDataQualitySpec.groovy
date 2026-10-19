package server

import support.TestDevice
import support.ToolSpecBase

/**
 * hub_get_info readings (#511): numbers as numbers, the database size in the hub's own unit (MB),
 * a device count covering every device the tools reach, and no hub-security field once the
 * firmware has retired the credentials it describes.
 */
class HubInfoDataQualitySpec extends ToolSpecBase {

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
