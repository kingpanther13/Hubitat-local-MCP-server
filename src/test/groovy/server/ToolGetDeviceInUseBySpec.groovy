package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolGetDeviceInUseBy (hubitat-mcp-server.groovy approx line 7573).
 * Gateway: hub_manage_installed_apps -> hub_list_device_dependents.
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
        settingsMap.enableBuiltinApp = false

        when:
        script.toolGetDeviceInUseBy([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    def "throws when deviceId is missing from args"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolGetDeviceInUseBy([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('deviceid is required')
    }

    def "throws Device-not-found before any HTTP call for an unknown deviceId"() {
        given:
        settingsMap.enableBuiltinApp = true

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

    @spock.lang.Unroll
    def "hub_list_device_dependents via dispatch maps device-not-found IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('hub_list_device_dependents', [deviceId: '999'])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('Device not found')
        response.error.message.contains('999')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_list_device_dependents via dispatch maps Built-in-App-disabled IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = false

        when:
        def response = mcpDriver.callTool('hub_list_device_dependents', [:])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('Built-in App')

        where:
        useGateways << [true, false]
    }

    def "golden path: returns appsUsing list with extraBreadcrumb as deviceName"() {
        given:
        settingsMap.enableBuiltinApp = true

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

    @spock.lang.Unroll
    def "hub_list_device_dependents via dispatch returns appsUsing list (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true
        def mockDevice = [id: '42', label: 'Kitchen Switch', name: 'Generic Switch']
        childDevicesList << mockDevice
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
        def response = mcpDriver.callTool('hub_list_device_dependents', [deviceId: '42'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.deviceId == '42'
        inner.deviceName == 'Kitchen Switch (breadcrumb)'
        inner.count == 2
        inner.appsUsing.size() == 2
        inner.appsUsing[0].name == 'Room Lighting'

        where:
        useGateways << [true, false]
    }

    def "deviceName falls back to .name when extraBreadcrumb is absent"() {
        given:
        settingsMap.enableBuiltinApp = true
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
        settingsMap.enableBuiltinApp = true
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

    def "non-numeric appsUsingCount falls back to appsUsing.size() and warn log fires"() {
        given:
        settingsMap.enableBuiltinApp = true
        def mockDevice = [id: '77', label: 'My Dev', name: 'Dev']
        childDevicesList << mockDevice

        and: 'collect mcpLog calls via per-test metaClass override'
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }

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

        and: 'warn log fires mentioning appsUsingCount on the installed-apps component'
        mcpLogCalls.any { it.level == 'warn' && it.component == 'installed-apps' && it.msg.contains('appsUsingCount') }
    }

    def "returns success=false with empty-response error when hub body is empty"() {
        given:
        settingsMap.enableBuiltinApp = true
        def mockDevice = [id: '88', label: 'Dev', name: 'Dev']
        childDevicesList << mockDevice
        hubGet.register('/device/fullJson/88') { params -> '' }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '88'])

        then:
        result.success == false
        result.error?.toLowerCase()?.contains('empty') == true
    }

    @spock.lang.Unroll
    def "hub_list_device_dependents via dispatch returns success=false envelope on empty hub body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true
        def mockDevice = [id: '88', label: 'Dev', name: 'Dev']
        childDevicesList << mockDevice
        hubGet.register('/device/fullJson/88') { params -> '' }

        when:
        def response = mcpDriver.callTool('hub_list_device_dependents', [deviceId: '88'])

        then: 'tool wraps the empty-body fallback into a success-envelope success=false'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error?.toLowerCase()?.contains('empty') == true

        where:
        useGateways << [true, false]
    }

    def "cursor pagination over appsUsing returns bounded page + total (#174)"() {
        given: 'a heavily-shared device with 150 apps referencing it'
        settingsMap.enableBuiltinApp = true
        def mockDevice = [id: '10', label: 'Switch', name: 'Switch']
        childDevicesList << mockDevice
        def apps = (0..<150).collect { i -> [id: i + 100, name: 'Rule Machine', label: "Rule ${i}", trueLabel: "Rule ${i}", disabled: false] }
        def responseJson = JsonOutput.toJson([extraBreadcrumb: 'Switch', appsUsing: apps, appsUsingCount: 150])
        hubGet.register('/device/fullJson/10') { params -> responseJson }

        when: 'first page (cursor="")'
        def page1 = script.toolGetDeviceInUseBy([deviceId: '10', cursor: ''])

        then:
        page1.appsUsing.size() == 100
        page1.total == 150
        page1.nextCursor == '100'
        // count reports the firmware-reported full count, NOT the page size --
        // a paginating client needs the full count to know when to stop
        page1.count == 150

        when: 'last page (cursor=100)'
        def page2 = script.toolGetDeviceInUseBy([deviceId: '10', cursor: '100'])

        then:
        page2.appsUsing.size() == 50
        !page2.containsKey('nextCursor')
    }

    def "count vs appsUsing.size() mismatch surfaces countMismatch field (firmware truncation signal)"() {
        given:
        settingsMap.enableBuiltinApp = true
        def mockDevice = [id: '11', label: 'Switch', name: 'Switch']
        childDevicesList << mockDevice
        // firmware reports 42 but only 3 entries in the array (truncation case)
        def responseJson = JsonOutput.toJson([extraBreadcrumb: 'Switch', appsUsing: [[id: 1], [id: 2], [id: 3]], appsUsingCount: 42])
        hubGet.register('/device/fullJson/11') { params -> responseJson }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '11'])

        then:
        result.count == 42
        result.appsUsing.size() == 3
        result.countMismatch != null
        result.countMismatch.contains('42')
        result.countMismatch.contains('3')
    }

    def "appsUsing arriving as a Map is rejected with a clear error instead of producing all-null entries"() {
        given:
        settingsMap.enableBuiltinApp = true
        def mockDevice = [id: '12', label: 'Switch', name: 'Switch']
        childDevicesList << mockDevice
        // firmware shape drift -- appsUsing as a Map would silently produce all-null entries
        def responseJson = JsonOutput.toJson([extraBreadcrumb: 'Switch', appsUsing: [foo: 'bar'], appsUsingCount: 1])
        hubGet.register('/device/fullJson/12') { params -> responseJson }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '12'])

        then:
        result.success == false
        result.error.toLowerCase().contains('unexpected appsusing shape')
    }

    def "gateway dispatch via handleGateway routes correctly"() {
        given:
        settingsMap.enableBuiltinApp = true
        def mockDevice = [id: '10', label: 'Switch', name: 'Switch']
        childDevicesList << mockDevice

        def responseJson = JsonOutput.toJson([
            extraBreadcrumb: 'Switch',
            appsUsing: [],
            appsUsingCount: 0
        ])
        hubGet.register('/device/fullJson/10') { params -> responseJson }

        when:
        def result = script.handleGateway('hub_manage_installed_apps', 'hub_list_device_dependents', [deviceId: '10'])

        then:
        result.deviceId == '10'
    }
}
