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

    def "button_event firing a pushed event routes through handleButtonEvent to the action device"() {
        given: 'a button (source) and a light (target). handleButtonEvent matches deviceId as String + action + buttonNumber'
        def button = new TestDevice(id: 5, label: 'Kitchen Remote')
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [5L: button, 99L: targetDevice])
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Toggle light on button push'
        // Same caller-contract gotcha as device_event: handleButtonEvent does
        // `t.deviceId == evt.device.id.toString()`, so trigger.deviceId must be
        // the stringified id ('5') to match.
        atomicStateMap.triggers = [[
            type: 'button_event', deviceId: '5', action: 'pushed', buttonNumber: 1
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'on'
        ]]
        script.subscribeToTriggers()

        when: 'button 1 is pushed — fire the recorded subscription. fireEvent sets evt.value = the value arg, which handleButtonEvent compares to buttonNumber.toString()'
        subscriptions.fireEvent(script, button, 'pushed', '1')

        then: 'the light was commanded on'
        1 * targetDevice.on()
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

    def "hsm_change firing armedAway routes through handleHsmEvent to the action device"() {
        given: 'hsm subscribes the location, not a device. Fire with source = testLocation'
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [99L: targetDevice])
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Lock front door when HSM arms away'
        atomicStateMap.triggers = [[type: 'hsm_change', status: 'armedAway']]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'on'
        ]]
        script.subscribeToTriggers()

        when: 'HSM status flips to armedAway on the location'
        subscriptions.fireEvent(script, testLocation, 'hsmStatus', 'armedAway')

        then:
        1 * targetDevice.on()
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

    def "time trigger with HH:mm fires handleTimeEvent which routes through to the action device"() {
        given: 'time triggers register via schedule(), not subscribe — invoke the recorded handler by name directly'
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [99L: targetDevice])
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Morning routine at 07:30'
        atomicStateMap.triggers = [[type: 'time', time: '07:30']]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'on'
        ]]
        script.subscribeToTriggers()
        assert scheduleCalls.size() == 1 && scheduleCalls[0][1] == 'handleTimeEvent'

        when: 'the hub fires the scheduled handler. Invoke directly — runOnce / schedule do not call through to a SubscriptionRecorder'
        script.handleTimeEvent()

        then:
        1 * targetDevice.on()
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

    def "sunrise trigger fires handleSunriseEvent which routes through to the action device"() {
        given: 'sunrise is registered via runOnce(), not subscribe — invoke the recorded handler by name'
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [99L: targetDevice])
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Turn off porch light at sunrise'
        testLocation.sunrise = new Date(1234567890000L + 3_600_000L)
        atomicStateMap.triggers = [[type: 'time', sunrise: true, offset: 0]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'off'
        ]]
        script.subscribeToTriggers()
        assert runOnceCalls.size() == 1 && runOnceCalls[0][1] == 'handleSunriseEvent'

        when: 'the hub fires the sunrise handler (the runOnce target)'
        // handleSunriseEvent also calls rescheduleSunriseTrigger -> runOnce again;
        // we don't care about runOnceCalls afterwards, only about the action.
        script.handleSunriseEvent()

        then:
        1 * targetDevice.off()
    }

    def "sunset trigger fires handleSunsetEvent which routes through to the action device (distinct from sunrise)"() {
        given:
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [99L: targetDevice])
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Turn on porch light at sunset'
        testLocation.sunset = new Date(1234567890000L + 7_200_000L)
        atomicStateMap.triggers = [[type: 'time', sunset: true, offset: 0]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'on'
        ]]
        script.subscribeToTriggers()
        assert runOnceCalls.size() == 1 && runOnceCalls[0][1] == 'handleSunsetEvent'

        when:
        script.handleSunsetEvent()

        then: 'sunset handler dispatches the same way as sunrise but to its own action — locks the per-handler routing'
        1 * targetDevice.on()
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

    def "periodic trigger fires handlePeriodicEvent which routes through to the action device"() {
        given:
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [99L: targetDevice])
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Periodic poll'
        atomicStateMap.triggers = [[type: 'periodic', interval: 5, unit: 'minutes']]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'on'
        ]]
        script.subscribeToTriggers()
        assert scheduleCalls.size() == 1 && scheduleCalls[0][1] == 'handlePeriodicEvent'

        when: 'the cron expression fires — invoke the recorded handler directly'
        script.handlePeriodicEvent()

        then:
        1 * targetDevice.on()
    }

    def "matchMode='all' multi-device trigger: action fires only after every device matches"() {
        given: 'three switches in matchMode=all; target is the action device'
        // checkAllDevicesMatch (hubitat-mcp-rule.groovy:2698-2707) iterates
        // trigger.deviceIds and calls parent.findDevice(devId.toString()).each
        // checks against device.currentValue(trigger.attribute). To simulate
        // "device 3 has now flipped on", we mutate dev3.attributeValues
        // BEFORE firing — currentValue reads it back.
        def d1 = new TestDevice(id: 1, label: 'A', attributeValues: [switch: 'on'])
        def d2 = new TestDevice(id: 2, label: 'B', attributeValues: [switch: 'on'])
        def d3 = new TestDevice(id: 3, label: 'C', attributeValues: [switch: 'off'])
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [1L: d1, 2L: d2, 3L: d3, 99L: targetDevice])
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'All-on cascade'
        atomicStateMap.triggers = [[
            type: 'device_event', deviceIds: ['1', '2', '3'],
            attribute: 'switch', value: 'on', matchMode: 'all'
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'off'
        ]]
        script.subscribeToTriggers()

        when: 'd1 fires switch=on but d3 is still off — matchMode=all rejects'
        subscriptions.fireEvent(script, d1, 'switch', 'on')

        then: 'subset match: no action'
        0 * targetDevice.off()

        when: 'd2 fires switch=on, still d3 lagging'
        subscriptions.fireEvent(script, d2, 'switch', 'on')

        then:
        0 * targetDevice.off()

        when: 'd3 flips on and fires its event — all three now match'
        d3.attributeValues.switch = 'on'
        subscriptions.fireEvent(script, d3, 'switch', 'on')

        then: 'every device matches → action fires exactly once'
        1 * targetDevice.off()
    }

    def "duration trigger lifecycle: timer start, cancel-on-condition-flip, fire-on-expiry"() {
        given: 'motion sensor + light; trigger requires 30s sustained active before firing'
        // Cancel-on-flip path: handleDeviceEvent's else branch (lines 2796-2829)
        // clears durationTimers/durationFired in-place; it deliberately does NOT
        // call unschedule('checkDurationTrigger') because that would cancel
        // every duration trigger, not just this one. So the assertion target is
        // atomicStateMap.durationTimers, not unscheduleCalls.
        def sensor = new TestDevice(id: 1, label: 'Motion',
            attributeValues: [motion: 'active'])
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [1L: sensor, 99L: targetDevice])
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Duration trigger'
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1',
            attribute: 'motion', value: 'active', duration: 30
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'on'
        ]]
        script.subscribeToTriggers()

        when: 'condition first met — handler arms the runIn timer for 30s'
        subscriptions.fireEvent(script, sensor, 'motion', 'active')

        then: 'runInCalls records the scheduling; action has NOT fired yet'
        runInCalls.size() == 1
        runInCalls[0][0] == 30L
        runInCalls[0][1] == 'checkDurationTrigger'
        atomicStateMap.durationTimers?.size() == 1
        0 * targetDevice.on()

        when: 'condition flips to false before the window elapses — handler cancels the in-flight timer'
        sensor.attributeValues.motion = 'inactive'
        def triggerBefore = atomicStateMap.triggers[0]
        println "DEBUG: trigger.deviceId class=${triggerBefore.deviceId.class.name}, value=${triggerBefore.deviceId}"
        println "DEBUG: sensor.id class=${sensor.id.class.name}, value=${sensor.id}, toString=${sensor.id.toString()}"
        println "DEBUG: before fire, durationTimers=${atomicStateMap.durationTimers}, triggers=${atomicStateMap.triggers}"
        subscriptions.fireEvent(script, sensor, 'motion', 'inactive')
        println "DEBUG: after fire, durationTimers=${atomicStateMap.durationTimers}"

        then: 'durationTimers cleared (cancel does NOT call unschedule on purpose, see line 2816-2818)'
        atomicStateMap.durationTimers == [:]
        0 * targetDevice.on()

        when: 'condition met again, timer re-arms, then we simulate the timer firing with condition still true'
        sensor.attributeValues.motion = 'active'
        subscriptions.fireEvent(script, sensor, 'motion', 'active')
        // Re-arming the timer: runInCalls now has a second entry; we don't care.
        assert atomicStateMap.durationTimers?.size() == 1
        // Simulate the hub firing the scheduled handler. data carries the same
        // triggerKey the handler stored in durationTimers.
        def triggerKey = atomicStateMap.durationTimers.keySet().iterator().next()
        script.checkDurationTrigger([
            triggerKey: triggerKey,
            deviceLabel: 'Motion',
            attribute: 'motion'
        ])

        then: 'condition still met at expiry → action fires, fired flag set, timer consumed'
        1 * targetDevice.on()
        atomicStateMap.durationFired[triggerKey] == true
        !atomicStateMap.durationTimers.containsKey(triggerKey)
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
