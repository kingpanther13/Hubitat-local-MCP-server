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
        sdk.metaClass.getSupportedAttributes = { throw new AssertionError('SDK declarations must not be read') }
        sdk.metaClass.getSupportedCommands = { throw new AssertionError('SDK commands must not be read') }
        sdk.metaClass.getCapabilities = { throw new AssertionError('SDK capabilities must not be read') }
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

    @Unroll
    def 'native access rejects nonnumeric device path #deviceId before HTTP'() {
        given:
        settingsMap.bypassDeviceAllowlist = true

        when:
        script.toolGetAttribute(deviceId, 'switch')

        then:
        thrown(IllegalArgumentException)
        hubGet.calls.empty

        where:
        deviceId << ['../sysDriverByIdJson/430', '10/../../device/delete/10', '10?other=1',
                     '10#fragment', '%2e%2e%2f', 'abc', '-1', '1.5']
    }

    @Unroll
    def 'enabled readback cannot confirm an unavailable disabled flag #disabled'() {
        given:
        fixture('selected')
        nativeModel.device.disabled = disabled

        when:
        def result = script._confirmDisabledFlip('10', false)

        then:
        result.ok == false
        result.fetchFailed == true

        where:
        disabled << [null, '', 'unknown', 0, [:], []]
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

    def 'native numeric values retain their numeric JSON type in summaries attributes and polling'() {
        given:
        fixture('selected')
        nativeModel.device.currentStates = [temperature: [value: '74.29', numberValue: 74.29, dataType: 'NUMBER']]

        expect:
        script.toolGetDevice('10').attributes[0].value == 74.29
        script.toolGetAttribute('10', 'temperature').value == 74.29
        script.toolPollUntilAttribute([deviceId: '10', attribute: 'temperature', expectedValue: '74.29']).finalValue == 74.29
    }

    def 'unreported attribute discovery stays empty while an explicit read remains distinguishable from fetch failure'() {
        given:
        fixture('selected')
        nativeModel.device.currentStates = [:]

        when:
        def summary = script.toolGetDevice('10')
        def attribute = script.toolGetAttribute('10', 'learnedCode')

        then:
        summary.attributes == []
        attribute.value == null
        attribute.neverReported == true
        !attribute.error
    }

    // Real /device/eventsJson wire shapes: producedBy is one hub-rendered <a> (or bare text) and
    // triggered is a <ul> of app links, each followed by the subscribed handler method.
    private static final String BUTTON_RULE_LINK = "<a href='/installedapp/configure/173' target='_blank' class='text-base'>Test Button: button 1 pushed</a>"
    private static final String BUTTON_TRIGGERED = "<ul class='pr-0 mb-0 -mx-2'><li class='pb-1'><a href='/installedapp/configure/173' target='_blank'>Test Button: button 1 pushed</a> (allHandlerX)</li><li>\n<a href='/installedapp/configure/62' target='_blank'>Maker API</a> (eventHandler)</li><li>\nUnknown app</li></ul>"

    private void registerEvents(List rows) {
        hubGet.register('/device/eventsJson/10') { JsonOutput.toJson(rows) }
    }

    def 'command events keep the producing app as a structured producedBy'() {
        given:
        fixture('selected')
        registerEvents([
            [id: 2, name: 'switch', value: 'on', unit: null, descriptionText: 'Lamp was turned on', source: 'DEVICE', type: 'digital',
             date: '2026-09-23T07:20:37.330-0400', producedBy: BUTTON_RULE_LINK, triggered: '', isStateChange: true,
             physical: false, digital: false, deviceId: 10],
            [id: 1, name: 'command-on', value: null, unit: null, descriptionText: 'Command called: on()', source: 'DEVICE', type: 'command',
             date: '2026-09-23T07:20:37.300-0400', producedBy: BUTTON_RULE_LINK, triggered: '', isStateChange: false,
             physical: false, digital: false, deviceId: 10]
        ])

        when:
        def recent = script.toolGetDeviceEvents('10', 10)
        def windowed = script.toolGetDeviceHistory([deviceId: '10', since: '2026-09-23T07:00:00.000-0400', attribute: 'command-on'])

        then: 'the rule app is named and chainable by appId; an empty triggered is omitted'
        recent.events[1].name == 'command-on'
        recent.events[1].type == 'command'
        recent.events[1].producedBy == [name: 'Test Button: button 1 pushed', appId: 173]
        !recent.events[1].containsKey('triggered')
        recent.events[0].type == 'digital'
        recent.events[0].producedBy == [name: 'Test Button: button 1 pushed', appId: 173]

        and: 'the windowed branch maps rows through the same shape'
        windowed.count == 1
        windowed.events[0].producedBy == [name: 'Test Button: button 1 pushed', appId: 173]
        windowed.events[0].type == 'command'
    }

    def 'a button event lists the apps it fired in triggered and its producing device'() {
        given:
        fixture('selected')
        registerEvents([
            [id: 3, name: 'pushed', value: '1', unit: null, descriptionText: 'Test Button button 1 was pushed', source: 'DEVICE', type: 'physical',
             date: '2026-09-23T07:20:36.920-0400', producedBy: "<a href='/device/edit/10' target='_blank' class='text-base'>Test Button</a>",
             triggered: BUTTON_TRIGGERED, isStateChange: true, physical: false, digital: false, deviceId: 10]
        ])

        when:
        def event = script.toolGetDeviceEvents('10', 10).events[0]

        then: 'device producers carry a string deviceId; anchorless list items keep their text'
        event.type == 'physical'
        event.producedBy == [name: 'Test Button', deviceId: '10']
        event.triggered == [
            [name: 'Test Button: button 1 pushed', appId: 173, handler: 'allHandlerX'],
            [name: 'Maker API', appId: 62, handler: 'eventHandler'],
            [name: 'Unknown app']
        ]
    }

    def 'bare-text producers and unrecognized triggered markup are kept as text'() {
        given:
        fixture('selected')
        registerEvents([
            [name: 'command-push', value: null, descriptionText: 'Command called: push(1)', type: 'command',
             date: '2026-09-23T07:20:36.000-0400', producedBy: 'Unknown app', triggered: '<div>Some <b>App</b> (handler)</div>']
        ])

        when:
        def event = script.toolGetDeviceEvents('10', 10).events[0]

        then:
        event.producedBy == [name: 'Unknown app']
        event.triggered == [[name: 'Some App (handler)']]
    }

    def 'rows without provenance keep the base event shape'() {
        given:
        fixture('selected')
        registerEvents([
            [name: 'switch', value: 'off', unit: null, descriptionText: 'turned off', source: 'DEVICE', type: null,
             date: '2026-09-23T07:00:00.000-0400', producedBy: '', triggered: '', isStateChange: true],
            [name: 'switch', value: 'on', descriptionText: 'turned on', date: '2026-09-23T06:00:00.000-0400']
        ])

        when:
        def events = script.toolGetDeviceEvents('10', 10).events

        then:
        events.every { it.keySet() == ['name', 'value', 'unit', 'description', 'date', 'isStateChange'] as Set }
    }
}
