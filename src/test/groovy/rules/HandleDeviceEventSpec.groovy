package rules

import support.TestDevice

/**
 * Spec for hubitat-mcp-rule.groovy::handleDeviceEvent — the subscribed
 * trigger handler that fires when a subscribed device attribute changes.
 *
 * Covers the primitive trigger-matching path: settings.ruleEnabled gate,
 * matching trigger fires executeRule (observable via action side effects
 * on parent-resolved devices), non-matching event is a no-op.
 *
 * Duration-triggered rules, matchMode="all" multi-device triggers,
 * per-trigger condition gates, and the loop-guard logic are out of scope
 * here — they fall under #75's broader rule-engine coverage.
 */
class HandleDeviceEventSpec extends RuleHarnessSpec {

    def "ignores the event when settings.ruleEnabled is false"() {
        given:
        settingsMap.ruleEnabled = false

        and: 'a target device that an action would drive if the rule fired'
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new EventParent(devices: [99L: target])

        and: 'a trigger that would match the incoming event, and an action that would run'
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1', attribute: 'switch', value: 'on'
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'off'
        ]]

        when:
        script.handleDeviceEvent(buildEvent(1, 'switch', 'on'))

        then: 'rule is disabled — no action side effects'
        0 * target.off()
    }

    def "matching trigger fires the rule — actions dispatch to devices"() {
        given:
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Test Rule'

        and:
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new EventParent(devices: [99L: target])

        and: 'trigger matches on deviceId=1, attribute=switch, value=on'
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1', attribute: 'switch', value: 'on'
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'off'
        ]]

        when:
        script.handleDeviceEvent(buildEvent(1, 'switch', 'on'))

        then:
        1 * target.off()
    }

    def "non-matching attribute does not fire the rule"() {
        given:
        settingsMap.ruleEnabled = true

        and:
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new EventParent(devices: [99L: target])

        and: 'trigger wants switch events but the incoming event is for level'
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1', attribute: 'switch', value: 'on'
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'off'
        ]]

        when:
        script.handleDeviceEvent(buildEvent(1, 'level', '50'))

        then:
        0 * target.off()
    }

    def "trigger value mismatch does not fire the rule"() {
        given:
        settingsMap.ruleEnabled = true

        and:
        def target = Spy(TestDevice) { getId() >> 99 }
        parent = new EventParent(devices: [99L: target])

        and: 'trigger wants switch=on but incoming value is off'
        atomicStateMap.triggers = [[
            type: 'device_event', deviceId: '1', attribute: 'switch', value: 'on'
        ]]
        atomicStateMap.actions = [[
            type: 'device_command', deviceId: 99, command: 'off'
        ]]

        when:
        script.handleDeviceEvent(buildEvent(1, 'switch', 'off'))

        then:
        0 * target.off()
    }

    /** Build a minimal event map matching handleDeviceEvent's expectations. */
    private Map buildEvent(Integer deviceId, String attr, String value) {
        [
            device: [id: deviceId, label: "Dev${deviceId}"],
            name: attr,
            value: value
        ]
    }

    /** Minimal parent — findDevice(id) by Long coercion. */
    static class EventParent {
        Map<Long, TestDevice> devices = [:]

        Object findDevice(id) {
            devices[(id as Long)]
        }
    }
}
