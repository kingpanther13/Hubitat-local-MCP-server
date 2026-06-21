package server

import support.TestDevice
import support.ToolSpecBase

// Spec for the read-side convergence extensions to toolPollUntilAttribute: the
// comparator (eq/ne/gt/gte/lt/lte/between) and stableForMs (debounce) args, plus their
// pass-through via _buildWaitForPollArgs (the hub_call_device_command waitFor path).
// stableForMs needs a clock that advances (the harness now() is a fixed Mock), so the
// debounce specs install installVirtualClock() -- see its comment for the mechanism.
class ToolPollComparatorStableSpec extends ToolSpecBase {

    // Install a virtual clock that advances only on sleep (as on the hub): pauseExecution
    // (script metaClass override, interceptable per the existing poll specs) advances clock[0]
    // by the requested ms and is otherwise instant; now() reads clock[0] via the base mock's
    // NOW_OVERRIDE indirection (a `_ * now() >>` interaction at Mock creation cannot be
    // overridden by a feature-method stubbing, so the base reads this holder). NOW_OVERRIDE is
    // reset to null in HarnessSpec.setup(), so the override is scoped to this feature.
    // Returns the clock array so a spec can read elapsed virtual time.
    private List installVirtualClock(long start = 1_000_000L) {
        def clock = [start]
        script.metaClass.pauseExecution = { long ms -> clock[0] = clock[0] + ms }
        NOW_OVERRIDE.set({ clock[0] })
        return clock
    }

    // -------------------------------------------------------------------------
    // comparator: gt / gte / lt / lte -- converge + timeout per operator
    // -------------------------------------------------------------------------

    def "gt converges when numeric value exceeds the threshold"() {
        given:
        def device = new TestDevice(id: 400, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '400', attribute: 'temperature', comparator: 'gt', expectedValue: '72', timeoutMs: 5000])

        then:
        r.success == true
        r.finalValue == '73'
        r.timedOut == false
    }

    def "gt times out when numeric value is not above the threshold (equal is not greater)"() {
        given:
        def device = new TestDevice(id: 401, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '72'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '401', attribute: 'temperature', comparator: 'gt', expectedValue: '72', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.success == false
        r.timedOut == true
    }

    def "gte converges when numeric value equals the threshold"() {
        given:
        def device = new TestDevice(id: 402, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '72'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '402', attribute: 'temperature', comparator: 'gte', expectedValue: '72', timeoutMs: 5000])

        then:
        r.success == true
        r.timedOut == false
    }

    def "gte times out when numeric value is below the threshold"() {
        given:
        def device = new TestDevice(id: 403, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '71'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '403', attribute: 'temperature', comparator: 'gte', expectedValue: '72', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.success == false
        r.timedOut == true
    }

    def "lt converges when numeric value is below the threshold"() {
        given:
        def device = new TestDevice(id: 404, label: 'Hum', supportedAttributes: [[name: 'humidity']], attributeValues: [humidity: '40'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '404', attribute: 'humidity', comparator: 'lt', expectedValue: '50', timeoutMs: 5000])

        then:
        r.success == true
        r.timedOut == false
    }

    def "lt times out when numeric value equals the threshold (equal is not less)"() {
        given:
        def device = new TestDevice(id: 405, label: 'Hum', supportedAttributes: [[name: 'humidity']], attributeValues: [humidity: '50'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '405', attribute: 'humidity', comparator: 'lt', expectedValue: '50', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.success == false
        r.timedOut == true
    }

    def "lte converges when numeric value equals the threshold"() {
        given:
        def device = new TestDevice(id: 406, label: 'Hum', supportedAttributes: [[name: 'humidity']], attributeValues: [humidity: '50'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '406', attribute: 'humidity', comparator: 'lte', expectedValue: '50', timeoutMs: 5000])

        then:
        r.success == true
        r.timedOut == false
    }

    def "lte times out when numeric value is above the threshold"() {
        given:
        def device = new TestDevice(id: 407, label: 'Hum', supportedAttributes: [[name: 'humidity']], attributeValues: [humidity: '51'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '407', attribute: 'humidity', comparator: 'lte', expectedValue: '50', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.success == false
        r.timedOut == true
    }

    // -------------------------------------------------------------------------
    // comparator: between -- inclusive boundaries + outside-range timeout
    // -------------------------------------------------------------------------

    def "between converges for a value inside the range"() {
        given:
        def device = new TestDevice(id: 410, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '410', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 5000])

        then:
        r.success == true
        r.timedOut == false
    }

    def "between is inclusive at the low boundary"() {
        given:
        def device = new TestDevice(id: 411, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '68'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '411', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 5000])

        then:
        r.success == true
    }

    def "between is inclusive at the high boundary"() {
        given:
        def device = new TestDevice(id: 412, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '72'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '412', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 5000])

        then:
        r.success == true
    }

    def "between times out for a value just below the range"() {
        given:
        def device = new TestDevice(id: 413, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '67'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '413', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.success == false
        r.timedOut == true
    }

    def "between times out for a value just above the range"() {
        given: 'high boundary is exclusive-above -- 73 is past the inclusive high of 72'
        def device = new TestDevice(id: 414, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '414', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.success == false
        r.timedOut == true
    }

    def "between with equal bounds (low == high) converges only on the exact value"() {
        given: 'a degenerate range [72,72] -- only 72 is inside'
        def match = new TestDevice(id: 415, label: 'Exact', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '72'])
        def miss  = new TestDevice(id: 416, label: 'Off By One', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '71'])
        childDevicesList << match
        childDevicesList << miss

        when: 'value equals the single point'
        def hit = script.toolPollUntilAttribute([deviceId: '415', attribute: 'temperature', comparator: 'between', expectedValues: ['72', '72'], timeoutMs: 5000])

        then:
        hit.success == true
        hit.timedOut == false

        when: 'value is off the single point'
        def out = script.toolPollUntilAttribute([deviceId: '416', attribute: 'temperature', comparator: 'between', expectedValues: ['72', '72'], timeoutMs: 100, pollIntervalMs: 50])

        then:
        out.success == false
        out.timedOut == true
    }

    // -------------------------------------------------------------------------
    // comparator: ne
    // -------------------------------------------------------------------------

    def "ne converges when value is not in the set"() {
        given:
        def device = new TestDevice(id: 420, label: 'Lock', supportedAttributes: [[name: 'lock']], attributeValues: [lock: 'unlocked'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '420', attribute: 'lock', comparator: 'ne', expectedValue: 'locked', timeoutMs: 5000])

        then:
        r.success == true
        r.finalValue == 'unlocked'
        r.timedOut == false
    }

    def "ne times out when value is in the set"() {
        given:
        def device = new TestDevice(id: 421, label: 'Lock', supportedAttributes: [[name: 'lock']], attributeValues: [lock: 'locked'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '421', attribute: 'lock', comparator: 'ne', expectedValue: 'locked', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.success == false
        r.timedOut == true
    }

    def "ne does not falsely match a never-reported attribute"() {
        given: 'attribute supported but never reported (absent from currentStates)'
        def device = new TestDevice(id: 422, label: 'Lock', supportedAttributes: [[name: 'lock']], attributeValues: [:])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '422', attribute: 'lock', comparator: 'ne', expectedValue: 'locked', timeoutMs: 100, pollIntervalMs: 50])

        then: 'a null value is not "not in set" -- ne must keep polling, then time out'
        r.success == false
        r.timedOut == true
        r.neverReported == true
    }

    // LOAD-BEARING GUARD (both-ways pending, orchestrator): ne must treat a value that
    // reverts to null AFTER reporting as NOT-matched, consistent with the numeric
    // comparators. The device reports an in-set value first (ne does not converge), then
    // the attribute disappears (currentStates empty -> finalValue null). With the
    // null-consistent condition (finalValue != null && !inSet) the null never satisfies ne,
    // so the poll times out. If the condition latched on a prior non-null report
    // (everNonNull && !inSet), the null poll would falsely converge with finalValue:null --
    // this spec goes RED in that case.
    def "ne does not converge on a value that reverts to null after reporting"() {
        given: 'first read is an in-set value (locked); subsequent reads drop the attribute'
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 423
        device.label = 'Disappearing Lock'
        device.supportedAttributes = [[name: 'lock']]
        device.getCurrentStates() >> {
            readCount++
            // 1: locked (in set -> ne does not match). 2+: attribute gone (finalValue null).
            return (readCount == 1) ? [[name: 'lock', value: 'locked']] : []
        }
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '423', attribute: 'lock', comparator: 'ne', expectedValue: 'locked', timeoutMs: 100, pollIntervalMs: 50])

        then: 'the revert-to-null never satisfies ne -- poll times out, does NOT converge on null'
        r.success == false
        r.timedOut == true
        r.finalValue == null
    }

    // -------------------------------------------------------------------------
    // numeric comparator + non-numeric finalValue -> never matches (timeout)
    // -------------------------------------------------------------------------

    def "numeric comparator never matches a non-numeric value and times out"() {
        given:
        def device = new TestDevice(id: 430, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '430', attribute: 'switch', comparator: 'gt', expectedValue: '5', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.success == false
        r.timedOut == true
        r.finalValue == 'on'
    }

    def "numeric comparator never matches a never-reported (null) value and times out"() {
        given:
        def device = new TestDevice(id: 431, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [:])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '431', attribute: 'temperature', comparator: 'gt', expectedValue: '5', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.success == false
        r.timedOut == true
    }

    // -------------------------------------------------------------------------
    // comparator validation -> IAE
    // -------------------------------------------------------------------------

    def "numeric comparator with expectedValues -> IAE naming the conflict"() {
        given:
        def device = new TestDevice(id: 440, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '440', attribute: 'temperature', comparator: 'gt', expectedValues: ['72'], timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedValue')
        ex.message.contains('expectedValues')
    }

    def "between with !=2 bounds -> IAE"() {
        given:
        def device = new TestDevice(id: 441, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '441', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '70', '72'], timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('exactly 2')
    }

    def "between with expectedValue -> IAE"() {
        given:
        def device = new TestDevice(id: 442, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '442', attribute: 'temperature', comparator: 'between', expectedValue: '70', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedValues')
    }

    def "numeric comparator with non-numeric threshold -> IAE"() {
        given:
        def device = new TestDevice(id: 443, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '443', attribute: 'temperature', comparator: 'gt', expectedValue: 'warm', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    def "between with non-numeric bound -> IAE"() {
        given:
        def device = new TestDevice(id: 444, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '444', attribute: 'temperature', comparator: 'between', expectedValues: ['68', 'hot'], timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    def "between with low > high -> IAE"() {
        given:
        def device = new TestDevice(id: 445, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '445', attribute: 'temperature', comparator: 'between', expectedValues: ['72', '68'], timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('low <= high')
    }

    def "unknown comparator -> IAE listing the valid set"() {
        given:
        def device = new TestDevice(id: 446, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '446', attribute: 'temperature', comparator: 'approx', expectedValue: '70', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('between')   // the valid set is enumerated
        ex.message.contains('approx')
    }

    def "comparator explicitly null -> IAE"() {
        given:
        def device = new TestDevice(id: 447, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '447', attribute: 'switch', expectedValue: 'on', comparator: null, timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('comparator')
        ex.message.toLowerCase().contains('null')
    }

    // -------------------------------------------------------------------------
    // stableForMs (debounce)
    // -------------------------------------------------------------------------

    // LOAD-BEARING GUARD (both-ways pending, orchestrator): asserts the debounce
    // does NOT converge on the first matching poll -- it must hold for stableForMs
    // of virtual time first. If the debounce gate were removed (converge on first
    // match), polledCount would be 1 and elapsed < stableForMs.
    def "stableForMs does not converge on the first matching poll; converges only after the window elapses"() {
        given: 'value is at target from the very first read'
        def device = new TestDevice(id: 450, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device
        def clock = installVirtualClock()

        when: 'stableForMs=300, pollIntervalMs=100 -> needs >=3 polls of held time before convergence'
        def r = script.toolPollUntilAttribute([deviceId: '450', attribute: 'switch', expectedValue: 'on', stableForMs: 300, timeoutMs: 5000, pollIntervalMs: 100])

        then:
        r.success == true
        r.timedOut == false
        // Did NOT short-circuit on the first matching poll: at least the window of
        // virtual time had to pass, so multiple polls fired.
        r.polledCount > 1
        // The matched condition was held for at least stableForMs of virtual time.
        (clock[0] - 1_000_000L) >= 300
    }

    // LOAD-BEARING GUARD (both-ways pending, orchestrator): a value that goes
    // true -> false -> true must RESET the stability window. If the reset
    // (conditionTrueSince = null on a false poll) were dropped, the run would
    // converge using stale accumulated true-time and finalValue could differ.
    def "stableForMs resets the window when the condition flaps false then true again"() {
        given: 'switch reads on (poll 1), off (poll 2 -> reset), then on from poll 3 onward'
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 451
        device.label = 'Flapping'
        device.supportedAttributes = [[name: 'switch']]
        device.getCurrentStates() >> {
            readCount++
            // 1: on, 2: off (resets the window), 3+: on (the run that must satisfy stability)
            def v = (readCount == 1) ? 'on' : (readCount == 2 ? 'off' : 'on')
            return [[name: 'switch', value: v]]
        }
        childDevicesList << device
        def clock = installVirtualClock()

        when: 'stableForMs=200, pollIntervalMs=100: the on-run only starts at poll 3'
        def r = script.toolPollUntilAttribute([deviceId: '451', attribute: 'switch', expectedValue: 'on', stableForMs: 200, timeoutMs: 5000, pollIntervalMs: 100])

        then: 'converged, but only after the flap reset -- so the window started at poll 3, not poll 1'
        r.success == true
        r.timedOut == false
        // Poll 1 was on, poll 2 off (reset). The stable on-run begins at poll 3, needs
        // 200ms = 2 more poll intervals, so convergence cannot occur before poll 5.
        r.polledCount >= 5
    }

    def "stableForMs >= timeoutMs -> IAE pre-poll (could never converge)"() {
        given:
        def device = new TestDevice(id: 452, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '452', attribute: 'switch', expectedValue: 'on', stableForMs: 5000, timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('stableforms')
        ex.message.contains('5000')
    }

    def "stableForMs negative -> IAE"() {
        given:
        def device = new TestDevice(id: 453, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '453', attribute: 'switch', expectedValue: 'on', stableForMs: -1, timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('stableforms')
    }

    def "stableForMs explicitly null -> IAE"() {
        given:
        def device = new TestDevice(id: 454, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '454', attribute: 'switch', expectedValue: 'on', stableForMs: null, timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('stableforms')
        ex.message.toLowerCase().contains('null')
    }

    def "stableForMs as a non-Number string -> IAE naming the integer requirement"() {
        given: 'a numeric-looking String is still not a Number -- the engine requires an integer'
        def device = new TestDevice(id: 456, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '456', attribute: 'switch', expectedValue: 'on', stableForMs: '300', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    def "stableForMs of 0 converges on the first matching poll (default behavior)"() {
        given:
        def device = new TestDevice(id: 455, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '455', attribute: 'switch', expectedValue: 'on', stableForMs: 0, timeoutMs: 5000])

        then:
        r.success == true
        r.polledCount == 1
    }

    // -------------------------------------------------------------------------
    // comparator + stableForMs combined
    // -------------------------------------------------------------------------

    def "gte + stableForMs converges only after a numeric value holds above threshold for the window"() {
        given: 'temperature already at 73 (>= 72) from the first read'
        def device = new TestDevice(id: 460, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        childDevicesList << device
        def clock = installVirtualClock()

        when:
        def r = script.toolPollUntilAttribute([deviceId: '460', attribute: 'temperature', comparator: 'gte', expectedValue: '72', stableForMs: 200, timeoutMs: 5000, pollIntervalMs: 100])

        then:
        r.success == true
        r.polledCount > 1
        (clock[0] - 1_000_000L) >= 200
    }

    // -------------------------------------------------------------------------
    // Backward-compat: an existing eq/OR call with neither new arg
    // -------------------------------------------------------------------------

    def "eq with OR (expectedValue + expectedValues) still matches when neither new arg is supplied"() {
        given:
        def device = new TestDevice(id: 470, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        childDevicesList << device

        when:
        def r = script.toolPollUntilAttribute([deviceId: '470', attribute: 'switch', expectedValue: 'on', expectedValues: ['off'], timeoutMs: 5000])

        then:
        r.success == true
        r.finalValue == 'off'
    }

    // =========================================================================
    // _buildWaitForPollArgs pass-through (hub_call_device_command waitFor path)
    // =========================================================================

    def "waitFor passes comparator + stableForMs through and converges via command flow"() {
        given:
        def fired = []
        def device = Spy(TestDevice)
        device.id = 500
        device.label = 'Cmd Temp'
        device.supportedAttributes = [[name: 'temperature']]
        device.supportedCommands = [[name: 'on']]
        device.getCurrentStates() >> [[name: 'temperature', value: '73']]
        device.invokeCommand(_, _) >> { String c, List a -> fired << c }
        childDevicesList << device
        def clock = installVirtualClock()

        when:
        def r = script.toolSendCommand('500', 'on', null, [attribute: 'temperature', comparator: 'gt', expectedValue: '72', stableForMs: 100, timeoutMs: 5000, pollIntervalMs: 100])

        then:
        fired == ['on']
        r.success == true
        r.waitFor.converged == true
        r.waitFor.attribute == 'temperature'
    }

    def "waitFor numeric comparator with expectedValues -> IAE BEFORE the command fires"() {
        given:
        def fired = []
        def device = Spy(TestDevice)
        device.id = 501
        device.label = 'Cmd NoFire'
        device.supportedAttributes = [[name: 'temperature']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { String c, List a -> fired << c }
        childDevicesList << device

        when:
        script.toolSendCommand('501', 'on', null, [attribute: 'temperature', comparator: 'gt', expectedValues: ['72'], timeoutMs: 5000])

        then: 'bad spec rejected pre-fire -- the command never actuated the device'
        thrown(IllegalArgumentException)
        fired == []
    }

    def "waitFor between with wrong bound count -> IAE before command fires"() {
        given:
        def fired = []
        def device = Spy(TestDevice)
        device.id = 502
        device.label = 'Cmd NoFire 2'
        device.supportedAttributes = [[name: 'temperature']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { String c, List a -> fired << c }
        childDevicesList << device

        when:
        script.toolSendCommand('502', 'on', null, [attribute: 'temperature', comparator: 'between', expectedValues: ['68'], timeoutMs: 5000])

        then:
        thrown(IllegalArgumentException)
        fired == []
    }

    def "waitFor stableForMs >= timeoutMs -> IAE before command fires"() {
        given:
        def fired = []
        def device = Spy(TestDevice)
        device.id = 503
        device.label = 'Cmd NoFire 3'
        device.supportedAttributes = [[name: 'switch']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { String c, List a -> fired << c }
        childDevicesList << device

        when:
        script.toolSendCommand('503', 'on', null, [attribute: 'switch', expectedValue: 'on', stableForMs: 5000, timeoutMs: 5000])

        then:
        thrown(IllegalArgumentException)
        fired == []
    }

    def "waitFor stableForMs >= the defaulted 5000 timeout (timeoutMs omitted) -> IAE before command fires"() {
        given: 'no timeoutMs key -- exercises the effectiveTimeoutMs : 5000 default branch'
        def fired = []
        def device = Spy(TestDevice)
        device.id = 506
        device.label = 'Cmd NoFire 6'
        device.supportedAttributes = [[name: 'level']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { String c, List a -> fired << c }
        childDevicesList << device

        when:
        script.toolSendCommand('506', 'on', null, [attribute: 'level', expectedValue: '50', stableForMs: 6000])

        then: 'the message names timeoutMs (5000), proving the 5000 default was applied (not an explicit value)'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('less than timeoutMs (5000)')
        fired == []
    }

    def "waitFor explicit null comparator -> IAE 'must not be null' before command fires"() {
        given:
        def fired = []
        def device = Spy(TestDevice)
        device.id = 504
        device.label = 'Cmd NoFire 4'
        device.supportedAttributes = [[name: 'switch']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { String c, List a -> fired << c }
        childDevicesList << device

        when:
        script.toolSendCommand('504', 'on', null, [attribute: 'switch', expectedValue: 'on', comparator: null])

        then: 'pre-fire null guard fires with the actionable message -- the command never actuated'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must not be null')
        ex.message.contains('comparator')
        fired == []
    }

    def "waitFor explicit null stableForMs -> IAE 'must not be null' before command fires"() {
        given:
        def fired = []
        def device = Spy(TestDevice)
        device.id = 505
        device.label = 'Cmd NoFire 5'
        device.supportedAttributes = [[name: 'switch']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { String c, List a -> fired << c }
        childDevicesList << device

        when:
        script.toolSendCommand('505', 'on', null, [attribute: 'switch', expectedValue: 'on', stableForMs: null])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must not be null')
        ex.message.contains('stableForMs')
        fired == []
    }

    // =========================================================================
    // Dispatch-envelope: comparator + stableForMs route to poll mode via executeTool
    // =========================================================================

    @spock.lang.Unroll
    def "hub_get_device_attribute with comparator routes to poll mode and converges (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 1100, label: 'Dispatch Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('hub_get_device_attribute', [deviceId: '1100', attribute: 'temperature', comparator: 'gt', expectedValue: '72', timeoutMs: 5000])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.timedOut == false

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_device_attribute with bad comparator returns -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 1101, label: 'Dispatch Bad Cmp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('hub_get_device_attribute', [deviceId: '1101', attribute: 'temperature', comparator: 'approx', expectedValue: '72'])

        then:
        response.error.code == -32602
        response.error.message.contains('approx')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_device_attribute with only stableForMs (no expectedValue) routes to poll mode and is rejected -32602 (useGateways=#useGateways)"() {
        given: 'stableForMs alone enters poll mode; the engine then rejects the missing expectedValue'
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 1102, label: 'Dispatch Stable Only', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('hub_get_device_attribute', [deviceId: '1102', attribute: 'switch', stableForMs: 100])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('expectedvalue')

        where:
        useGateways << [true, false]
    }
}
