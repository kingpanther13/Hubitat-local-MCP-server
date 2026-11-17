package server

import support.NativePollFixture
import support.TestDevice
import support.ToolSpecBase

/**
 * The waitFor engine shared by hub_get_device, hub_list_devices and hub_call_device_command:
 * converge/timeout, expected-value matching (numeric fallback both ways), the single waitFor
 * validator, neverReported / transitioning / interrupted, and dispatch through both catalog modes.
 * Single-device waits run through hub_get_device(waitFor); poll() maps the old flat arguments there.
 * pauseExecution() stubs inject via script.metaClass.pauseExecution; with no stub the Mock returns
 * instantly, and the fixed harness now() ends a wait by its check count.
 */
class ToolWaitForSpec extends ToolSpecBase {

    private void nativePollDevice(TestDevice device) {
        childDevicesList << device
        NativePollFixture.register(hubGet, device)
    }

    // A single-device wait through hub_get_device; returns its waitFor block.
    private Map poll(Map a) {
        Map waitFor = a.findAll { it.key != 'deviceId' }
        script.toolGetDevice(a.deviceId, 'summary', null, null, null, waitFor).waitFor
    }

    @spock.lang.Unroll
    def "_pollSleepMs keeps the cadence for fast reads and floors at half the interval for slow ones (#label)"() {
        expect:
        script._pollSleepMs(interval, remaining, tickElapsed) == sleep

        where:
        label                      | interval | remaining | tickElapsed | sleep
        'no read latency'          | 200      | 5000      | 0           | 200
        'latency subtracted'       | 200      | 5000      | 50          | 150
        'reads slower than interval' | 200    | 5000      | 300         | 100
        'reads equal the interval' | 200      | 5000      | 200         | 100
        'window nearly over'       | 200      | 30        | 50          | 30
        'window expired'           | 200      | 0         | 0           | 200
    }

    def "a malformed argument is rejected before any per-device native fetch"() {
        given:
        def device = new TestDevice(id: 10, name: 'TestSwitch', label: 'Test Switch',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        poll([deviceId: '10', attribute: 'switch', expectedValue: 'on', comparator: 'bogus'])

        then:
        thrown(IllegalArgumentException)
        !hubGet.calls.any { it.path.startsWith('/device/fullJson/') }
    }

    // ---------------------------------------------------------------------------
    // 1. Match on first poll
    // ---------------------------------------------------------------------------

    def "returns success immediately when currentValue already matches expectedValue"() {
        given:
        def device = new TestDevice(
            id: 10,
            name: 'TestSwitch',
            label: 'Test Switch',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '10',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.converged     == true
        result.finalValue  == 'on'
        result.polledCount >= 1
        !result.timedOut
        result.elapsedMs   >= 0
    }

    // ---------------------------------------------------------------------------
    // 2. Match on Nth poll (value changes mid-sequence)
    //    Script the native HTTP fixture state to flip after k value reads.
    // ---------------------------------------------------------------------------

    def "returns success when value changes to expected after several polls"() {
        given:
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 20
        device.label = 'Flip Switch'
        device.supportedAttributes = [[name: 'switch']]
        // NativePollFixture converts this scripted state to the fullJson HTTP response:
        // 'off' for value reads 1-2, 'on' from read 3 onward.
        device.getCurrentStates() >> {
            readCount++
            return [[name: 'switch', value: readCount >= 3 ? 'on' : 'off']]
        }
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '20',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 50
        ])

        then:
        result.converged     == true
        result.finalValue  == 'on'
        result.polledCount == 3
        hubGet.calls.findAll { it.path == '/device/fullJson/20' }.size() == result.polledCount
        !result.timedOut
    }

    // ---------------------------------------------------------------------------
    // 3. Timeout -- value never matches
    // ---------------------------------------------------------------------------

    def "returns success=false with timedOut=true when value never matches within timeout"() {
        given:
        def device = new TestDevice(
            id: 30,
            label: 'Stubborn Switch',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '30',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 100,     // very short so the test stays fast
            pollIntervalMs: 50
        ])

        then:
        result.converged    == false
        result.timedOut   == true
        result.finalValue == 'off'
        result.polledCount >= 1
    }

    // ---------------------------------------------------------------------------
    // 4. expectedValues (list) -- match found
    // ---------------------------------------------------------------------------

    def "returns success when currentValue is in expectedValues list"() {
        given:
        def device = new TestDevice(
            id: 40,
            label: 'Multi Match',
            supportedAttributes: [[name: 'contact']],
            attributeValues: [contact: 'open']
        )
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId       : '40',
            attribute      : 'contact',
            expectedValues : ['open', 'closed'],
            timeoutMs      : 5000,
            pollIntervalMs : 200
        ])

        then:
        result.converged    == true
        result.finalValue == 'open'
        !result.timedOut
    }

    // ---------------------------------------------------------------------------
    // 5. expectedValues (list) -- no match -> timeout
    // ---------------------------------------------------------------------------

    def "returns timedOut when currentValue is not in expectedValues list"() {
        given:
        def device = new TestDevice(
            id: 50,
            label: 'No Match',
            supportedAttributes: [[name: 'contact']],
            attributeValues: [contact: 'open']
        )
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId       : '50',
            attribute      : 'contact',
            expectedValues : ['closed'],
            timeoutMs      : 100,
            pollIntervalMs : 50
        ])

        then:
        result.converged  == false
        result.timedOut == true
    }

    // ---------------------------------------------------------------------------
    // 6. Both expectedValue AND expectedValues set -> rejected (exactly-one semantics,
    //    matching the waitFor pre-fire path; the two validators are kept identical).
    // ---------------------------------------------------------------------------

    def "throws when BOTH expectedValue and expectedValues are provided"() {
        given:
        def device = new TestDevice(
            id: 60,
            label: 'Both Test',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId       : '60',
            attribute      : 'switch',
            expectedValue  : 'on',
            expectedValues : ['off'],
            timeoutMs      : 5000
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one of expectedValue or expectedValues')
    }

    // ---------------------------------------------------------------------------
    // 7. Both expectedValue AND expectedValues null -> throws
    // ---------------------------------------------------------------------------

    def "throws when neither expectedValue nor expectedValues is provided"() {
        given:
        def device = new TestDevice(
            id: 70,
            label: 'No Expectation',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId : '70',
            attribute: 'switch',
            timeoutMs: 1000
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one of expectedValue or expectedValues is required')
    }

    // ---------------------------------------------------------------------------
    // 8. Type-validation throws
    // ---------------------------------------------------------------------------

    def "an integer deviceId is coerced past validation and reaches device resolution"() {
        when:
        poll([
            deviceId      : 99,          // numeric form, coerced to '99'
            attribute     : 'switch',
            expectedValue : 'on'
        ])

        then: 'the failure is resolution (no such device), not the old type rejection'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Device not found: 99')
    }

    def "throws when attribute is a list instead of string"() {
        given:
        def device = new TestDevice(
            id: 80,
            label: 'Type Check',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId      : '80',
            attribute     : ['switch'],  // should be String
            expectedValue : 'on'
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('attribute')
    }

    def "throws when timeoutMs is a string instead of a number"() {
        given:
        def device = new TestDevice(
            id: 81,
            label: 'Timeout Type',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId      : '81',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : '5000'      // should be Number
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('timeoutms')
    }

    def "throws when expectedValues contains a non-string element"() {
        given:
        def device = new TestDevice(
            id: 82,
            label: 'EV Type',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId       : '82',
            attribute      : 'switch',
            expectedValues : ['on', 42]  // 42 is not a string
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('expectedvalues')
    }

    // ---------------------------------------------------------------------------
    // 9. timeoutMs out of range
    // ---------------------------------------------------------------------------

    def "throws when timeoutMs is below minimum (100)"() {
        given:
        def device = new TestDevice(id: 90, label: 'Low Timeout',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(device)

        when:
        poll([
            deviceId      : '90',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 50
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('timeoutms')
        ex.message.contains('100')
    }

    def "throws when timeoutMs exceeds maximum (60000)"() {
        given:
        def device = new TestDevice(id: 91, label: 'High Timeout',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(device)

        when:
        poll([
            deviceId      : '91',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 90000
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('timeoutms')
        ex.message.contains('60000')
    }

    // ---------------------------------------------------------------------------
    // 10. pollIntervalMs out of range
    // ---------------------------------------------------------------------------

    def "throws when pollIntervalMs is below minimum (50)"() {
        given:
        def device = new TestDevice(id: 100, label: 'Fast Interval',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(device)

        when:
        poll([
            deviceId       : '100',
            attribute      : 'switch',
            expectedValue  : 'on',
            timeoutMs      : 1000,
            pollIntervalMs : 10
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pollintervalms')
        ex.message.contains('50')
    }

    def "throws when pollIntervalMs exceeds maximum (5000)"() {
        given:
        def device = new TestDevice(id: 101, label: 'Slow Interval',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(device)

        when:
        poll([
            deviceId       : '101',
            attribute      : 'switch',
            expectedValue  : 'on',
            timeoutMs      : 60000,
            pollIntervalMs : 6000
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pollintervalms')
        ex.message.contains('5000')
    }

    // ---------------------------------------------------------------------------
    // 11. pollIntervalMs > timeoutMs -- auto-clamped, at least one poll fires
    // ---------------------------------------------------------------------------

    def "auto-clamps pollIntervalMs to timeoutMs and still performs at least one poll"() {
        given:
        // Device already has the expected value -- clamp guarantees a poll fires,
        // so we should return success even with a large pollIntervalMs.
        def device = new TestDevice(
            id: 110,
            label: 'Clamped Interval',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId       : '110',
            attribute      : 'switch',
            expectedValue  : 'on',
            timeoutMs      : 200,
            pollIntervalMs : 5000  // larger than timeoutMs -- must be clamped, not rejected
        ])

        then:
        result.converged    == true
        result.polledCount >= 1
        !result.timedOut
    }

    // ---------------------------------------------------------------------------
    // 12. Device not found -> throws
    // ---------------------------------------------------------------------------

    def "throws when device is not found"() {
        given:
        childDevicesList.clear()
        settingsMap.selectedDevices = []

        when:
        poll([
            deviceId      : '999',
            attribute     : 'switch',
            expectedValue : 'on'
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('999')
    }

    // ---------------------------------------------------------------------------
    // 13. Attribute absent from currentStates (never reported) ->
    //     polls until timeout, returns cleanly with finalValue=null
    // ---------------------------------------------------------------------------

    def "polls until timeout when the attribute is absent from currentStates and returns finalValue=null cleanly"() {
        given:
        def device = new TestDevice(
            id: 120,
            label: 'No Value',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [:]  // no value for 'switch' -> absent from currentStates -> find returns null
        )
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '120',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        result.converged    == false
        result.timedOut   == true
        result.finalValue == null   // null is valid -- did not throw
        result.polledCount >= 1
    }

    // ---------------------------------------------------------------------------
    // 14. Unknown attribute names cannot be rejected from reported-only metadata
    // ---------------------------------------------------------------------------

    def "an attribute absent from native reported states times out with neverReported"() {
        given:
        def device = new TestDevice(
            id: 130,
            label: 'Typo Test',
            supportedAttributes: [[name: 'switch'], [name: 'level']],
            attributeValues: [switch: 'off', level: '50']
        )
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '130',
            attribute     : 'swich',
            expectedValue : 'on',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        result.converged == false
        result.timedOut == true
        result.neverReported == true
        result.finalValue == null
        result.polledCount >= 1
        !result.containsKey('readError')
    }

    def "a previously unreported attribute can appear on a later native poll without SDK declarations"() {
        given:
        def reads = 0
        def device = Spy(TestDevice)
        device.id = 131
        device.label = 'Reports Later'
        device.getSupportedAttributes() >> { throw new AssertionError('SDK declaration reads are forbidden') }
        device.getCurrentStates() >> {
            reads++
            reads == 1 ? [] : [[name: 'customValue', value: 'ready']]
        }
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId: '131', attribute: 'customValue', expectedValue: 'ready', timeoutMs: 500, pollIntervalMs: 50
        ])

        then:
        result.converged == true
        result.finalValue == 'ready'
        result.polledCount == 2
        reads == 2
        hubGet.calls.findAll { it.path == '/device/fullJson/131' }.size() == 2
        !result.neverReported
        !result.readError
    }

    def "an existing unselected device is denied before native polling when bypass is off"() {
        given:
        settingsMap.bypassDeviceAllowlist = false
        def device = new TestDevice(id: 132, label: 'Not Selected', attributeValues: [switch: 'on'])
        NativePollFixture.register(hubGet, device)

        when:
        poll([deviceId: '132', attribute: 'switch', expectedValue: 'on', timeoutMs: 100])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('132')
        hubGet.calls.empty
    }

    // ---------------------------------------------------------------------------
    // 15. Unknown arg (timeoutSeconds instead of timeoutMs) -> throws with hint
    // ---------------------------------------------------------------------------

    def "throws with helpful message when caller passes timeoutSeconds instead of timeoutMs"() {
        given:
        def device = new TestDevice(
            id: 140,
            label: 'Unknown Arg Test',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId       : '140',
            attribute      : 'switch',
            expectedValue  : 'on',
            timeoutSeconds : 10      // wrong name -- should be timeoutMs
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('timeoutSeconds')   // the bad key is named in the error
        ex.message.contains('timeoutMs')        // the correct key is suggested
    }

    // ---------------------------------------------------------------------------
    // 16. Multiple unknown args -> all listed in error
    // ---------------------------------------------------------------------------

    def "throws listing all unknown args when multiple bad keys are passed"() {
        given:
        def device = new TestDevice(
            id: 141,
            label: 'Multi Unknown Arg Test',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId          : '141',
            attribute         : 'switch',
            expectedValue     : 'on',
            timeoutSeconds    : 10,     // wrong name
            pollIntervalSeconds: 1      // also wrong name
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('timeoutSeconds')
        ex.message.contains('pollIntervalSeconds')
        ex.message.contains('timeoutMs')        // gotcha hint still present
    }

    // ---------------------------------------------------------------------------
    // 17. Numeric attribute -- BigDecimal integer-equivalent matches string "50"
    //     Real hub returns level/temperature as BigDecimal; toString() yields "50.0"
    //     for an integer-equivalent value, so strict string equality would fail.
    // ---------------------------------------------------------------------------

    def "matches when BigDecimal 50.0 attribute value is compared to expectedValue '50'"() {
        given:
        def device = Spy(TestDevice)
        device.id = 170
        device.label = 'Numeric Level'
        device.supportedAttributes = [[name: 'level']]
        // A State's value is a String in Hubitat; an integer-equivalent level reads as "50.0".
        device.getCurrentStates() >> [[name: 'level', value: '50.0']]
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '170',
            attribute     : 'level',
            expectedValue : '50',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.converged    == true
        !result.timedOut
        result.polledCount >= 1
    }

    // ---------------------------------------------------------------------------
    // 18. Numeric attribute -- fractional value does NOT match integer expectedValue
    //     BigDecimal 50.5 must not match expectedValue '50'.
    // ---------------------------------------------------------------------------

    def "does not match when BigDecimal 50.5 attribute value is compared to expectedValue '50'"() {
        given:
        def device = Spy(TestDevice)
        device.id = 171
        device.label = 'Fractional Level'
        device.supportedAttributes = [[name: 'level']]
        device.getCurrentStates() >> [[name: 'level', value: '50.5']]
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '171',
            attribute     : 'level',
            expectedValue : '50',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        result.converged  == false
        result.timedOut == true
    }

    // ---------------------------------------------------------------------------
    // 19. Numeric finalValue against non-numeric expectedValue -- no exception
    //     The isNumber() guard must prevent a cast attempt on non-numeric strings.
    // ---------------------------------------------------------------------------

    def "does not throw and does not match when numeric attribute is compared to non-numeric expectedValue"() {
        given:
        def device = Spy(TestDevice)
        device.id = 172
        device.label = 'Numeric vs Non-Numeric'
        device.supportedAttributes = [[name: 'level']]
        device.getCurrentStates() >> [[name: 'level', value: '50.0']]
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '172',
            attribute     : 'level',
            expectedValue : 'abc',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        notThrown(Exception)
        result.converged  == false
        result.timedOut == true
    }

    // ---------------------------------------------------------------------------
    // 20. B1: empty expectedValues list -> throws
    // ---------------------------------------------------------------------------

    def "throws when expectedValues is an empty list"() {
        given:
        def device = new TestDevice(
            id: 200,
            label: 'Empty EV Test',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId       : '200',
            attribute      : 'switch',
            expectedValues : []   // empty -- not a valid filter
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('expectedvalues')
        ex.message.toLowerCase().contains('empty')
    }

    // ---------------------------------------------------------------------------
    // 21. W1: empty string expectedValue -> throws
    // ---------------------------------------------------------------------------

    def "throws when expectedValue is an empty string"() {
        given:
        def device = new TestDevice(
            id: 201,
            label: 'Empty EV String Test',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId      : '201',
            attribute     : 'switch',
            expectedValue : ''   // empty string -- ambiguous semantics; reject
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('expectedvalue')
        ex.message.toLowerCase().contains('empty')
    }

    // ---------------------------------------------------------------------------
    // 22. B2: null value for present keys -> throws with descriptive messages
    // ---------------------------------------------------------------------------

    def "throws when timeoutMs is explicitly null"() {
        given:
        def device = new TestDevice(
            id: 210,
            label: 'Null TimeoutMs',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId      : '210',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : null   // key present, value null
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('timeoutms')
        ex.message.toLowerCase().contains('null')
    }

    def "throws when pollIntervalMs is explicitly null"() {
        given:
        def device = new TestDevice(
            id: 211,
            label: 'Null PollIntervalMs',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId       : '211',
            attribute      : 'switch',
            expectedValue  : 'on',
            pollIntervalMs : null   // key present, value null
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pollintervalms')
        ex.message.toLowerCase().contains('null')
    }

    // ---------------------------------------------------------------------------
    // 23. I9: missing type-validation tests -- pollIntervalMs and expectedValue
    // ---------------------------------------------------------------------------

    def "throws when pollIntervalMs is a string instead of a number"() {
        given:
        def device = new TestDevice(
            id: 220,
            label: 'PollInterval Type',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId       : '220',
            attribute      : 'switch',
            expectedValue  : 'on',
            pollIntervalMs : '200'   // should be Number
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pollintervalms')
    }

    def "throws when expectedValue is a non-string type (integer)"() {
        given:
        def device = new TestDevice(
            id: 221,
            label: 'EV Type Check',
            supportedAttributes: [[name: 'level']],
            attributeValues: [level: '50']
        )
        nativePollDevice(device)

        when:
        poll([
            deviceId      : '221',
            attribute     : 'level',
            expectedValue : 50   // should be String "50", not Integer
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('expectedvalue')
    }

    // ---------------------------------------------------------------------------
    // 24. W2: exact boundary tests for timeoutMs
    // ---------------------------------------------------------------------------

    def "throws when timeoutMs is exactly 99 (one below minimum)"() {
        given:
        def device = new TestDevice(id: 230, label: 'Boundary Low',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(device)

        when:
        poll([deviceId: '230', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 99])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('timeoutms')
    }

    def "throws when timeoutMs is exactly 60001 (one above maximum)"() {
        given:
        def device = new TestDevice(id: 231, label: 'Boundary High',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(device)

        when:
        poll([deviceId: '231', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 60001])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('timeoutms')
    }

    def "accepts timeoutMs at exact minimum (100)"() {
        given:
        def device = new TestDevice(id: 232, label: 'Boundary Min Accept',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        def result = poll([deviceId: '232', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 100])

        then:
        notThrown(IllegalArgumentException)
        result.converged == true
    }

    def "accepts timeoutMs at exact maximum (60000)"() {
        given:
        def device = new TestDevice(id: 233, label: 'Boundary Max Accept',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        def result = poll([deviceId: '233', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 60000])

        then:
        notThrown(IllegalArgumentException)
        result.converged == true
    }

    // ---------------------------------------------------------------------------
    // 25. W2: exact boundary tests for pollIntervalMs
    // ---------------------------------------------------------------------------

    def "throws when pollIntervalMs is exactly 49 (one below minimum)"() {
        given:
        def device = new TestDevice(id: 240, label: 'Interval Low',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(device)

        when:
        poll([deviceId: '240', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 49])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pollintervalms')
    }

    def "throws when pollIntervalMs is exactly 5001 (one above maximum)"() {
        given:
        def device = new TestDevice(id: 241, label: 'Interval High',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        nativePollDevice(device)

        when:
        poll([deviceId: '241', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 60000, pollIntervalMs: 5001])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pollintervalms')
    }

    def "accepts pollIntervalMs at exact minimum (50)"() {
        given:
        def device = new TestDevice(id: 242, label: 'Interval Min Accept',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        def result = poll([deviceId: '242', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 50])

        then:
        notThrown(IllegalArgumentException)
        result.converged == true
    }

    def "accepts pollIntervalMs at exact maximum (5000)"() {
        given:
        def device = new TestDevice(id: 243, label: 'Interval Max Accept',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        nativePollDevice(device)

        when:
        // pollIntervalMs=5000 > timeoutMs=200 so it gets clamped -- still accepts, not throws
        def result = poll([deviceId: '243', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 200, pollIntervalMs: 5000])

        then:
        notThrown(IllegalArgumentException)
        result.converged == true
    }

    // ---------------------------------------------------------------------------
    // 26. I5: neverReported=true when attribute is always null during poll
    // ---------------------------------------------------------------------------

    def "sets neverReported=true when attribute never returns a non-null value"() {
        given:
        def device = new TestDevice(
            id: 250,
            label: 'Always Null',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [:]   // null throughout
        )
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '250',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        result.converged      == false
        result.timedOut     == true
        result.neverReported == true
    }

    def "does not set neverReported when attribute returns non-null on at least one poll"() {
        given:
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 251
        device.label = 'Null Then Wrong'
        device.supportedAttributes = [[name: 'switch']]
        // First read: attribute absent from currentStates (never reported yet -> find returns
        // null); subsequent reads: present with 'off' (wrong value, but it did report).
        device.getCurrentStates() >> {
            readCount++
            return readCount == 1 ? [] : [[name: 'switch', value: 'off']]
        }
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '251',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        result.converged      == false
        result.timedOut     == true
        !result.neverReported   // key absent or false -- attribute did report eventually
    }

    // ---------------------------------------------------------------------------
    // 26c. transitioning flag on the TIMEOUT path: distinguishes a still-moving value
    //      (>=2 distinct non-null reads) from a stable non-target mismatch.
    // ---------------------------------------------------------------------------

    def "sets transitioning=true on timeout when the non-target value changed across polls"() {
        given: 'a level that keeps moving (10 -> 20 -> 30 ...) but never reaches the target'
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 260
        device.label = 'Still Moving'
        device.supportedAttributes = [[name: 'level']]
        // Each read returns a DIFFERENT non-null value -- the device is still settling.
        device.getCurrentStates() >> {
            readCount++
            return [[name: 'level', value: (readCount * 10).toString()]]
        }
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '260',
            attribute     : 'level',
            expectedValue : '99',
            timeoutMs     : 200,
            pollIntervalMs: 50
        ])

        then: 'timed out, and transitioning is true because >=2 distinct non-null values were seen'
        result.converged      == false
        result.timedOut     == true
        result.transitioning == true
    }

    def "sets transitioning=false on timeout when the non-target value was stable across polls"() {
        given: 'a switch stuck at off the whole window -- a stable non-target, not still settling'
        def device = new TestDevice(
            id: 261,
            label: 'Stable Wrong',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '261',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 200,
            pollIntervalMs: 50
        ])

        then: 'timed out, and transitioning is false because every read was the same value'
        result.converged      == false
        result.timedOut     == true
        result.transitioning == false
    }

    // ---------------------------------------------------------------------------
    // 27. I6: pauseExecution throws -> returns interrupted=true with context fields
    // ---------------------------------------------------------------------------

    def "returns interrupted=true when pauseExecution throws an exception"() {
        given:
        def device = new TestDevice(
            id: 260,
            label: 'Interrupted Poll',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']   // won't match, so we'll reach sleep
        )
        nativePollDevice(device)

        // pauseExecution is on BaseExecutor/@Delegate chain; override via metaClass on the script
        script.metaClass.pauseExecution = { long ms ->
            throw new InterruptedException("hub reloading")
        }

        when:
        def result = poll([
            deviceId      : '260',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.converged      == false
        result.interrupted  == true
        result.polledCount  >= 1
        result.finalValue   == 'off'
        result.elapsedMs    >= 0
    }

    // ---------------------------------------------------------------------------
    // 28. B3: String-typed numeric attribute matched by expectedValue
    //     Hubitat drivers return some attributes as String "50.0" not BigDecimal.
    // ---------------------------------------------------------------------------

    def "matches when String attribute '50.0' is compared to expectedValue '50'"() {
        given:
        def device = Spy(TestDevice)
        device.id = 280
        device.label = 'String Numeric Attr'
        device.supportedAttributes = [[name: 'level']]
        // Driver reports the level as the String "50.0" in the event store.
        device.getCurrentStates() >> [[name: 'level', value: '50.0']]
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '280',
            attribute     : 'level',
            expectedValue : '50',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.converged    == true
        !result.timedOut
        result.polledCount >= 1
    }

    // ---------------------------------------------------------------------------
    // 29. I8: inverse direction -- expectedValue '50.0' against Integer currentValue 50
    // ---------------------------------------------------------------------------

    def "matches when integer-valued attribute '50' is compared to expectedValue '50.0'"() {
        given:
        def device = Spy(TestDevice)
        device.id = 290
        device.label = 'Integer vs Decimal EV'
        device.supportedAttributes = [[name: 'level']]
        // An integer-valued level reads from the event store as the String "50".
        device.getCurrentStates() >> [[name: 'level', value: '50']]
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '290',
            attribute     : 'level',
            expectedValue : '50.0',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.converged    == true
        !result.timedOut
    }

    def "matches when decimal-valued attribute '50.0' is compared to expectedValue '50'"() {
        given:
        def device = Spy(TestDevice)
        device.id = 291
        device.label = 'Double vs String EV'
        device.supportedAttributes = [[name: 'level']]
        // A decimal-valued level reads from the event store as the String "50.0".
        device.getCurrentStates() >> [[name: 'level', value: '50.0']]
        nativePollDevice(device)

        when:
        def result = poll([
            deviceId      : '291',
            attribute     : 'level',
            expectedValue : '50',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.converged    == true
        !result.timedOut
    }

    // ---------------------------------------------------------------------------
    // 30. Non-InterruptedException from pauseExecution propagates (not swallowed
    //     as interrupted=true). After the narrow catch, any other exception
    //     escapes to the JSON-RPC error layer rather than masking as a poll result.
    // ---------------------------------------------------------------------------

    def "propagates RuntimeException from pauseExecution rather than returning interrupted=true"() {
        given:
        def device = new TestDevice(
            id: 300,
            label: 'NPE In Sleep',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']   // won't match, so we'll reach the sleep
        )
        nativePollDevice(device)

        // Inject a non-interrupted exception to verify the narrow catch does NOT swallow it
        script.metaClass.pauseExecution = { long ms ->
            throw new RuntimeException("simulated bug in sleep path")
        }

        when:
        poll([
            deviceId      : '300',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        // RuntimeException is NOT caught by the narrow InterruptedException handler --
        // it propagates up to the caller (JSON-RPC layer maps to -32603 in production).
        thrown(RuntimeException)
    }

    // ---------------------------------------------------------------------------
    // 31. InterruptedException path emits log.warn with poll count and elapsed time.
    //     Verifies operator observability added in the interrupt catch block.
    // ---------------------------------------------------------------------------

    def "emits warn log when pauseExecution throws InterruptedException"() {
        given:
        def device = new TestDevice(
            id: 310,
            label: 'Warn On Interrupt',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']   // won't match, so we'll reach the sleep
        )
        nativePollDevice(device)

        // mcpLog is a script-defined method (bucket 1 -- purely dynamic; not on
        // AppExecutor/BaseExecutor). Per-instance metaClass overrides intercept
        // cleanly. The production code calls mcpLog("warn", "device", ...)
        // in the interrupt catch block.
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }

        script.metaClass.pauseExecution = { long ms ->
            throw new InterruptedException("hub reloading")
        }

        when:
        def result = poll([
            deviceId      : '310',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.converged     == false
        result.interrupted == true
        // A warn was emitted via mcpLog naming the poll count and component
        mcpLogCalls.any { it.level == 'warn' && it.component == 'device' &&
                          it.msg ==~ /waitFor interrupted after \d+ check\(s\).*/ }
    }

    // ---------------------------------------------------------------------------
    // Dispatch-envelope counterparts (issue #187): hub_get_device(waitFor) through executeTool,
    // flat and gateway catalogs. A wait that does not converge is a successful read, not an error.
    // ---------------------------------------------------------------------------

    @spock.lang.Unroll
    def "hub_get_device waitFor via dispatch converges at once when the value already matches (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        nativePollDevice(new TestDevice(id: 1010, name: 'DispatchSwitch', label: 'Dispatch Switch', attributeValues: [switch: 'on']))

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '1010',
            waitFor: [attribute: 'switch', expectedValue: 'on', timeoutMs: 5000, pollIntervalMs: 200]])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.label == 'Dispatch Switch'
        inner.attributes.find { it.name == 'switch' }.value == 'on'
        inner.waitFor.converged == true
        inner.waitFor.finalValue == 'on'
        inner.waitFor.attribute == 'switch'
        inner.waitFor.expected == 'on'
        !inner.waitFor.containsKey('timedOut')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_device waitFor via dispatch reports a timeout as converged false, not a tool error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        nativePollDevice(new TestDevice(id: 1020, label: 'Dispatch Stubborn', attributeValues: [switch: 'off']))

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '1020',
            waitFor: [attribute: 'switch', expectedValue: 'on', timeoutMs: 100, pollIntervalMs: 50]])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.waitFor.converged == false
        inner.waitFor.timedOut == true
        inner.waitFor.finalValue == 'off'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_device waitFor via dispatch returns an isError validation result for #scenario (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        nativePollDevice(new TestDevice(id: 1030, label: 'Dispatch Validation', attributeValues: [switch: 'off']))

        when:
        def response = mcpDriver.callTool('hub_get_device', args)

        then:
        response.result.isError == true
        expected.every { mcpDriver.parseInner(response).error.contains(it) }

        where:
        [useGateways, scenario, args, expected] << [[true, false], [
            ['an unknown device', [deviceId: '9999', waitFor: [attribute: 'switch', expectedValue: 'on']], ['9999']],
            ['no expected value', [deviceId: '1030', waitFor: [attribute: 'switch', timeoutMs: 1000]], ['exactly one of expectedValue or expectedValues']],
            ['an unknown waitFor key', [deviceId: '1030', waitFor: [attribute: 'switch', expectedValue: 'on', timeoutSeconds: 10]], ['timeoutSeconds', 'timeoutMs']],
            ['a details-mode wait', [deviceId: '1030', mode: 'details', waitFor: [attribute: 'switch', expectedValue: 'on']], ['summary mode']],
            ['mode inside a single-device wait', [deviceId: '1030', waitFor: [attribute: 'switch', expectedValue: 'on', mode: 'any']], ['unknown key: mode']]
        ]].combinations().collect { [it[0]] + it[1] }
    }

    @spock.lang.Unroll
    def "hub_get_device waitFor via dispatch reports interrupted when pauseExecution throws InterruptedException (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        nativePollDevice(new TestDevice(id: 1050, label: 'Dispatch Interrupted', attributeValues: [switch: 'off']))
        script.metaClass.pauseExecution = { long ms -> throw new InterruptedException("hub reloading") }

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '1050',
            waitFor: [attribute: 'switch', expectedValue: 'on', timeoutMs: 5000, pollIntervalMs: 200]])

        then: 'InterruptedException is caught inside the wait and reported in the waitFor block'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.waitFor.converged == false
        inner.waitFor.interrupted == true
        inner.waitFor.finalValue == 'off'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_device waitFor via dispatch returns isError envelope when pauseExecution throws RuntimeException (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        nativePollDevice(new TestDevice(id: 1060, label: 'Dispatch Runtime Boom', attributeValues: [switch: 'off']))
        script.metaClass.pauseExecution = { long ms -> throw new RuntimeException("simulated bug in sleep path") }

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '1060',
            waitFor: [attribute: 'switch', expectedValue: 'on', timeoutMs: 5000, pollIntervalMs: 200]])

        then: 'non-IAE escapes the tool and lands in the generic-Exception path -> isError envelope per MCP spec'
        response.error == null
        response.result.isError == true
        response.result.content[0].type == 'text'
        response.result.content[0].text.startsWith('Tool error:')
        response.result.content[0].text.contains('simulated bug')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "one attribute reads from hub_get_device attributes by name, an absent entry meaning never reported (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        nativePollDevice(new TestDevice(id: 1070, name: 'DispatchReadSwitch', label: 'Dispatch Read Switch', attributeValues: [switch: 'on']))

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: deviceId])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.label == 'Dispatch Read Switch'
        inner.attributes.find { it.name == 'switch' }.value == 'on'
        !inner.attributes.any { it.name == 'level' }
        !inner.containsKey('waitFor')

        where: 'hub_list_devices format=ids returns integer ids'
        [useGateways, deviceId] << [[true, false], ['1070', 1070]].combinations()
    }
}
