package server

import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for toolPollUntilAttribute (hubitat-mcp-server.groovy, DEVICE TOOLS section).
 *
 * Covers:
 *  - Match on first poll (already-expected value)
 *  - Match on Nth poll (value changes mid-sequence)
 *  - Timeout path (value never matches)
 *  - expectedValues (list) match
 *  - expectedValues (list) no match -> timeout
 *  - Both expectedValue and expectedValues set (OR semantics)
 *  - Both null -> throws
 *  - Empty expectedValues list -> throws (B1)
 *  - Empty string expectedValue -> throws (W1)
 *  - Null value for present keys (timeoutMs, pollIntervalMs, expectedValue, expectedValues) -> throws (B2)
 *  - Type-validation throws: deviceId as int, attribute as list, timeoutMs string,
 *    expectedValues with int element, pollIntervalMs string, expectedValue as int (6 tests)
 *  - timeoutMs out of range (< 100, > 60000)
 *  - timeoutMs exact boundaries (99 throws, 60001 throws, 100 accepts, 60000 accepts) (W2)
 *  - pollIntervalMs out of range (< 50, > 5000)
 *  - pollIntervalMs exact boundaries (49 throws, 5001 throws, 50 accepts, 5000 accepts) (W2)
 *  - pollIntervalMs > timeoutMs -> auto-clamped (at least one poll still fires)
 *  - Device not found -> throws
 *  - Attribute with null currentValue (neverReported=true path) (I5)
 *  - Attribute value transitions from null to wrong -> neverReported absent or false (I5)
 *  - Attribute name typo (not in supportedAttributes) -> throws with helpful message listing available attributes
 *  - Unknown arg (timeoutSeconds) -> throws with message naming the bad key and suggesting timeoutMs
 *  - Multiple unknown args -> all listed in the error plus gotcha hint
 *  - pauseExecution throws InterruptedException -> returns interrupted=true with context fields (I6)
 *  - pauseExecution throws non-InterruptedException -> propagates (not swallowed as interrupted=true)
 *  - pauseExecution throws InterruptedException -> emits mcpLog warn with poll count and elapsed
 *  - BigDecimal integer-equivalent (50.0) matches expectedValue '50' (numeric fallback, Number->String)
 *  - String "50.0" attribute matches expectedValue '50' (numeric fallback, String->String, B3)
 *  - expectedValue '50.0' matches Integer currentValue 50 (inverse direction, I8)
 *  - expectedValue '50' matches Double 50.0d (I8)
 *  - BigDecimal fractional (50.5) does NOT match expectedValue '50' (correct behavior)
 *  - Numeric finalValue against non-numeric expectedValue -> no match, no exception (isNumber() guard)
 *
 * pauseExecution() stubs inject via script.metaClass.pauseExecution (empirically
 * verified in scenarios 27, 30, 31); for normal test paths with no stub, the Mock
 * returns void silently, making sleeps instant.
 */
class ToolPollUntilAttributeSpec extends ToolSpecBase {

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
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '10',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.success     == true
        result.finalValue  == 'on'
        result.polledCount >= 1
        result.timedOut    == false
        result.elapsedMs   >= 0
    }

    // ---------------------------------------------------------------------------
    // 2. Match on Nth poll (value changes mid-sequence)
    //    Use a Spy on TestDevice to intercept currentValue() and flip after k reads.
    // ---------------------------------------------------------------------------

    def "returns success when value changes to expected after several polls"() {
        given:
        def readCount = 0
        def device = Spy(TestDevice)
        device.id = 20
        device.label = 'Flip Switch'
        device.supportedAttributes = [[name: 'switch']]
        // Override currentValue: returns 'off' for reads 1-2, 'on' from read 3 onward
        device.currentValue(_) >> { String attr ->
            readCount++
            return readCount >= 3 ? 'on' : 'off'
        }
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '20',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 50
        ])

        then:
        result.success     == true
        result.finalValue  == 'on'
        result.polledCount == 3
        result.timedOut    == false
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
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '30',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 100,     // very short so the test stays fast
            pollIntervalMs: 50
        ])

        then:
        result.success    == false
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
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId       : '40',
            attribute      : 'contact',
            expectedValues : ['open', 'closed'],
            timeoutMs      : 5000,
            pollIntervalMs : 200
        ])

        then:
        result.success    == true
        result.finalValue == 'open'
        result.timedOut   == false
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
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId       : '50',
            attribute      : 'contact',
            expectedValues : ['closed'],
            timeoutMs      : 100,
            pollIntervalMs : 50
        ])

        then:
        result.success  == false
        result.timedOut == true
    }

    // ---------------------------------------------------------------------------
    // 6. Both expectedValue AND expectedValues set -- OR semantics
    // ---------------------------------------------------------------------------

    def "succeeds when currentValue matches expectedValue even though expectedValues does not contain it"() {
        given:
        def device = new TestDevice(
            id: 60,
            label: 'OR Test',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId       : '60',
            attribute      : 'switch',
            expectedValue  : 'on',      // matches this
            expectedValues : ['off'],   // does NOT match this, but OR semantics
            timeoutMs      : 5000
        ])

        then:
        result.success    == true
        result.finalValue == 'on'
    }

    def "succeeds when currentValue matches expectedValues entry even though expectedValue does not match"() {
        given:
        def device = new TestDevice(
            id: 61,
            label: 'OR Test 2',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId       : '61',
            attribute      : 'switch',
            expectedValue  : 'on',       // does NOT match
            expectedValues : ['off'],    // matches this
            timeoutMs      : 5000
        ])

        then:
        result.success    == true
        result.finalValue == 'off'
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
            deviceId : '70',
            attribute: 'switch',
            timeoutMs: 1000
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('expectedvalue')
    }

    // ---------------------------------------------------------------------------
    // 8. Type-validation throws
    // ---------------------------------------------------------------------------

    def "throws when deviceId is an integer instead of string"() {
        when:
        script.toolPollUntilAttribute([
            deviceId      : 99,          // should be String
            attribute     : 'switch',
            expectedValue : 'on'
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('deviceid')
    }

    def "throws when attribute is a list instead of string"() {
        given:
        def device = new TestDevice(
            id: 80,
            label: 'Type Check',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId       : '110',
            attribute      : 'switch',
            expectedValue  : 'on',
            timeoutMs      : 200,
            pollIntervalMs : 5000  // larger than timeoutMs -- must be clamped, not rejected
        ])

        then:
        result.success    == true
        result.polledCount >= 1
        result.timedOut   == false
    }

    // ---------------------------------------------------------------------------
    // 12. Device not found -> throws
    // ---------------------------------------------------------------------------

    def "throws when device is not found"() {
        given:
        childDevicesList.clear()
        settingsMap.selectedDevices = []

        when:
        script.toolPollUntilAttribute([
            deviceId      : '999',
            attribute     : 'switch',
            expectedValue : 'on'
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('999')
    }

    // ---------------------------------------------------------------------------
    // 13. Attribute with null currentValue (attribute not present on device) ->
    //     polls until timeout, returns cleanly with finalValue=null
    // ---------------------------------------------------------------------------

    def "polls until timeout when currentValue returns null and returns finalValue=null cleanly"() {
        given:
        def device = new TestDevice(
            id: 120,
            label: 'No Value',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [:]  // no value for 'switch' -> currentValue returns null
        )
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '120',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        result.success    == false
        result.timedOut   == true
        result.finalValue == null   // null is valid -- did not throw
        result.polledCount >= 1
    }

    // ---------------------------------------------------------------------------
    // 14. Attribute typo (not in supportedAttributes) -> throws with helpful message
    // ---------------------------------------------------------------------------

    def "throws when attribute name is not in device supportedAttributes"() {
        given:
        def device = new TestDevice(
            id: 130,
            label: 'Typo Test',
            supportedAttributes: [[name: 'switch'], [name: 'level']],
            attributeValues: [switch: 'off', level: '50']
        )
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
            deviceId      : '130',
            attribute     : 'swich',   // typo -- not in supportedAttributes
            expectedValue : 'on'
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('swich')
        ex.message.contains('switch')   // 'switch' appears in the Available list
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        device.currentValue(_) >> { String attr -> new BigDecimal('50.0') }
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '170',
            attribute     : 'level',
            expectedValue : '50',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.success    == true
        result.timedOut   == false
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
        device.currentValue(_) >> { String attr -> new BigDecimal('50.5') }
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '171',
            attribute     : 'level',
            expectedValue : '50',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        result.success  == false
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
        device.currentValue(_) >> { String attr -> new BigDecimal('50.0') }
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '172',
            attribute     : 'level',
            expectedValue : 'abc',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        notThrown(Exception)
        result.success  == false
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '230', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 99])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('timeoutms')
    }

    def "throws when timeoutMs is exactly 60001 (one above maximum)"() {
        given:
        def device = new TestDevice(id: 231, label: 'Boundary High',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '231', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 60001])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('timeoutms')
    }

    def "accepts timeoutMs at exact minimum (100)"() {
        given:
        def device = new TestDevice(id: 232, label: 'Boundary Min Accept',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([deviceId: '232', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 100])

        then:
        notThrown(IllegalArgumentException)
        result.success == true
    }

    def "accepts timeoutMs at exact maximum (60000)"() {
        given:
        def device = new TestDevice(id: 233, label: 'Boundary Max Accept',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([deviceId: '233', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 60000])

        then:
        notThrown(IllegalArgumentException)
        result.success == true
    }

    // ---------------------------------------------------------------------------
    // 25. W2: exact boundary tests for pollIntervalMs
    // ---------------------------------------------------------------------------

    def "throws when pollIntervalMs is exactly 49 (one below minimum)"() {
        given:
        def device = new TestDevice(id: 240, label: 'Interval Low',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '240', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 49])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pollintervalms')
    }

    def "throws when pollIntervalMs is exactly 5001 (one above maximum)"() {
        given:
        def device = new TestDevice(id: 241, label: 'Interval High',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'off'])
        childDevicesList << device

        when:
        script.toolPollUntilAttribute([deviceId: '241', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 60000, pollIntervalMs: 5001])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pollintervalms')
    }

    def "accepts pollIntervalMs at exact minimum (50)"() {
        given:
        def device = new TestDevice(id: 242, label: 'Interval Min Accept',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([deviceId: '242', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 50])

        then:
        notThrown(IllegalArgumentException)
        result.success == true
    }

    def "accepts pollIntervalMs at exact maximum (5000)"() {
        given:
        def device = new TestDevice(id: 243, label: 'Interval Max Accept',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << device

        when:
        // pollIntervalMs=5000 > timeoutMs=200 so it gets clamped -- still accepts, not throws
        def result = script.toolPollUntilAttribute([deviceId: '243', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 200, pollIntervalMs: 5000])

        then:
        notThrown(IllegalArgumentException)
        result.success == true
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
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '250',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        result.success      == false
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
        // First read returns null, subsequent reads return 'off' (wrong value)
        device.currentValue(_) >> { String attr ->
            readCount++
            return readCount == 1 ? null : 'off'
        }
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '251',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        result.success      == false
        result.timedOut     == true
        !result.neverReported   // key absent or false -- attribute did report eventually
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
        childDevicesList << device

        // pauseExecution is on BaseExecutor/@Delegate chain; override via metaClass on the script
        script.metaClass.pauseExecution = { long ms ->
            throw new InterruptedException("hub reloading")
        }

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '260',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.success      == false
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
        // Driver returns a String, not BigDecimal
        device.currentValue(_) >> { String attr -> '50.0' }
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '280',
            attribute     : 'level',
            expectedValue : '50',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.success    == true
        result.timedOut   == false
        result.polledCount >= 1
    }

    // ---------------------------------------------------------------------------
    // 29. I8: inverse direction -- expectedValue '50.0' against Integer currentValue 50
    // ---------------------------------------------------------------------------

    def "matches when Integer attribute 50 is compared to expectedValue '50.0'"() {
        given:
        def device = Spy(TestDevice)
        device.id = 290
        device.label = 'Integer vs Decimal EV'
        device.supportedAttributes = [[name: 'level']]
        device.currentValue(_) >> { String attr -> 50 as Integer }
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '290',
            attribute     : 'level',
            expectedValue : '50.0',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.success    == true
        result.timedOut   == false
    }

    def "matches when Double attribute 50.0d is compared to expectedValue '50'"() {
        given:
        def device = Spy(TestDevice)
        device.id = 291
        device.label = 'Double vs String EV'
        device.supportedAttributes = [[name: 'level']]
        device.currentValue(_) >> { String attr -> 50.0d }
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '291',
            attribute     : 'level',
            expectedValue : '50',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.success    == true
        result.timedOut   == false
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
        childDevicesList << device

        // Inject a non-interrupted exception to verify the narrow catch does NOT swallow it
        script.metaClass.pauseExecution = { long ms ->
            throw new RuntimeException("simulated bug in sleep path")
        }

        when:
        script.toolPollUntilAttribute([
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
        childDevicesList << device

        // mcpLog is a script-defined method (bucket 1 -- purely dynamic; not on
        // AppExecutor/BaseExecutor). Per-instance metaClass overrides intercept
        // cleanly. The production code calls mcpLog("warn", "device-tools", ...)
        // in the interrupt catch block.
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }

        script.metaClass.pauseExecution = { long ms ->
            throw new InterruptedException("hub reloading")
        }

        when:
        def result = script.toolPollUntilAttribute([
            deviceId      : '310',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        result.success     == false
        result.interrupted == true
        // A warn was emitted via mcpLog naming the poll count and component
        mcpLogCalls.any { it.level == 'warn' && it.component == 'device-tools' &&
                          it.msg ==~ /poll_until_attribute interrupted after \d+ poll\(s\).*/ }
    }

    // ---------------------------------------------------------------------------
    // Dispatch-envelope counterparts (issue #187)
    //
    // poll_until_attribute is wired through the executeTool switch (no gateway
    // group), so useGateways doesn't change routing — the parameter is varied
    // here to assert the JSON-RPC envelope behaves identically in both modes.
    // The set is intentionally selective: poll_until_attribute has 30+ direct
    // features, and the dispatch path differs only by envelope shape per
    // outcome class. We cover one feature per distinct envelope:
    //   - success-immediate (success envelope with result body)
    //   - timeout (success envelope, success=false/timedOut=true)
    //   - IAE validation -> -32602 (device not found)
    //   - IAE validation -> -32602 (missing both expectedValue/expectedValues)
    //   - IAE validation -> -32602 (unknown arg / typo hint)
    //   - InterruptedException -> success envelope with interrupted=true
    //   - non-IAE RuntimeException -> isError success envelope (MCP spec)
    // ---------------------------------------------------------------------------

    @spock.lang.Unroll
    def "poll_until_attribute via dispatch returns success immediately when value already matches (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(
            id: 1010,
            name: 'DispatchSwitch',
            label: 'Dispatch Switch',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'on']
        )
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('poll_until_attribute', [
            deviceId      : '1010',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.finalValue == 'on'
        inner.timedOut == false

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "poll_until_attribute via dispatch returns success=false/timedOut=true when no match (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(
            id: 1020,
            label: 'Dispatch Stubborn',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('poll_until_attribute', [
            deviceId      : '1020',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 100,
            pollIntervalMs: 50
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.timedOut == true
        inner.finalValue == 'off'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "poll_until_attribute via dispatch returns -32602 when device is not found (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        childDevicesList.clear()
        settingsMap.selectedDevices = []

        when:
        def response = mcpDriver.callTool('poll_until_attribute', [
            deviceId      : '9999',
            attribute     : 'switch',
            expectedValue : 'on'
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('9999')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "poll_until_attribute via dispatch returns -32602 when neither expectedValue nor expectedValues provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(
            id: 1030,
            label: 'Dispatch Missing EV',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('poll_until_attribute', [
            deviceId : '1030',
            attribute: 'switch',
            timeoutMs: 1000
        ])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('expectedvalue')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "poll_until_attribute via dispatch returns -32602 when unknown arg is passed (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(
            id: 1040,
            label: 'Dispatch Unknown Arg',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('poll_until_attribute', [
            deviceId       : '1040',
            attribute      : 'switch',
            expectedValue  : 'on',
            timeoutSeconds : 10
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('timeoutSeconds')
        response.error.message.contains('timeoutMs')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "poll_until_attribute via dispatch returns success envelope with interrupted=true when pauseExecution throws InterruptedException (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(
            id: 1050,
            label: 'Dispatch Interrupted',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        script.metaClass.pauseExecution = { long ms ->
            throw new InterruptedException("hub reloading")
        }

        when:
        def response = mcpDriver.callTool('poll_until_attribute', [
            deviceId      : '1050',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then: 'InterruptedException is caught inside the tool and surfaced as a normal tool result'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.interrupted == true
        inner.finalValue == 'off'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "poll_until_attribute via dispatch returns isError envelope when pauseExecution throws RuntimeException (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(
            id: 1060,
            label: 'Dispatch Runtime Boom',
            supportedAttributes: [[name: 'switch']],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        script.metaClass.pauseExecution = { long ms ->
            throw new RuntimeException("simulated bug in sleep path")
        }

        when:
        def response = mcpDriver.callTool('poll_until_attribute', [
            deviceId      : '1060',
            attribute     : 'switch',
            expectedValue : 'on',
            timeoutMs     : 5000,
            pollIntervalMs: 200
        ])

        then: 'non-IAE escapes the tool catch and lands in handleToolsCall generic-Exception path -> isError envelope per MCP spec'
        response.error == null
        response.result.isError == true
        response.result.content[0].type == 'text'
        response.result.content[0].text.startsWith('Tool error:')
        response.result.content[0].text.contains('simulated bug')

        where:
        useGateways << [true, false]
    }
}
