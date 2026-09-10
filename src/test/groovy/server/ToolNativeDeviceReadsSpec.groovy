package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolNativeDeviceReadsSpec extends ToolSpecBase {
    private Map nativeModel

    private void fixture(String ownership) {
        def sdk = new TestDevice(id: 10, name: 'Stale SDK name',
            capabilities: [[name: 'Switch']], supportedCommands: [[name: 'on']],
            supportedAttributes: [[name: 'switch', dataType: 'ENUM']], attributeValues: [switch: 'off'])
        sdk.metaClass.currentValue = { String attribute -> throw new AssertionError('SDK values must not be read') }
        sdk.metaClass.getCurrentStates = { throw new AssertionError('SDK states must not be read') }
        sdk.metaClass.events = { Map options -> throw new AssertionError('SDK events must not be read') }
        sdk.metaClass.eventsSince = { Date since, Map options -> throw new AssertionError('SDK history must not be read') }
        if (ownership == 'selected') settingsMap.selectedDevices = [sdk]
        if (ownership == 'child') childDevicesList << sdk
        nativeModel = [device: [id: 10, name: 'Native fixture', label: null, capabilities: ['Switch'],
                               currentStates: [switch: [value: 'on', dataType: 'ENUM']]],
                       commands: [[name: 'on', arguments: []]]]
        hubGet.register('/device/fullJson/10') { JsonOutput.toJson(nativeModel) }
        hubGet.register('/device/eventsJson/10') { JsonOutput.toJson([
            [name: 'switch', value: 'on', date: '2026-09-10T10:01:00+0000', descriptionText: 'turned on']
        ]) }
    }

    @Unroll
    def '#operation reads #ownership device natively with bypass #bypass'() {
        given:
        settingsMap.bypassDeviceAllowlist = bypass
        fixture(ownership)

        when:
        def result
        switch (operation) {
            case 'summary': result = script.toolGetDevice('10'); break
            case 'attribute': result = script.toolGetAttribute('10', 'switch'); break
            case 'poll': result = script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch', expectedValue: 'on']); break
            case 'events': result = script.toolGetDeviceEvents('10', 10); break
            case 'history': result = script.toolGetDeviceHistory([deviceId: '10', since: '2026-09-10T10:00:00Z']); break
        }

        then:
        switch (operation) {
            case 'summary':
                assert result.name == 'Native fixture'
                assert result.attributes.find { it.name == 'switch' }.value == 'on'
                break
            case 'attribute': assert result.value == 'on'; break
            case 'poll': assert result.success == true && result.finalValue == 'on'; break
            default:
                assert result.count == 1
                assert result.events[0].value == 'on'
        }

        where:
        [operation, ownership, bypass] << ['summary', 'attribute', 'poll', 'events', 'history'].collectMany { op ->
            ['selected', 'child'].collectMany { owner -> [false, true].collect { enabled -> [op, owner, enabled] } }
        }
    }

    @Unroll
    def '#operation denies unselected device before native fetch when bypass disabled'() {
        given:
        fixture('unselected')
        settingsMap.bypassDeviceAllowlist = false

        when:
        switch (operation) {
            case 'summary': script.toolGetDevice('10'); break
            case 'attribute': script.toolGetAttribute('10', 'switch'); break
            case 'events': script.toolGetDeviceEvents('10', 10); break
            case 'history': script.toolGetDeviceHistory([deviceId: '10', hoursBack: 1]); break
        }

        then:
        thrown(IllegalArgumentException)
        !hubGet.calls.any { it.path.startsWith('/device/') }

        where:
        operation << ['summary', 'attribute', 'events', 'history']
    }

    def 'native poll read failure is a read error rather than a never reported attribute'() {
        given:
        fixture('selected')
        hubGet.register('/device/fullJson/10') { throw new IOException('unreachable') }

        when:
        def result = script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch', expectedValue: 'on', timeoutMs: 100, pollIntervalMs: 100])

        then:
        result.success == false
        result.readError == true
    }
}
