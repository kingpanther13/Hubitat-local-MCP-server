package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolGetDeviceInUseBy (hubitat-mcp-server.groovy approx line 7573).
 * Gateway: manage_installed_apps -> get_device_in_use_by.
 *
 * Critical: PR-79-review fix tightened deviceId validation -- findDevice()
 * is called before any HTTP request, so unknown IDs throw
 * IllegalArgumentException without a network round-trip.
 *
 * Covers: gate-throw, missing deviceId, unknown-deviceId (throws before HTTP),
 * golden path with deviceName fallback chain, non-numeric appsUsingCount warn
 * fallback, and empty response body.
 */
class ToolGetDeviceInUseBySpec extends ToolSpecBase {

    def "throws when Built-in App Read is disabled"() {
        given:
        settingsMap.enableBuiltinAppRead = false

        when:
        script.toolGetDeviceInUseBy([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    def "throws when deviceId is missing from args"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        script.toolGetDeviceInUseBy([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('deviceid is required')
    }

    def "throws Device-not-found before any HTTP call for an unknown deviceId"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        and: 'no device registered -- childDevicesList is empty and selectedDevices is empty'
        // Do NOT register the /device/fullJson/ endpoint; if the tool calls HTTP
        // before validation, the HubInternalGetMock will throw loudly and we will
        // see a different failure mode than the expected IllegalArgumentException.

        when:
        script.toolGetDeviceInUseBy([deviceId: '999'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Device not found')
        ex.message.contains('999')
    }

    def "golden path: returns appsUsing list with extraBreadcrumb as deviceName"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        and: 'device is in childDevicesList so findDevice succeeds'
        def mockDevice = [id: '42', label: 'Kitchen Switch', name: 'Generic Switch']
        childDevicesList << mockDevice

        and: 'hub returns fullJson with appsUsing entries'
        def responseJson = JsonOutput.toJson([
            extraBreadcrumb: 'Kitchen Switch (breadcrumb)',
            name: 'Generic Switch',
            label: 'Kitchen Switch',
            appsUsing: [
                [id: 100, name: 'Room Lighting', label: 'Kitchen Lights', trueLabel: null, disabled: false],
                [id: 200, name: 'Rule-5.1', label: '<b>Auto Rule</b>', trueLabel: 'Auto Rule', disabled: true]
            ],
            appsUsingCount: 2,
            parentApp: null
        ])
        hubGet.register('/device/fullJson/42') { params -> responseJson }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '42'])

        then: 'extraBreadcrumb wins in the deviceName fallback chain'
        result.deviceId == '42'
        result.deviceName == 'Kitchen Switch (breadcrumb)'
        result.count == 2

        and: 'both app entries mapped correctly'
        result.appsUsing.size() == 2
        result.appsUsing[0].id == 100
        result.appsUsing[0].name == 'Room Lighting'
        result.appsUsing[1].disabled == true
        result.appsUsing[1].trueLabel == 'Auto Rule'
    }

    def "deviceName falls back to .name when extraBreadcrumb is absent"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        def mockDevice = [id: '55', label: 'My Device', name: 'Generic Device']
        childDevicesList << mockDevice

        def responseJson = JsonOutput.toJson([
            extraBreadcrumb: null,
            name: 'Generic Device',
            label: 'My Device',
            appsUsing: [],
            appsUsingCount: 0
        ])
        hubGet.register('/device/fullJson/55') { params -> responseJson }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '55'])

        then:
        result.deviceName == 'Generic Device'
    }

    def "deviceName falls back to .label when both extraBreadcrumb and name are absent"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        def mockDevice = [id: '66', label: 'Label Only', name: null]
        childDevicesList << mockDevice

        def responseJson = JsonOutput.toJson([
            extraBreadcrumb: null,
            name: null,
            label: 'Label Only',
            appsUsing: [],
            appsUsingCount: 0
        ])
        hubGet.register('/device/fullJson/66') { params -> responseJson }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '66'])

        then:
        result.deviceName == 'Label Only'
    }

    def "non-numeric appsUsingCount falls back to appsUsing.size()"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        def mockDevice = [id: '77', label: 'My Dev', name: 'Dev']
        childDevicesList << mockDevice

        and: 'hub returns a truncated-count sentinel string for appsUsingCount'
        def responseJson = JsonOutput.toJson([
            extraBreadcrumb: 'My Dev',
            appsUsing: [
                [id: 1, name: 'App A', label: 'App A', trueLabel: null, disabled: false],
                [id: 2, name: 'App B', label: 'App B', trueLabel: null, disabled: false]
            ],
            appsUsingCount: '42+'   // non-numeric sentinel
        ])
        hubGet.register('/device/fullJson/77') { params -> responseJson }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '77'])

        then: 'count falls back to list size (2) rather than the unparseable "42+"'
        result.count == 2
    }

    def "returns success=false with empty-response error when hub body is empty"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        def mockDevice = [id: '88', label: 'Dev', name: 'Dev']
        childDevicesList << mockDevice
        hubGet.register('/device/fullJson/88') { params -> '' }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '88'])

        then:
        result.success == false
        result.error?.toLowerCase()?.contains('empty') == true
    }

    def "gateway dispatch via handleGateway routes correctly"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        def mockDevice = [id: '10', label: 'Switch', name: 'Switch']
        childDevicesList << mockDevice

        def responseJson = JsonOutput.toJson([
            extraBreadcrumb: 'Switch',
            appsUsing: [],
            appsUsingCount: 0
        ])
        hubGet.register('/device/fullJson/10') { params -> responseJson }

        when:
        def result = script.handleGateway('manage_installed_apps', 'get_device_in_use_by', [deviceId: '10'])

        then:
        result.deviceId == '10'
    }
}
