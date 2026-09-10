package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolNativeAccessSurfacesSpec extends ToolSpecBase {
    private void nativeDevices() {
        settingsMap.selectedDevices = [new TestDevice(id: 1, name: 'SDK old name')]
        childDevicesList << new TestDevice(id: 2, name: 'SDK child old name')
        hubGet.register('/device/listWithCapabilities/json') {
            JsonOutput.toJson((1..3).collect { [id: it, label: "Native ${it}", capabilities: []] })
        }
        (1..3).each { id ->
            hubGet.register("/device/fullJson/${id}") {
                JsonOutput.toJson([device: [id: id, name: "Native ${id}", label: "Native ${id}",
                    lastActivityTime: id == 1 ? '2009-02-13T23:31:30.000Z' :
                        (id == 2 ? '2009-02-11T23:31:30.000Z' : null), currentStates: [:]], commands: []])
            }
        }
    }

    @Unroll
    def 'health uses native activity and includes allowed children with bypass #bypass'() {
        given:
        nativeDevices()
        settingsMap.bypassDeviceAllowlist = bypass

        when:
        def result = script.toolDeviceHealthCheck([includeHealthy: true])

        then:
        result.summary.totalDevices == (bypass ? 3 : 2)
        result.summary.healthyCount == 1
        result.summary.staleCount == 1
        result.summary.unknownCount == (bypass ? 1 : 0)
        result.healthyDevices*.name == ['Native 1']
        result.staleDevices*.id == ['2']
        result.staleDevices[0].hoursAgo == 48.0d
        !hubGet.calls.any { it.path == '/device/fullJson/3' } || bypass

        where:
        bypass << [false, true]
    }

    def 'health includes native bypass inventory when there are no selected devices'() {
        given:
        nativeDevices()
        settingsMap.selectedDevices = []
        childDevicesList.clear()
        settingsMap.bypassDeviceAllowlist = true

        when:
        def result = script.toolDeviceHealthCheck([:])

        then:
        result.summary.totalDevices == 3
        result.staleDevices*.id == ['2']
    }

    def 'health inventory failure is explicit and preserves completed network diagnostics'() {
        given:
        nativeDevices()
        hubGet.register('/device/fullJson/1') { throw new IOException('offline') }
        hubGet.register('/hub/networkTest/traceroute/1.1.1.1') { 'route output' }
        hubGet.register('/hub/networkTest/speedtest') { 'speed output' }

        when:
        def result = script.toolDeviceHealthCheck([tracerouteHost: '1.1.1.1', speedtest: true])

        then:
        result.success == false
        result.error
        !result.containsKey('summary')
        result.traceroute.output == 'route output'
        result.speedtest.output == 'speed output'
    }

    @Unroll
    def 'dependents native lookup permits #ownership with bypass #bypass'() {
        given:
        def sdk = new TestDevice(id: 42)
        if (ownership == 'selected') settingsMap.selectedDevices = [sdk]
        if (ownership == 'child') childDevicesList << sdk
        settingsMap.bypassDeviceAllowlist = bypass
        hubGet.register('/device/fullJson/42') {
            JsonOutput.toJson([device: [id: 42], appsUsing: [[id: 8, name: 'Rule']], appsUsingCount: 1])
        }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '42'])

        then:
        result.count == 1
        result.appsUsing*.id == [8]

        where:
        ownership    | bypass
        'selected'   | false
        'selected'   | true
        'child'      | false
        'child'      | true
        'unselected' | true
    }

    @Unroll
    def 'dependents bypass rejects unavailable or mismatched identity #body'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/device/fullJson/42') { body }

        when:
        def result = script.toolGetDeviceInUseBy([deviceId: '42'])

        then:
        result.success == false
        result.error
        !result.containsKey('appsUsing')

        where:
        body << ['{}', '{"device":{},"appsUsing":[]}', '{"device":{"id":99},"appsUsing":[]}', '[]', 'invalid']
    }

    def 'dependents denies unselected without bypass before HTTP'() {
        when:
        script.toolGetDeviceInUseBy([deviceId: '42'])

        then:
        thrown(IllegalArgumentException)
        hubGet.calls.isEmpty()
    }

    def 'swap permits bypass identities and checks existence before opening native wizard'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        stateMap.lastBackupTimestamp = 1234567890000L
        def steps = []
        ['101', '202'].each { id ->
            hubGet.register("/device/fullJson/${id}") {
                steps << "device:${id}".toString()
                JsonOutput.toJson([device: [id: id], appsUsing: [], appsUsingCount: 0])
            }
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            steps << 'wizard'
            [status: 200, location: null]
        }

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then:
        result.success == false
        result.error.contains('Could not open')
        steps.indexOf('device:101') < steps.indexOf('wizard')
        steps.indexOf('device:202') < steps.indexOf('wizard')
        steps.contains('wizard')
    }

    @Unroll
    def 'swap refuses unreadable #missing identity without opening wizard'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        stateMap.lastBackupTimestamp = 1234567890000L
        ['101', '202'].each { id ->
            hubGet.register("/device/fullJson/${id}") {
                JsonOutput.toJson([device: id == missing ? [:] : [id: id], appsUsing: []])
            }
        }
        def wizardCalls = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            wizardCalls << path
            [status: 200, location: null]
        }

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then:
        result.success == false
        result.error.contains(missing)
        wizardCalls.isEmpty()

        where:
        missing << ['101', '202']
    }
}
