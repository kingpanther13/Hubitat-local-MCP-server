package rules

import support.TestDevice

/**
 * Breadth coverage for every non-primitive condition type in
 * {@code hubitat-mcp-rule.groovy::evaluateCondition} (see #75).
 * Primitive types ({@code device_state}, {@code variable}) are covered
 * in {@link EvaluateConditionSpec}; this spec picks up the rest:
 *
 * <ul>
 *   <li>{@code mode} — {@code location.mode} in a {@code modes} list,
 *       {@code operator=not_in} inverts membership.</li>
 *   <li>{@code time_range} — stubs {@code timeOfDayIsBetween} on the
 *       AppExecutor mock (it's defined on {@code BaseExecutor}, so script
 *       calls resolve through @Delegate, not metaClass).</li>
 *   <li>{@code days_of_week} — {@code new Date().format("EEEE")}; the
 *       rule engine uses system time unconditionally, so tests compute
 *       today's name locally and compare.</li>
 *   <li>{@code sun_position} — compares {@code new Date()} against
 *       {@code location.sunrise}/{@code location.sunset}; tests seed past/future
 *       timestamps on the shared {@code testLocation}.</li>
 *   <li>{@code hsm_status} — reads {@code location.hsmStatus}. The field
 *       isn't declared on HubitatCI's {@code Location} interface, but
 *       Groovy property dispatch resolves it on the concrete TestLocation
 *       at runtime — real Hubitat exposes it the same way.</li>
 *   <li>{@code device_was} — drives {@code eventsSince} (added to
 *       TestDevice) to exercise the lookback/filter logic.</li>
 *   <li>{@code presence}, {@code lock}, {@code thermostat_mode},
 *       {@code thermostat_state}, {@code illuminance}, {@code power} —
 *       simple attribute reads via {@code device.currentValue(...)}.</li>
 * </ul>
 */
class ConditionTypesSpec extends RuleHarnessSpec {

    // -------- mode --------

    def "mode condition returns true when location.mode is in the modes list"() {
        given:
        testLocation.mode = 'Night'

        expect:
        script.evaluateCondition([
            type: 'mode', modes: ['Night', 'Away']
        ]) == true
    }

    def "mode condition returns false when location.mode is not in the modes list"() {
        given:
        testLocation.mode = 'Home'

        expect:
        script.evaluateCondition([
            type: 'mode', modes: ['Night', 'Away']
        ]) == false
    }

    def "mode condition with operator=not_in inverts membership"() {
        given:
        testLocation.mode = 'Home'

        expect:
        script.evaluateCondition([
            type: 'mode', modes: ['Night', 'Away'], operator: 'not_in'
        ]) == true
    }

    def "mode condition accepts singular 'mode' key as a single-item list"() {
        given:
        testLocation.mode = 'Vacation'

        expect:
        script.evaluateCondition([
            type: 'mode', mode: 'Vacation'
        ]) == true
    }

    // -------- time_range --------

    def "time_range returns true when timeOfDayIsBetween returns true"() {
        given: 'timeOfDayIsBetween reports current time inside the window'
        stubTimeOfDayResult = true

        expect:
        script.evaluateCondition([
            type: 'time_range', start: '09:00', end: '17:00'
        ]) == true
    }

    def "time_range returns false when timeOfDayIsBetween returns false"() {
        given:
        stubTimeOfDayResult = false

        expect:
        script.evaluateCondition([
            type: 'time_range', start: '09:00', end: '17:00'
        ]) == false
    }

    def "time_range fails closed when the time strings are unparseable"() {
        expect: 'null start trips parseTimeString, which throws and is caught as false'
        script.evaluateCondition([
            type: 'time_range', start: null, end: '17:00'
        ]) == false
    }

    // -------- days_of_week --------

    def "days_of_week returns true when today's day name is in the list"() {
        given: 'compute today locally — the rule engine uses system time'
        def today = new Date().format('EEEE')

        expect:
        script.evaluateCondition([
            type: 'days_of_week', days: [today]
        ]) == true
    }

    def "days_of_week returns false when today is not in the list"() {
        given:
        def today = new Date().format('EEEE')
        // The six-other-days list never contains today, so the check always fails.
        def otherDays = (['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']
            - today)

        expect:
        script.evaluateCondition([
            type: 'days_of_week', days: otherDays
        ]) == false
    }

    def "days_of_week returns false when days list is missing"() {
        expect:
        script.evaluateCondition([type: 'days_of_week']) == false
    }

    // -------- sun_position --------

    def "sun_position returns true for 'up' when current time is between sunrise and sunset"() {
        given: 'sunrise is in the past, sunset is in the future'
        testLocation.sunrise = new Date(System.currentTimeMillis() - 3600_000L) // 1h ago
        testLocation.sunset = new Date(System.currentTimeMillis() + 3600_000L) // 1h ahead

        expect:
        script.evaluateCondition([
            type: 'sun_position', position: 'up'
        ]) == true
    }

    def "sun_position returns false for 'up' when sunset is already past"() {
        given: 'both sunrise and sunset are in the past — sun is down'
        testLocation.sunrise = new Date(System.currentTimeMillis() - 7200_000L) // 2h ago
        testLocation.sunset = new Date(System.currentTimeMillis() - 3600_000L) // 1h ago

        expect:
        script.evaluateCondition([
            type: 'sun_position', position: 'up'
        ]) == false
    }

    def "sun_position returns true for 'down' when sun is below horizon"() {
        given:
        testLocation.sunrise = new Date(System.currentTimeMillis() - 7200_000L)
        testLocation.sunset = new Date(System.currentTimeMillis() - 3600_000L)

        expect:
        script.evaluateCondition([
            type: 'sun_position', position: 'down'
        ]) == true
    }

    def "sun_position fails closed when sunrise/sunset are unavailable"() {
        given: 'testLocation.sunrise/sunset default to null from setup()'

        expect:
        script.evaluateCondition([
            type: 'sun_position', position: 'up'
        ]) == false
    }

    // -------- hsm_status --------

    def "hsm_status returns true when location.hsmStatus matches"() {
        given:
        testLocation.hsmStatus = 'armedAway'

        expect:
        script.evaluateCondition([
            type: 'hsm_status', status: 'armedAway'
        ]) == true
    }

    def "hsm_status returns false when status differs"() {
        given:
        testLocation.hsmStatus = 'disarmed'

        expect:
        script.evaluateCondition([
            type: 'hsm_status', status: 'armedAway'
        ]) == false
    }

    // -------- device_was --------

    def "device_was returns true when attribute has held the target value across the lookback"() {
        given:
        def device = new TestDevice(id: 1, label: 'Motion')
        device.attributeValues['motion'] = 'inactive'
        // Only same-value events in the lookback → no recentChange → true
        device.events = [
            [name: 'motion', value: 'inactive'],
            [name: 'motion', value: 'inactive']
        ]
        parent = new DeviceParent(devices: [1L: device])

        expect:
        script.evaluateCondition([
            type: 'device_was', deviceId: 1L, attribute: 'motion',
            value: 'inactive', forSeconds: 60
        ]) == true
    }

    def "device_was returns false when a different-value event is present in the lookback"() {
        given:
        def device = new TestDevice(id: 1, label: 'Motion')
        device.attributeValues['motion'] = 'inactive'
        // A recent 'active' event means the attribute changed in-window → false
        device.events = [
            [name: 'motion', value: 'inactive'],
            [name: 'motion', value: 'active']
        ]
        parent = new DeviceParent(devices: [1L: device])

        expect:
        script.evaluateCondition([
            type: 'device_was', deviceId: 1L, attribute: 'motion',
            value: 'inactive', forSeconds: 60
        ]) == false
    }

    def "device_was returns false when current attribute value does not match target"() {
        given:
        def device = new TestDevice(id: 1, label: 'Motion')
        device.attributeValues['motion'] = 'active'  // currently NOT inactive
        parent = new DeviceParent(devices: [1L: device])

        expect: 'current value mismatch short-circuits before event lookup'
        script.evaluateCondition([
            type: 'device_was', deviceId: 1L, attribute: 'motion',
            value: 'inactive', forSeconds: 60
        ]) == false
    }

    def "device_was returns false when forSeconds is missing"() {
        given:
        def device = new TestDevice(id: 1, label: 'Motion')
        device.attributeValues['motion'] = 'inactive'
        parent = new DeviceParent(devices: [1L: device])

        expect:
        script.evaluateCondition([
            type: 'device_was', deviceId: 1L, attribute: 'motion', value: 'inactive'
        ]) == false
    }

    // -------- presence --------

    def "presence condition returns true when device.presence matches"() {
        given:
        def device = new TestDevice(id: 2, label: 'Phone')
        device.attributeValues['presence'] = 'present'
        parent = new DeviceParent(devices: [2L: device])

        expect:
        script.evaluateCondition([
            type: 'presence', deviceId: 2L, status: 'present'
        ]) == true
    }

    def "presence condition returns false when device not found"() {
        given:
        parent = new DeviceParent(devices: [:])

        expect:
        script.evaluateCondition([
            type: 'presence', deviceId: 999L, status: 'present'
        ]) == false
    }

    // -------- lock --------

    def "lock condition returns true when device.lock matches"() {
        given:
        def device = new TestDevice(id: 3, label: 'Front Door')
        device.attributeValues['lock'] = 'locked'
        parent = new DeviceParent(devices: [3L: device])

        expect:
        script.evaluateCondition([
            type: 'lock', deviceId: 3L, status: 'locked'
        ]) == true
    }

    // -------- thermostat --------

    def "thermostat_mode returns true when thermostatMode matches"() {
        given:
        def device = new TestDevice(id: 4, label: 'Tstat')
        device.attributeValues['thermostatMode'] = 'heat'
        parent = new DeviceParent(devices: [4L: device])

        expect:
        script.evaluateCondition([
            type: 'thermostat_mode', deviceId: 4L, mode: 'heat'
        ]) == true
    }

    def "thermostat_state returns true when thermostatOperatingState matches"() {
        given:
        def device = new TestDevice(id: 4, label: 'Tstat')
        device.attributeValues['thermostatOperatingState'] = 'heating'
        parent = new DeviceParent(devices: [4L: device])

        expect:
        script.evaluateCondition([
            type: 'thermostat_state', deviceId: 4L, state: 'heating'
        ]) == true
    }

    // -------- illuminance / power (numeric comparisons) --------

    def "illuminance condition compares via evaluateComparison"() {
        given:
        def device = new TestDevice(id: 5, label: 'Sensor')
        device.attributeValues['illuminance'] = 120
        parent = new DeviceParent(devices: [5L: device])

        expect:
        script.evaluateCondition([
            type: 'illuminance', deviceId: 5L, operator: '<', value: 200
        ]) == true
    }

    def "power condition compares via evaluateComparison"() {
        given:
        def device = new TestDevice(id: 6, label: 'Plug')
        device.attributeValues['power'] = 15.5
        parent = new DeviceParent(devices: [6L: device])

        expect:
        script.evaluateCondition([
            type: 'power', deviceId: 6L, operator: '>=', value: 10
        ]) == true
    }

    /** Minimal parent stub — findDevice(id) by Long coercion. */
    static class DeviceParent {
        Map<Long, TestDevice> devices = [:]

        Object findDevice(id) {
            devices[(id as Long)]
        }
    }
}
