package server

import support.TestDevice
import support.ToolSpecBase

/**
 * Device-tool primitives: findDevice (the resolver used by every
 * device-targeting tool), toolGetDevice (summary response shape), and
 * toolSendCommand (command dispatch).
 *
 * Consolidated from FindDeviceSpec + ToolGetDeviceSpec + ToolSendCommandSpec —
 * same harness fixture, same support imports, three thematically adjacent
 * slices of the device-tool surface. Sandbox compile is amortised across
 * all 11 features instead of three separate spec-class compiles.
 *
 * findDevice search order: settings.selectedDevices first, then
 * getChildDevices(). Returns null on miss. Both String and Integer ids
 * are accepted (internally coerces via .toString() equality).
 */
class ToolDeviceBasicsSpec extends ToolSpecBase {

    // ---- findDevice resolver -------------------------------------------------

    def "findDevice finds device in settings.selectedDevices by integer id"() {
        given:
        def device = new TestDevice(id: 10, label: 'In Settings')
        settingsMap.selectedDevices = [device]

        expect:
        script.findDevice(10)?.is(device)
    }

    def "findDevice finds device in settings.selectedDevices by string id"() {
        given:
        def device = new TestDevice(id: 10, label: 'In Settings')
        settingsMap.selectedDevices = [device]

        expect:
        script.findDevice('10')?.is(device)
    }

    def "findDevice falls through to getChildDevices when not in settings.selectedDevices"() {
        given:
        settingsMap.selectedDevices = []
        def virtual = new TestDevice(id: 20, label: 'Virtual Child')
        childDevicesList << virtual

        expect:
        script.findDevice('20')?.is(virtual)
    }

    def "findDevice finds device in getChildDevices by integer id (fallthrough branch coercion)"() {
        given:
        settingsMap.selectedDevices = []
        def virtual = new TestDevice(id: 20, label: 'Virtual Child')
        childDevicesList << virtual

        expect:
        script.findDevice(20)?.is(virtual)
    }

    def "findDevice gives settings.selectedDevices priority over getChildDevices on id collision"() {
        given:
        def fromSettings = new TestDevice(id: 30, label: 'From Settings')
        def fromChildren = new TestDevice(id: 30, label: 'From Children')
        settingsMap.selectedDevices = [fromSettings]
        childDevicesList << fromChildren

        expect:
        script.findDevice('30')?.is(fromSettings)
    }

    def "findDevice returns null when id is not found anywhere"() {
        given:
        settingsMap.selectedDevices = []
        childDevicesList.clear()

        expect:
        script.findDevice('404') == null
    }

    def "findDevice returns null for null id without throwing"() {
        expect:
        script.findDevice(null) == null
    }

    // no dispatch counterparts for findDevice: helper, not a tool

    // ---- toolGetDevice response shape ---------------------------------------

    def "toolGetDevice returns device summary shape for an existing device"() {
        given:
        def device = new TestDevice(
            id: 10,
            name: 'TestSwitch',
            label: 'Test Switch',
            roomName: 'Living Room',
            capabilities: [[name: 'Switch']],
            supportedAttributes: [[name: 'switch', dataType: 'ENUM']],
            supportedCommands: [[name: 'on', arguments: null], [name: 'off', arguments: null]],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        when:
        def result = script.toolGetDevice('10')

        then:
        result.id == '10'
        result.label == 'Test Switch'
        result.room == 'Living Room'
        result.capabilities == ['Switch']
        result.attributes.size() == 1
        result.attributes[0].name == 'switch'
        result.attributes[0].value == 'off'
    }

    @spock.lang.Unroll
    def "via dispatch: hub_get_device returns device summary shape for an existing device (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(
            id: 10,
            name: 'TestSwitch',
            label: 'Test Switch',
            roomName: 'Living Room',
            capabilities: [[name: 'Switch']],
            supportedAttributes: [[name: 'switch', dataType: 'ENUM']],
            supportedCommands: [[name: 'on', arguments: null], [name: 'off', arguments: null]],
            attributeValues: [switch: 'off']
        )
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '10'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.id == '10'
        inner.label == 'Test Switch'
        inner.room == 'Living Room'
        inner.capabilities == ['Switch']
        inner.attributes.size() == 1
        inner.attributes[0].name == 'switch'
        inner.attributes[0].value == 'off'

        where:
        useGateways << [true, false]
    }

    def "toolGetDevice throws when device is not found"() {
        given:
        childDevicesList.clear()
        settingsMap.selectedDevices = []

        when:
        script.toolGetDevice('999')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Device not found: 999'
    }

    @spock.lang.Unroll
    def "via dispatch: hub_get_device returns -32602 when device is not found (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        childDevicesList.clear()
        settingsMap.selectedDevices = []

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: '999'])

        then:
        response.error.code == -32602
        response.error.message.contains('Device not found: 999')

        where:
        useGateways << [true, false]
    }

    // ---- toolSendCommand dispatch -------------------------------------------

    def "toolSendCommand dispatches command to device and returns success"() {
        given: 'a TestDevice that supports on/off and reports its switch state'
        // Live hubs hand back java.util.Date in State.date -- mock the LIVE type so this
        // exercises the production direct Date.format(String) path (a String mock would
        // not, and could mask a Date-handling regression).
        def stateDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> [[name: 'switch', value: 'on', date: stateDate]]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'the device method was invoked exactly once'
        1 * device.on()

        and: 'the result shape reflects success'
        result.success == true
        result.command == 'on'
        result.device == 'Test Switch'

        and: 'the post-command state snapshot carries the attribute value (not just an empty map)'
        result.state instanceof Map
        result.state.switch?.value == 'on'
    }

    def "toolSendCommand returns a post-command state snapshot from currentStates (value + timestamp)"() {
        given: 'a device whose currentStates expose name/value/date as a real Date (the live type)'
        def stateDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> [
                [name: 'switch', value: 'on', date: stateDate],
                [name: 'level', value: 75, date: null]
            ]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'a Date is formatted as yyyy-MM-dd HH:mm:ss (guards against the formatTimestamp-on-Date toString mangle)'
        result.state.switch.value == 'on'
        result.state.switch.timestamp == '2025-01-15 10:30:00'
        result.state.switch.timestamp ==~ /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/

        and: 'an attribute with no event date carries a null timestamp (never the literal "Never")'
        result.state.level.value == 75
        result.state.level.timestamp == null
    }

    @spock.lang.Unroll
    def "toolSendCommand snapshot falls back to supportedAttributes + currentValue when currentStates is falsy (#emptyStates)"() {
        given: 'a device with no currentStates (null OR empty list both falsy) but declared attributes'
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> emptyStates
            getSupportedAttributes() >> [[name: 'switch']]
            currentValue('switch') >> 'on'
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'the fallback path reports the current value with a null timestamp'
        result.state.switch.value == 'on'
        result.state.switch.timestamp == null

        where: 'both falsy shapes -- guards a future "if (states != null)" refactor from skipping the empty-list case'
        emptyStates << [null, []]
    }

    def "toolSendCommand snapshot fallback degrades only the throwing attribute, keeping the others"() {
        given: 'a no-currentStates device whose currentValue throws for one attribute but returns for the other'
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> []
            getSupportedAttributes() >> [[name: 'switch'], [name: 'level']]
            currentValue('switch') >> { String a -> throw new RuntimeException('read exploded') }
            currentValue('level') >> '50'
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'the throwing attribute degrades to value:null but the other attribute survives -- NOT a whole-snapshot discard'
        result.success == true
        result.state.switch.value == null
        result.state.level.value == '50'
        !result.containsKey('stateError')
    }

    def "toolSendCommand snapshot is an empty map when the device exposes no states and no attributes"() {
        given: 'a device that reports neither currentStates nor supportedAttributes'
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> []
            getSupportedAttributes() >> []
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'the command succeeds with an empty snapshot and NO stateError (legitimately empty, not a failed read)'
        result.success == true
        result.state == [:]
        !result.containsKey('stateError')
    }

    def "toolSendCommand snapshot read-back failure never breaks the command response and surfaces stateError"() {
        given: 'a device whose state read throws after the command fires'
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> { throw new RuntimeException('boom') }
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'the command still succeeds; state clears to empty but stateError signals the failed read (with class + message)'
        1 * device.on()
        result.success == true
        result.state == [:]
        result.stateError?.contains('device-state read-back failed')
        result.stateError?.contains('RuntimeException')
        result.stateError?.contains('boom')

        and: 'a degraded confirmation step flags partial=true'
        result.partial == true
    }

    def "toolSendCommand snapshot discards the partial map when a later attribute throws mid-iteration"() {
        given: 'currentStates whose SECOND entry throws when read, after the first was already added'
        def goodDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def throwingState = new Object() {
            String getName() { throw new RuntimeException('exploding state') }
            Object getValue() { 'x' }
            Date getDate() { null }
        }
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> [[name: 'switch', value: 'on', date: goodDate], throwingState]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'the partial snapshot (the switch entry built before the throw) is discarded, not leaked, and stateError marks the failed read'
        1 * device.on()
        result.success == true
        result.state == [:]
        result.stateError?.contains('device-state read-back failed')
    }

    def "toolSendCommand snapshot keeps an attribute with timestamp null when only its date format throws (other attrs intact)"() {
        given: 'a state whose date is truthy but throws on format, alongside a clean state'
        def goodDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def explodingDate = new Object() {
            String format(String pattern) { throw new RuntimeException('bad date') }
        }
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> [
                [name: 'switch', value: 'on', date: explodingDate],
                [name: 'level', value: 75, date: goodDate]
            ]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'the date-only failure degrades that attribute to timestamp null, keeping its value'
        1 * device.on()
        result.success == true
        result.state.switch.value == 'on'
        result.state.switch.timestamp == null

        and: 'the other attribute and its formatted timestamp survive (snapshot not discarded)'
        result.state.level.value == 75
        result.state.level.timestamp == '2025-01-15 10:30:00'
    }

    // ---- toolSendCommand waitFor (block-poll to the resulting state) --------

    def "toolSendCommand with waitFor that converges reports converged + snapshot reflects the target (taken AFTER the poll)"() {
        given: 'a device whose switch is OFF until the poll runs, then converges to ON'
        // Stateful: currentStates reads off on the first poll read, then on. The poll engine
        // AND the post-poll snapshot both read currentStates, so a snapshot taken BEFORE the
        // poll would read off; taken AFTER (the production order) it reads the converged on.
        // This makes the "snapshot reflects the converged value" assertion non-vacuous: it
        // would FAIL if the snapshot were moved ahead of the poll.
        def stateDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def reads = 0
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> { [[name: 'switch', value: ((++reads >= 2) ? 'on' : 'off'), date: stateDate]] }
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', timeoutMs: 5000, pollIntervalMs: 50])

        then: 'the command fired and the waitFor block reports convergence'
        1 * device.on()
        result.success == true
        result.waitFor.attribute == 'switch'
        result.waitFor.expected == 'on'
        result.waitFor.converged == true
        result.waitFor.finalValue == 'on'
        result.waitFor.elapsedMs instanceof Integer
        result.waitFor.elapsedMs >= 0

        and: 'the snapshot taken AFTER the poll reflects the converged value (not the pre-poll off)'
        result.state.switch.value == 'on'

        and: 'a fully clean result carries NO partial flag'
        !result.containsKey('partial')
    }

    def "toolSendCommand with waitFor converges off the live event store even when currentValue AND currentState stay stale (real async device)"() {
        given: 'a device where currentValue AND currentState are frozen at the PRE-command value while currentStates reports the FRESH value'
        // Real-device failure mode (confirmed on real hardware): on Matter/Zigbee/Z-Wave/cloud
        // devices the hub caches BOTH currentValue() and currentState(attr) at request start and
        // never refreshes them within the request, so a poll reading either would read the
        // pre-command value for the whole loop and ALWAYS time out -- even though the device
        // already reported the new value. Only the full currentStates list re-reads live. This
        // guard FAILS (times out, converged=false) if the poll source reverts to currentValue()
        // OR currentState(attr).
        def stateDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            // currentValue is FROZEN stale (pre-command), as the hub returns within a request.
            currentValue('switch') >> 'off'
            // currentState(attr) is ALSO frozen stale -- it is cached the same way.
            currentState('switch') >> [value: 'off']
            // currentStates reports the FRESH post-command value -- the only source that re-reads live.
            getCurrentStates() >> [[name: 'switch', value: 'on', date: stateDate]]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 50])

        then: 'the poll converges on the fresh currentStates value (would time out if it read stale currentValue or currentState)'
        1 * device.on()
        result.success == true
        result.waitFor.converged == true
        result.waitFor.finalValue == 'on'
        result.waitFor.timedOut == null
    }

    def "toolSendCommand with waitFor that never matches reports converged=false + finalValue"() {
        given: 'a device whose switch stays off so the poll times out'
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> [[name: 'switch', value: 'off']]
        }
        childDevicesList << device

        when: 'a short timeout keeps the test fast'
        def result = script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', timeoutMs: 100, pollIntervalMs: 50])

        then: 'the command still fired and waitFor reports non-convergence with the last value read, flagged timedOut'
        1 * device.on()
        result.success == true
        result.waitFor.converged == false
        result.waitFor.finalValue == 'off'
        result.waitFor.timedOut == true
    }

    def "toolSendCommand with waitFor on an attribute that never reports flags neverReported"() {
        given: 'a device whose attribute exists but always reads null (driver never emitted it)'
        // The attribute is absent from currentStates until it has reported -- find returns
        // null, the neverReported signal. An empty list keeps the snapshot fallback path too.
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> []
        }
        childDevicesList << device

        when: 'a short timeout keeps the test fast'
        def result = script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', timeoutMs: 100, pollIntervalMs: 50])

        then: 'non-convergence is flagged neverReported (distinct from a wrong-value timeout)'
        1 * device.on()
        result.success == true
        result.waitFor.converged == false
        result.waitFor.neverReported == true
    }

    def "toolSendCommand with waitFor surfaces interrupted when the poll is interrupted (hub reload)"() {
        given: 'a non-matching attribute so the poll reaches the sleep, where pauseExecution throws'
        def stateDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> [[name: 'switch', value: 'off', date: stateDate]]
        }
        childDevicesList << device
        script.metaClass.pauseExecution = { long ms -> throw new InterruptedException('hub reloading') }

        when:
        def result = script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', timeoutMs: 5000, pollIntervalMs: 250])

        then: 'the command fired and the waitFor block carries the interrupt flag (not a plain timeout)'
        1 * device.on()
        result.success == true
        result.waitFor.converged == false
        result.waitFor.interrupted == true
    }

    def "toolSendCommand with waitFor expectedValues (list OR) converges on any member"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> [[name: 'switch', value: 'on']]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValues: ['on', 'dim']])

        then:
        result.waitFor.converged == true
        result.waitFor.expected == ['on', 'dim']
        result.waitFor.finalValue == 'on'
    }

    def "toolSendCommand with waitFor matches a level attribute reported as a numeric string against a string expectedValue"() {
        given: 'a level attribute whose event store reports "50.0" while the spec awaits the string "50"'
        // A State's value is a String in Hubitat -- a driver that stores a numeric attribute
        // may report it as "50.0". The engine's numeric-string fallback matches that against
        // expectedValue "50", and finalValue is the String form (same source as the snapshot).
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'setLevel'], [name: 'on']]
            getSupportedAttributes() >> [[name: 'level']]
            getCurrentStates() >> [[name: 'level', value: '50.0']]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'setLevel', [50], [attribute: 'level', expectedValue: '50'])

        then: 'the engine numeric-string fallback matches; the converged finalValue is the String "50.0"'
        result.success == true
        result.waitFor.converged == true
        result.waitFor.finalValue == '50.0'
    }

    def "toolSendCommand without waitFor omits the waitFor block (unchanged immediate snapshot)"() {
        given:
        def stateDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> [[name: 'switch', value: 'off', date: stateDate]]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'no waitFor block; the snapshot is the immediate (pre-effect) read'
        result.success == true
        !result.containsKey('waitFor')
        result.state.switch.value == 'off'
    }

    def "toolSendCommand waitFor missing attribute throws IllegalArgumentException before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
        }
        childDevicesList << device

        when:
        script.toolSendCommand('10', 'on', [], [expectedValue: 'on'])

        then: 'rejected on validation, and the command never fired (no side effect)'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor.attribute')
        0 * device.on()
    }

    def "toolSendCommand waitFor with both expectedValue and expectedValues throws"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when:
        script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', expectedValues: ['on']])

        then: 'the BOTH-provided path is distinguished by its own substring'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not both')
        0 * device.on()
    }

    def "toolSendCommand waitFor with neither expectedValue nor expectedValues throws"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when:
        script.toolSendCommand('10', 'on', [], [attribute: 'switch'])

        then: 'the NEITHER-provided path is distinguished by its own substring'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one of expectedValue or expectedValues is required')
        0 * device.on()
    }

    def "toolSendCommand waitFor with out-of-range timeoutMs throws before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when: 'timeoutMs above the command-flow cap (30000) -- stricter than the standalone engine because a waitFor poll pins a hub thread for the full timeout'
        script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', timeoutMs: 30001])

        then: 'rejected pre-fire so the device is never actuated'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('timeoutMs')
        ex.message.contains('30000')
        0 * device.on()
    }

    def "toolSendCommand waitFor with out-of-range pollIntervalMs throws before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when: 'pollIntervalMs above the engine ceiling (5000)'
        script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', pollIntervalMs: 5001])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('pollIntervalMs')
        0 * device.on()
    }

    def "toolSendCommand waitFor with an empty expectedValues list throws before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when:
        script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValues: []])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedValues')
        0 * device.on()
    }

    def "toolSendCommand waitFor with a non-string expectedValues element throws before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when: 'a numeric element 42 in the list -- rejected before the command fires'
        script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValues: [42]])

        then: 'the bad element is named with its typed render, and the command never fired'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedValues')
        ex.message.contains('42 (number)')
        0 * device.on()
    }

    def "toolSendCommand waitFor with a whitespace-only expectedValue throws before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when: 'a whitespace-only expectedValue -- .trim() rejects it for parity with attribute'
        script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: '   '])

        then: 'rejected non-empty before fire; the empty render is quoted, not a bare gap'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor.expectedValue')
        ex.message.contains('"   "')
        0 * device.on()
    }

    def "toolSendCommand waitFor that is not a Map throws before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
        }
        childDevicesList << device

        when:
        script.toolSendCommand('10', 'on', [], 'on')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor must be an object')
        0 * device.on()
    }

    def "toolSendCommand waitFor with an unknown key throws before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
        }
        childDevicesList << device

        when:
        script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', bogus: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('unknown key')
        0 * device.on()
    }

    def "toolSendCommand waitFor with an unsupported attribute throws before firing the command"() {
        given: 'the device does not support the requested waitFor attribute'
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when:
        script.toolSendCommand('10', 'on', [], [attribute: 'level', expectedValue: '50'])

        then: 'rejected pre-fire so the device is never actuated'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("waitFor.attribute 'level' not found")
        0 * device.on()
    }

    def "toolSendCommand waitFor with a blank attribute throws before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
        }
        childDevicesList << device

        when:
        script.toolSendCommand('10', 'on', [], [attribute: '   ', expectedValue: 'on'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor.attribute is required')
        0 * device.on()
    }

    @spock.lang.Unroll
    def "toolSendCommand waitFor with a non-string/empty expectedValue throws before firing the command (#badValue)"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when:
        script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: badValue])

        then: 'rejected pre-fire -- a non-String or empty scalar never reaches the device'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('waitFor.expectedValue')
        0 * device.on()

        where: 'integer, boolean, null, and empty-string all rejected'
        badValue << [42, false, null, '']
    }

    def "toolSendCommand waitFor with an out-of-range fractional timeoutMs throws before firing the command"() {
        given:
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
        }
        childDevicesList << device

        when: 'a fractional value above the command-flow cap (30000)'
        script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', timeoutMs: 30000.5])

        then: 'rejected out-of-range before the device is actuated'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('timeoutMs')
        0 * device.on()
    }

    def "toolSendCommand waitFor accepts an in-range non-Integer Number for timeoutMs/pollIntervalMs (#desc)"() {
        given: 'a device that converges immediately so an accepted spec fires the command'
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> [[name: 'switch', value: 'on']]
        }
        childDevicesList << device

        when: 'an in-range Long / BigDecimal that the engine accepts via instanceof Number'
        def result = script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on', timeoutMs: t, pollIntervalMs: p])

        then: 'the spec is accepted (not rejected pre-fire) and the command fires'
        1 * device.on()
        result.success == true
        result.waitFor.converged == true

        where:
        desc                  | t          | p
        'Long'                | 5000L      | 50L
        'in-range BigDecimal' | 5000.0G    | 50.0G
    }

    def "toolSendCommand waitFor poll-loop exception still returns success + snapshot (command already fired)"() {
        given: 'the poll loop throws after the command fires (e.g. a malformed read)'
        // The poll AND the snapshot both read currentStates. Throw on the poll's first read,
        // then return a valid list so the post-fire snapshot still succeeds.
        def stateDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def reads = 0
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> {
                if (++reads == 1) throw new RuntimeException('read exploded')
                return [[name: 'switch', value: 'on', date: stateDate]]
            }
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on'])

        then: 'the command succeeded, waitFor reports non-convergence with an error note carrying the class, snapshot still taken'
        1 * device.on()
        result.success == true
        result.waitFor.converged == false
        result.waitFor.finalValue == null
        result.waitFor.error?.contains('waitFor poll failed')
        result.waitFor.error?.contains('RuntimeException')
        result.state.switch.value == 'on'

        and: 'the snapshot itself succeeded (read 2 valid), so partial comes purely from waitFor.error'
        result.partial == true
    }

    def "toolSendCommand waitFor poll-loop NON-Exception throwable still returns success + snapshot (catch Throwable)"() {
        given: 'the poll loop throws a bare Error (not an Exception) after the command fires'
        // The poll AND the snapshot both read currentStates. Throw on the poll's first read,
        // then return a valid list so the post-fire snapshot still succeeds.
        def stateDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def reads = 0
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> {
                if (++reads == 1) throw new StackOverflowError('deep')
                return [[name: 'switch', value: 'on', date: stateDate]]
            }
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [], [attribute: 'switch', expectedValue: 'on'])

        then: 'a non-Exception Throwable does NOT escape: command still succeeds, state present, waitFor.error carries the class'
        1 * device.on()
        result.success == true
        result.waitFor.converged == false
        result.waitFor.error?.contains('waitFor poll failed')
        result.waitFor.error?.contains('StackOverflowError')
        result.state.switch.value == 'on'
    }

    @spock.lang.Unroll
    def "via dispatch: hub_call_device_command dispatches command to device and returns success (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def stateDate = Date.parse("yyyy-MM-dd HH:mm:ss", "2025-01-15 10:30:00")
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> [[name: 'switch', value: 'on', date: stateDate]]
        }
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '10', command: 'on', parameters: []])

        then: 'the device method was invoked exactly once'
        1 * device.on()

        and: 'the dispatch envelope carries the success result plus the post-command state snapshot'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.command == 'on'
        inner.device == 'Test Switch'
        inner.state.switch.value == 'on'
        inner.state.switch.timestamp == '2025-01-15 10:30:00'

        where:
        useGateways << [true, false]
    }

    def "via dispatch: hub_call_device_command threads the waitFor arg through executeTool"() {
        given: 'a device already at the target so the poll converges immediately'
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'TestSwitch'
            getLabel() >> 'Test Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> [[name: 'switch', value: 'on']]
        }
        childDevicesList << device

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [
            deviceId: '10', command: 'on', parameters: [],
            waitFor: [attribute: 'switch', expectedValue: 'on']
        ])

        then: 'the waitFor result block survives the dispatch envelope'
        1 * device.on()
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.waitFor.converged == true
        inner.waitFor.finalValue == 'on'
    }

    def "toolSendCommand throws when device is not found"() {
        given:
        childDevicesList.clear()

        when:
        script.toolSendCommand('999', 'on', [])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Device not found: 999'
    }

    @spock.lang.Unroll
    def "via dispatch: hub_call_device_command returns -32602 when device is not found (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        childDevicesList.clear()

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '999', command: 'on', parameters: []])

        then:
        response.error.code == -32602
        response.error.message.contains('Device not found: 999')

        where:
        useGateways << [true, false]
    }
}
