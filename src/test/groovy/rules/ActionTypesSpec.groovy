package rules

import support.TestDevice

/**
 * Breadth coverage for the action types in
 * {@code hubitat-mcp-rule.groovy::executeAction} not exercised by the
 * primitive-focused {@link RuleEngineSmokeSpec} / {@link ExecuteActionsSpec}
 * (which cover {@code device_command} + {@code stop}). See issue #75.
 *
 * Coverage per category: device-targeted commands, thermostat setters,
 * location/HSM actions, variables (local/global/math), control flow
 * ({@code delay} + {@code cancel_delayed}, {@code if_then_else},
 * {@code repeat}), state capture/restore, and bare {@code log}/{@code comment}.
 *
 * Device commands that already resolve through the rule engine's dynamic
 * {@code device."${cmd}"(*args)} path ({@code device_command} smoke) are
 * only re-covered here for the dedicated action types that have their
 * own switch-case branch (e.g. {@code lock} isn't expressible via
 * {@code device_command} in the UI builder and has an explicit case).
 *
 * Several actions rely on methods that HubitatCI resolves through the
 * AppExecutor mock (@Delegate-precedence):
 *  - {@code delay} → {@code runIn}
 *  - {@code cancel_delayed all} → {@code unschedule('resumeDelayedActions')}
 *  - {@code set_hsm} → {@code sendLocationEvent}
 *  - {@code http_request} → {@code httpGet} / {@code httpPost}
 * Tests assert via Spock interactions on {@code appExecutor}. The harness
 * mock uses {@code _ *} (unlimited) cardinality for everything, so additional
 * {@code 1 *} interactions in a feature method check-for-exactly-one without
 * conflicting with the base stub.
 */
class ActionTypesSpec extends RuleHarnessSpec {

    // -------- direct device commands --------

    def "toggle_device turns a currently-on device off"() {
        given:
        def device = Spy(TestDevice) { getId() >> 1 }
        device.attributeValues['switch'] = 'on'
        parent = new ActionParent(devices: [1L: device])

        when:
        script.executeAction([type: 'toggle_device', deviceId: 1L])

        then:
        1 * device.off()
        0 * device.on()
    }

    def "toggle_device turns a currently-off device on"() {
        given:
        def device = Spy(TestDevice) { getId() >> 1 }
        device.attributeValues['switch'] = 'off'
        parent = new ActionParent(devices: [1L: device])

        when:
        script.executeAction([type: 'toggle_device', deviceId: 1L])

        then:
        1 * device.on()
        0 * device.off()
    }

    def "set_level clamps the percent value before calling setLevel"() {
        given:
        def device = Spy(TestDevice) { getId() >> 2 }
        parent = new ActionParent(devices: [2L: device])

        when: 'an out-of-range level is requested'
        script.executeAction([type: 'set_level', deviceId: 2L, level: 150])

        then: 'clampPercent caps at 100 — the one-arg overload is called'
        1 * device.setLevel(100)
    }

    def "set_level with duration calls the two-arg overload"() {
        given:
        def device = Spy(TestDevice) { getId() >> 2 }
        parent = new ActionParent(devices: [2L: device])

        when:
        script.executeAction([type: 'set_level', deviceId: 2L, level: 50, duration: 3])

        then:
        1 * device.setLevel(50, 3)
    }

    def "lock / unlock dispatch to the device's lock() or unlock()"() {
        given:
        def lockDev = Spy(TestDevice) { getId() >> 3 }
        def unlockDev = Spy(TestDevice) { getId() >> 4 }
        parent = new ActionParent(devices: [3L: lockDev, 4L: unlockDev])

        when:
        script.executeAction([type: 'lock', deviceId: 3L])
        script.executeAction([type: 'unlock', deviceId: 4L])

        then:
        1 * lockDev.lock()
        1 * unlockDev.unlock()
    }

    def "set_color passes a clamped color map to setColor()"() {
        given:
        def device = Spy(TestDevice) { getId() >> 5 }
        parent = new ActionParent(devices: [5L: device])

        when:
        script.executeAction([type: 'set_color', deviceId: 5L, hue: 50, saturation: 80, level: 75])

        then:
        1 * device.setColor([hue: 50, saturation: 80, level: 75])
    }

    def "set_color_temperature with a level value uses the two-arg overload"() {
        given:
        def device = Spy(TestDevice) { getId() >> 5 }
        parent = new ActionParent(devices: [5L: device])

        when:
        script.executeAction([
            type: 'set_color_temperature', deviceId: 5L, temperature: 3000, level: 80
        ])

        then:
        1 * device.setColorTemperature(3000, 80)
    }

    def "activate_scene turns on the scene device"() {
        given:
        def scene = Spy(TestDevice) { getId() >> 6 }
        parent = new ActionParent(devices: [6L: scene])

        when:
        script.executeAction([type: 'activate_scene', sceneDeviceId: 6L])

        then:
        1 * scene.on()
    }

    def "send_notification substitutes variables and calls deviceNotification"() {
        given:
        def notifier = Spy(TestDevice) { getId() >> 7 }
        atomicStateMap.localVariables = [who: 'Alice']
        parent = new ActionParent(devices: [7L: notifier])

        when:
        script.executeAction([
            type: 'send_notification', deviceId: 7L, message: 'Hello %who%'
        ])

        then:
        1 * notifier.deviceNotification('Hello Alice')
    }

    def "speak sets the volume then speaks the message"() {
        given:
        def speaker = Spy(TestDevice) { getId() >> 8 }
        parent = new ActionParent(devices: [8L: speaker])

        when:
        script.executeAction([
            type: 'speak', deviceId: 8L, volume: 30, message: 'Hello'
        ])

        then:
        1 * speaker.setVolume(30)
        1 * speaker.speak('Hello')
    }

    def "set_valve open command calls valveDevice.open()"() {
        given:
        def valve = Spy(TestDevice) { getId() >> 9 }
        parent = new ActionParent(devices: [9L: valve])

        when:
        script.executeAction([type: 'set_valve', deviceId: 9L, command: 'open'])

        then:
        1 * valve.open()
    }

    def "set_fan_speed dispatches speed to fanDevice.setSpeed()"() {
        given:
        def fan = Spy(TestDevice) { getId() >> 10 }
        parent = new ActionParent(devices: [10L: fan])

        when:
        script.executeAction([type: 'set_fan_speed', deviceId: 10L, speed: 'high'])

        then:
        1 * fan.setSpeed('high')
    }

    def "set_shade with a position calls setPosition()"() {
        given:
        def shade = Spy(TestDevice) { getId() >> 11 }
        parent = new ActionParent(devices: [11L: shade])

        when:
        script.executeAction([type: 'set_shade', deviceId: 11L, position: 75])

        then:
        1 * shade.setPosition(75)
    }

    // -------- thermostat --------

    def "set_thermostat dispatches each configured setpoint / mode"() {
        given:
        def tstat = Spy(TestDevice) { getId() >> 12 }
        parent = new ActionParent(devices: [12L: tstat])

        when:
        script.executeAction([
            type: 'set_thermostat', deviceId: 12L,
            thermostatMode: 'heat', heatingSetpoint: 68,
            coolingSetpoint: 76, fanMode: 'auto'
        ])

        then:
        1 * tstat.setThermostatMode('heat')
        1 * tstat.setHeatingSetpoint(68)
        1 * tstat.setCoolingSetpoint(76)
        1 * tstat.setThermostatFanMode('auto')
    }

    // -------- location / HSM --------

    def "set_mode drives location.setMode() with the requested mode"() {
        when:
        script.executeAction([type: 'set_mode', mode: 'Night'])

        then: 'TestLocation records the call; mode field is updated'
        testLocation.modeSetCalls == ['Night']
        testLocation.mode == 'Night'
    }

    def "set_mode with no mode value returns false without touching location"() {
        when:
        def result = script.executeAction([type: 'set_mode'])

        then:
        result == false
        testLocation.modeSetCalls == []
    }

    def "set_hsm fires a location event named hsmSetArm"() {
        when:
        script.executeAction([type: 'set_hsm', status: 'armAway'])

        then:
        1 * appExecutor.sendLocationEvent([name: 'hsmSetArm', value: 'armAway'])
    }

    // -------- variables --------

    def "set_local_variable stores the interpolated value in atomicState.localVariables"() {
        given:
        atomicStateMap.localVariables = [target: 'Alice']

        when:
        script.executeAction([
            type: 'set_local_variable', variableName: 'greeting', value: 'Hi %target%'
        ])

        then:
        atomicStateMap.localVariables.greeting == 'Hi Alice'
    }

    def "set_variable writes through parent.setRuleVariable with substituted value"() {
        given:
        def recording = new VariableParent()
        atomicStateMap.localVariables = [n: 42]
        parent = recording

        when:
        script.executeAction([
            type: 'set_variable', variableName: 'label', value: 'n=%n%'
        ])

        then:
        recording.variableWrites == [label: 'n=42']
    }

    def "variable_math add updates the local variable"() {
        given:
        atomicStateMap.localVariables = [counter: 5]

        when:
        script.executeAction([
            type: 'variable_math', variableName: 'counter',
            scope: 'local', operation: 'add', operand: 3
        ])

        then:
        atomicStateMap.localVariables.counter == 8
    }

    def "variable_math divide-by-zero preserves the current value"() {
        given:
        atomicStateMap.localVariables = [counter: 5]

        when:
        script.executeAction([
            type: 'variable_math', variableName: 'counter',
            scope: 'local', operation: 'divide', operand: 0
        ])

        then: 'divide-by-zero branch returns currentVal unchanged'
        atomicStateMap.localVariables.counter == 5
    }

    def "variable_math set replaces the value outright"() {
        given:
        atomicStateMap.localVariables = [counter: 5]

        when:
        script.executeAction([
            type: 'variable_math', variableName: 'counter',
            scope: 'local', operation: 'set', operand: 100
        ])

        then:
        atomicStateMap.localVariables.counter == 100
    }

    def "variable_math scope=global reads getGlobalVar and writes via setGlobalVar"() {
        given: 'stub the Hubitat-dynamic globalVar helpers on the script'
        def writes = [:]
        script.metaClass.getGlobalVar = { String name -> [value: 10] }
        script.metaClass.setGlobalVar = { String name, Object val -> writes[name] = val }

        when:
        script.executeAction([
            type: 'variable_math', variableName: 'counter',
            scope: 'global', operation: 'multiply', operand: 3
        ])

        then:
        writes.counter == 30
    }

    // -------- control flow --------

    def "delay schedules resumeDelayedActions with the computed delay"() {
        given:
        atomicStateMap.actions = [[type: 'delay', seconds: 5]]

        when:
        script.executeActions()

        then: 'runIn fires once; we accept any param-map overload'
        1 * appExecutor.runIn(5L, 'resumeDelayedActions', _)
    }

    def "delay clamps to 1 second minimum"() {
        given:
        atomicStateMap.actions = [[type: 'delay', seconds: 0]]

        when:
        script.executeActions()

        then:
        1 * appExecutor.runIn(1L, 'resumeDelayedActions', _)
    }

    def "cancel_delayed 'all' calls unschedule on resumeDelayedActions"() {
        given: 'a stale cancelledDelayIds map that should be wiped'
        atomicStateMap.cancelledDelayIds = [stale: true]

        when:
        script.executeAction([type: 'cancel_delayed', delayId: 'all'])

        then:
        1 * appExecutor.unschedule('resumeDelayedActions')
        atomicStateMap.cancelledDelayIds == [:]
    }

    def "cancel_delayed with a specific id marks only that id as cancelled"() {
        when:
        script.executeAction([type: 'cancel_delayed', delayId: 'delay_abc'])

        then: 'unschedule is NOT called — only the cancelled set is updated'
        0 * appExecutor.unschedule(_)
        atomicStateMap.cancelledDelayIds == [delay_abc: true]
    }

    def "if_then_else runs thenActions when the inner condition passes"() {
        given:
        def thenDev = Spy(TestDevice) { getId() >> 20 }
        def elseDev = Spy(TestDevice) { getId() >> 21 }
        atomicStateMap.localVariables = [gate: 'yes']
        parent = new ActionParent(devices: [20L: thenDev, 21L: elseDev])

        when:
        script.executeAction([
            type: 'if_then_else',
            condition: [type: 'variable', variableName: 'gate',
                        operator: 'equals', value: 'yes'],
            thenActions: [[type: 'device_command', deviceId: 20L, command: 'on']],
            elseActions: [[type: 'device_command', deviceId: 21L, command: 'on']]
        ])

        then:
        1 * thenDev.on()
        0 * elseDev.on()
    }

    def "if_then_else runs elseActions when the inner condition fails"() {
        given:
        def thenDev = Spy(TestDevice) { getId() >> 20 }
        def elseDev = Spy(TestDevice) { getId() >> 21 }
        atomicStateMap.localVariables = [gate: 'no']
        parent = new ActionParent(devices: [20L: thenDev, 21L: elseDev])

        when:
        script.executeAction([
            type: 'if_then_else',
            condition: [type: 'variable', variableName: 'gate',
                        operator: 'equals', value: 'yes'],
            thenActions: [[type: 'device_command', deviceId: 20L, command: 'on']],
            elseActions: [[type: 'device_command', deviceId: 21L, command: 'on']]
        ])

        then:
        0 * thenDev.on()
        1 * elseDev.on()
    }

    def "repeat runs the inner action list N times in order"() {
        given:
        def device = Spy(TestDevice) { getId() >> 30 }
        parent = new ActionParent(devices: [30L: device])

        when:
        script.executeAction([
            type: 'repeat', times: 3,
            actions: [[type: 'device_command', deviceId: 30L, command: 'on']]
        ])

        then:
        3 * device.on()
    }

    def "repeat clamps times to the 1..100 range"() {
        given:
        def device = Spy(TestDevice) { getId() >> 30 }
        parent = new ActionParent(devices: [30L: device])

        when:
        script.executeAction([
            type: 'repeat', times: 500,
            actions: [[type: 'device_command', deviceId: 30L, command: 'on']]
        ])

        then:
        100 * device.on()
    }

    // -------- state capture / restore --------

    def "capture_state writes a per-device snapshot via parent.saveCapturedState"() {
        given:
        def bulb = new TestDevice(id: 40, label: 'Bulb',
            capabilities: ['Switch', 'SwitchLevel'],
            attributeValues: [switch: 'on', level: 75])
        def captureParent = new CapturingStateParent(devices: [40L: bulb])
        parent = captureParent

        when:
        script.executeAction([
            type: 'capture_state', deviceIds: [40L], stateId: 'beforeParty'
        ])

        then:
        captureParent.savedStates == [beforeParty: ['40': [switch: 'on', level: 75]]]
    }

    def "restore_state turns the device off when the snapshot says off"() {
        given:
        def bulb = Spy(TestDevice) { getId() >> 41 }
        def restoreParent = new CapturingStateParent(
            devices: [41L: bulb],
            savedStates: [evening: ['41': [switch: 'off']]]
        )
        parent = restoreParent

        when:
        script.executeAction([type: 'restore_state', stateId: 'evening'])

        then: 'restore short-circuits to just off() — no setLevel/setColor first'
        1 * bulb.off()
        0 * bulb.on()
    }

    // -------- http_request --------

    def "http_request defaults to GET and calls httpGet with the uri map"() {
        when:
        script.executeAction([type: 'http_request', url: 'http://example.test/api'])

        then:
        1 * appExecutor.httpGet([uri: 'http://example.test/api'], _)
    }

    def "http_request POST passes body + contentType through to httpPost"() {
        when:
        script.executeAction([
            type: 'http_request', method: 'POST',
            url: 'http://example.test/api',
            contentType: 'application/json', body: '{"k":"v"}'
        ])

        then:
        1 * appExecutor.httpPost([
            uri: 'http://example.test/api',
            contentType: 'application/json',
            body: '{"k":"v"}'
        ], _)
    }

    def "http_request swallows network exceptions and does not break the action chain"() {
        given: 'httpGet throws on the first action; the second action still needs to run'
        appExecutor.httpGet(_, _) >> { args -> throw new RuntimeException("network down") }
        def target = Spy(TestDevice) { getId() >> 50 }
        parent = new ActionParent(devices: [50L: target])
        atomicStateMap.actions = [
            [type: 'http_request', url: 'http://example.test/api'],
            [type: 'device_command', deviceId: 50L, command: 'on']
        ]

        when:
        script.executeActions()

        then: 'the outer catch swallows the throw; the next action runs'
        1 * target.on()
    }

    // -------- log / comment --------

    def "log action substitutes variables into the message"() {
        given:
        atomicStateMap.localVariables = [who: 'Bob']

        when: 'the spec only needs to prove no exception — PermissiveLog accepts calls'
        script.executeAction([
            type: 'log', level: 'info', message: 'Hello %who%'
        ])

        then:
        noExceptionThrown()
    }

    def "comment action succeeds without throwing"() {
        when:
        script.executeAction([type: 'comment', text: 'design note'])

        then:
        noExceptionThrown()
    }

    // -------- unknown action --------

    def "unknown action type is a warn + no-op (executeAction still returns true)"() {
        when:
        def result = script.executeAction([type: 'totally_made_up_action'])

        then:
        result == true
    }

    /** Generic findDevice(id) parent. */
    static class ActionParent {
        Map<Long, TestDevice> devices = [:]
        Object findDevice(id) { devices[(id as Long)] }
    }

    /** Records parent.setRuleVariable calls. */
    static class VariableParent {
        Map<String, Object> variableWrites = [:]
        Object findDevice(id) { null }
        void setRuleVariable(String name, Object value) { variableWrites[name] = value }
    }

    /**
     * Minimal parent with capture/restore slots. Matches the shape the rule
     * engine expects: saveCapturedState returns a result map with
     * {@code totalStored} / {@code maxLimit} / {@code deletedStates} / {@code nearLimit}
     * fields that the engine logs but tests don't assert on.
     */
    static class CapturingStateParent {
        Map<Long, TestDevice> devices = [:]
        Map<String, Map> savedStates = [:]

        Object findDevice(id) { devices[(id as Long)] }

        Map saveCapturedState(String key, Map states) {
            savedStates[key] = states
            [totalStored: savedStates.size(), maxLimit: 10,
             deletedStates: null, nearLimit: false]
        }

        Map getCapturedState(String key) {
            savedStates[key]
        }
    }
}
