package server

import spock.lang.Shared
import support.TestLocation
import support.ToolSpecBase

/**
 * Spec for the set_hsm tool in hubitat-mcp-server.groovy. The tool now has two
 * shapes: classic HSM mode change ({@code mode: armAway|armHome|armNight|disarm}),
 * and an arbitrary location-event broadcast ({@code event: <name>, value, data,
 * descriptionText, confirm: true}) — see issue #95.
 *
 * sendLocationEvent is declared on AppExecutor so it goes through the @Delegate
 * chain (class-1 in docs/testing.md "Which interception point to use"). Per-feature
 * given:-block >> stubs on a @Shared mock don't propagate, so the recorder is a
 * permanent setupSpec stub feeding a @Shared list, cleared in setup().
 */
class ToolSetHsmSpec extends ToolSpecBase {

    @Shared protected final List<Map> sendLocationEventCalls = []
    @Shared protected TestLocation testLocation = new TestLocation()

    def setupSpec() {
        appExecutor.sendLocationEvent(_) >> { args -> sendLocationEventCalls << (args[0] as Map) }
        appExecutor.getLocation() >> testLocation
    }

    def setup() {
        sendLocationEventCalls.clear()
        testLocation.hsmStatus = null
    }

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // -------- Classic HSM mode change --------

    def "set_hsm mode=armAway fires hsmSetArm location event"() {
        given:
        testLocation.hsmStatus = 'disarmed'

        when:
        def result = script.toolSetHsm([mode: 'armAway'])

        then:
        sendLocationEventCalls == [[name: 'hsmSetArm', value: 'armAway']]
        result.success == true
        result.previousStatus == 'disarmed'
        result.newMode == 'armAway'
    }

    def "set_hsm rejects invalid HSM mode with the valid-modes list"() {
        when:
        script.toolSetHsm([mode: 'armEverything'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Invalid HSM mode")
        ex.message.contains("armAway")
        sendLocationEventCalls.isEmpty()
    }

    // -------- Mutual exclusion --------

    def "set_hsm refuses when both mode and event are passed"() {
        when:
        script.toolSetHsm([mode: 'armAway', event: 'mcp_pizza'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("either 'mode'")
        ex.message.contains("'event'")
        sendLocationEventCalls.isEmpty()
    }

    // -------- Custom location event --------

    def "set_hsm event fires the named location event with value, descriptionText, and JSON-serialized data"() {
        given:
        enableHubAdminWrite()

        when:
        def result = script.toolSetHsm([
            event: 'mcp_pizza_arrived',
            value: 'large_pepperoni',
            descriptionText: 'Pizza is here',
            data: [orderId: 42, slices: 8],
            confirm: true
        ])

        then:
        sendLocationEventCalls.size() == 1
        def evt = sendLocationEventCalls[0]
        evt.name == 'mcp_pizza_arrived'
        evt.value == 'large_pepperoni'
        evt.descriptionText == 'Pizza is here'
        // data Map -> JSON string for subscriber parseJson(evt.data) compat
        evt.data == '{"orderId":42,"slices":8}'

        and: 'response echoes the broadcast'
        result.success == true
        result.event == 'mcp_pizza_arrived'
        result.value == 'large_pepperoni'
        result.data == '{"orderId":42,"slices":8}'
    }

    def "set_hsm event with only the event name fires a minimal location event"() {
        given:
        enableHubAdminWrite()

        when:
        def result = script.toolSetHsm([event: 'mcp_signal', confirm: true])

        then:
        sendLocationEventCalls == [[name: 'mcp_signal']]
        result.success == true
        result.event == 'mcp_signal'
        result.value == null
        result.data == null
    }

    def "set_hsm event passes a String data payload through unchanged (no double-encode)"() {
        // Callers that already JSON-serialized themselves shouldn't get re-wrapped.
        given:
        enableHubAdminWrite()

        when:
        script.toolSetHsm([event: 'mcp_raw', data: '{"already":"encoded"}', confirm: true])

        then:
        sendLocationEventCalls[0].data == '{"already":"encoded"}'
    }

    def "set_hsm event coerces non-String value to String for subscriber consistency"() {
        // Hubitat surfaces evt.value as a String for location events; document/lock
        // that contract so a numeric caller can't accidentally hand subscribers an
        // Integer that breaks string-comparison conditions.
        given:
        enableHubAdminWrite()

        when:
        script.toolSetHsm([event: 'mcp_count', value: 42, confirm: true])

        then:
        sendLocationEventCalls[0].value == '42'
    }

    def "set_hsm event rejects empty event name"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolSetHsm([event: '   ', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("event name is required")
        sendLocationEventCalls.isEmpty()
    }

    def "set_hsm event rejects when confirm is missing (Hub Admin Write gate)"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolSetHsm([event: 'mcp_pizza'])

        then:
        thrown(IllegalArgumentException)
        sendLocationEventCalls.isEmpty()
    }

    def "set_hsm event rejects when no recent backup exists (24h backup gate)"() {
        given: 'enableHubAdminWrite is true but no lastBackupTimestamp seeded'
        settingsMap.enableHubAdminWrite = true

        when:
        script.toolSetHsm([event: 'mcp_pizza', confirm: true])

        then:
        thrown(IllegalArgumentException)
        sendLocationEventCalls.isEmpty()
    }

    def "set_hsm event rejects when enableHubAdminWrite setting is off"() {
        given: 'no settingsMap.enableHubAdminWrite seed — gate refuses on the first layer'
        stateMap.lastBackupTimestamp = 1234567890000L

        when:
        script.toolSetHsm([event: 'mcp_pizza', confirm: true])

        then:
        thrown(IllegalArgumentException)
        sendLocationEventCalls.isEmpty()
    }
}
