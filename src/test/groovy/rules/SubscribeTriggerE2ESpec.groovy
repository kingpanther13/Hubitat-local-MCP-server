package rules

import support.TestDevice

/**
 * Drives the rule engine's trigger lifecycle end-to-end: install a rule's
 * triggers via {@code subscribeToTriggers()}, assert the subscribe calls
 * were recorded against the right sources/attributes/handlers, then fire
 * a synthetic event through {@link support.SubscriptionRecorder#fireEvent}
 * and assert the wired-up rule action ran on the target device.
 *
 * Where this fits in the test ladder:
 *
 *   - {@code HandleDeviceEventSpec} and siblings drive individual handler
 *     bodies ({@code handleDeviceEvent} / {@code handleButtonEvent} /
 *     {@code handleModeEvent}) with hand-built event Maps. They skip the
 *     subscribe step entirely.
 *   - This spec covers the seam those specs can't reach:
 *     {@code subscribeToTriggers()} registered the right subscription for
 *     the trigger config, and the handler routed to the right action.
 *
 * Part of #77 — in-harness E2E drive-through.
 */
class SubscribeTriggerE2ESpec extends RuleHarnessSpec {

    def "device_event trigger subscribes device+attribute to handleDeviceEvent"() {
        given: 'a source device the trigger references by id'
        def sourceDevice = new TestDevice(id: 1, label: 'Front Door')
        parent = new FindDeviceParent(devices: [1L: sourceDevice])

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
        parent = new FindDeviceParent(devices: [1L: sourceDevice, 99L: targetDevice])

        and: 'rule is enabled with a device_event trigger and a device_command action'
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Turn on porch light when front door opens'
        // deviceId is stored as a String post-normalization in toolCreateRule;
        // handleDeviceEvent() stringifies the event's device.id and does a
        // strict == comparison (triggerMatchesDevice(), rule engine line 2691),
        // so a numeric trigger.deviceId would never match a String evt id.
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
        parent = new FindDeviceParent(devices: [1L: sourceDevice, 99L: targetDevice])

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
        parent = new FindDeviceParent(devices: [1L: d1, 2L: d2, 3L: d3])
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

    def "fireEvent throws when no subscription matches the source + attribute"() {
        given: 'no subscriptions recorded (subscribeToTriggers not called)'
        def orphan = new TestDevice(id: 42, label: 'Not subscribed')

        when:
        subscriptions.fireEvent(script, orphan, 'switch', 'on')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('no matching subscription')
    }

    /**
     * Minimal parent stub — rule engine reads {@code parent.findDevice(id)}
     * during subscribeToTriggers() and during action dispatch. Keyed by
     * Long to match the engine's coercion of deviceId strings/ints.
     * {@code settings} satisfies the engine's {@code parent?.settings?.loopGuardMax}
     * read (a null-safe chain, but Groovy throws MissingProperty on a
     * non-null receiver without the key).
     */
    static class FindDeviceParent {
        Map<Long, TestDevice> devices = [:]
        Map settings = [:]

        Object findDevice(id) {
            devices[(id as Long)]
        }
    }
}
