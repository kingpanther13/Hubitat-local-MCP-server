package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolNativeDeviceLogAccessSpec extends ToolSpecBase {
    @Unroll
    def 'global logs remain hub wide without device selection and bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        hubGet.register('/logs/past/json') { params ->
            assert !params
            JsonOutput.toJson(['2026-09-10 10:00:00.000\tERROR\tdev|42|Unselected fixture|global diagnostic'])
        }

        when:
        def response = mcpDriver.callTool('hub_get_logs', [:])

        then:
        response.error == null
        response.result.isError != true
        mcpDriver.parseInner(response).logs[0].message == 'dev|42|Unselected fixture|global diagnostic'
        !hubGet.calls.any { it.path.startsWith('/device/') }

        where:
        bypass << [false, true]
    }

    @Unroll
    def 'scoped device logs permit unselected identity through dispatch with bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        hubGet.register('/logs/past/json') { params ->
            assert params == [type: 'dev', id: '42']
            JsonOutput.toJson(['2026-09-10 10:00:00.000\tERROR\tdev|42|Unselected fixture|scoped diagnostic'])
        }

        when:
        def response = mcpDriver.callTool('hub_get_logs', [deviceId: '42'])

        then:
        response.error == null
        response.result.isError != true
        mcpDriver.parseInner(response).logs[0].message == 'dev|42|Unselected fixture|scoped diagnostic'
        hubGet.calls*.path == ['/logs/past/json']

        where:
        bypass << [false, true]
    }

    @Unroll
    def 'native device logs remain hub wide for #ownership ownership with bypass #bypass'() {
        given:
        def device = new TestDevice(id: 42, name: 'Log fixture')
        if (ownership == 'selected') settingsMap.selectedDevices = [device]
        if (ownership == 'child') childDevicesList << device
        settingsMap.bypassDeviceAllowlist = bypass
        hubGet.register('/logs/past/json') { params ->
            assert params == [type: 'dev', id: '42']
            JsonOutput.toJson(['2026-09-10 10:00:00.000\tERROR\tdev|42|Log fixture|native diagnostic'])
        }

        when:
        def result = script.toolGetHubLogs([deviceId: '42'])

        then:
        result.count == 1
        result.logs[0].message.contains('native diagnostic')
        hubGet.calls*.path == ['/logs/past/json']

        where:
        ownership    | bypass
        'selected'   | false
        'selected'   | true
        'child'      | false
        'child'      | true
        'unselected' | false
        'unselected' | true
    }

    def 'device log access remains available after disabling bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/logs/past/json') { '[]' }
        script.toolGetHubLogs([deviceId: '42'])
        int priorCalls = hubGet.calls.size()
        settingsMap.bypassDeviceAllowlist = false

        when:
        def result = script.toolGetHubLogs([deviceId: '42'])

        then:
        result.count == 0
        hubGet.calls.size() == priorCalls + 1
    }

    def 'historical device logs do not require current device metadata'() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/device/fullJson/42') { throw new IOException('unavailable') }
        hubGet.register('/logs/past/json') { '[]' }

        when:
        def result = script.toolGetHubLogs([deviceId: '42'])

        then:
        result.success != false
        result.count == 0
        hubGet.calls*.path == ['/logs/past/json']
    }

    @Unroll
    def 'device log filter rejects nonnumeric or unsafe identity #deviceId before HTTP'() {
        when:
        def response = mcpDriver.callTool('hub_get_logs', [deviceId: deviceId])

        then:
        response.error.code == -32602
        hubGet.calls.empty

        where:
        deviceId << ['-1', '0x1234', '../42', '42?type=app', 'name']
    }
}
