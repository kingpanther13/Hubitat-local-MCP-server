package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolNativeCommandRoutingSpec extends ToolSpecBase {
    private List writes = []

    def setup() {
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420, boolean retry = false ->
            assert path == '/device/runmethod'
            writes << new JsonSlurper().parseText(body)
            [success: true]
        }
    }

    private void device(String ownership, String id = '10', String type = 'STRING') {
        def sdk = new TestDevice(id: id.toInteger(), name: 'SDK fixture',
            supportedCommands: [[name: 'probe', arguments: [type]], [name: 'on', arguments: []]],
            supportedAttributes: [[name: 'switch']])
        sdk.metaClass.probe = { value -> throw new AssertionError('SDK command must never execute') }
        sdk.metaClass.on = { throw new AssertionError('SDK command must never execute') }
        sdk.metaClass.currentValue = { String attribute -> throw new AssertionError('SDK state must never be read') }
        sdk.metaClass.getCurrentStates = { throw new AssertionError('SDK states must never be read') }
        if (ownership == 'selected') settingsMap.selectedDevices = (settingsMap.selectedDevices ?: []) + [sdk]
        if (ownership == 'child') childDevicesList << sdk
        hubGet.register("/device/fullJson/${id}") { JsonOutput.toJson([
            device: [id: id.toInteger(), name: 'Native fixture', currentStates: [switch: [value: 'on', date: null]]],
            commands: [[name: 'probe', parameters: [[name: 'value', type: type]]], [name: 'on', parameters: []]]
        ]) }
    }

    @Unroll
    def 'native command and snapshot for #ownership device with bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        device(ownership)

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then:
        result.success == true
        result.device == 'Native fixture'
        result.state == [switch: [value: 'on', timestamp: null]]
        writes == [[id: 10, method: 'on', args: []]]
        hubGet.calls.count { it.path == '/device/fullJson/10' } == 2

        where:
        ownership | bypass
        'selected' | false
        'selected' | true
        'child'    | false
        'child'    | true
        'unlisted' | true
    }

    @Unroll
    def 'native #ownership #type argument retains #input with bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        device(ownership, '10', type)

        when:
        def result = script.toolSendCommand('10', 'probe', [input], null, null, null, false)

        then:
        result.success == true
        result.parameters == [expected]
        writes == [[id: 10, method: 'probe', args: [[type: type, value: expected]]]]
        (writes[0].args[0].value instanceof String) == (expected instanceof String)

        where:
        [ownership, bypass, type, input, expected] << ['selected', 'child'].collectMany { owner ->
            [false, true].collectMany { enabled ->
                [['STRING', '0007', '0007'], ['STRING', '{"a":1}', '{"a":1}'],
                 ['NUMBER', '75', 75], ['JSON_OBJECT', '{"a":1}', [a: 1]]].collect { [owner, enabled] + it }
            }
        }
    }

    def 'unselected device is denied before reads or writes when bypass is off'() {
        given:
        settingsMap.bypassDeviceAllowlist = false
        device('unlisted')

        when:
        script.toolSendCommand('10', 'on', [])

        then:
        thrown(IllegalArgumentException)
        writes.empty
        !hubGet.calls.any { it.path.startsWith('/device/') }
    }

    def 'native metadata failure never falls back to a selected SDK device'() {
        given:
        device('selected')
        hubGet.register('/device/fullJson/10') { null }

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then:
        result.success == false
        result.error.contains('fullJson')
        result.note
        writes.empty
    }

    def 'platform data writer is not exposed as a public device command'() {
        given:
        device('selected')

        when:
        script.toolSendCommand('10', 'updateDataValue', ['key', 'value'])

        then:
        thrown(IllegalArgumentException)
        writes.empty
    }

    def 'batch rejects malformed later entries before any native command executes'() {
        given:
        device('selected')

        when:
        script.toolSendCommand(null, null, null, null,
            [[deviceId: '10', command: 'on'], [deviceId: '10', command: 'probe', parameters: [bad: 'shape']]])

        then:
        thrown(IllegalArgumentException)
        writes.empty
    }

    def 'batch dispatches selected and child devices natively while retaining denied entry outcome'() {
        given:
        settingsMap.bypassDeviceAllowlist = false
        device('selected', '10')
        device('child', '11')
        device('unlisted', '12')

        when:
        def result = script.toolSendCommand(null, null, null, null,
            ['10', '12', '11'].collect { [deviceId: it, command: 'on'] })

        then:
        result.success == false
        result.partial == true
        result.sentCount == 2
        result.failedCount == 1
        result.results*.deviceId == ['10', '12', '11']
        result.results*.success == [true, false, true]
        result.results.every { !it.containsKey('state') }
        writes == [[id: 10, method: 'on', args: []], [id: 11, method: 'on', args: []]]
        !hubGet.calls.any { it.path == '/device/fullJson/12' }
        hubGet.calls.count { it.path.startsWith('/device/fullJson/') } == 2
    }
}
