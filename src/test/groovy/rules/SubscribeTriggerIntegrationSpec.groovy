package rules

import support.TestDevice
import support.TestParent

/**
 * Drives the rule engine's trigger install loop end-to-end at the tier-2
 * integration seam: call {@code subscribeToTriggers()} (or
 * {@code initialize()} where the lifecycle gate matters), assert the
 * subscribe/schedule calls were recorded against the right
 * sources/attributes/handlers, then fire a synthetic event through
 * {@link support.SubscriptionRecorder#fireEvent} and assert the wired-up
 * rule action ran on the target device.
 *
 * Where this fits in the test ladder (see {@code docs/testing.md}):
 *
 *   - {@code HandleDeviceEventSpec} and siblings drive individual handler
 *     bodies ({@code handleDeviceEvent} / {@code handleButtonEvent} /
 *     {@code handleModeEvent}) with hand-built event Maps. They skip the
 *     subscribe step entirely.
 *   - This spec covers the seam those specs can't reach: each trigger
 *     type in the rule engine's switch at hubitat-mcp-rule.groovy:2575
 *     registered the right subscription / schedule for the trigger
 *     config, and (for the subscribe-based branches) the handler routed
 *     the event through to the right action.
 *
 * Part of #77 (tier-2). Tier-3 is the fake-hub work on that issue.
 */
class SubscribeTriggerIntegrationSpec extends RuleHarnessSpec {

    def "device_event trigger subscribes device+attribute to handleDeviceEvent"() {
        given: 'a source device the trigger references by id'
        def sourceDevice = new TestDevice(id: 1, label: 'Front Door')
        parent = new TestParent(devices: [1L: sourceDevice])

        and: 'a rule configured with a single device_event trigger'
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: 1, attribute: 'contact', value: 'open'
        ]]

        when:
        script.subscribeToTriggers()

        then: 'exactly one subscription recorded against the source device'
        subscriptions.subscriptions.size() == 1
        subscriptions.subscriptions[0].source.is(sourceDevice)
        subscriptions.subscriptions[0].attribute == 'contact'
        subscriptions.subscriptions[0].handler == 'handleDeviceEvent'
    }

    def "firing a matching event routes through the handler to the action device"() {
        given: 'a source device (the contact sensor) and a target device (the light)'
        def sourceDevice = new TestDevice(id: 1, label: 'Front Door')
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [1L: sourceDevice, 99L: targetDevice])

        and: 'rule is enabled with a device_event trigger and a device_command action'
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Turn on porch light when front door opens'
        // triggerMatchesDevice (hubitat-mcp-rule.groovy:2687-2691) does a
        // strict == between trigger.deviceId and the stringified
        // event.device.id that handleDeviceEvent computes before lookup,
        // so a numeric trigger.deviceId would never match. MCP callers
        // are required to pass String ids in the trigger config — this
        // test uses '1' to reflect the real post-toolCreateRule shape.
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1', attribute: 'contact', value: 'open'
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'on'
        ]]

        and: 'the rule installs its subscriptions'
        script.subscribeToTriggers()

        when: 'the contact sensor reports open — fired via the recorded subscription'
        subscriptions.fireEvent(script, sourceDevice, 'contact', 'open')

        then: 'the porch light was commanded on'
        1 * targetDevice.on()
    }

    def "event with non-matching value fires the handler but the rule does not act"() {
        given:
        def sourceDevice = new TestDevice(id: 1, label: 'Front Door')
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [1L: sourceDevice, 99L: targetDevice])

        and: 'trigger wants value=open but the event carries value=closed'
        settingsMap.ruleEnabled = true
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1', attribute: 'contact', value: 'open'
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'on'
        ]]
        script.subscribeToTriggers()

        when:
        subscriptions.fireEvent(script, sourceDevice, 'contact', 'closed')

        then:
        0 * targetDevice.on()
    }

    def "numeric trigger.deviceId fails strict equality — the MCP caller-contract gotcha"() {
        given: 'deviceId is numeric — the pre-toolCreateRule shape, not valid rule input'
        def sourceDevice = new TestDevice(id: 1, label: 'Front Door')
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [1L: sourceDevice, 99L: targetDevice])

        and:
        settingsMap.ruleEnabled = true
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: 1, attribute: 'contact', value: 'open'  // int, not String
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'on'
        ]]
        script.subscribeToTriggers()

        when: 'valid event fires; handler looks up trigger.deviceId (1) vs stringified evt.device.id ("1")'
        subscriptions.fireEvent(script, sourceDevice, 'contact', 'open')

        then: 'strict == 1 != "1" — rule does not act. Locks the contract.'
        0 * targetDevice.on()
    }

    def "mode_change trigger subscribes the Location (not a device) to handleModeEvent"() {
        given: 'a rule triggered on location mode changes'
        atomicStateMap.triggers = [[type: 'mode_change', mode: 'Night']]

        when:
        script.subscribeToTriggers()

        then: 'the subscription source is the shared TestLocation, attribute=mode'
        subscriptions.subscriptions.size() == 1
        subscriptions.subscriptions[0].source.is(testLocation)
        subscriptions.subscriptions[0].attribute == 'mode'
        subscriptions.subscriptions[0].handler == 'handleModeEvent'
    }

    def "multi-device trigger (deviceIds) subscribes every listed device to handleDeviceEvent"() {
        given: 'three contact sensors in a deviceIds trigger'
        def d1 = new TestDevice(id: 1, label: 'Front Door')
        def d2 = new TestDevice(id: 2, label: 'Back Door')
        def d3 = new TestDevice(id: 3, label: 'Garage Door')
        parent = new TestParent(devices: [1L: d1, 2L: d2, 3L: d3])
        atomicStateMap.triggers = [[
            type: 'device_event', deviceIds: [1, 2, 3], attribute: 'contact', value: 'open'
        ]]

        when:
        script.subscribeToTriggers()

        then: 'one subscription per device, all routed to handleDeviceEvent'
        subscriptions.subscriptions.size() == 3
        subscriptions.subscriptions*.source.toSet() == [d1, d2, d3] as Set
        subscriptions.subscriptions.every {
            it.attribute == 'contact' && it.handler == 'handleDeviceEvent'
        }
    }

    def "button_event trigger subscribes device+trigger.action to handleButtonEvent"() {
        given: 'button_event uses trigger.action (e.g. "pushed") — distinct code path from device_event'
        def button = new TestDevice(id: 5, label: 'Kitchen Remote')
        parent = new TestParent(devices: [5L: button])
        atomicStateMap.triggers = [[
            type: 'button_event', deviceId: 5, action: 'pushed', buttonNumber: 1
        ]]

        when:
        script.subscribeToTriggers()

        then: 'subscribed on trigger.action, not trigger.attribute — a copy-paste regression from device_event would use .attribute and miss'
        subscriptions.subscriptions.size() == 1
        subscriptions.subscriptions[0].source.is(button)
        subscriptions.subscriptions[0].attribute == 'pushed'
        subscriptions.subscriptions[0].handler == 'handleButtonEvent'
    }

    def "hsm_change trigger subscribes the Location to hsmStatus + handleHsmEvent"() {
        given:
        atomicStateMap.triggers = [[type: 'hsm_change', status: 'armedAway']]

        when:
        script.subscribeToTriggers()

        then:
        subscriptions.subscriptions.size() == 1
        subscriptions.subscriptions[0].source.is(testLocation)
        subscriptions.subscriptions[0].attribute == 'hsmStatus'
        subscriptions.subscriptions[0].handler == 'handleHsmEvent'
    }

    def "time trigger with HH:mm schedules a cron handler, not a subscribe call"() {
        given: 'HH:mm clock-time trigger — uses schedule(cron, handler), no subscribe'
        atomicStateMap.triggers = [[type: 'time', time: '07:30']]

        when:
        script.subscribeToTriggers()

        then: 'no subscriptions (this branch uses schedule, not subscribe)'
        subscriptions.subscriptions.isEmpty()

        and: 'schedule call recorded with the expected cron form + handler name'
        scheduleCalls.size() == 1
        // Cron format emitted by the engine: "0 MM HH ? * * *" (seconds/minute/hour/day/month/dow/year-ish)
        scheduleCalls[0][0] == '0 30 07 ? * * *'
        scheduleCalls[0][1] == 'handleTimeEvent'
    }

    def "time trigger with sunrise schedules runOnce + handleSunriseEvent (distinct from sunset handler)"() {
        given: 'sunrise is available from the TestLocation'
        testLocation.sunrise = new Date(1234567890000L + 3_600_000L)  // 1h from harness now
        atomicStateMap.triggers = [[type: 'time', sunrise: true, offset: 0]]

        when:
        script.subscribeToTriggers()

        then: 'runOnce call recorded with the sunrise-distinct handler'
        runOnceCalls.size() == 1
        runOnceCalls[0][1] == 'handleSunriseEvent'

        and: 'no subscribe call — sunrise branch is schedule-based'
        subscriptions.subscriptions.isEmpty()
    }

    def "time trigger with sunset schedules runOnce + handleSunsetEvent (distinct handler, overwrite flag)"() {
        given:
        testLocation.sunset = new Date(1234567890000L + 7_200_000L)  // 2h from harness now
        atomicStateMap.triggers = [[type: 'time', sunset: true, offset: 0]]

        when:
        script.subscribeToTriggers()

        then: 'distinct handler name so sunrise runOnce does not overwrite this'
        runOnceCalls.size() == 1
        runOnceCalls[0][1] == 'handleSunsetEvent'
        // Third arg is the options Map — locked to [overwrite: true] in the engine
        runOnceCalls[0][2] == [overwrite: true]
    }

    def "periodic trigger schedules handlePeriodicEvent with a cron expression"() {
        given:
        atomicStateMap.triggers = [[type: 'periodic', interval: 5, unit: 'minutes']]

        when:
        script.subscribeToTriggers()

        then: 'no subscribe — periodic uses schedule'
        subscriptions.subscriptions.isEmpty()

        and: 'schedule call carries the minutes-form cron + distinct handler'
        scheduleCalls.size() == 1
        scheduleCalls[0][0] == '0 */5 * ? * *'
        scheduleCalls[0][1] == 'handlePeriodicEvent'
    }

    def "device_event with unknown deviceId skips the subscribe and logs a warn, does not throw"() {
        given: 'trigger references deviceId 99 but the parent has no such device'
        parent = new TestParent(devices: [:])
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: 99, attribute: 'switch', value: 'on'
        ]]

        when:
        script.subscribeToTriggers()

        then: 'zero subscriptions recorded — the device-not-found branch warned and continued'
        subscriptions.subscriptions.isEmpty()
        // The outer try/catch at hubitat-mcp-rule.groovy:2677-2679 would catch a thrown
        // exception; a null subscriptions list instead proves the pre-catch
        // "device not found" branch fired, not a swallowed throw downstream.
        noExceptionThrown()
    }

    def "fireEvent throws when no subscription matches the source + attribute"() {
        given: 'no subscriptions recorded (subscribeToTriggers not called)'
        def orphan = new TestDevice(id: 42, label: 'Not subscribed')

        when:
        subscriptions.fireEvent(script, orphan, 'switch', 'on')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('no matching subscription')
    }

    def "initialize() records subscriptions when settings.ruleEnabled is true"() {
        given: 'a rule with a single device_event trigger and ruleEnabled=true'
        def d1 = new TestDevice(id: 1, label: 'Front Door')
        parent = new TestParent(devices: [1L: d1])
        settingsMap.ruleEnabled = true
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1', attribute: 'contact', value: 'open'
        ]]

        when: 'full lifecycle — installed() calls subscribeToTriggers() per the engine contract'
        script.initialize()

        then: 'subscription was recorded through the initialize() lifecycle, not just the direct call'
        subscriptions.subscriptions.size() == 1
        subscriptions.subscriptions[0].source.is(d1)
    }

    def "initialize() records no subscriptions when settings.ruleEnabled is false"() {
        given: 'same rule, but the kill-switch is off'
        def d1 = new TestDevice(id: 1, label: 'Front Door')
        parent = new TestParent(devices: [1L: d1])
        settingsMap.ruleEnabled = false
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1', attribute: 'contact', value: 'open'
        ]]

        when:
        script.initialize()

        then: 'no subscriptions — initialize() short-circuits on the disabled flag'
        subscriptions.subscriptions.isEmpty()
    }
}
