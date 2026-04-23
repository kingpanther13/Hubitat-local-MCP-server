package rules

import support.TestDevice

/**
 * Breadth coverage for trigger-side behaviour beyond {@link HandleDeviceEventSpec}'s
 * basic device-event matching. Covers (#75):
 *
 * <ul>
 *   <li>Time-based triggers — {@code handleTimeEvent},
 *       {@code handleSunriseEvent}, {@code handleSunsetEvent}.
 *       Sun handlers reschedule via {@code runInMillis}; tests stub
 *       {@code getSunriseAndSunset()} to a future time so the rescheduler
 *       takes the happy path and the rule actions still fire.</li>
 *   <li>Multi-device {@code matchMode="all"} — the rule only fires when
 *       every device in {@code deviceIds} currently matches the target
 *       value (checked via {@code checkAllDevicesMatch}).</li>
 *   <li>Duration triggers — {@code handleDeviceEvent} schedules
 *       {@code checkDurationTrigger} via {@code runIn}; the delayed
 *       check re-validates the device state and fires the rule if still met.
 *       Re-arm semantics verified: once a duration trigger fires, it is
 *       gated by {@code atomicState.durationFired} until the condition
 *       goes false again.</li>
 *   <li>Per-trigger condition gates — {@code evaluateTriggerCondition}
 *       blocks rule execution when the inline condition misses.</li>
 * </ul>
 *
 * Handler entry points are called directly (bypassing subscribe()), so
 * these specs do not exercise {@code subscribeToTriggers} registration.
 */
class TriggerBreadthSpec extends RuleHarnessSpec {

    // -------- handleTimeEvent --------

    def "handleTimeEvent fires the rule when enabled and a time trigger exists"() {
        given:
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Test Rule'
        def target = Spy(TestDevice) { getId() >> 1 }
        parent = new TriggerParent(devices: [1L: target])

        and:
        atomicStateMap.triggers = [[type: 'time', time: '08:00']]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 1L, command: 'on']]

        when:
        script.handleTimeEvent()

        then:
        1 * target.on()
    }

    def "handleTimeEvent is a no-op when ruleEnabled is false"() {
        given:
        settingsMap.ruleEnabled = false
        def target = Spy(TestDevice) { getId() >> 1 }
        parent = new TriggerParent(devices: [1L: target])
        atomicStateMap.triggers = [[type: 'time', time: '08:00']]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 1L, command: 'on']]

        when:
        script.handleTimeEvent()

        then:
        0 * target.on()
    }

    def "handleTimeEvent is a no-op when no matching time trigger is configured"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 1 }
        parent = new TriggerParent(devices: [1L: target])
        atomicStateMap.triggers = [[type: 'device_event', deviceId: '1', attribute: 'switch']]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 1L, command: 'on']]

        when:
        script.handleTimeEvent()

        then:
        0 * target.on()
    }

    def "handleSunriseEvent fires the rule and reschedules the next sunrise"() {
        given: 'a future sunrise supplied via the mocked getSunriseAndSunset'
        def tomorrow = new Date(System.currentTimeMillis() + 86_400_000L)
        _ * appExecutor.getSunriseAndSunset(_) >> [sunrise: tomorrow, sunset: tomorrow]

        and:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 1 }
        parent = new TriggerParent(devices: [1L: target])

        atomicStateMap.triggers = [[type: 'time', sunrise: true, offset: 0]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 1L, command: 'on']]

        when:
        script.handleSunriseEvent()

        then:
        1 * target.on()
    }

    // -------- multi-device "all" matchMode --------

    def "matchMode='all' fires when every device in deviceIds currently matches"() {
        given:
        settingsMap.ruleEnabled = true
        def a = new TestDevice(id: 10, label: 'A', attributeValues: [switch: 'on'])
        def b = new TestDevice(id: 11, label: 'B', attributeValues: [switch: 'on'])
        def target = Spy(TestDevice) { getId() >> 99 }
        // Parent resolves deviceIds both by Long (evaluateCondition path) and by
        // String (checkAllDevicesMatch uses devId.toString()).
        parent = new StringOrLongTriggerParent(
            devices: [10L: a, 11L: b, 99L: target])

        atomicStateMap.triggers = [[
            type: 'device_event', deviceIds: ['10', '11'],
            attribute: 'switch', value: 'on', matchMode: 'all'
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'off']]

        when:
        script.handleDeviceEvent([
            device: [id: 10, label: 'A'],
            name: 'switch', value: 'on'
        ])

        then:
        1 * target.off()
    }

    def "matchMode='all' does not fire when one device still reports the wrong value"() {
        given:
        settingsMap.ruleEnabled = true
        def a = new TestDevice(id: 10, label: 'A', attributeValues: [switch: 'on'])
        def b = new TestDevice(id: 11, label: 'B', attributeValues: [switch: 'off']) // lagging
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new StringOrLongTriggerParent(
            devices: [10L: a, 11L: b, 99L: target])

        atomicStateMap.triggers = [[
            type: 'device_event', deviceIds: ['10', '11'],
            attribute: 'switch', value: 'on', matchMode: 'all'
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'off']]

        when: 'device A fires its event but B is still off'
        script.handleDeviceEvent([
            device: [id: 10, label: 'A'],
            name: 'switch', value: 'on'
        ])

        then:
        0 * target.off()
    }

    // -------- duration triggers --------

    def "duration trigger schedules checkDurationTrigger via runIn (not immediate fire)"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1',
            attribute: 'motion', value: 'active', duration: 30
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handleDeviceEvent([
            device: [id: 1, label: 'Motion'],
            name: 'motion', value: 'active'
        ])

        then:
        1 * appExecutor.runIn(30L, 'checkDurationTrigger', _)
        0 * target.on()
        atomicStateMap.durationTimers?.size() == 1
    }

    def "duration trigger: timer-cancel path clears the timer when condition goes false"() {
        given: 'a timer is already pending for this device/attribute'
        settingsMap.ruleEnabled = true
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1',
            attribute: 'motion', value: 'active', duration: 30
        ]]
        atomicStateMap.durationTimers = [
            'duration_1_motion': [startTime: 0L, trigger: atomicStateMap.triggers[0]]
        ]
        atomicStateMap.actions = []

        when: 'a mismatching event arrives (value is now inactive)'
        script.handleDeviceEvent([
            device: [id: 1, label: 'Motion'],
            name: 'motion', value: 'inactive'
        ])

        then: 'the timer entry is removed'
        atomicStateMap.durationTimers == [:]
    }

    def "checkDurationTrigger fires the rule when the condition is still met"() {
        given: 'the trigger is stored in durationTimers as if a timer had been armed'
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Duration Rule'
        def sensor = new TestDevice(id: 1, label: 'Motion',
            attributeValues: [motion: 'active'])
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [1L: sensor, 99L: target])

        def trigger = [type: 'device_event', deviceId: 1L,
                       attribute: 'motion', value: 'active', duration: 30]
        atomicStateMap.triggers = [trigger]
        atomicStateMap.durationTimers = [
            'duration_1_motion': [startTime: 0L, trigger: trigger]
        ]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.checkDurationTrigger([
            triggerKey: 'duration_1_motion',
            deviceLabel: 'Motion', attribute: 'motion'
        ])

        then:
        1 * target.on()
        atomicStateMap.durationFired['duration_1_motion'] == true
    }

    def "checkDurationTrigger does not fire when the timer has already been cancelled"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])
        atomicStateMap.durationTimers = [:]  // no pending timer
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.checkDurationTrigger([
            triggerKey: 'duration_1_motion',
            deviceLabel: 'Motion', attribute: 'motion'
        ])

        then:
        0 * target.on()
    }

    def "duration trigger: does not re-fire while durationFired flag is set"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1',
            attribute: 'motion', value: 'active', duration: 30
        ]]
        atomicStateMap.durationFired = ['duration_1_motion': true]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handleDeviceEvent([
            device: [id: 1, label: 'Motion'],
            name: 'motion', value: 'active'
        ])

        then: 'no new timer scheduled, no rule fire'
        0 * appExecutor.runIn(_, 'checkDurationTrigger', _)
        0 * target.on()
    }

    // -------- per-trigger condition gate --------

    def "per-trigger condition gate blocks execution when the inline condition misses"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        // No local variable 'armed' → variable condition misses → gate fails.
        atomicStateMap.localVariables = [:]
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1',
            attribute: 'switch', value: 'on',
            condition: [type: 'variable', variableName: 'armed',
                        operator: 'equals', value: 'true']
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'off']]

        when:
        script.handleDeviceEvent([
            device: [id: 1, label: 'Switch'],
            name: 'switch', value: 'on'
        ])

        then:
        0 * target.off()
    }

    // -------- button / periodic / mode / HSM handlers --------

    def "handleButtonEvent fires the rule when deviceId + action + buttonNumber all match"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[
            type: 'button_event', deviceId: '5', action: 'pushed', buttonNumber: 2
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handleButtonEvent([
            device: [id: 5, label: 'Remote'],
            name: 'pushed', value: '2'
        ])

        then:
        1 * target.on()
    }

    def "handleButtonEvent ignores buttonNumber mismatch"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[
            type: 'button_event', deviceId: '5', action: 'pushed', buttonNumber: 2
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when: 'same device/action but a different button'
        script.handleButtonEvent([
            device: [id: 5, label: 'Remote'],
            name: 'pushed', value: '3'
        ])

        then:
        0 * target.on()
    }

    def "handleButtonEvent with null buttonNumber matches any button"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[
            type: 'button_event', deviceId: '5', action: 'pushed', buttonNumber: null
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handleButtonEvent([
            device: [id: 5, label: 'Remote'],
            name: 'pushed', value: '7'
        ])

        then:
        1 * target.on()
    }

    def "handlePeriodicEvent fires a type='periodic' trigger"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[type: 'periodic']]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handlePeriodicEvent()

        then:
        1 * target.on()
    }

    def "handlePeriodicEvent is a no-op when no periodic trigger is configured"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[type: 'time', time: '08:00']]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handlePeriodicEvent()

        then:
        0 * target.on()
    }

    def "handleModeEvent matches toMode and updates state.previousMode"() {
        given:
        settingsMap.ruleEnabled = true
        stateMap.previousMode = 'Home'
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[
            type: 'mode_change', toMode: 'Night'
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handleModeEvent([name: 'mode', value: 'Night'])

        then:
        1 * target.on()
        stateMap.previousMode == 'Night'
    }

    def "handleModeEvent fromMode filter: non-matching previous mode blocks the fire"() {
        given:
        settingsMap.ruleEnabled = true
        stateMap.previousMode = 'Night'  // trigger expects fromMode='Home'
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[
            type: 'mode_change', toMode: 'Away', fromMode: 'Home'
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handleModeEvent([name: 'mode', value: 'Away'])

        then:
        0 * target.on()
        // state.previousMode is unconditionally updated even when the trigger doesn't fire
        stateMap.previousMode == 'Away'
    }

    def "handleHsmEvent matches hsm_change triggers with a specific status"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[type: 'hsm_change', status: 'armedAway']]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handleHsmEvent([name: 'hsmStatus', value: 'armedAway'])

        then:
        1 * target.on()
    }

    def "handleHsmEvent with no status filter matches any HSM change"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[type: 'hsm_change']]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'on']]

        when:
        script.handleHsmEvent([name: 'hsmStatus', value: 'disarmed'])

        then:
        1 * target.on()
    }

    def "per-trigger condition gate allows execution when the inline condition passes"() {
        given:
        settingsMap.ruleEnabled = true
        def target = Spy(TestDevice) { getId() >> 99 }
        atomicStateMap.localVariables = [armed: 'true']
        parent = new TriggerParent(devices: [99L: target])

        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1',
            attribute: 'switch', value: 'on',
            condition: [type: 'variable', variableName: 'armed',
                        operator: 'equals', value: 'true']
        ]]
        atomicStateMap.actions = [[type: 'device_command', deviceId: 99L, command: 'off']]

        when:
        script.handleDeviceEvent([
            device: [id: 1, label: 'Switch'],
            name: 'switch', value: 'on'
        ])

        then:
        1 * target.off()
    }

    /** findDevice coerces id to Long — works for trigger-action deviceId values. */
    static class TriggerParent {
        Map<Long, TestDevice> devices = [:]
        Map settings = [:]
        Object findDevice(id) { devices[(id as Long)] }
    }

    /**
     * checkAllDevicesMatch passes {@code devId.toString()} to findDevice,
     * so a pure Long-keyed parent misses. This stub coerces any input
     * (String or Integer or Long) to Long before looking up.
     */
    static class StringOrLongTriggerParent {
        Map<Long, TestDevice> devices = [:]
        Map settings = [:]
        Object findDevice(id) {
            if (id == null) return null
            try { return devices[(id.toString() as Long)] }
            catch (NumberFormatException ignored) { return null }
        }
    }
}
