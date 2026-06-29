package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import support.TestDevice
import support.ToolSpecBase

/**
 * Device-allowlist bypass: when settings.bypassDeviceAllowlist is ON, the per-device tools reach a
 * device that is NOT in settings.selectedDevices (and is not an MCP-managed virtual device) through
 * the hub's id-keyed admin endpoints instead of throwing "Device not found".
 *
 * Three invariants per tool:
 *   - toggle OFF + unlisted device           -> throws/blocked exactly as today
 *   - toggle ON  + unlisted device           -> routes to the fullJson/eventsJson/runmethod/admin endpoints
 *   - LISTED device with the toggle ON       -> UNCHANGED rich Groovy-device path (regression guard)
 *
 * Mocking: hubInternalGet is routed by HarnessSpec to the @Shared HubInternalGetMock
 * (hubGet.register(exactPath)); hubInternalPost / hubInternalPostJson / hubInternalPostFormRaw are
 * not on HubitatAppScript, so they're stubbed per-test on script.metaClass.
 */
class ToolDeviceAllowlistBypassSpec extends ToolSpecBase {

    static final String UNLISTED_ID = '555'

    // A fullJson model for an unlisted switch. `switchValue` lets a test drive the reported value.
    private static Map fullJsonModel(String switchValue = 'off') {
        [
            device: [
                id: 555, name: 'Unlisted Switch', label: 'Unlisted Switch',
                roomName: 'Garage', deviceNetworkId: 'ABCD', capabilities: ['Switch', 'SwitchLevel'],
                disabled: false, typeName: 'Generic Z-Wave Switch',
                currentStates: [
                    switch: [name: 'switch', attributeName: 'switch', value: switchValue,
                             dataType: 'ENUM', unit: null, numberValue: null,
                             date: '2026-06-27T10:30:00.000-0600'],
                    level: [name: 'level', attributeName: 'level', value: '40',
                            dataType: 'NUMBER', unit: '%', numberValue: 40,
                            date: '2026-06-27T10:30:00.000-0600']
                ]
            ],
            commands: [
                [capability: 'Switch', name: 'on', arguments: [], parameters: [], relatedAttribute: 'switch'],
                [capability: 'Switch', name: 'off', arguments: [], parameters: [], relatedAttribute: 'switch'],
                [capability: 'SwitchLevel', name: 'setLevel', arguments: ['NUMBER'],
                 parameters: [[name: 'level', type: 'NUMBER']], relatedAttribute: 'level']
            ],
            dashboards: []
        ]
    }

    private void registerFullJson(Closure valueProvider = { 'off' }) {
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params -> JsonOutput.toJson(fullJsonModel(valueProvider())) }
    }

    // A /device/eventsJson row list (newest-first), with descriptionText + ISO date strings.
    private static List eventsJsonModel() {
        [
            [id: 9002, name: 'switch', value: 'on', unit: null, descriptionText: 'Unlisted Switch was turned on',
             source: 'DEVICE', type: null, date: '2026-06-27T10:30:00.000-0600', isStateChange: true, deviceId: 555],
            [id: 9001, name: 'switch', value: 'off', unit: null, descriptionText: 'Unlisted Switch was turned off',
             source: 'DEVICE', type: null, date: '2026-06-27T10:00:00.000-0600', isStateChange: true, deviceId: 555]
        ]
    }

    private void registerEventsJson(List rows = eventsJsonModel()) {
        hubGet.register("/device/eventsJson/${UNLISTED_ID}") { params -> JsonOutput.toJson(rows) }
    }

    // ---- toggle OFF: unchanged "Device not found" -------------------------------

    def "bypass OFF: toolGetDevice throws for an unlisted device (no fullJson fetch)"() {
        given:
        settingsMap.bypassDeviceAllowlist = false

        when:
        script.toolGetDevice(UNLISTED_ID)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Device not found: ${UNLISTED_ID}"
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
    }

    def "bypass OFF: toolSendCommand throws for an unlisted device (no model read first)"() {
        given:
        settingsMap.bypassDeviceAllowlist = false

        when:
        script.toolSendCommand(UNLISTED_ID, 'on', [])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Device not found: ${UNLISTED_ID}"
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
    }

    def "bypass OFF: toolUpdateDevice throws Device not found for an unlisted device (no model read first)"() {
        given:
        settingsMap.bypassDeviceAllowlist = false

        when:
        script.toolUpdateDevice([deviceId: UNLISTED_ID, label: 'X'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Device not found: ${UNLISTED_ID}")
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
    }

    def "bypass OFF: toolGetAttribute throws for an unlisted device (no fullJson fetch)"() {
        given:
        settingsMap.bypassDeviceAllowlist = false

        when:
        script.toolGetAttribute(UNLISTED_ID, 'switch')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Device not found: ${UNLISTED_ID}"
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
    }

    def "bypass OFF: toolGetDeviceHistory device branch throws for an unlisted device (no eventsJson fetch)"() {
        given:
        settingsMap.bypassDeviceAllowlist = false

        when:
        script.toolGetDeviceHistory([deviceId: UNLISTED_ID, hoursBack: 24])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Device not found: ${UNLISTED_ID}")
        !hubGet.calls.any { it.path.startsWith('/device/eventsJson') }
    }

    def "bypass OFF: toolGetDeviceEvents throws for an unlisted device (no eventsJson fetch)"() {
        given:
        settingsMap.bypassDeviceAllowlist = false

        when:
        script.toolGetDeviceEvents(UNLISTED_ID, 10)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Device not found: ${UNLISTED_ID}"
        !hubGet.calls.any { it.path.startsWith('/device/eventsJson') }
    }

    def "bypass OFF: toolPollUntilAttribute throws for an unlisted device"() {
        given:
        settingsMap.bypassDeviceAllowlist = false

        when:
        script.toolPollUntilAttribute([deviceId: UNLISTED_ID, attribute: 'switch', expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 50])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Device not found: ${UNLISTED_ID}"
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
    }

    // ---- toggle ON: read paths --------------------------------------------------

    def "bypass ON: toolGetDevice builds the summary shape from fullJson"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()

        when:
        def result = script.toolGetDevice(UNLISTED_ID)

        then:
        result.id == UNLISTED_ID
        result.label == 'Unlisted Switch'
        result.room == 'Garage'
        result.capabilities == ['Switch', 'SwitchLevel']
        result.attributes.find { it.name == 'switch' }?.value == 'off'
        result.commands*.name.containsAll(['on', 'off', 'setLevel'])
    }

    def "bypass ON: toolGetAttribute reads the value from fullJson currentStates"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson({ 'on' })

        when:
        def result = script.toolGetAttribute(UNLISTED_ID, 'switch')

        then:
        result.device == 'Unlisted Switch'
        result.attribute == 'switch'
        result.value == 'on'
    }

    def "bypass ON: toolGetAttribute throws for an attribute absent from the fullJson model"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()

        when:
        script.toolGetAttribute(UNLISTED_ID, 'contact')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Attribute 'contact' not found")
    }

    def "bypass ON but fullJson reports no device: still throws Device not found"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params -> JsonOutput.toJson([device: null]) }

        when:
        script.toolGetDevice(UNLISTED_ID)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Device not found: ${UNLISTED_ID}"
    }

    def "bypass ON but fullJson reports no device: toolSendCommand throws Device not found"() {
        given: 'the device is unlisted AND fullJson has no device -- bypass stays off so the not-found throw fires'
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params -> JsonOutput.toJson([device: null]) }

        when:
        script.toolSendCommand(UNLISTED_ID, 'on', [])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Device not found: ${UNLISTED_ID}"
    }

    def "bypass ON but fullJson reports no device: toolUpdateDevice throws Device not found"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params -> JsonOutput.toJson([device: null]) }

        when:
        script.toolUpdateDevice([deviceId: UNLISTED_ID, label: 'X'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Device not found: ${UNLISTED_ID}")
    }

    // ---- toggle ON: events path (/device/eventsJson) ---------------------------

    def "bypass ON: toolGetDeviceEvents routes to /device/eventsJson and maps the existing shape"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        registerEventsJson()

        when:
        def result = script.toolGetDeviceEvents(UNLISTED_ID, 10)

        then: 'label from fullJson; events mapped to {name,value,unit,description,date,isStateChange}'
        result.device == 'Unlisted Switch'
        result.count == 2
        result.events[0].name == 'switch'
        result.events[0].value == 'on'
        result.events[0].description == 'Unlisted Switch was turned on'
        result.events[0].date == '2026-06-27T10:30:00.000-0600'
        result.events[0].isStateChange == true
        hubGet.calls.any { it.path == "/device/eventsJson/${UNLISTED_ID}" }
    }

    def "bypass ON: toolGetDeviceEvents applies the limit client-side (newest-first)"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        registerEventsJson()

        when:
        def result = script.toolGetDeviceEvents(UNLISTED_ID, 1)

        then: 'only the newest row is returned'
        result.count == 1
        result.events.size() == 1
        result.events[0].value == 'on'
    }

    def "bypass ON: toolGetDeviceHistory device branch windows /device/eventsJson rows"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        registerEventsJson()

        when: 'a wide relative window returns both rows in the device-branch shape'
        def result = script.toolGetDeviceHistory([deviceId: UNLISTED_ID, hoursBack: 168])

        then:
        result.source == 'device'
        result.device == 'Unlisted Switch'
        result.deviceId == UNLISTED_ID
        result.sinceMode == 'relative'
        result.count == 2
        result.events*.name == ['switch', 'switch']
    }

    def "bypass ON: toolGetDeviceHistory device branch surfaces a structured error when /device/eventsJson FETCH FAILS (no empty-success lie)"() {
        given: 'the device resolves via fullJson, but the windowed eventsJson fetch throws (null sentinel)'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        hubGet.register("/device/eventsJson/${UNLISTED_ID}") { params -> throw new RuntimeException('events store offline') }

        when:
        def result = script.toolGetDeviceHistory([deviceId: UNLISTED_ID, hoursBack: 24])

        then: 'a fetch failure is a structured device-branch error, NOT an empty-success'
        result.success == false
        result.source == 'device'
        result.error?.contains('/device/eventsJson')
        result.note != null
    }

    def "bypass ON: toolGetDeviceEvents surfaces a structured error when /device/eventsJson FETCH FAILS (no empty-success lie)"() {
        given: 'the device resolves via fullJson, but the eventsJson fetch throws (null sentinel)'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        hubGet.register("/device/eventsJson/${UNLISTED_ID}") { params -> throw new RuntimeException('events read exploded') }

        when:
        def result = script.toolGetDeviceEvents(UNLISTED_ID, 10)

        then: 'a fetch failure is a structured error, NOT {events:[],count:0} success'
        result.success == false
        result.error?.contains('/device/eventsJson')
        result.note != null
        !result.containsKey('events')
    }

    def "bypass ON: toolGetDeviceEvents real-empty history is an empty-success (distinct from a fetch failure)"() {
        given: 'eventsJson returns an empty array -- a real device with no events'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        registerEventsJson([])

        when:
        def result = script.toolGetDeviceEvents(UNLISTED_ID, 10)

        then: 'empty success, NOT an error'
        !result.containsKey('success')
        result.events == []
        result.count == 0
    }

    // ---- toggle ON: command path (runmethod) -----------------------------------

    def "bypass ON: toolSendCommand fires via /device/runmethod and snapshots from fullJson"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson({ 'on' })
        def posted = null
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            posted = [path: path, body: body]; return [success: true, message: null]
        }

        when:
        def result = script.toolSendCommand(UNLISTED_ID, 'on', [])

        then: 'the command fired through runmethod with an empty args list'
        posted.path == '/device/runmethod'
        def parsed = new JsonSlurper().parseText(posted.body)
        parsed.id == 555
        parsed.method == 'on'
        parsed.args == []

        and: 'the success result + fullJson snapshot come back'
        result.success == true
        result.command == 'on'
        result.device == 'Unlisted Switch'
        result.state.switch.value == 'on'
        // TZ-robust: the snapshot reformats the offset-bearing ISO date in the JVM default zone, so
        // compute the expected from the SAME instant+format (a literal MT string false-fails on UTC CI).
        result.state.switch.timestamp == Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", '2026-06-27T10:30:00.000-0600').format('yyyy-MM-dd HH:mm:ss')
    }

    def "bypass ON: toolSendCommand types a numeric parameter from the fullJson command metadata"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        def posted = null
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            posted = [path: path, body: body]; return [success: true]
        }

        when:
        script.toolSendCommand(UNLISTED_ID, 'setLevel', [50])

        then: 'the runmethod arg carries the declared NUMBER type and the value'
        def parsed = new JsonSlurper().parseText(posted.body)
        parsed.method == 'setLevel'
        parsed.args.size() == 1
        parsed.args[0].type == 'NUMBER'
        parsed.args[0].value == 50
    }

    def "bypass ON: toolSendCommand rejects an unsupported command using the fullJson command set"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()

        when:
        script.toolSendCommand(UNLISTED_ID, 'lock', [])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('does not support command: lock')
    }

    def "bypass ON: toolSendCommand returns a structured [success:false] when runmethod reports failure (not a throw)"() {
        given: 'the runmethod endpoint reports a hub-side failure'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            [success: false, message: 'device rejected the command']
        }

        when:
        def result = script.toolSendCommand(UNLISTED_ID, 'on', [])

        then: 'the runtime-error envelope is returned, not thrown -- no state/waitFor block'
        result.success == false
        result.error?.contains('did not confirm success')
        result.note != null
        !result.containsKey('state')
    }

    @spock.lang.Unroll
    def "bypass ON: toolSendCommand FAILS CLOSED on a non-confirming runmethod response (#desc)"() {
        given: 'runmethod returns something that is not a positive success==true confirmation'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> resp }

        when:
        def result = script.toolSendCommand(UNLISTED_ID, 'on', [])

        then: 'never a silent success -- a structured runtime error, no state block'
        result.success == false
        result.error != null
        result.note != null
        !result.containsKey('state')

        where: 'null/empty body, an empty Map, and a non-JSON sentinel all fail closed'
        desc                  | resp
        'null (dropped body)' | null
        'empty Map {}'        | [:]
        'non-JSON body'       | [_unparseable: true, message: 'login page']
    }

    def "bypass ON: toolSendCommand FAILS CLOSED on a non-Map (non-null) runmethod body"() {
        given: 'runmethod returns a non-null body that is not a Map (e.g. a bare String) -- hits the non-Map branch'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> 'unexpected string body' }

        when:
        def result = script.toolSendCommand(UNLISTED_ID, 'on', [])

        then: 'the non-Map branch yields the did-not-confirm runtime error, no state block'
        result.success == false
        result.error?.contains('did not confirm success')
        result.note != null
        !result.containsKey('state')
    }

    def "bypass ON: toolSendCommand returns the runmethod-call-failed envelope when /device/runmethod THROWS"() {
        given: 'the runmethod POST throws (hub/network blip) -- the catch must return a structured error, not propagate'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> throw new RuntimeException('runmethod exploded') }

        when:
        def result = script.toolSendCommand(UNLISTED_ID, 'on', [])

        then: 'the catch returns the runmethod-call-failed error carrying the cause, no state block'
        result.success == false
        result.error?.contains('runmethod call failed')
        result.error?.contains('runmethod exploded')
        result.note != null
        !result.containsKey('state')
    }

    def "bypass ON: toolSendCommand reports partial:true + stateError when the post-command snapshot fetch fails"() {
        given: 'the command fires; the snapshot re-fetch returns a model with no currentStates (read-back FAILURE sentinel)'
        settingsMap.bypassDeviceAllowlist = true
        def calls = 0
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            // 1st fetch (command resolution) carries the full model; the 2nd (snapshot) has no currentStates.
            calls++
            if (calls >= 2) { def m = fullJsonModel(); m.device.currentStates = null; JsonOutput.toJson(m) }
            else JsonOutput.toJson(fullJsonModel())
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> [success: true] }

        when:
        def result = script.toolSendCommand(UNLISTED_ID, 'on', [])

        then: 'the command still succeeds, but the failed confirmation snapshot is flagged (success:true + partial + stateError)'
        result.success == true
        result.partial == true
        result.state == [:]
        result.stateError?.contains('device-state read-back failed')
    }

    def "bypass ON: toolSendCommand waitFor converges off the re-fetched fullJson value"() {
        given: 'fullJson reports off on the first read, on thereafter (re-fetch each poll)'
        settingsMap.bypassDeviceAllowlist = true
        def reads = 0
        registerFullJson({ (++reads >= 2) ? 'on' : 'off' })
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> [success: true] }

        when:
        def result = script.toolSendCommand(UNLISTED_ID, 'on', [],
            [attribute: 'switch', expectedValue: 'on', timeoutMs: 5000, pollIntervalMs: 50])

        then: 'the poll re-fetched fullJson until the value converged'
        result.success == true
        result.waitFor.converged == true
        result.waitFor.finalValue == 'on'
    }

    def "bypass ON: toolSendCommand waitFor on a NOT-YET-REPORTED attribute fires the command and reports neverReported (no pre-fire hard-fail)"() {
        given: 'fullJson does NOT list the waitFor attribute (declared-but-unreported); the command IS supported'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()   // currentStates exposes switch + level, NOT power
        def runmethodFired = false
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            if (path == '/device/runmethod') runmethodFired = true
            return [success: true]
        }

        when: 'waitFor on the unreported attribute -- fullJson can not enumerate it, so it must NOT block the command'
        def result = script.toolSendCommand(UNLISTED_ID, 'on', [],
            [attribute: 'power', expectedValue: '5', timeoutMs: 200, pollIntervalMs: 50])

        then: 'not rejected pre-fire; the command fired and the poll reports neverReported (not a thrown not-found)'
        notThrown(IllegalArgumentException)
        runmethodFired
        result.success == true
        result.waitFor.converged == false
        result.waitFor.neverReported == true
    }

    // ---- toggle ON: poll path ---------------------------------------------------

    def "bypass ON: toolPollUntilAttribute on a NOT-YET-REPORTED attribute times out neverReported (does not throw)"() {
        given: 'the attribute is absent from fullJson currentStates -- bypass can not enumerate declared attrs'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()

        when:
        def result = script.toolPollUntilAttribute([deviceId: UNLISTED_ID, attribute: 'power',
            expectedValue: '5', timeoutMs: 200, pollIntervalMs: 50])

        then: 'no thrown "Attribute not found"; it polls and reports neverReported'
        notThrown(IllegalArgumentException)
        result.success == false
        result.neverReported == true
    }

    def "bypass ON: toolPollUntilAttribute converges via the fullJson value reader"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson({ 'on' })

        when:
        def result = script.toolPollUntilAttribute([deviceId: UNLISTED_ID, attribute: 'switch',
            expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 50])

        then:
        result.success == true
        result.finalValue == 'on'
        result.timedOut == false
    }

    // ---- toggle ON: update path -------------------------------------------------

    def "bypass ON: toolUpdateDevice label routes to the wholesale POST /device/update form (not GET /device/updateLabel)"() {
        given: 'label rides the portable /device/update form -- the dedicated GET /device/updateLabel 404s on some firmwares (2.5.0.157)'
        settingsMap.bypassDeviceAllowlist = true
        def fjLabel = 'Unlisted Switch'
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.label = fjLabel
            JsonOutput.toJson(m)
        }
        def postedBody = null
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            postedBody = body
            if (body.contains('label=Renamed')) fjLabel = 'Renamed'   // hub applies the label
            return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, label: 'Renamed'])

        then: 'the wholesale form carried the label, read-back verified it, and updateLabel was never called'
        result.success == true
        result.changes.find { it.property == 'label' }?.newValue == 'Renamed'
        postedBody.contains('label=Renamed')
        !hubGet.calls.any { it.path.startsWith('/device/updateLabel') }
    }

    def "bypass ON: toolUpdateDevice enabled routes to POST /device/disable"() {
        given: 'a stateful fullJson whose disabled flag reflects the POST, so the read-back can confirm the flip'
        settingsMap.bypassDeviceAllowlist = true
        def disabledState = false
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.disabled = disabledState
            JsonOutput.toJson(m)
        }
        def posted = null
        script.metaClass.hubInternalPost = { String path, Map body = null, int t = 30, boolean r = false ->
            posted = [path: path, body: body]
            if (path == '/device/disable') disabledState = (body.disable == 'true')
            return ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, enabled: false])

        then: 'disable:true disables the device and the read-back confirms it'
        result.success == true
        posted.path == '/device/disable'
        posted.body.disable == 'true'
        result.changes.find { it.property == 'enabled' }?.newValue == false
    }

    def "bypass ON: toolUpdateDevice enabled read-back MISMATCH records a structured error (no false success)"() {
        given: 'the POST is a no-op; fullJson still reports disabled:false so the requested disable did not land'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()   // device.disabled stays false
        script.metaClass.hubInternalPost = { String path, Map body = null, int t = 30, boolean r = false -> '' }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, enabled: false])

        then: 'the unflipped read-back is a structured error, NOT a recorded change'
        result.success == false
        result.errors.find { it.property == 'enabled' }?.error?.contains('read back as')
        !(result.changes.find { it.property == 'enabled' })
    }

    def "bypass ON: toolUpdateDevice enabled read-back FETCH-NULL records the distinct could-not-confirm error"() {
        given: 'the POST is accepted but the confirming fullJson re-fetch returns no device'
        settingsMap.bypassDeviceAllowlist = true
        def calls = 0
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            // 1st fetch (bypass entry) resolves the device; the 2nd (enabled read-back) returns no device.
            calls++
            (calls >= 2) ? JsonOutput.toJson([device: null]) : JsonOutput.toJson(fullJsonModel())
        }
        script.metaClass.hubInternalPost = { String path, Map body = null, int t = 30, boolean r = false -> '' }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, enabled: false])

        then: 'a failed read-back fetch is a DISTINCT error, never a false success'
        result.success == false
        result.errors.find { it.property == 'enabled' }?.error?.contains('could not confirm the change -- the read-back fetch failed')
        !(result.changes.find { it.property == 'enabled' })
    }

    def "bypass ON: toolUpdateDevice enabled read-back CONFIRMS when fullJson renders disabled as the STRING 'true'"() {
        given: 'fullJson reports disabled as the JSON string "true" (not a Boolean) -- a real disable must still confirm'
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.disabled = 'true'   // string, not Boolean
            JsonOutput.toJson(m)
        }
        script.metaClass.hubInternalPost = { String path, Map body = null, int t = 30, boolean r = false -> '' }

        when: 'request disable; the re-fetch reports disabled:"true" -> confirmed, no false mismatch error'
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, enabled: false])

        then: 'the change is recorded with no error'
        result.success == true
        result.changes.find { it.property == 'enabled' }?.newValue == false
        !(result.errors?.find { it.property == 'enabled' })
    }

    def "bypass ON: toolUpdateDevice room assign sends the room NAME to /device/updateRoom (NOT an id)"() {
        given: 'the room exists; updateRoom is keyed on the NAME (sending an id would create a spurious room)'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        script.metaClass.getRooms = { -> [[id: 7, name: 'Garage', deviceIds: []]] }
        hubGet.register("/device/updateRoom?deviceId=${UNLISTED_ID}&room=Garage") { params -> 'true' }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, room: 'Garage'])

        then: 'the NAME (Garage), not the id (7), is sent'
        result.success == true
        result.changes.find { it.property == 'room' }?.newValue == 'Garage'
        hubGet.calls.any { it.path == "/device/updateRoom?deviceId=${UNLISTED_ID}&room=Garage" }
        !hubGet.calls.any { it.path == "/device/updateRoom?deviceId=${UNLISTED_ID}&room=7" }
    }

    def "bypass ON: toolUpdateDevice room with an UNKNOWN name returns Room not found and never calls updateRoom"() {
        given: 'the requested room is not on the hub -- updateRoom would silently create it, so we must refuse first'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        script.metaClass.getRooms = { -> [[id: 7, name: 'Garage', deviceIds: []]] }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, room: 'Nonexistent'])

        then: 'parity with the listed path: Room not found, and NO updateRoom call (no spurious-room creation)'
        result.success == false
        result.errors.find { it.property == 'room' }?.error?.contains('Room \'Nonexistent\' not found')
        !hubGet.calls.any { it.path.startsWith('/device/updateRoom') }
    }

    def "bypass ON: toolUpdateDevice room assign reports 'Unable to list rooms' when getRooms THROWS (distinct from not-found)"() {
        given: 'the room-existence check can not enumerate rooms (hub blip), so a name can not be resolved'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        script.metaClass.getRooms = { -> throw new RuntimeException('rooms read exploded') }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, room: 'Garage'])

        then: 'the getRooms failure is reported distinctly from "room not found", and updateRoom is never called'
        result.success == false
        result.errors.find { it.property == 'room' }?.error?.contains('Unable to list rooms')
        !hubGet.calls.any { it.path.startsWith('/device/updateRoom') }
    }

    def "bypass ON: toolUpdateDevice room assign records 'Hub did not accept' when /device/updateRoom returns non-true"() {
        given: 'the room exists but updateRoom does not return true (the hub rejected the assign)'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        script.metaClass.getRooms = { -> [[id: 7, name: 'Garage', deviceIds: []]] }
        hubGet.register("/device/updateRoom?deviceId=${UNLISTED_ID}&room=Garage") { params -> 'false' }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, room: 'Garage'])

        then: 'a non-true response is a structured error, not a recorded change'
        result.success == false
        result.errors.find { it.property == 'room' }?.error?.contains('Hub did not accept the room update')
        !(result.changes.find { it.property == 'room' })
    }

    def "bypass ON: toolUpdateDevice room assign HAPPY PATH records the change when the re-fetch confirms the room landed"() {
        given: 'the device starts unassigned; updateRoom returns true and the re-fetch shows it now in Garage'
        settingsMap.bypassDeviceAllowlist = true
        def landed = false
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.roomName = landed ? 'Garage' : null
            JsonOutput.toJson(m)
        }
        script.metaClass.getRooms = { -> [[id: 7, name: 'Garage', deviceIds: []]] }
        hubGet.register("/device/updateRoom?deviceId=${UNLISTED_ID}&room=Garage") { params -> landed = true; 'true' }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, room: 'Garage'])

        then: 'the confirmed assign records the change (oldValue none -> newValue Garage)'
        result.success == true
        result.changes.find { it.property == 'room' }?.newValue == 'Garage'
        result.changes.find { it.property == 'room' }?.oldValue == 'none'
        !(result.errors?.find { it.property == 'room' })
    }

    def "bypass ON: toolUpdateDevice room assign LANDING MISMATCH records a structured error when the re-fetch shows a different room"() {
        given: 'updateRoom returns true but the re-fetch shows the device in a DIFFERENT room (assign did not land)'
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.roomName = 'Office'   // never becomes Garage
            JsonOutput.toJson(m)
        }
        script.metaClass.getRooms = { -> [[id: 7, name: 'Garage', deviceIds: []]] }
        hubGet.register("/device/updateRoom?deviceId=${UNLISTED_ID}&room=Garage") { params -> 'true' }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, room: 'Garage'])

        then: 'the unconfirmed landing is a structured error naming the actual + expected room, NOT a recorded change'
        result.success == false
        result.errors.find { it.property == 'room' }?.error?.contains("is in room 'Office' (expected 'Garage')")
        !(result.changes.find { it.property == 'room' })
    }

    def "bypass ON: toolUpdateDevice room assign FETCH-NULL records the distinct could-not-confirm error"() {
        given: 'updateRoom returns true but the confirming re-fetch returns no device'
        settingsMap.bypassDeviceAllowlist = true
        def calls = 0
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            // 1st fetch (bypass entry) resolves the device; the 2nd (assign read-back) returns no device.
            calls++
            (calls >= 2) ? JsonOutput.toJson([device: null]) : JsonOutput.toJson(fullJsonModel())
        }
        script.metaClass.getRooms = { -> [[id: 7, name: 'Garage', deviceIds: []]] }
        hubGet.register("/device/updateRoom?deviceId=${UNLISTED_ID}&room=Garage") { params -> 'true' }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, room: 'Garage'])

        then: 'a failed read-back fetch is a DISTINCT error, never a false success'
        result.success == false
        result.errors.find { it.property == 'room' }?.error?.contains('could not confirm the room change -- the read-back fetch failed')
        !(result.changes.find { it.property == 'room' })
    }

    @spock.lang.Unroll
    def "bypass ON: toolUpdateDevice room unassign (#unassignVal) routes to /device/update roomId=0 (no updateRoom)"() {
        given: 'an unassign value -- must NOT send an empty name to updateRoom; route through the wholesale form'
        settingsMap.bypassDeviceAllowlist = true
        // After the form POST the read-back shows the device unassigned (roomName cleared).
        def cleared = false
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            if (cleared) m.device.roomName = null
            JsonOutput.toJson(m)
        }
        def postedBody = null
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            postedBody = body; cleared = true; return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, room: unassignVal])

        then: 'the wholesale form carried roomId=0 and updateRoom was never called'
        result.success == true
        postedBody.contains('roomId=0&')   // '&'-terminated so it can't match roomId=05 etc.
        result.changes.find { it.property == 'room' }?.newValue == 'none'
        !hubGet.calls.any { it.path.startsWith('/device/updateRoom') }

        where:
        unassignVal << ['', 'none', 'null']
    }

    def "bypass ON: toolUpdateDevice room unassign reports an ERROR when the read-back fetch FAILS (no false success)"() {
        given: 'the POST is accepted but the confirming read-back fetch fails (null device)'
        settingsMap.bypassDeviceAllowlist = true
        def calls = 0
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            // First fetch (toolUpdateDevice top) + second (model rebuild) return the device; the
            // read-back fetch (3rd) returns no device, simulating a fetch failure after the POST.
            calls++
            (calls >= 3) ? JsonOutput.toJson([device: null]) : JsonOutput.toJson(fullJsonModel())
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false -> [status: 200] }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, room: ''])

        then: 'a null read-back is an error, never recorded as a successful unassign'
        result.success == false
        result.errors.find { it.property == 'room' }?.error?.contains('read-back to confirm the unassign failed')
    }

    @spock.lang.Unroll
    def "bypass ON: toolUpdateDevice refuses '#field' on an unlisted device with a per-field error (no wholesale form POST)"() {
        given: 'the field has no safe id-keyed route on the bypass path (Groovy-object-only or unverified form encoding)'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        def formPosted = false
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            formPosted = true; return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID] + [(field): value])

        then: 'refused with the per-field "is not supported via the allowlist bypass" error and no /device/update form POST'
        result.success == false
        result.errors.find { it.property == field }?.error?.contains('is not supported when reaching a device via the allowlist bypass')
        !formPosted

        where:
        field                 | value
        'showOnHome'          | true
        'dataValues'          | [foo: 'bar']
        'defaultCurrentState' | 'temperature'
        'tags'                | ['kitchen']
    }

    // ---- toggle ON: the wholesale /device/update form (name + deviceNetworkId) ----
    // The highest-risk leg: the form BLANKS any field it omits, so the full device model must be
    // reconstructed. A stateful fullJson handler reflects the applied change on the read-back.

    private void registerStatefulFullJson(Map fjState) {
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.name = fjState.name
            m.device.deviceNetworkId = fjState.dni
            JsonOutput.toJson(m)
        }
    }

    def "bypass ON: toolUpdateDevice name+dni POST /device/update reconstructs the full model and verifies the read-back"() {
        given: 'the fullJson read-back reflects the form POST (hub applied it)'
        settingsMap.bypassDeviceAllowlist = true
        def fjState = [name: 'Unlisted Switch', dni: 'ABCD']
        registerStatefulFullJson(fjState)
        def postedBody = null
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            postedBody = body
            fjState.name = 'NewName'; fjState.dni = 'NEWDNI'   // hub applies the change
            return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, name: 'NewName', deviceNetworkId: 'NEWDNI'])

        then: 'the form body carries the overrides AND a preserved model field (full reconstruction)'
        postedBody.contains('name=NewName')
        postedBody.contains('deviceNetworkId=NEWDNI')
        postedBody.contains('label=Unlisted+Switch')

        and: 'both fields verified on the read-back and recorded as changes'
        result.success == true
        result.changes.find { it.property == 'name' }?.newValue == 'NewName'
        result.changes.find { it.property == 'deviceNetworkId' }?.newValue == 'NEWDNI'
    }

    def "bypass ON: toolUpdateDevice name read-back mismatch records a structured error (errors list)"() {
        given: 'the hub does NOT apply the change, so the read-back still shows the old name'
        settingsMap.bypassDeviceAllowlist = true
        def fjState = [name: 'Unlisted Switch', dni: 'ABCD']
        registerStatefulFullJson(fjState)
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            return [status: 200]   // POST 'accepted' but nothing changed
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, name: 'NewName'])

        then: 'the failure uses the SAME {success, device, deviceId, changes, errors} shape as the listed sibling'
        result.success == false
        result.errors instanceof List
        result.errors.find { it.property == 'name' }?.error?.contains('read back as')
    }

    def "bypass ON: toolUpdateDevice label read-back MISMATCH records a structured error (errors list)"() {
        given: 'the hub does NOT apply the label, so the fresh read-back still shows the old label'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()   // label stays 'Unlisted Switch'
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            return [status: 200]   // POST 'accepted' but nothing changed
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, label: 'NewLabel'])

        then: 'the label leg records a per-property read-back error, no change'
        result.success == false
        result.errors.find { it.property == 'label' }?.error?.contains('label read back as')
        !(result.changes.find { it.property == 'label' })
    }

    def "bypass ON: toolUpdateDevice deviceNetworkId read-back MISMATCH records a structured error (errors list)"() {
        given: 'the hub does NOT apply the dni, so the read-back still shows the old dni'
        settingsMap.bypassDeviceAllowlist = true
        def fjState = [name: 'Unlisted Switch', dni: 'ABCD']
        registerStatefulFullJson(fjState)
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            return [status: 200]   // no-op
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, deviceNetworkId: 'NEWDNI'])

        then: 'the dni leg records a per-property read-back error, no change'
        result.success == false
        result.errors.find { it.property == 'deviceNetworkId' }?.error?.contains('deviceNetworkId read back as')
        !(result.changes.find { it.property == 'deviceNetworkId' })
    }

    def "bypass ON: toolUpdateDevice name leg surfaces the reworded read-back-failure error when the model re-fetch returns no device"() {
        given: 'bypass entry resolves the device, but the _postBypassDeviceModel re-fetch returns no device'
        settingsMap.bypassDeviceAllowlist = true
        def calls = 0
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            // 1st fetch (bypass entry) resolves the device; the 2nd (model rebuild inside
            // _postBypassDeviceModel) returns no device, so the form is never POSTed.
            calls++
            (calls >= 2) ? JsonOutput.toJson([device: null]) : JsonOutput.toJson(fullJsonModel())
        }
        def formPosted = false
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            formPosted = true; return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, name: 'NewName'])

        then: 'the deviceModel error names the PRE-write model-read failure (distinct from the post-write read-back legs); the form was never POSTed'
        result.success == false
        result.errors.find { it.property == 'deviceModel' }?.error?.contains('Could not read the device model')
        !(result.changes.find { it.property == 'name' })
        !formPosted
    }

    // ---- multi-leg: the form must rebuild from FRESH fullJson so a prior leg isn't reverted (B1) ---
    // label now rides the same /device/update form as name (the dedicated GET /device/updateLabel
    // 404s on some firmwares), so a label+name edit is ONE post carrying both. When a SECOND post
    // follows (label+room-unassign), the stateful handler tracks the label the first post applied so
    // the guard can prove the second form rebuilds from FRESH fullJson and re-sends the just-applied
    // label. With the stale-fj bug the second form would carry the ORIGINAL label (label=Unlisted+
    // Switch), reverting it -- that guard goes RED in that case.

    def "bypass ON: toolUpdateDevice label+name ride ONE wholesale /device/update form carrying both"() {
        given: 'a label+name edit posts a single form with both overrides'
        settingsMap.bypassDeviceAllowlist = true
        def fjState = [label: 'Unlisted Switch', name: 'Unlisted Switch']
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.label = fjState.label
            m.device.name = fjState.name
            JsonOutput.toJson(m)
        }
        def postedBody = null
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            postedBody = body
            if (body.contains('label=NewLabel')) fjState.label = 'NewLabel'
            if (body.contains('name=NewName')) fjState.name = 'NewName'
            return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, label: 'NewLabel', name: 'NewName'])

        then: 'the single form carries BOTH label and name (full model, nothing blanked) and updateLabel is never called'
        postedBody.contains('label=NewLabel')
        postedBody.contains('name=NewName')
        !hubGet.calls.any { it.path.startsWith('/device/updateLabel') }

        and: 'both legs recorded as successful changes'
        result.success == true
        result.changes.find { it.property == 'label' }?.newValue == 'NewLabel'
        result.changes.find { it.property == 'name' }?.newValue == 'NewName'
    }

    def "bypass ON: toolUpdateDevice label+room-unassign re-sends the FRESH label in the roomId=0 form (no clobber)"() {
        given: 'label rides the first /device/update form, then the unassign rebuilds a SECOND form with roomId=0'
        settingsMap.bypassDeviceAllowlist = true
        def fjState = [label: 'Unlisted Switch', roomName: 'Garage']
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.label = fjState.label
            m.device.roomName = fjState.roomName
            JsonOutput.toJson(m)
        }
        def postedBody = null
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            postedBody = body                                              // capture the LAST post (the roomId=0 form)
            if (body.contains('label=NewLabel')) fjState.label = 'NewLabel'   // first post applies the label
            if (body.contains('roomId=0')) fjState.roomName = null            // second post unassigns the room
            return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, label: 'NewLabel', room: ''])

        then: 'the SECOND (unassign) form carries roomId=0 AND the fresh label -- the label is NOT reverted by a stale model'
        postedBody.contains('roomId=0&')
        postedBody.contains('label=NewLabel')
        !postedBody.contains('label=Unlisted+Switch')

        and: 'both legs succeed'
        result.success == true
        result.changes.find { it.property == 'label' }?.newValue == 'NewLabel'
        result.changes.find { it.property == 'room' }?.newValue == 'none'
    }

    // ---- preferences leg (W6 / B-PREF) ----
    // Driver prefs MUST ride the preferences:[{name,type,value}] ARRAY; a flat top-level key is a
    // silent no-op that still returns {success:true}, so a read-back against fullJson `settings` is
    // mandatory. The stateful mock here only HONORS the array shape (like the live hub) -- a flat-key
    // revert would not apply the pref, the read-back would mismatch, and this spec would go RED.

    def "bypass ON: toolUpdateDevice preferences POSTs the {preferences:[{name,type,value}]} ARRAY and read-back verifies"() {
        given: 'the hub applies only the ARRAY shape; the read-back reflects it via fullJson settings'
        settingsMap.bypassDeviceAllowlist = true
        def applied = [:]
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.settings = applied.collect { k, v -> [name: k, type: 'number', value: v] }
            JsonOutput.toJson(m)
        }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            posts << [path: path, body: body]
            def parsed = new JsonSlurper().parseText(body)
            if (parsed.preferences instanceof List) {   // live hub only honors the array shape
                parsed.preferences.each { p -> applied[p.name] = p.value?.toString() }
            }
            return [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, preferences: [tempOffset: [type: 'number', value: 3]]])

        then: 'the body is the ARRAY shape (NOT a flat top-level key)'
        posts.size() == 1
        posts[0].path == '/device/preference/save'
        def body = new JsonSlurper().parseText(posts[0].body)
        body.deviceId == 555
        body.preferences instanceof List
        body.preferences[0].name == 'tempOffset'
        body.preferences[0].type == 'number'
        body.preferences[0].value == '3'
        !body.containsKey('tempOffset')

        and: 'the read-back confirmed the change, so it is recorded'
        result.success == true
        result.changes.find { it.property == 'preference.tempOffset' } != null
    }

    @spock.lang.Unroll
    def "bypass ON: toolUpdateDevice preferences CLEAR (#clearVal) succeeds and is NOT a false error"() {
        given: 'clearing a pref -- the hub applies it and the read-back shows it cleared (empty/null)'
        settingsMap.bypassDeviceAllowlist = true
        def applied = [logEnable: 'true']   // starts set
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            m.device.settings = applied.collect { k, v -> [name: k, type: 'bool', value: v] }
            JsonOutput.toJson(m)
        }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            posts << body
            def parsed = new JsonSlurper().parseText(body)
            if (parsed.preferences instanceof List) parsed.preferences.each { p -> applied[p.name] = p.value?.toString() }
            return [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, preferences: [logEnable: clearVal]])

        then: 'a successful clear records a change, NOT a false "read back as null" error'
        result.success == true
        result.changes.find { it.property == 'preference.logEnable' } != null
        !(result.errors?.find { it.property == 'preference.logEnable' })

        and: 'the wire body sends value:"" (not null) for the clear'
        new JsonSlurper().parseText(posts[0]).preferences[0].value == ''

        where: 'a bare null and a Map {value:null} both mean clear'
        clearVal << [null, [value: null]]
    }

    def "bypass ON: toolUpdateDevice preferences read-back MISMATCH records a structured error (no false success on a silent no-op)"() {
        given: 'the POST returns {success:true} but the pref never appears in fullJson settings (the no-op class)'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()   // no settings array -> the pref never reads back
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> [success: true] }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, preferences: [logEnable: [type: 'bool', value: true]]])

        then: 'the {success:true}-but-no-op is caught by the read-back -> structured error, not a false change'
        result.success == false
        result.errors.find { it.property == 'preference.logEnable' }?.error?.contains('read back as')
        !(result.changes.find { it.property == 'preference.logEnable' })
    }

    def "bypass ON: toolUpdateDevice preferences records a per-key error when the save throws"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            throw new RuntimeException('pref save rejected')
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, preferences: [tempOffset: [type: 'number', value: 3]]])

        then: 'the per-key error carries the cause (with the e.message ?: e.toString() fallback)'
        result.success == false
        result.errors.find { it.property == 'preference.tempOffset' }?.error?.contains('pref save rejected')
    }

    def "bypass ON: toolUpdateDevice preference CLEAR with a FAILED read-back fetch records the distinct could-not-confirm error (no false success)"() {
        given: 'a CLEAR (null value); the POST is accepted but the confirming fullJson re-fetch returns no device'
        settingsMap.bypassDeviceAllowlist = true
        def calls = 0
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            // 1st fetch (bypass entry) resolves the device; the 2nd (pref read-back) returns no device.
            // A FAILED re-fetch must NOT read as a confirmed clear -- that is the bug this pin guards.
            calls++
            (calls >= 2) ? JsonOutput.toJson([device: null]) : JsonOutput.toJson(fullJsonModel())
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> [success: true] }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, preferences: [logEnable: [value: null]]])

        then: 'a CLEAR whose read-back fetch failed is a DISTINCT error, never a recorded change'
        result.success == false
        result.errors.find { it.property == 'preference.logEnable' }?.error?.contains('could not confirm the preference -- the read-back fetch failed')
        !(result.changes.find { it.property == 'preference.logEnable' })
    }

    // ---- regression guard: a LISTED device keeps the Groovy-device path -----------

    def "regression: bypass ON does NOT divert a LISTED device through fullJson (toolGetDevice)"() {
        given: 'the toggle is ON but the device IS in the MCP scope'
        settingsMap.bypassDeviceAllowlist = true
        def device = new TestDevice(id: 10, name: 'Listed', label: 'Listed Switch',
            roomName: 'Den', capabilities: [[name: 'Switch']],
            supportedAttributes: [[name: 'switch', dataType: 'ENUM']],
            supportedCommands: [[name: 'on', arguments: null]],
            attributeValues: [switch: 'off'])
        childDevicesList << device

        when:
        def result = script.toolGetDevice('10')

        then: 'the rich device-object path is used -- fullJson is never fetched'
        result.label == 'Listed Switch'
        result.room == 'Den'
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
    }

    def "regression: bypass ON does NOT divert a LISTED device through the bypass HTTP path (toolUpdateDevice)"() {
        given: 'the toggle is ON but the device IS listed -- the enabled write must use the LISTED toolUpdateDevice flow, never _toolUpdateDeviceBypass'
        settingsMap.bypassDeviceAllowlist = true
        def device = new TestDevice(id: 10, name: 'Listed', label: 'Listed Switch')
        childDevicesList << device
        def posted = null
        script.metaClass.hubInternalPost = { String path, Map body = null, int t = 30, boolean r = false ->
            posted = [path: path, body: body]; return ''
        }
        // Both the listed and the bypass enabled paths now confirm the flip via a FRESH /device/fullJson
        // re-read, so a "no fullJson fetch" assertion no longer discriminates them. The listed flow is
        // identified instead by its result message, which never carries the bypass path's "(allowlist
        // bypass)" suffix.
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"label":"Listed Switch","disabled":false}}' }

        when: 'enable the already-enabled device (no-op flip; the listed read-back stays disabled:false == wanted)'
        def result = script.toolUpdateDevice([deviceId: '10', enabled: true])

        then: 'routed through the listed Groovy flow: /device/disable posted, read-back confirmed, and NOT the bypass variant'
        posted.path == '/device/disable'
        result.changes.find { it.property == 'enabled' }?.newValue == true
        result.success == true
        !result.message.contains('allowlist bypass')
    }

    def "regression: bypass ON commands a LISTED device via the device object, not runmethod"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        def runmethodHit = false
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            if (path == '/device/runmethod') runmethodHit = true
            return [success: true]
        }
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'Listed'
            getLabel() >> 'Listed Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> [[name: 'switch', value: 'on', date: null]]
        }
        childDevicesList << device

        when:
        def result = script.toolSendCommand('10', 'on', [])

        then: 'the Groovy command fired, runmethod was NOT used, and no fullJson fetch happened'
        1 * device.on()
        !runmethodHit
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
        result.success == true
    }

    def "regression: bypass ON polls a LISTED device via device.currentStates, never the bypass reader"() {
        given: 'bypass ON, but the polled device IS listed -- the valueReaders refactor must keep it on the device object'
        settingsMap.bypassDeviceAllowlist = true
        def bypassReaderCalled = false
        script.metaClass._readBypassAttrValue = { id, attr -> bypassReaderCalled = true; null }
        def device = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'Listed'
            getLabel() >> 'Listed Switch'
            getSupportedAttributes() >> [[name: 'switch']]
            getCurrentStates() >> [[name: 'switch', value: 'on', date: null]]
        }
        childDevicesList << device

        when:
        def result = script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 50])

        then: 'converged off the live currentStates list; the bypass fullJson reader was never invoked'
        result.success == true
        result.finalValue == 'on'
        !bypassReaderCalled
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
    }

    def "regression: _pollMultiDevice with a MIX of listed + unlisted deviceIds (bypass ON) converges both"() {
        given: 'one listed device (10) and one unlisted device (555) reached via the bypass'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson({ 'on' })
        def listed = new TestDevice(id: 10, name: 'Listed', label: 'Listed Switch',
            supportedAttributes: [[name: 'switch']], attributeValues: [switch: 'on'])
        childDevicesList << listed

        when: "mode 'all' over both ids"
        def result = script.toolPollUntilAttribute([deviceIds: ['10', UNLISTED_ID], mode: 'all',
            attribute: 'switch', expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 50])

        then: 'both per-device value readers fired and the aggregate converged'
        result.success == true
        result.mode == 'all'
        result.convergedCount == 2
        result.devices.find { it.deviceId == '10' }?.matched == true
        result.devices.find { it.deviceId == UNLISTED_ID }?.matched == true
        hubGet.calls.any { it.path == "/device/fullJson/${UNLISTED_ID}" }
    }

    // ---- dispatch envelope ------------------------------------------------------

    @spock.lang.Unroll
    def "via dispatch: hub_get_device reaches an unlisted device when bypass is ON (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: UNLISTED_ID])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.id == UNLISTED_ID
        inner.label == 'Unlisted Switch'

        where:
        useGateways << [true, false]
    }

    def "via dispatch: hub_get_device still 404s for an unlisted device when bypass is OFF"() {
        given:
        settingsMap.bypassDeviceAllowlist = false

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: UNLISTED_ID])

        then:
        response.error.code == -32602
        response.error.message.contains("Device not found: ${UNLISTED_ID}")
    }
}
