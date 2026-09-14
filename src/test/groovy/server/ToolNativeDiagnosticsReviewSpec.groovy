package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolNativeDiagnosticsReviewSpec extends ToolSpecBase {
    @Unroll
    def 'Zigbee ping targets require authorized existing hub device ID for #ownership bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        if (ownership == 'selected') settingsMap.selectedDevices = [new TestDevice(id: 42)]
        if (ownership == 'child') childDevicesList << new TestDevice(id: 42)
        hubGet.register('/device/fullJson/42') { JsonOutput.toJson([device: [id: 42]]) }
        hubGet.register('/hub/zigbee/updatePingDevice/42/true') { 'ok' }

        when:
        def result = script.toolSetZigbee([ping_device: [device_id: '42', enabled: true]])

        then:
        result.success == true
        hubGet.calls*.path == ['/device/fullJson/42', '/hub/zigbee/updatePingDevice/42/true']

        where:
        ownership    | bypass
        'selected'   | false
        'selected'   | true
        'child'      | false
        'child'      | true
        'unselected' | true
    }

    @Unroll
    def 'Zigbee ping rejects unauthorized or non-hub ID #deviceId before HTTP'() {
        when:
        def response = mcpDriver.callTool('hub_set_zigbee', [ping_device: [device_id: deviceId, enabled: true]])

        then:
        response.result.isError == true
        hubGet.calls.empty

        where:
        deviceId << ['42', '0x1234']
    }

    def 'Zigbee ping refuses native identity mismatch before mutation'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/device/fullJson/42') { JsonOutput.toJson([device: [id: 43]]) }

        when:
        def response = mcpDriver.callTool('hub_set_zigbee', [ping_device: [device_id: '42', enabled: true]])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).success == false
        hubGet.calls*.path == ['/device/fullJson/42']
    }

    def 'health inventory exception records operation and class without logging native response text'() {
        given:
        def logs = []
        script.metaClass.mcpLog = { String level, String component, String message ->
            logs << [level: level, component: component, message: message]
        }
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/hub2/devicesList') {
            throw new IllegalStateException('private native response sentinel')
        }
        hubGet.register('/hub/networkTest/traceroute/1.1.1.1') { 'route output' }

        when:
        def result = script.toolDeviceHealthCheck([tracerouteHost: '1.1.1.1'])

        then:
        result.success == false
        result.error.contains('IllegalStateException')
        result.traceroute.output == 'route output'
        !result.containsKey('summary')
        logs.any { it.level == 'error' && it.component == 'monitoring' &&
            it.message.contains('hub_get_device_health') && it.message.contains('IllegalStateException') }
        !logs.any { it.message.contains('private native response sentinel') }
    }

    @Unroll
    def 'health reads bulk activity once for selected children and bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        settingsMap.selectedDevices = [new TestDevice(id: 1)]
        childDevicesList << new TestDevice(id: 2)
        hubGet.register('/hub2/devicesList') {
            JsonOutput.toJson([devices: [[data: [id: 1, name: 'Healthy', lastActivity: '2026-09-10T11:35:07+0000'],
                children: [[data: [id: 2, name: 'Stale child', lastActivity: 1234395090000L]]]]] +
                (3..75).collect { [data: [id: it, name: "Device ${it}", lastActivity: null]] }])
        }

        when:
        def response = mcpDriver.callTool('hub_get_device_health', [includeHealthy: true])

        then:
        response.error == null
        response.result.isError != true
        def result = mcpDriver.parseInner(response)
        result.summary.totalDevices == (bypass ? 75 : 2)
        result.healthyDevices*.name == ['Healthy']
        result.staleDevices*.name == ['Stale child']
        result.summary.unknownCount == (bypass ? 73 : 0)
        hubGet.calls*.path == ['/hub2/devicesList']

        where:
        bypass << [false, true]
    }

    def 'health missing activity and missing allowed identities remain explicit unknowns'() {
        given:
        settingsMap.selectedDevices = [1, 2, 3, 4].collect { new TestDevice(id: it) }
        hubGet.register('/hub2/devicesList') {
            JsonOutput.toJson([devices: [[data: [id: 1, name: 'Never', lastActivity: null]],
                [data: [id: 2, name: 'Missing activity']],
                [data: [id: 4, name: 'Invalid activity', lastActivity: 'unparseable native activity']]]])
        }

        when:
        def result = script.toolDeviceHealthCheck([:])

        then:
        result.summary.totalDevices == 4
        result.summary.unknownCount == 4
        result.unknownDevices.find { it.id == '1' }.lastActivity == 'never'
        result.unknownDevices.find { it.id == '2' }.metadataUnavailable == true
        result.unknownDevices.find { it.id == '3' }.metadataUnavailable == true
        result.unknownDevices.find { it.id == '4' }.lastActivity == 'unavailable'
        hubGet.calls*.path == ['/hub2/devicesList']
    }

    def 'health description identifies selected owned and bypass populations'() {
        when:
        def description = script.getAllToolDefinitions().find { it.name == 'hub_get_device_health' }.description

        then:
        description.contains('selected devices and MCP-managed children')
        description.contains('all hub devices when allowlist bypass is enabled')
    }
}
