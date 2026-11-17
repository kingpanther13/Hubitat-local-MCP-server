package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.IgnoreIf
import support.NativeInventoryFixture
import support.NativePollFixture
import support.TestDevice
import support.ToolSpecBase

// The waitFor comparator (eq/ne/gt/gte/lt/lte/between) and stableForMs (debounce) on every tool
// that takes waitFor: hub_get_device (one device), hub_list_devices (deviceIds, mode any/all, one
// Devices page read per check) and hub_call_device_command (validated before the command fires).
// stableForMs needs a clock that advances (the harness now() is a fixed Mock), so the
// debounce specs install installVirtualClock() -- see its comment for the mechanism.
class ToolWaitForComparatorSpec extends ToolSpecBase {

    private List nativeCommandCalls = []

    def setup() {
        NativeInventoryFixture.registerDevicesPage(hubGet) { -> childDevicesList }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420, boolean retry = false ->
            assert path == '/device/runmethod'
            nativeCommandCalls << new JsonSlurper().parseText(body).method
            [success: true]
        }
    }

    private void nativePollDevice(TestDevice device) {
        childDevicesList << device
        NativePollFixture.register(hubGet, device)
    }

    // deviceIds waits run through hub_list_devices, single-device waits through hub_get_device;
    // returns the waitFor block.
    private Map poll(Map a) {
        Map waitFor = a.findAll { !(it.key in ['deviceId', 'deviceIds']) }
        if (a.containsKey('deviceIds')) return script.toolListDevices([deviceIds: a.deviceIds, waitFor: waitFor]).waitFor
        script.toolGetDevice(a.deviceId, 'summary', null, null, null, waitFor).waitFor
    }

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
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '400', attribute: 'temperature', comparator: 'gt', expectedValue: '72', timeoutMs: 5000])

        then:
        r.converged == true
        r.finalValue == '73'
        !r.timedOut
    }

    def "gt times out when numeric value is not above the threshold (equal is not greater)"() {
        given:
        def device = new TestDevice(id: 401, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '72'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '401', attribute: 'temperature', comparator: 'gt', expectedValue: '72', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.converged == false
        r.timedOut == true
    }

    def "gte converges when numeric value equals the threshold"() {
        given:
        def device = new TestDevice(id: 402, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '72'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '402', attribute: 'temperature', comparator: 'gte', expectedValue: '72', timeoutMs: 5000])

        then:
        r.converged == true
        !r.timedOut
    }

    def "gte times out when numeric value is below the threshold"() {
        given:
        def device = new TestDevice(id: 403, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '71'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '403', attribute: 'temperature', comparator: 'gte', expectedValue: '72', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.converged == false
        r.timedOut == true
    }

    def "lt converges when numeric value is below the threshold"() {
        given:
        def device = new TestDevice(id: 404, label: 'Hum', supportedAttributes: [[name: 'humidity']], attributeValues: [humidity: '40'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '404', attribute: 'humidity', comparator: 'lt', expectedValue: '50', timeoutMs: 5000])

        then:
        r.converged == true
        !r.timedOut
    }

    def "lt times out when numeric value equals the threshold (equal is not less)"() {
        given:
        def device = new TestDevice(id: 405, label: 'Hum', supportedAttributes: [[name: 'humidity']], attributeValues: [humidity: '50'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '405', attribute: 'humidity', comparator: 'lt', expectedValue: '50', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.converged == false
        r.timedOut == true
    }

    def "lte converges when numeric value equals the threshold"() {
        given:
        def device = new TestDevice(id: 406, label: 'Hum', supportedAttributes: [[name: 'humidity']], attributeValues: [humidity: '50'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '406', attribute: 'humidity', comparator: 'lte', expectedValue: '50', timeoutMs: 5000])

        then:
        r.converged == true
        !r.timedOut
    }

    def "lte times out when numeric value is above the threshold"() {
        given:
        def device = new TestDevice(id: 407, label: 'Hum', supportedAttributes: [[name: 'humidity']], attributeValues: [humidity: '51'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '407', attribute: 'humidity', comparator: 'lte', expectedValue: '50', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.converged == false
        r.timedOut == true
    }

    // -------------------------------------------------------------------------
    // comparator: between -- inclusive boundaries + outside-range timeout
    // -------------------------------------------------------------------------

    def "between converges for a value inside the range"() {
        given:
        def device = new TestDevice(id: 410, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '410', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 5000])

        then:
        r.converged == true
        !r.timedOut
    }

    def "between is inclusive at the low boundary"() {
        given:
        def device = new TestDevice(id: 411, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '68'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '411', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 5000])

        then:
        r.converged == true
    }

    def "between is inclusive at the high boundary"() {
        given:
        def device = new TestDevice(id: 412, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '72'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '412', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 5000])

        then:
        r.converged == true
    }

    def "between times out for a value just below the range"() {
        given:
        def device = new TestDevice(id: 413, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '67'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '413', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.converged == false
        r.timedOut == true
    }

    def "between times out for a value just above the range"() {
        given: 'high boundary is exclusive-above -- 73 is past the inclusive high of 72'
        def device = new TestDevice(id: 414, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '414', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.converged == false
        r.timedOut == true
    }

    def "between with equal bounds (low == high) converges only on the exact value"() {
        given: 'a degenerate range [72,72] -- only 72 is inside'
        def match = new TestDevice(id: 415, label: 'Exact', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '72'])
        def miss  = new TestDevice(id: 416, label: 'Off By One', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '71'])
        nativePollDevice(match)
        nativePollDevice(miss)

        when: 'value equals the single point'
        def hit = poll([deviceId: '415', attribute: 'temperature', comparator: 'between', expectedValues: ['72', '72'], timeoutMs: 5000])

        then:
        hit.converged == true
        !hit.timedOut

        when: 'value is off the single point'
        def out = poll([deviceId: '416', attribute: 'temperature', comparator: 'between', expectedValues: ['72', '72'], timeoutMs: 100, pollIntervalMs: 50])

        then:
        out.converged == false
        out.timedOut == true
    }

    // -------------------------------------------------------------------------
    // comparator: ne
    // -------------------------------------------------------------------------

    def "ne converges when value is not in the set"() {
        given:
        def device = new TestDevice(id: 420, label: 'Lock', supportedAttributes: [[name: 'lock']], attributeValues: [lock: 'unlocked'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '420', attribute: 'lock', comparator: 'ne', expectedValue: 'locked', timeoutMs: 5000])

        then:
        r.converged == true
        r.finalValue == 'unlocked'
        !r.timedOut
    }

    def "ne times out when value is in the set"() {
        given:
        def device = new TestDevice(id: 421, label: 'Lock', supportedAttributes: [[name: 'lock']], attributeValues: [lock: 'locked'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '421', attribute: 'lock', comparator: 'ne', expectedValue: 'locked', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.converged == false
        r.timedOut == true
    }

    def "ne does not falsely match a never-reported attribute"() {
        given: 'attribute supported but never reported (absent from currentStates)'
        def device = new TestDevice(id: 422, label: 'Lock', supportedAttributes: [[name: 'lock']], attributeValues: [:])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '422', attribute: 'lock', comparator: 'ne', expectedValue: 'locked', timeoutMs: 100, pollIntervalMs: 50])

        then: 'a null value is not "not in set" -- ne must keep polling, then time out'
        r.converged == false
        r.timedOut == true
        r.neverReported == true
    }

    // LOAD-BEARING GUARD (both-ways VERIFIED RED on revert, orchestrator): ne must treat a value that
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
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '423', attribute: 'lock', comparator: 'ne', expectedValue: 'locked', timeoutMs: 100, pollIntervalMs: 50])

        then: 'the revert-to-null never satisfies ne -- poll times out, does NOT converge on null'
        r.converged == false
        r.timedOut == true
        r.finalValue == null
    }

    def "ne against an expectedValues SET stays un-converged while the value is any set member, converges once it leaves them all"() {
        given: 'thermostatMode cycles through set members (heat, cool) then leaves to auto'
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 424
        device.label = 'Thermostat'
        device.supportedAttributes = [[name: 'thermostatMode']]
        device.getCurrentStates() >> {
            readCount++
            // 1: heat (in set), 2: cool (in set) -> ne stays false; 3+: auto (NOT in set) -> ne matches
            def v = (readCount == 1) ? 'heat' : (readCount == 2 ? 'cool' : 'auto')
            return [[name: 'thermostatMode', value: v]]
        }
        nativePollDevice(device)

        when: 'ne {heat, cool}: must not converge while reading heat or cool, converges on auto'
        def r = poll([deviceId: '424', attribute: 'thermostatMode', comparator: 'ne', expectedValues: ['heat', 'cool'], timeoutMs: 5000, pollIntervalMs: 50])

        then: 'converged only after the value left every set member (poll 3 = auto)'
        r.converged == true
        !r.timedOut
        r.finalValue == 'auto'
        r.polledCount >= 3
    }

    // -------------------------------------------------------------------------
    // numeric comparator + non-numeric finalValue -> never matches (timeout)
    // -------------------------------------------------------------------------

    def "numeric comparator on a non-numeric attribute times out with nonNumericAttribute (not neverReported)"() {
        given: 'switch reports "on" the whole window -- it parses non-numeric, so gt can never match'
        def device = new TestDevice(id: 430, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '430', attribute: 'switch', comparator: 'gt', expectedValue: '5', timeoutMs: 100, pollIntervalMs: 50])

        then: 'the honesty signal: the attribute WAS reported (so NOT neverReported) but never numeric'
        r.converged == false
        r.timedOut == true
        r.finalValue == 'on'
        r.nonNumericAttribute == true
        !r.containsKey('neverReported')
        r.note?.contains('can never match')
    }

    def "numeric comparator on a never-reported attribute times out with neverReported (not nonNumericAttribute)"() {
        given: 'attribute never reports -- this is neverReported, the distinct cause from nonNumericAttribute'
        def device = new TestDevice(id: 431, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [:])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '431', attribute: 'temperature', comparator: 'gt', expectedValue: '5', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.converged == false
        r.timedOut == true
        r.neverReported == true
        !r.containsKey('nonNumericAttribute')
    }

    def "numeric comparator that saw a numeric value at least once does NOT flag nonNumericAttribute on timeout"() {
        given: 'value is numeric on poll 1 (40), then non-numeric (on) for the rest -- sawNumeric latches true'
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 432
        device.label = 'Mixed'
        device.supportedAttributes = [[name: 'level']]
        device.getCurrentStates() >> {
            readCount++
            // poll 1: "40" (numeric, but 40 > 50 is false so no converge); polls 2+: "on" (non-numeric)
            def v = (readCount == 1) ? '40' : 'on'
            return [[name: 'level', value: v]]
        }
        nativePollDevice(device)

        when: 'gt 50: never converges, but it DID report a numeric value once'
        def r = poll([deviceId: '432', attribute: 'level', comparator: 'gt', expectedValue: '50', timeoutMs: 200, pollIntervalMs: 50])

        then: 'the can-never-match signal must NOT fire -- the attribute is numeric-capable (sawNumeric)'
        r.converged == false
        r.timedOut == true
        !r.containsKey('nonNumericAttribute')
    }

    def "a flapping non-numeric value under a numeric comparator flags nonNumericAttribute and suppresses transitioning"() {
        given: 'switch flaps on -> off -> on (all non-numeric) under gt -- sawChange would set transitioning=true'
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 433
        device.label = 'Flapping NonNumeric'
        device.supportedAttributes = [[name: 'switch']]
        device.getCurrentStates() >> {
            readCount++
            def v = (readCount == 1) ? 'on' : (readCount == 2 ? 'off' : 'on')
            return [[name: 'switch', value: v]]
        }
        nativePollDevice(device)

        when: 'gt 5 on the flapping non-numeric attribute'
        def r = poll([deviceId: '433', attribute: 'switch', comparator: 'gt', expectedValue: '5', timeoutMs: 200, pollIntervalMs: 50])

        then: 'nonNumericAttribute fires and supersedes transitioning (can-never-match, not still-settling)'
        r.converged == false
        r.timedOut == true
        r.nonNumericAttribute == true
        r.transitioning != true
    }

    // -------------------------------------------------------------------------
    // comparator validation -> IAE
    // -------------------------------------------------------------------------

    def "numeric comparator with expectedValues -> IAE naming the conflict"() {
        given:
        def device = new TestDevice(id: 440, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        nativePollDevice(device)

        when:
        poll([deviceId: '440', attribute: 'temperature', comparator: 'gt', expectedValues: ['72'], timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedValue')
        ex.message.contains('expectedValues')
    }

    def "between with !=2 bounds -> IAE"() {
        given:
        def device = new TestDevice(id: 441, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        nativePollDevice(device)

        when:
        poll([deviceId: '441', attribute: 'temperature', comparator: 'between', expectedValues: ['68', '70', '72'], timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('exactly 2')
    }

    def "between with expectedValue -> IAE"() {
        given:
        def device = new TestDevice(id: 442, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        nativePollDevice(device)

        when:
        poll([deviceId: '442', attribute: 'temperature', comparator: 'between', expectedValue: '70', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedValues')
    }

    def "numeric comparator with non-numeric threshold -> IAE"() {
        given:
        def device = new TestDevice(id: 443, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        nativePollDevice(device)

        when:
        poll([deviceId: '443', attribute: 'temperature', comparator: 'gt', expectedValue: 'warm', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    def "between with non-numeric bound -> IAE"() {
        given:
        def device = new TestDevice(id: 444, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        nativePollDevice(device)

        when:
        poll([deviceId: '444', attribute: 'temperature', comparator: 'between', expectedValues: ['68', 'hot'], timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    def "between with low > high -> IAE"() {
        given:
        def device = new TestDevice(id: 445, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        nativePollDevice(device)

        when:
        poll([deviceId: '445', attribute: 'temperature', comparator: 'between', expectedValues: ['72', '68'], timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('low <= high')
    }

    def "unknown comparator -> IAE listing the valid set"() {
        given:
        def device = new TestDevice(id: 446, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        nativePollDevice(device)

        when:
        poll([deviceId: '446', attribute: 'temperature', comparator: 'approx', expectedValue: '70', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('between')   // the valid set is enumerated
        ex.message.contains('approx')
    }

    def "comparator explicitly null -> IAE"() {
        given:
        def device = new TestDevice(id: 447, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        poll([deviceId: '447', attribute: 'switch', expectedValue: 'on', comparator: null, timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('comparator')
        ex.message.toLowerCase().contains('null')
    }

    // -------------------------------------------------------------------------
    // stableForMs (debounce)
    // -------------------------------------------------------------------------

    // 100 ms reads against a 250 ms deadline: after the first read 150 ms remain, so a sleep
    // followed by a second read would finish past the deadline.
    @IgnoreIf({ System.getProperty('harnessStrictMetaClass') == 'true' })  // virtual clock, as below
    def "a budgeted wait starts no read that cannot finish by the request deadline"() {
        given:
        def clock = installVirtualClock()
        int reads = 0
        hubGet.register('/device/fullJson/10') { Map params ->
            reads++
            clock[0] = clock[0] + 100
            groovy.json.JsonOutput.toJson([device: [id: 10, currentStates: [switch: [value: 'off']]]])
        }
        Map spec = script._waitForSpec([attribute: 'switch', expectedValue: 'on', timeoutMs: 5000], false, 60000, 200)

        when:
        Map r = script._waitForCore(spec, ['10'], false, null, 1_000_000L + 250)

        then:
        r.timedOut == true
        r.budgetStopped == true
        reads == 1
        clock[0] <= 1_000_000L + 250
    }

    // LOAD-BEARING GUARD (both-ways VERIFIED RED on revert, orchestrator): asserts the debounce
    // does NOT converge on the first matching poll -- it must hold for stableForMs
    // of virtual time first. If the debounce gate were removed (converge on first
    // match), polledCount would be 1 and elapsed < stableForMs.
    @IgnoreIf({ System.getProperty('harnessStrictMetaClass') == 'true' })  // virtual clock needs the pauseExecution metaClass override, which the strict-metaClass groovy2x lane disallows; full coverage runs in the primary test lanes
    def "stableForMs does not converge on the first matching poll; converges only after the window elapses"() {
        given: 'value is at target from the very first read'
        def device = new TestDevice(id: 450, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)
        def clock = installVirtualClock()

        when: 'stableForMs=300, pollIntervalMs=100 -> needs >=3 polls of held time before convergence'
        def r = poll([deviceId: '450', attribute: 'switch', expectedValue: 'on', stableForMs: 300, timeoutMs: 5000, pollIntervalMs: 100])

        then:
        r.converged == true
        !r.timedOut
        // Did NOT short-circuit on the first matching poll: at least the window of
        // virtual time had to pass, so multiple polls fired.
        r.polledCount > 1
        // The matched condition was held for at least stableForMs of virtual time.
        (clock[0] - 1_000_000L) >= 300
    }

    // LOAD-BEARING GUARD (both-ways VERIFIED RED on revert, orchestrator): a value that goes
    // true -> false -> true must RESET the stability window. If the reset
    // (conditionTrueSince = null on a false poll) were dropped, the run would
    // converge using stale accumulated true-time and finalValue could differ.
    @IgnoreIf({ System.getProperty('harnessStrictMetaClass') == 'true' })  // virtual clock needs the pauseExecution metaClass override, which the strict-metaClass groovy2x lane disallows; full coverage runs in the primary test lanes
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
        nativePollDevice(device)
        def clock = installVirtualClock()

        when: 'stableForMs=200, pollIntervalMs=100: the on-run only starts at poll 3'
        def r = poll([deviceId: '451', attribute: 'switch', expectedValue: 'on', stableForMs: 200, timeoutMs: 5000, pollIntervalMs: 100])

        then: 'converged, but only after the flap reset -- so the window started at poll 3, not poll 1'
        r.converged == true
        !r.timedOut
        // Poll 1 was on, poll 2 off (reset). The stable on-run begins at poll 3, needs
        // 200ms = 2 more poll intervals, so convergence cannot occur before poll 5.
        r.polledCount >= 5
    }

    def "stableForMs >= timeoutMs -> IAE pre-poll (could never converge)"() {
        given:
        def device = new TestDevice(id: 452, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        poll([deviceId: '452', attribute: 'switch', expectedValue: 'on', stableForMs: 5000, timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('stableforms')
        ex.message.contains('5000')
    }

    def "stableForMs negative -> IAE"() {
        given:
        def device = new TestDevice(id: 453, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        poll([deviceId: '453', attribute: 'switch', expectedValue: 'on', stableForMs: -1, timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('stableforms')
    }

    def "stableForMs explicitly null -> IAE"() {
        given:
        def device = new TestDevice(id: 454, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        poll([deviceId: '454', attribute: 'switch', expectedValue: 'on', stableForMs: null, timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('stableforms')
        ex.message.toLowerCase().contains('null')
    }

    def "stableForMs as a non-Number string -> IAE naming the number requirement"() {
        given: 'a numeric-looking String is still not a Number -- the engine requires an integer'
        def device = new TestDevice(id: 456, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        poll([deviceId: '456', attribute: 'switch', expectedValue: 'on', stableForMs: '300', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must be a number')
    }

    def "stableForMs of 0 converges on the first matching poll (default behavior)"() {
        given:
        def device = new TestDevice(id: 455, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '455', attribute: 'switch', expectedValue: 'on', stableForMs: 0, timeoutMs: 5000])

        then:
        r.converged == true
        r.polledCount == 1
    }

    // -------------------------------------------------------------------------
    // comparator + stableForMs combined
    // -------------------------------------------------------------------------

    @IgnoreIf({ System.getProperty('harnessStrictMetaClass') == 'true' })  // virtual clock needs the pauseExecution metaClass override, which the strict-metaClass groovy2x lane disallows; full coverage runs in the primary test lanes
    def "gte + stableForMs converges only after a numeric value holds above threshold for the window"() {
        given: 'temperature already at 73 (>= 72) from the first read'
        def device = new TestDevice(id: 460, label: 'Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        nativePollDevice(device)
        def clock = installVirtualClock()

        when:
        def r = poll([deviceId: '460', attribute: 'temperature', comparator: 'gte', expectedValue: '72', stableForMs: 200, timeoutMs: 5000, pollIntervalMs: 100])

        then:
        r.converged == true
        r.polledCount > 1
        (clock[0] - 1_000_000L) >= 200
    }

    // -------------------------------------------------------------------------
    // Engine rejects BOTH expectedValue + expectedValues (exactly-one, matching the
    // waitFor pre-fire path -- the two validators are kept identical)
    // -------------------------------------------------------------------------

    def "the validator rejects BOTH expectedValue and expectedValues (exactly-one semantics)"() {
        given:
        def device = new TestDevice(id: 470, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(device)

        when:
        poll([deviceId: '470', attribute: 'switch', expectedValue: 'on', expectedValues: ['off'], timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one of expectedValue or expectedValues')
    }

    // -------------------------------------------------------------------------
    // numeric parsing + match-shape edge cases
    // -------------------------------------------------------------------------

    def "numeric comparator parses a GString-typed value (CharSequence path)"() {
        given: 'a driver reports the value as a GString, not a plain String'
        def device = Spy(TestDevice)
        device.id = 480
        device.label = 'GString Temp'
        device.supportedAttributes = [[name: 'temperature']]
        def reading = 73
        device.getCurrentStates() >> [[name: 'temperature', value: "${reading}"]]   // GString value
        nativePollDevice(device)

        when: 'gt 50 -- _parseBigDecimalOrNull must accept the GString via its CharSequence branch'
        def r = poll([deviceId: '480', attribute: 'temperature', comparator: 'gt', expectedValue: '50', timeoutMs: 5000])

        then:
        r.converged == true
        !r.timedOut
    }

    def "eq numeric-string fallback matches a Number-typed attribute (50.0 vs \"50\")"() {
        given: 'attribute reports an actual Number 50.0; expectedValue is the string "50"'
        def device = Spy(TestDevice)
        device.id = 481
        device.label = 'Numeric Level'
        device.supportedAttributes = [[name: 'level']]
        device.getCurrentStates() >> [[name: 'level', value: new BigDecimal('50.0')]]
        nativePollDevice(device)

        when: 'eq "50" -- the Number-vs-numeric-string fallback must match 50.0 == 50'
        def r = poll([deviceId: '481', attribute: 'level', comparator: 'eq', expectedValue: '50', timeoutMs: 5000])

        then: 'the numeric-string fallback converges even though "50.0".toString() != "50"'
        r.converged == true
        !r.timedOut
        r.finalValue instanceof Number
        r.finalValue == new BigDecimal('50.0')
    }

    def "a converged (success) response omits the timeout-only transitioning and neverReported fields"() {
        given:
        def device = new TestDevice(id: 482, label: 'Switch', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '482', attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then: 'success shape is the lean one -- the honesty signals are TIMEOUT-only'
        r.converged == true
        !r.timedOut
        !r.containsKey('transitioning')
        !r.containsKey('neverReported')
        !r.containsKey('nonNumericAttribute')
        // The shared _evalAttrCondition refactor must NOT bleed multi-device fields into the
        // single-device response shape.
        !r.containsKey('mode')
        !r.containsKey('devices')
        !r.containsKey('convergedCount')
    }

    // =========================================================================
    // hub_call_device_command waitFor: the same validator, run before the command fires
    // =========================================================================

    @IgnoreIf({ System.getProperty('harnessStrictMetaClass') == 'true' })  // virtual clock needs the pauseExecution metaClass override, which the strict-metaClass groovy2x lane disallows; full coverage runs in the primary test lanes
    def "waitFor passes comparator + stableForMs through and converges via command flow"() {
        given:
        def fired = nativeCommandCalls
        def device = Spy(TestDevice)
        device.id = 500
        device.label = 'Cmd Temp'
        device.supportedAttributes = [[name: 'temperature']]
        device.supportedCommands = [[name: 'on']]
        device.getCurrentStates() >> [[name: 'temperature', value: '73']]
        device.invokeCommand(_, _) >> { throw new AssertionError("SDK command execution is forbidden") }
        nativePollDevice(device)
        def clock = installVirtualClock()

        when:
        def r = script.toolSendCommand('500', 'on', null, [attribute: 'temperature', comparator: 'gt', expectedValue: '72', stableForMs: 100, timeoutMs: 5000, pollIntervalMs: 100])

        then:
        fired == ['on']
        r.success == true
        r.waitFor.converged == true
        r.waitFor.attribute == 'temperature'
    }

    def "waitFor on an unreported name runs the native command then reports neverReported"() {
        given:
        def device = Spy(TestDevice)
        device.id = 507
        device.label = 'Reported States Only'
        device.supportedCommands = [[name: 'on']]
        device.attributeValues = [switch: 'on']
        device.getSupportedAttributes() >> { throw new AssertionError('SDK declaration reads are forbidden') }
        device.invokeCommand(_, _) >> { throw new AssertionError('SDK command execution is forbidden') }
        nativePollDevice(device)

        when:
        def result = script.toolSendCommand('507', 'on', null,
            [attribute: 'unreportedName', expectedValue: 'ready', timeoutMs: 100, pollIntervalMs: 50])

        then:
        nativeCommandCalls == ['on']
        result.success == true
        result.waitFor.converged == false
        result.waitFor.timedOut == true
        result.waitFor.neverReported == true
        result.waitFor.finalValue == null
        !result.waitFor.readError
    }

    def "waitFor numeric comparator with expectedValues -> IAE BEFORE the command fires"() {
        given:
        def fired = nativeCommandCalls
        def device = Spy(TestDevice)
        device.id = 501
        device.label = 'Cmd NoFire'
        device.supportedAttributes = [[name: 'temperature']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { throw new AssertionError("SDK command execution is forbidden") }
        nativePollDevice(device)

        when:
        script.toolSendCommand('501', 'on', null, [attribute: 'temperature', comparator: 'gt', expectedValues: ['72'], timeoutMs: 5000])

        then: 'bad spec rejected pre-fire -- the command never actuated the device'
        thrown(IllegalArgumentException)
        fired == []
    }

    def "waitFor between with wrong bound count -> IAE before command fires"() {
        given:
        def fired = nativeCommandCalls
        def device = Spy(TestDevice)
        device.id = 502
        device.label = 'Cmd NoFire 2'
        device.supportedAttributes = [[name: 'temperature']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { throw new AssertionError("SDK command execution is forbidden") }
        nativePollDevice(device)

        when:
        script.toolSendCommand('502', 'on', null, [attribute: 'temperature', comparator: 'between', expectedValues: ['68'], timeoutMs: 5000])

        then:
        thrown(IllegalArgumentException)
        fired == []
    }

    def "waitFor stableForMs >= timeoutMs -> IAE before command fires"() {
        given:
        def fired = nativeCommandCalls
        def device = Spy(TestDevice)
        device.id = 503
        device.label = 'Cmd NoFire 3'
        device.supportedAttributes = [[name: 'switch']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { throw new AssertionError("SDK command execution is forbidden") }
        nativePollDevice(device)

        when:
        script.toolSendCommand('503', 'on', null, [attribute: 'switch', expectedValue: 'on', stableForMs: 5000, timeoutMs: 5000])

        then:
        thrown(IllegalArgumentException)
        fired == []
    }

    def "waitFor stableForMs >= the defaulted 5000 timeout (timeoutMs omitted) -> IAE before command fires"() {
        given: 'no timeoutMs key -- exercises the effectiveTimeoutMs : 5000 default branch'
        def fired = nativeCommandCalls
        def device = Spy(TestDevice)
        device.id = 506
        device.label = 'Cmd NoFire 6'
        device.supportedAttributes = [[name: 'level']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { throw new AssertionError("SDK command execution is forbidden") }
        nativePollDevice(device)

        when:
        script.toolSendCommand('506', 'on', null, [attribute: 'level', expectedValue: '50', stableForMs: 6000])

        then: 'the message names timeoutMs (5000), proving the 5000 default was applied (not an explicit value)'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('less than timeoutMs (5000)')
        fired == []
    }

    def "waitFor explicit null comparator -> IAE naming comparator before command fires"() {
        given:
        def fired = nativeCommandCalls
        def device = Spy(TestDevice)
        device.id = 504
        device.label = 'Cmd NoFire 4'
        device.supportedAttributes = [[name: 'switch']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { throw new AssertionError("SDK command execution is forbidden") }
        nativePollDevice(device)

        when:
        script.toolSendCommand('504', 'on', null, [attribute: 'switch', expectedValue: 'on', comparator: null])

        then: 'pre-fire check names the key and the null it got -- the command never actuated'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor.comparator must be one of')
        ex.message.contains('got: null')
        fired == []
    }

    def "waitFor explicit null stableForMs -> IAE naming stableForMs before command fires"() {
        given:
        def fired = nativeCommandCalls
        def device = Spy(TestDevice)
        device.id = 505
        device.label = 'Cmd NoFire 5'
        device.supportedAttributes = [[name: 'switch']]
        device.supportedCommands = [[name: 'on']]
        device.invokeCommand(_, _) >> { throw new AssertionError("SDK command execution is forbidden") }
        nativePollDevice(device)

        when:
        script.toolSendCommand('505', 'on', null, [attribute: 'switch', expectedValue: 'on', stableForMs: null])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor.stableForMs must be a number')
        ex.message.contains('got: null')
        fired == []
    }

    // =========================================================================
    // Dispatch-envelope: comparator + stableForMs inside waitFor via executeTool
    // =========================================================================

    @spock.lang.Unroll
    def "hub_get_device waitFor with a comparator converges through dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 1100, label: 'Dispatch Temp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        nativePollDevice(device)

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '1100',
            waitFor: [attribute: 'temperature', comparator: 'gt', expectedValue: '72', timeoutMs: 5000]])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.waitFor.converged == true
        !inner.waitFor.timedOut

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_device waitFor with a bad comparator returns isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 1101, label: 'Dispatch Bad Cmp', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '73'])
        nativePollDevice(device)

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '1101',
            waitFor: [attribute: 'temperature', comparator: 'approx', expectedValue: '72']])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('approx')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_device waitFor with only stableForMs (no expectedValue) is an isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 1102, label: 'Dispatch Stable Only', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '1102', waitFor: [attribute: 'switch', stableForMs: 100]])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('expectedvalue')

        where:
        useGateways << [true, false]
    }

    // =========================================================================
    // Multi-device convergence: deviceIds + mode (any/all)
    // =========================================================================

    // LOAD-BEARING GUARD (both-ways pending): mode:all must NOT converge while any device
    // still fails the condition. Two devices, one at target and one not -- all-mode times out.
    def "mode all converges only when EVERY device matches"() {
        given: 'A at on (matches), B at off (does not) -- all-mode cannot converge'
        def a = new TestDevice(id: 600, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        def b = new TestDevice(id: 601, label: 'B', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(a)
        nativePollDevice(b)

        when:
        def r = poll([deviceIds: ['600', '601'], attribute: 'switch', expectedValue: 'on', mode: 'all', timeoutMs: 100, pollIntervalMs: 50])

        then: 'B never matches, so all-mode times out; the per-device array reports both states'
        r.converged == false
        r.timedOut == true
        r.mode == 'all'
        r.convergedCount == 1
        r.devices.size() == 2
        // Positional pin: devices[i] aligns with the input deviceIds order (devices[i] <-> deviceIdList[i]).
        r.devices*.deviceId == ['600', '601']
        r.devices.find { it.deviceId == '600' }.matched == true
        r.devices.find { it.deviceId == '601' }.matched == false
    }

    def "mode all converges when both devices match"() {
        given: 'both at on'
        def a = new TestDevice(id: 602, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        def b = new TestDevice(id: 603, label: 'B', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(a)
        nativePollDevice(b)

        when:
        def r = poll([deviceIds: ['602', '603'], attribute: 'switch', expectedValue: 'on', mode: 'all', timeoutMs: 5000])

        then:
        r.converged == true
        !r.timedOut
        r.convergedCount == 2
        r.devices.every { it.matched == true }
    }

    // LOAD-BEARING GUARD (both-ways pending): mode:any must converge as soon as ONE device
    // matches, even while others do not. If the predicate were ALL by mistake, this would time out.
    def "mode any converges on the first device to match"() {
        given: 'A matches, B does not -- any-mode converges'
        def a = new TestDevice(id: 604, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        def b = new TestDevice(id: 605, label: 'B', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(a)
        nativePollDevice(b)

        when:
        def r = poll([deviceIds: ['604', '605'], attribute: 'switch', expectedValue: 'on', mode: 'any', timeoutMs: 5000])

        then:
        r.converged == true
        !r.timedOut
        r.mode == 'any'
        r.convergedCount == 1
    }

    def "mode defaults to all when omitted with deviceIds"() {
        given: 'one device off -> default all-mode cannot converge'
        def a = new TestDevice(id: 606, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        def b = new TestDevice(id: 607, label: 'B', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(a)
        nativePollDevice(b)

        when:
        def r = poll([deviceIds: ['606', '607'], attribute: 'switch', expectedValue: 'on', timeoutMs: 100, pollIntervalMs: 50])

        then: 'default all-mode -> B keeps it from converging'
        r.converged == false
        r.mode == 'all'
    }

    def "device-count cap rejects more than 20 deviceIds -> IAE naming the cap and the count"() {
        given:
        (700..722).each {
            nativePollDevice(new TestDevice(id: it, label: "D${it}",
                supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on']))
        }
        def ids = (700..720).collect { it.toString() }   // 21 devices

        when:
        poll([deviceIds: ids, attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor requires deviceIds with 1 to 20 devices')
    }

    def "a missing id in deviceIds -> IAE naming WHICH id"() {
        given: 'only 731 exists; 999 is absent'
        def a = new TestDevice(id: 731, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(a)

        when:
        poll([deviceIds: ['731', '999'], attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then: 'refused before waiting: the scope read is the only request'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("deviceIds not in scope 'authorized': [999]")
        hubGet.calls*.path == ['/hub2/devicesList']
    }

    def "an unreported attribute on one device times out with per-device neverReported"() {
        given: 'A has switch, B (the named one) does not'
        def a = new TestDevice(id: 732, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        def b = new TestDevice(id: 733, label: 'No Switch B', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        nativePollDevice(a)
        nativePollDevice(b)

        when:
        def result = poll([
            deviceIds: ['732', '733'], attribute: 'switch', expectedValue: 'on',
            mode: 'all', timeoutMs: 100, pollIntervalMs: 50
        ])

        then:
        result.converged == false
        result.timedOut == true
        result.convergedCount == 1
        def reported = result.devices.find { it.deviceId == '732' }
        def unreported = result.devices.find { it.deviceId == '733' }
        reported.matched == true
        reported.finalValue == 'on'
        !reported.neverReported
        unreported.matched == false
        unreported.finalValue == null
        unreported.neverReported == true
        result.devices.every { !it.readError }
    }

    def "mode in a single-device wait -> IAE (mode applies only to hub_list_devices)"() {
        given:
        def a = new TestDevice(id: 734, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(a)

        when:
        poll([deviceId: '734', attribute: 'switch', expectedValue: 'on', mode: 'any', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor has an unknown key: mode')
    }

    def "invalid mode value -> IAE listing [any, all]"() {
        given:
        def a = new TestDevice(id: 735, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(a)

        when:
        poll([deviceIds: ['735'], attribute: 'switch', expectedValue: 'on', mode: 'either', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('any')
        ex.message.contains('all')
        ex.message.contains('either')
    }

    def "mode explicitly null -> IAE"() {
        given:
        def a = new TestDevice(id: 736, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(a)

        when:
        poll([deviceIds: ['736'], attribute: 'switch', expectedValue: 'on', mode: null, timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('mode')
        ex.message.toLowerCase().contains('null')
    }

    def "deviceIds explicitly null -> IAE"() {
        when:
        poll([deviceIds: null, attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor requires deviceIds')
    }

    def "empty deviceIds list -> IAE"() {
        when:
        poll([deviceIds: [], attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceIds')
    }

    def "hub_list_devices waitFor without deviceIds -> IAE"() {
        when:
        script.toolListDevices([waitFor: [attribute: 'switch', expectedValue: 'on']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor requires deviceIds with 1 to 20 devices')
        hubGet.calls.empty
    }

    def "a non-string, non-numeric element in deviceIds -> IAE naming the index and the value"() {
        when: 'a boolean is neither a device id nor coercible to one'
        poll([deviceIds: ['100', true], attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceIds[1]')
        ex.message.contains('true (boolean)')
    }

    def "a NUMERIC deviceIds element is coerced to its string form, not rejected"() {
        given: 'a hub device id IS a number, so a caller that sends JSON numbers is not wrong'
        def a = new TestDevice(id: 744, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(a)

        when:
        def r = poll([deviceIds: [744], attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then: 'it resolved, and reports the id in the string form the rest of the engine compares on'
        r.converged == true
        r.devices*.deviceId == ['744']
    }

    def "deviceIds sees 745 and '745' as one device and rejects the pair as a duplicate"() {
        when: 'the coercion runs BEFORE the duplicate check, so the two spellings collide'
        poll([deviceIds: [745, '745'], attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then: 'caught as a duplicate rather than double-counting one device in convergedCount'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('duplicate entries')
        ex.message.contains('745')
    }

    def "a NUMERIC single deviceId is coerced too, so the two forms accept the same ids"() {
        given: 'the single branch does the same coercion as the deviceIds branch above'
        def a = new TestDevice(id: 746, label: 'Numeric Id', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(a)

        when:
        def numeric = poll([deviceId: 746, attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])
        def strung = poll([deviceId: '746', attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then: 'it resolved and converged -- an unresolved id throws Device not found, so this is the proof'
        numeric.converged == true
        numeric.finalValue == 'on'

        and: 'the two spellings of the same id agree'
        numeric.converged == strung.converged
        numeric.finalValue == strung.finalValue
    }

    def "a non-string, non-numeric single deviceId -> IAE naming the accepted forms"() {
        when: 'a boolean is neither a device id nor coercible to one'
        poll([deviceId: true, attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceId must be a non-empty string or integral number')
    }

    def "per-device neverReported and nonNumericAttribute surface on a multi-device timeout"() {
        given: 'A never reports temperature; B reports a non-numeric value the whole window'
        def a = new TestDevice(id: 740, label: 'Never', supportedAttributes: [[name: 'temperature']], attributeValues: [:])
        def b = new TestDevice(id: 741, label: 'NonNumeric', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: 'warm'])
        nativePollDevice(a)
        nativePollDevice(b)

        when: 'gt 50 (numeric comparator), all-mode -- neither device can match'
        def r = poll([deviceIds: ['740', '741'], attribute: 'temperature', comparator: 'gt', expectedValue: '50', mode: 'all', timeoutMs: 100, pollIntervalMs: 50])

        then:
        r.converged == false
        r.timedOut == true
        def da = r.devices.find { it.deviceId == '740' }
        def db = r.devices.find { it.deviceId == '741' }
        da.neverReported == true
        !da.containsKey('nonNumericAttribute')
        db.nonNumericAttribute == true
        !db.containsKey('neverReported')
        r.note?.contains('can never match')
    }

    // LOAD-BEARING GUARD (both-ways pending): the stableForMs window debounces the AGGREGATE
    // predicate. An all-mode poll where one device flaps out of the condition must RESTART the
    // window -- the whole any/all condition has to hold continuously, not just at the final poll.
    @IgnoreIf({ System.getProperty('harnessStrictMetaClass') == 'true' })  // virtual clock needs the pauseExecution metaClass override, which the strict-metaClass groovy2x lane disallows; full coverage runs in the primary test lanes
    def "stableForMs debounces the aggregate predicate -- an all-mode device that flaps restarts the window"() {
        given: 'A holds on; B reads on (poll1), off (poll2 -> aggregate false, reset), on from poll3'
        def a = new TestDevice(id: 742, label: 'Steady', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        def readCount = 0
        def b = Spy(TestDevice)
        b.id = 743
        b.label = 'Flapping B'
        b.supportedAttributes = [[name: 'switch']]
        b.getCurrentStates() >> {
            readCount++
            def v = (readCount == 1) ? 'on' : (readCount == 2 ? 'off' : 'on')
            return [[name: 'switch', value: v]]
        }
        nativePollDevice(a)
        nativePollDevice(b)
        def clock = installVirtualClock()

        when: 'all-mode, stableForMs=200, pollIntervalMs=100: the stable aggregate run begins only at poll 3'
        def r = poll([deviceIds: ['742', '743'], attribute: 'switch', expectedValue: 'on', mode: 'all', stableForMs: 200, timeoutMs: 5000, pollIntervalMs: 100])

        then: 'B off at poll 2 resets the aggregate window -- convergence cannot occur before poll 5'
        r.converged == true
        !r.timedOut
        r.polledCount >= 5
    }

    // LOAD-BEARING GUARD (both-ways pending): the multi-device timeout must surface aggregate
    // transitioning=true when at least one device is still MOVING (>=2 distinct numeric reads)
    // under a numeric comparator it never satisfies -- distinct from a stable non-target. The
    // companion stable device asserts transitioning is the aggregate (any-device-moving), not
    // per-device. (The lone prior multi-device timeout spec used static values, so the
    // anyTransitioning branch was untested.)
    @IgnoreIf({ System.getProperty('harnessStrictMetaClass') == 'true' })  // virtual clock needs the pauseExecution metaClass override, which the strict-metaClass groovy2x lane disallows; full coverage runs in the primary test lanes
    def "multi-device timeout flags aggregate transitioning when one device is still moving (numeric)"() {
        given: 'A stable at 30 (a real non-target); B moves 40 -> 45 across polls -- both < gt 50'
        def a = new TestDevice(id: 760, label: 'Stable NonTarget', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '30'])
        def readCount = 0
        def b = Spy(TestDevice)
        b.id = 761
        b.label = 'Moving B'
        b.supportedAttributes = [[name: 'temperature']]
        b.getCurrentStates() >> {
            readCount++
            // Two distinct numeric reads (sawChange=true), neither satisfies gt 50, both numeric
            // (so it is still-settling, NOT nonNumericAttribute).
            def v = (readCount == 1) ? '40' : '45'
            return [[name: 'temperature', value: v]]
        }
        nativePollDevice(a)
        nativePollDevice(b)
        installVirtualClock()

        when: 'all-mode gt 50 -- never converges; B keeps moving across polls'
        def r = poll([deviceIds: ['760', '761'], attribute: 'temperature', comparator: 'gt', expectedValue: '50', mode: 'all', timeoutMs: 500, pollIntervalMs: 100])

        then: 'aggregate transitioning fires (B moved); no nonNumericAttribute (both reads numeric)'
        r.converged == false
        r.timedOut == true
        r.transitioning == true
        def db = r.devices.find { it.deviceId == '761' }
        !db.containsKey('nonNumericAttribute')
        !r.containsKey('note')
    }

    def "multi-device timeout reports transitioning false when every device is a stable non-target"() {
        given: 'both stable below the threshold -- no movement across polls'
        def a = new TestDevice(id: 762, label: 'Stable A', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '30'])
        def b = new TestDevice(id: 763, label: 'Stable B', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '35'])
        nativePollDevice(a)
        nativePollDevice(b)

        when: 'all-mode gt 50 -- stable non-targets, neither moves'
        def r = poll([deviceIds: ['762', '763'], attribute: 'temperature', comparator: 'gt', expectedValue: '50', mode: 'all', timeoutMs: 100, pollIntervalMs: 50])

        then: 'no device moved -> transitioning is false (a real mismatch, not still-settling)'
        r.converged == false
        r.timedOut == true
        r.transitioning == false
    }

    def "multi-device poll interrupted by hub reload returns the per-device shape with convergedCount"() {
        given: 'two devices not yet matching, so the poll reaches sleep; pauseExecution throws'
        def a = new TestDevice(id: 764, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        def b = new TestDevice(id: 765, label: 'B', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(a)
        nativePollDevice(b)
        script.metaClass.pauseExecution = { long ms -> throw new InterruptedException("hub reloading") }

        when:
        def r = poll([deviceIds: ['764', '765'], attribute: 'switch', expectedValue: 'on', mode: 'all', timeoutMs: 5000, pollIntervalMs: 200])

        then: 'interrupted shape: per-device array, convergedCount present, no timedOut'
        r.converged == false
        r.interrupted == true
        r.mode == 'all'
        r.devices.size() == 2
        r.convergedCount == 1
        !r.containsKey('timedOut')
    }

    // LOAD-BEARING GUARD (both-ways pending): any-mode with NO device matching must time out,
    // success==false, convergedCount==0. Pins the any predicate at convergedCount > 0 -- a
    // regression to >= 0 would falsely converge here on the first poll.
    def "mode any with NO device matching times out (convergedCount 0, no false-converge)"() {
        given: 'both off; expecting on -> any-mode has zero matches'
        def a = new TestDevice(id: 608, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        def b = new TestDevice(id: 609, label: 'B', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(a)
        nativePollDevice(b)

        when:
        def r = poll([deviceIds: ['608', '609'], attribute: 'switch', expectedValue: 'on', mode: 'any', timeoutMs: 100, pollIntervalMs: 50])

        then: 'no match -> any-mode times out, never false-converges'
        r.converged == false
        r.timedOut == true
        r.mode == 'any'
        r.convergedCount == 0
        r.devices.every { !it.matched }
    }

    // LOAD-BEARING GUARD (both-ways pending): a duplicate id in deviceIds must be rejected up
    // front -- a dup would double-count convergedCount and emit duplicate devices[] rows.
    def "duplicate deviceIds -> IAE naming the duplicate(s)"() {
        given:
        def a = new TestDevice(id: 744, label: 'A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(a)

        when:
        poll([deviceIds: ['744', '744'], attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('duplicate')
        ex.message.contains('744')
    }

    @spock.lang.Unroll
    def "hub_list_devices deviceIds + waitFor converges through dispatch and lists the devices from the last check (useGateways=#useGateways)"() {
        given: 'B turns on at its second read'
        settingsMap.useGateways = useGateways
        int bReads = 0
        def a = new TestDevice(id: 1110, label: 'Disp A', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        def b = Spy(TestDevice)
        b.id = 1111
        b.label = 'Disp B'
        b.getCurrentStates() >> { [[name: 'switch', value: ++bReads == 1 ? 'off' : 'on']] }
        nativePollDevice(a)
        nativePollDevice(b)

        when:
        def response = mcpDriver.callTool('hub_list_devices', [deviceIds: ['1110', 1111],
            waitFor: [attribute: 'switch', expectedValue: 'on', mode: 'all', timeoutMs: 5000]])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.devices*.id == ['1110', '1111']
        inner.devices.find { it.id == '1111' }.currentStates.switch == 'on'
        inner.waitFor.attribute == 'switch'
        inner.waitFor.expected == 'on'
        inner.waitFor.mode == 'all'
        inner.waitFor.converged == true
        inner.waitFor.convergedCount == 2
        inner.waitFor.polledCount == 2
        inner.waitFor.devices == [[deviceId: '1110', finalValue: 'on', matched: true], [deviceId: '1111', finalValue: 'on', matched: true]]

        where:
        useGateways << [true, false]
    }

    def "a multi-device wait reads the Devices page once per check and never per device"() {
        given: 'three devices that stay off for the whole window'
        (1120..1122).each { nativePollDevice(new TestDevice(id: it, label: "Off ${it}", attributeValues: [switch: 'off'])) }

        when:
        def r = poll([deviceIds: ['1120', '1121', '1122'], attribute: 'switch', expectedValue: 'on', timeoutMs: 200, pollIntervalMs: 50])

        then: 'the scope read is the first check; every later check is one page read'
        r.converged == false
        r.timedOut == true
        r.polledCount > 1
        hubGet.calls.count { it.path == '/hub2/devicesList' } == r.polledCount
        !hubGet.calls.any { it.path.startsWith('/device/fullJson/') }
    }

    def "a multi-device wait whose Devices page read fails on one check flags readError and keeps waiting"() {
        given: 'the second page read fails; the third shows the device on'
        int pageReads = 0
        def device = new TestDevice(id: 1123, label: 'Flaky Page', attributeValues: [switch: 'off'])
        nativePollDevice(device)
        hubGet.register('/hub2/devicesList') {
            pageReads++
            if (pageReads == 2) throw new IOException('page read failed')
            device.attributeValues = [switch: pageReads >= 3 ? 'on' : 'off']
            JsonOutput.toJson([devices: [NativeInventoryFixture.pageNode(device)]])
        }

        when:
        def r = poll([deviceIds: ['1123'], attribute: 'switch', expectedValue: 'on', timeoutMs: 5000, pollIntervalMs: 50])

        then:
        r.converged == true
        r.readError == true
        r.devices[0].readError == true
        r.devices[0].finalValue == 'on'
        r.polledCount == 3
    }

    def "deviceIds without waitFor filters the listing and names ids outside the scope"() {
        given:
        (1130..1132).each { nativePollDevice(new TestDevice(id: it, label: "Dev ${it}", attributeValues: [switch: 'on'])) }

        when:
        def r = script.toolListDevices([deviceIds: [1132, '1130', '4040']])

        then: 'page order, integer ids accepted, the unknown id reported rather than dropped'
        r.devices*.id == ['1130', '1132']
        r.total == 2
        r.unfilteredTotal == 3
        r.deviceIdsNotFound == ['4040']
        !r.containsKey('waitFor')
        hubGet.calls*.path == ['/hub2/devicesList']
    }

    def "deviceIds combines with the other filters before paging"() {
        given:
        nativePollDevice(new TestDevice(id: 1133, label: 'Kitchen Lamp', attributeValues: [switch: 'on']))
        nativePollDevice(new TestDevice(id: 1134, label: 'Hall Lamp', attributeValues: [switch: 'on']))
        nativePollDevice(new TestDevice(id: 1135, label: 'Kitchen Fan', attributeValues: [switch: 'on']))

        when:
        def r = script.toolListDevices([deviceIds: ['1133', '1134'], labelFilter: 'kitchen', limit: 1])

        then:
        r.devices*.id == ['1133']
        r.total == 1
        r.hasMore == false
    }

    def "deviceIds with an empty scope reports every id as not found"() {
        when:
        def r = script.toolListDevices([deviceIds: ['1136']])

        then:
        r.devices == []
        r.deviceIdsNotFound == ['1136']
        hubGet.calls.empty
    }

    @spock.lang.Unroll
    def "hub_list_devices refuses #scenario before any read"() {
        given:
        nativePollDevice(new TestDevice(id: 1137, label: 'Refusal', attributeValues: [switch: 'on']))

        when:
        script.toolListDevices(args)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expected)
        hubGet.calls.empty

        where:
        scenario                 | args                                                                                         || expected
        'waitFor with a cursor'  | [deviceIds: ['1137'], cursor: '1', waitFor: [attribute: 'switch', expectedValue: 'on']]      || 'waitFor cannot be combined with cursor'
        "waitFor with scope=all" | [deviceIds: ['1137'], scope: 'all', waitFor: [attribute: 'switch', expectedValue: 'on']]     || "scope 'authorized' or 'owned'"
        'a non-array deviceIds'  | [deviceIds: '1137']                                                                          || 'deviceIds must be a non-empty array'
        'an unknown waitFor key' | [deviceIds: ['1137'], waitFor: [attribute: 'switch', expectedValue: 'on', timeout: 5]]       || 'waitFor has an unknown key: timeout'
    }

    def "a waitFor on an empty scope is refused before any read"() {
        given:
        settingsMap.selectedDevices = []
        childDevicesList.clear()

        when:
        script.toolListDevices([deviceIds: ['1138'], waitFor: [attribute: 'switch', expectedValue: 'on']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("deviceIds not in scope 'authorized': [1138]")
        hubGet.calls.empty
    }

    def "hub_get_device refuses a waitFor with cursor"() {
        given:
        nativePollDevice(new TestDevice(id: 1139, label: 'Cursor', attributeValues: [switch: 'on']))

        when:
        script.toolGetDevice('1139', 'details', null, null, 'v2:00000000-0000-0000-0000-000000000000:0', [attribute: 'switch', expectedValue: 'on'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor applies to summary mode only')
        hubGet.calls.empty
    }

    // A client that echoes a declared default would start a wait nobody asked for, so the waitFor
    // sub-schemas state their defaults in description text only.
    @spock.lang.Unroll
    def "#tool waitFor declares no schema default and requires only attribute"() {
        when:
        def schema = script.getAllToolDefinitions().find { it.name == tool }.inputSchema.properties.waitFor

        then:
        schema.type == 'object'
        schema.required == ['attribute']
        !groovy.json.JsonOutput.toJson(schema).contains('"default"')
        schema.properties.keySet() == keys as Set
        schema.properties.comparator.enum == ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'between']
        schema.properties.timeoutMs.maximum == 60000

        where:
        tool               | keys
        'hub_get_device'   | ['attribute', 'expectedValue', 'expectedValues', 'comparator', 'stableForMs', 'timeoutMs', 'pollIntervalMs']
        'hub_list_devices' | ['attribute', 'expectedValue', 'expectedValues', 'comparator', 'stableForMs', 'timeoutMs', 'pollIntervalMs', 'mode']
    }

    @spock.lang.Unroll
    def "hub_get_device refuses a #scenario deviceId in #mode mode before any read"() {
        when:
        script.toolGetDevice(id, mode)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceId must be a non-empty string or integral number')
        hubGet.calls.empty

        where:
        scenario     | id  | mode
        'fractional' | 1.5 | 'summary'
        'empty'      | ''  | 'configuration'
        'blank'      | ' ' | 'details'
    }

    def "deviceIdsNotFound and waitFor rows carry string ids in the caller's order"() {
        given:
        nativePollDevice(new TestDevice(id: 1140, label: 'Order A', attributeValues: [switch: 'on']))
        nativePollDevice(new TestDevice(id: 1141, label: 'Order B', attributeValues: [switch: 'on']))

        when:
        def listed = script.toolListDevices([deviceIds: [9002, '1141', 9001, 1140]])
        def waited = poll([deviceIds: [1141, '1140'], attribute: 'switch', expectedValue: 'on'])

        then:
        listed.deviceIdsNotFound == ['9002', '9001']
        waited.devices*.deviceId == ['1141', '1140']
    }

    def "hub_list_devices declares deviceIds as string or integer ids"() {
        expect:
        script.getAllToolDefinitions().find { it.name == 'hub_list_devices' }.inputSchema.properties.deviceIds.items.type == ['string', 'integer']
    }

    // =========================================================================
    // Multi-device + non-eq/gt comparators: between, ne, multi-member expectedValues
    // (the aggregate path runs the shared _evalAttrCondition for every comparator).
    // =========================================================================

    def "mode all + between does NOT converge when one device is out of range (per-device matched flags)"() {
        given: 'A at 70 (inside [68,72]), B at 75 (outside) -- all-mode cannot converge'
        def a = new TestDevice(id: 770, label: 'In Range', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '70'])
        def b = new TestDevice(id: 771, label: 'Out Of Range', supportedAttributes: [[name: 'temperature']], attributeValues: [temperature: '75'])
        nativePollDevice(a)
        nativePollDevice(b)

        when:
        def r = poll([deviceIds: ['770', '771'], attribute: 'temperature', comparator: 'between', expectedValues: ['68', '72'], mode: 'all', timeoutMs: 100, pollIntervalMs: 50])

        then: 'B out of range keeps all-mode from converging; the in-range device still counts as 1 (partial-convergence count)'
        r.converged == false
        r.timedOut == true
        r.mode == 'all'
        r.convergedCount == 1
        r.devices.find { it.deviceId == '770' }.matched == true
        r.devices.find { it.deviceId == '771' }.matched == false
    }

    def "mode any + ne converges when one device's value is NOT in the set"() {
        given: 'A locked (in set -> not-ne), B unlocked (NOT in set -> satisfies ne) -- any-mode converges via B'
        def a = new TestDevice(id: 772, label: 'A Locked', supportedAttributes: [[name: 'lock']], attributeValues: [lock: 'locked'])
        def b = new TestDevice(id: 773, label: 'B Unlocked', supportedAttributes: [[name: 'lock']], attributeValues: [lock: 'unlocked'])
        nativePollDevice(a)
        nativePollDevice(b)

        when:
        def r = poll([deviceIds: ['772', '773'], attribute: 'lock', comparator: 'ne', expectedValue: 'locked', mode: 'any', timeoutMs: 5000])

        then: 'B is not-in-set so ne is satisfied -> any-mode converges; A still does not match'
        r.converged == true
        !r.timedOut
        r.mode == 'any'
        r.convergedCount == 1
        r.devices.find { it.deviceId == '772' }.matched == false
        r.devices.find { it.deviceId == '773' }.matched == true
    }

    def "mode all + multi-member expectedValues OR-set converges when each device matches a (possibly different) set member"() {
        given: 'A reads heat, B reads cool -- the eq OR-set {heat, cool} matches each'
        def a = new TestDevice(id: 774, label: 'A Heat', supportedAttributes: [[name: 'thermostatMode']], attributeValues: [thermostatMode: 'heat'])
        def b = new TestDevice(id: 775, label: 'B Cool', supportedAttributes: [[name: 'thermostatMode']], attributeValues: [thermostatMode: 'cool'])
        nativePollDevice(a)
        nativePollDevice(b)

        when: 'eq {heat, cool}: each device matches a different member -> all-mode converges'
        def r = poll([deviceIds: ['774', '775'], attribute: 'thermostatMode', expectedValues: ['heat', 'cool'], mode: 'all', timeoutMs: 5000])

        then:
        r.converged == true
        !r.timedOut
        r.mode == 'all'
        r.convergedCount == 2
        r.devices.every { it.matched == true }
        // Negative pin: a clean converge with no read fault must NOT emit readError on any device.
        r.devices.every { !it.containsKey('readError') }
    }

    // =========================================================================
    // A device missing from a later check degrades that device, does NOT abort the wait
    // (regression guard for the per-device readError flag).
    // =========================================================================

    // LOAD-BEARING GUARD (both-ways pending): a device that drops off the Devices page mid-wait must
    // degrade to matched:false + readError for that device, while the OTHER device's converged state
    // is still returned -- the wait must NOT error out and discard everyone.
    def "multi-device: a device that drops off the page degrades to readError while the other device still converges"() {
        given: 'both read off on the scope read; A is then gone while B turns on'
        int pageReads = 0
        def a = new TestDevice(id: 780, label: 'Faulting A', attributeValues: [switch: 'off'])
        def b = new TestDevice(id: 781, label: 'Healthy B', attributeValues: [switch: 'off'])
        nativePollDevice(a)
        nativePollDevice(b)
        hubGet.register('/hub2/devicesList') {
            if (++pageReads > 1) b.attributeValues = [switch: 'on']
            JsonOutput.toJson([devices: (pageReads == 1 ? [a, b] : [b]).collect { NativeInventoryFixture.pageNode(it) }])
        }

        when: 'any-mode: B matches, so the aggregate converges despite A missing'
        def r = poll([deviceIds: ['780', '781'], attribute: 'switch', expectedValue: 'on', mode: 'any', timeoutMs: 5000])

        then: 'the wait did NOT throw; B converged; A is degraded (matched:false + readError) not lost'
        r.converged == true
        !r.timedOut
        r.mode == 'any'
        r.convergedCount == 1
        def da = r.devices.find { it.deviceId == '780' }
        def db = r.devices.find { it.deviceId == '781' }
        da.matched == false
        da.readError == true
        db.matched == true
        !db.containsKey('readError')
    }

    def "multi-device timeout: a missing device reports readError per-device; a healthy non-target is unaffected"() {
        given: 'A is gone after the scope read; B remains off -- all-mode times out'
        int pageReads = 0
        def a = new TestDevice(id: 782, label: 'Faulting A', attributeValues: [switch: 'off'])
        def b = new TestDevice(id: 783, label: 'NonTarget B', attributeValues: [switch: 'off'])
        nativePollDevice(a)
        nativePollDevice(b)
        hubGet.register('/hub2/devicesList') {
            JsonOutput.toJson([devices: (++pageReads == 1 ? [a, b] : [b]).collect { NativeInventoryFixture.pageNode(it) }])
        }

        when:
        def r = poll([deviceIds: ['782', '783'], attribute: 'switch', expectedValue: 'on', mode: 'all', timeoutMs: 100, pollIntervalMs: 50])

        then: 'timed out, returned the per-device shape (not thrown); A flagged readError, B not'
        r.converged == false
        r.timedOut == true
        r.devices.size() == 2
        def da = r.devices.find { it.deviceId == '782' }
        def db = r.devices.find { it.deviceId == '783' }
        da.readError == true
        da.matched == false
        !db.containsKey('readError')
        db.matched == false
    }

    // LOAD-BEARING GUARD (both-ways pending): the SINGLE-device path -- a read that fails mid-poll must degrade to an unread (null) tick and surface readError on the
    // timeout, not propagate the exception out of the whole poll. Without the try/catch the call
    // throws and this spec goes RED.
    def "single-device: a read that throws mid-poll times out with readError, does not propagate"() {
        given: 'the initial identity read succeeds, then state reads fail'
        int reads = 0
        def device = Spy(TestDevice)
        device.id = 784
        device.label = 'Faulting Single'
        device.supportedAttributes = [[name: 'switch']]
        device.getCurrentStates() >> {
            if (++reads == 1) return [[name: 'switch', value: 'off']]
            throw new IllegalStateException("device removed mid-poll")
        }
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '784', attribute: 'switch', expectedValue: 'on', timeoutMs: 100, pollIntervalMs: 50])

        then: 'the poll returned a structured timeout (did not throw) and flagged readError'
        r.converged == false
        r.timedOut == true
        r.readError == true
        r.finalValue == null
        reads > 1
    }

    def "single-device: a read that throws early then recovers still converges and flags readError"() {
        given: 'initial native identity reads off, poll 2 throws, then poll 3 recovers'
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 785
        device.label = 'Recovering Single'
        device.supportedAttributes = [[name: 'switch']]
        device.getCurrentStates() >> {
            readCount++
            if (readCount == 1) return [[name: 'switch', value: 'off']]
            if (readCount == 2) throw new IllegalStateException("transient read fault")
            return [[name: 'switch', value: 'on']]
        }
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '785', attribute: 'switch', expectedValue: 'on', timeoutMs: 5000, pollIntervalMs: 50])

        then: 'converged after recovery; readError latched from the earlier fault even on success'
        r.converged == true
        !r.timedOut
        r.readError == true
        r.finalValue == 'on'
    }

    def "single-device: a clean converge with no read fault does NOT emit readError"() {
        given: 'a healthy device at on -- no read ever throws'
        def device = new TestDevice(id: 786, label: 'Healthy Single', supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        def r = poll([deviceId: '786', attribute: 'switch', expectedValue: 'on', timeoutMs: 5000])

        then: 'negative pin: success path must not latch readError when nothing faulted (guards an always-latch regression)'
        r.converged == true
        !r.timedOut
        !r.containsKey('readError')
    }
}
