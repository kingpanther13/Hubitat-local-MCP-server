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
 *  - Type-validation throws (deviceId as int, attribute as list, timeoutMs string, expectedValues with int element)
 *  - timeoutMs out of range (< 100, > 60000)
 *  - pollIntervalMs out of range (< 50, > 5000)
 *  - pollIntervalMs > timeoutMs -> auto-clamped (at least one poll still fires)
 *  - Device not found -> throws
 *  - Attribute with null currentValue -> polls until timeout, returns cleanly
 *  - Attribute name typo (not in supportedAttributes) -> throws with helpful message listing available attributes
 *  - Unknown arg (timeoutSeconds) -> throws with message naming the bad key and suggesting timeoutMs
 *  - Multiple unknown args -> all listed in the error plus gotcha hint
 *
 * pauseExecution() is declared on AppExecutor / BaseExecutor, so it goes through
 * the @Delegate chain -- it's a no-op in tests (the Mock doesn't stub it, which
 * means Spock returns null/void silently, effectively making sleeps instant).
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
}
