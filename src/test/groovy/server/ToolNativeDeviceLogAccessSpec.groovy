package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolNativeDeviceLogAccessSpec extends ToolSpecBase {
    @Unroll
    def 'native device logs respect #ownership ownership with bypass #bypass'() {
        given:
        def device = new TestDevice(id: 42, name: 'Log fixture')
        if (ownership == 'selected') settingsMap.selectedDevices = [device]
        if (ownership == 'child') childDevicesList << device
        settingsMap.bypassDeviceAllowlist = bypass
        hubGet.register('/device/fullJson/42') { JsonOutput.toJson([device: [id: 42, name: 'Log fixture']]) }
        hubGet.register('/logs/past/json') { params ->
            assert params == [type: 'dev', id: '42']
            JsonOutput.toJson(['2026-09-10 10:00:00.000\tERROR\tdev|42|Log fixture|native diagnostic'])
        }

        when:
        def result = script.toolGetHubLogs([deviceId: '42'])

        then:
        result.count == 1
        result.logs[0].message == 'native diagnostic'
        hubGet.calls.any { it.path == '/logs/past/json' }

        where:
        ownership    | bypass
        'selected'   | false
        'selected'   | true
        'child'      | false
        'child'      | true
        'unselected' | true
    }

    def 'device log access is rechecked after disabling bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/device/fullJson/42') { JsonOutput.toJson([device: [id: 42]]) }
        hubGet.register('/logs/past/json') { '[]' }
        script.toolGetHubLogs([deviceId: '42'])
        int priorCalls = hubGet.calls.size()
        settingsMap.bypassDeviceAllowlist = false

        when:
        script.toolGetHubLogs([deviceId: '42'])

        then:
        thrown(IllegalArgumentException)
        hubGet.calls.size() == priorCalls
    }

    def 'bypass cannot turn an unreadable device into a successful empty log history'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/device/fullJson/42') { throw new IOException('unavailable') }
        hubGet.register('/logs/past/json') { '[]' }

        when:
        def result = script.toolGetHubLogs([deviceId: '42'])

        then:
        result.success == false
        result.error.contains('Device metadata')
        !hubGet.calls.any { it.path == '/logs/past/json' }
    }
}
