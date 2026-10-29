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
 *   - LISTED device with the toggle ON       -> native endpoints with the same access policy
 *
 * Mocking: hubInternalGet is routed by HarnessSpec to the @Shared HubInternalGetMock
 * (hubGet.register(exactPath)); hubInternalPost / hubInternalPostJson / hubInternalPostFormRaw are
 * not on HubitatAppScript, so they're stubbed per-test on script.metaClass.
 */
class ToolDeviceAllowlistBypassSpec extends ToolSpecBase {

    def "bypass attribute polling reads sandbox-sensitive names and preserves false zero and null"() {
        given:
        def states = [fields: [value: false], class: [value: 0], metaClass: [value: null],
                      Fields: [value: 'upper'], getClass: [value: 'data']]
        def model = [device: [currentStates: states]]

        expect:
        states.every { key, state -> script._readBypassAttrValueFrom(model, key) == state.value }
        script._readBypassAttrValueFrom(model, 'missing') == null
    }

    static final String UNLISTED_ID = '555'

    // A fullJson model for an unlisted switch. `switchValue` lets a test drive the reported value.
    private static Map fullJsonModel(String switchValue = 'off') {
        [
            device: [
                id: 555, name: 'Unlisted Switch', label: 'Unlisted Switch',
                version: 0, controllerType: 'ZWV', zigbeeId: null,
                maxEvents: 10, maxStates: 10, spammyThreshold: 100,
                deviceTypeId: 100, deviceTypeReadableType: 'System', roomId: 7,
                meshEnabled: false, retryEnabled: false, meshFullSync: false,
                showOnHome: false, defaultCurrentState: '',
                locationId: 1, hubId: 1, groupId: null, tags: '', defaultIcon: null, notes: null,
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
            settings: [[name: 'logEnable', type: 'bool', value: 'false'],
                       [name: 'tempOffset', type: 'number', value: '0']],
            inputValues: [],
            homeKitEnabled: false,
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

    def "until ends a device history window inclusively on the device-events path"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        registerEventsJson()

        when:
        def result = script.toolGetDeviceHistory([deviceId: UNLISTED_ID, since: '2026-06-27T09:00:00.000-0600', until: '2026-06-27T10:00:00.000-0600'])

        then:
        result.events*.value == ['off']
        result.untilTimestamp != null
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
        script.toolUpdateDevice([deviceId: UNLISTED_ID, fields: [label: 'X']])

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

    def "bypass ON: toolGetAttribute returns null for an attribute absent from reported native states"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()

        when:
        def result = script.toolGetAttribute(UNLISTED_ID, 'contact')

        then:
        result.device == 'Unlisted Switch'
        result.attribute == 'contact'
        result.value == null
        hubGet.calls.count { it.path == "/device/fullJson/${UNLISTED_ID}" } == 1
    }

    def "bypass ON but fullJson reports no device: summary returns explicit native metadata failure"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params -> JsonOutput.toJson([device: null]) }

        when:
        def result = script.toolGetDevice(UNLISTED_ID)

        then:
        result.success == false
        result.error.contains('Device metadata fetch failed')
        result.error.contains("/device/fullJson/${UNLISTED_ID}")
        result.note
        !result.containsKey('attributes')
    }

    def "bypass ON but fullJson reports no device: toolSendCommand returns a native metadata runtime failure"() {
        given: 'native metadata cannot confirm the bypass-permitted device'
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params -> JsonOutput.toJson([device: null]) }

        when:
        def result = script.toolSendCommand(UNLISTED_ID, 'on', [])

        then:
        result.success == false
        result.error.contains('fullJson')
        result.note
    }

    def "bypass ON but fullJson reports no device: toolUpdateDevice refuses before any write"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params -> JsonOutput.toJson([device: null]) }
        def writes = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> writes << path; '' }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> writes << path; [success: true] }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, fields: [label: 'X']])

        then:
        result.success == false
        result.error.contains('/device/fullJson')
        result.note
        writes.empty
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
        def model = fullJsonModel('on')
        model.device.currentStates.put('fields', [value: 'driver fields', date: null])
        model.device.currentStates.put('getClass', [value: 42, date: null])
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params -> JsonOutput.toJson(model) }
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
        result.state.get('fields') == [value: 'driver fields', timestamp: null]
        result.state.get('getClass') == [value: 42, timestamp: null]
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
        settingsMap.mcpLogLevel = 'error'
        seedDebugLogHistory([entries: [], config: [logLevel: 'error', maxEntries: 100]])
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

        and: 'the unsuccessful confirmation read remains visible at the default ERROR threshold'
        script.getDebugLogEntries().any {
            it.level == 'error' && (it.message?.contains('fullJson') || it.message?.contains('snapshot')) &&
                (it.message?.contains(UNLISTED_ID) || it.message?.contains('Unlisted Switch'))
        }
    }

    @spock.lang.Unroll
    def "fullJson #failure returns null and logs safe ERROR diagnostics"() {
        given:
        settingsMap.mcpLogLevel = 'error'
        seedDebugLogHistory([entries: [], config: [logLevel: 'error', maxEntries: 100]])
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            if (fetchThrows) throw new RuntimeException('NATIVE-EXCEPTION-SECRET')
            responseBody
        }

        when:
        def result = script._fetchDeviceFullJson(UNLISTED_ID)

        then:
        result == null
        def entries = script.getDebugLogEntries()
        entries.any { it.level == 'error' && it.message?.contains('fullJson') && it.message?.contains(UNLISTED_ID) }

        and: 'native response and exception contents cannot become log messages or structured details'
        def serializedLogs = JsonOutput.toJson(entries)
        !serializedLogs.contains('NATIVE-EXCEPTION-SECRET')
        !serializedLogs.contains('NATIVE-BODY-SECRET')

        where:
        failure          | fetchThrows | responseBody
        'fetch failure'  | true        | null
        'parse failure'  | false       | '{"password":"NATIVE-BODY-SECRET", broken'
        'null body'      | false       | null
        'empty body'     | false       | ''
        'JSON null'      | false       | 'null'
        'array body'     | false       | '["NATIVE-BODY-SECRET"]'
        'string body'    | false       | '"NATIVE-BODY-SECRET"'
        'numeric body'   | false       | '42'
    }

    def "valid fullJson body is preserved without a failure ERROR"() {
        given:
        settingsMap.mcpLogLevel = 'error'
        seedDebugLogHistory([entries: [], config: [logLevel: 'error', maxEntries: 100]])
        registerFullJson({ 'on' })

        when:
        def result = script._fetchDeviceFullJson(UNLISTED_ID)

        then:
        result.device.currentStates.switch.value == 'on'
        !script.getDebugLogEntries().any { it.level == 'error' }
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

    def "bypass ON: a batch mixing a listed device with an unconfirmable unlisted one reports both outcomes"() {
        given: 'both entries use native commands and only the unlisted command is unconfirmed'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson()
        def posted = []
        hubGet.register('/device/fullJson/10') {
            def model = fullJsonModel()
            model.device.id = 10
            JsonOutput.toJson(model)
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            def payload = new JsonSlurper().parseText(body)
            posted << payload
            [success: payload.id == 10, message: 'device rejected the command']
        }
        def listed = Spy(TestDevice) {
            getId() >> 10
            getName() >> 'Listed'
            getLabel() >> 'Listed Switch'
            getSupportedCommands() >> [[name: 'on'], [name: 'off']]
            getCurrentStates() >> [[name: 'switch', value: 'on', date: null]]
        }
        childDevicesList << listed

        when:
        def result = script.toolSendCommand(null, null, null, null, [
            [deviceId: '10', command: 'on'],
            [deviceId: UNLISTED_ID, command: 'on']
        ])

        then: 'the listed entry actuated'
        posted*.id == [10, 555]
        posted*.method == ['on', 'on']
        0 * listed.on()

        and: 'the unconfirmed bypass fire counts as a failure -- it is returned, not thrown, so the batch has to notice'
        result.sentCount == 1
        result.failedCount == 1
        result.partial == true
        result.failedDeviceIds == [UNLISTED_ID]

        and: 'the failed entry carries the id, the backfilled command, and the bypass cause + recovery'
        result.results[1].deviceId == UNLISTED_ID
        result.results[1].command == 'on'
        result.results[1].success == false
        result.results[1].error.contains('did not confirm success')
        result.results[1].note.contains('hub_get_device')
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
    // An unlisted device gets the same native saves as a listed one; these pin the bypass-specific
    // access path plus the fields whose saves resolve hub state (room, driver preferences).

    private Map stubDeviceSaves(Map fjState, Closure apply = { path, body -> }) {
        def posts = [:].withDefault { [] }
        hubGet.register("/device/fullJson/${UNLISTED_ID}") { params ->
            def m = fullJsonModel()
            fjState.each { k, v -> if (k == 'settings') m.settings = v else m.device.put(k, v) }
            JsonOutput.toJson(m)
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            posts[path] << body
            apply(path, body)
            [status: 200, data: '']
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            def parsed = new JsonSlurper().parseText(body)
            posts[path] << parsed
            apply(path, parsed)
            [success: true]
        }
        posts
    }

    @spock.lang.Unroll
    def "bypass ON: toolUpdateDevice enabled=false confirms a native disabled flag of #disabled"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        def fjState = [disabled: false]
        def posts = stubDeviceSaves(fjState) { path, body -> fjState.disabled = disabled }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, fields: [enabled: false]])

        then:
        posts.keySet() == ['/device/disable'] as Set
        posts['/device/disable'] == [[id: 555, disable: true]]
        result.success == true
        result.changes.find { it.property == 'enabled' }?.newValue == false

        where:
        disabled << [true, 'true']
    }

    def "bypass ON: toolUpdateDevice room resolves the room NAME to its id in the Device Info form"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        script.metaClass.getRooms = { -> [[id: 7, name: 'Garage'], [id: 9, name: 'Office']] }
        def fjState = [roomId: 7, roomName: 'Garage']
        def posts = stubDeviceSaves(fjState) { path, body ->
            fjState.roomId = 9
            fjState.roomName = 'Office'
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, fields: [room: 'office', label: 'NewLabel']])

        then: 'one form carries the room id and the label; nothing goes to a name-keyed room setter'
        posts.keySet() == ['/device/update'] as Set
        posts['/device/update'].size() == 1
        posts['/device/update'][0].contains('roomId=9&')
        posts['/device/update'][0].contains('label=NewLabel')
        !posts['/device/update'][0].contains('Office')

        and: 'the room is verified by name; the label did not land and is reported'
        result.success == false
        result.changes == [[property: 'room', oldValue: 'Garage', newValue: 'Office']]
        result.errors*.property == ['label']
    }

    @spock.lang.Unroll
    def "bypass ON: toolUpdateDevice refuses a room that #failure before any write"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        script.metaClass.getRooms = rooms
        def posts = stubDeviceSaves([:])

        when:
        script.toolUpdateDevice([deviceId: UNLISTED_ID, fields: [label: 'Must not apply', room: 'Nonexistent']])

        then: 'the hub would create an unknown room, so the name must resolve first'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(fragment)
        posts.isEmpty()

        where:
        failure            | rooms                                                     | fragment
        'does not exist'   | { -> [[id: 7, name: 'Garage']] }                          | "Room 'Nonexistent' not found. Available rooms: Garage"
        'cannot be listed' | { -> throw new RuntimeException('rooms read exploded') } | 'Unable to list rooms'
    }

    def "bypass ON: toolUpdateDevice name+dni ride one complete form and are verified on the read-back"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        stateMap.lastBackupTimestamp = System.currentTimeMillis()
        def fjState = [name: 'Unlisted Switch', deviceNetworkId: 'ABCD']
        def posts = stubDeviceSaves(fjState) { path, body ->
            fjState.name = 'NewName'
            fjState.deviceNetworkId = 'NEWDNI'
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, confirm: true, fields: [name: 'NewName', deviceNetworkId: 'NEWDNI']])

        then: 'the form carries both overrides and the untouched model fields'
        posts['/device/update'].size() == 1
        def body = posts['/device/update'][0]
        body.contains('name=NewName')
        body.contains('deviceNetworkId=NEWDNI')
        body.contains('label=Unlisted+Switch')
        body.contains('roomId=7&')
        !body.contains('notes=')

        and:
        result.success == true
        result.changes*.property == ['name', 'deviceNetworkId']
        hubGet.calls.count { it.path == "/device/fullJson/${UNLISTED_ID}" } == 2
    }

    def "bypass ON: toolUpdateDevice preferences ride the pane save as a {name,type,value} array"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        def fjState = [settings: [[name: 'logEnable', type: 'bool', value: 'true', id: 1, deviceId: 555],
                                  [name: 'tempOffset', type: 'number', value: '0']]]
        def posts = stubDeviceSaves(fjState) { path, body ->
            // The hub only honours the array shape.
            body.preferences.each { p ->
                def row = fjState.settings.find { it.name == p.name }
                if (p.value == '') row.putAll([value: null, id: null, deviceId: null])
                else row.value = p.value.toString()
            }
        }

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, preferences: [tempOffset: [type: 'number', value: 3], logEnable: [clear: true]]])

        then:
        posts.keySet() == ['/device/preference/save'] as Set
        posts['/device/preference/save'] == [[deviceId: 555, defaultCurrentState: '', commandRetry: false, showOnHome: false,
            preferences: [[name: 'tempOffset', type: 'number', value: 3], [name: 'logEnable', type: 'bool', value: '']]]]
        result.success == true
        result.changes*.property == ['preference.tempOffset', 'preference.logEnable']
    }

    def "bypass ON: toolUpdateDevice preference the hub did not store is a mismatch, not a change"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        stubDeviceSaves([:])

        when:
        def result = script.toolUpdateDevice([deviceId: UNLISTED_ID, preferences: [logEnable: [type: 'bool', value: true]]])

        then:
        result.success == false
        result.changes.empty
        result.errors.find { it.property == 'preference.logEnable' }?.status == 'mismatch'
    }

    // ---- regression guard: listed and bypass devices both use native transport ----

    def "regression: bypass ON reads a LISTED device summary through native fullJson"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        def device = Spy(TestDevice) {
            getId() >> 10
            getLabel() >> { throw new AssertionError('SDK label read is forbidden') }
            getSupportedAttributes() >> { throw new AssertionError('SDK declarations are forbidden') }
            currentValue(_) >> { throw new AssertionError('SDK value read is forbidden') }
        }
        childDevicesList << device
        def nativeModel = fullJsonModel('off')
        nativeModel.device.id = 10
        nativeModel.device.label = 'Listed Switch'
        nativeModel.device.roomName = 'Den'
        hubGet.register('/device/fullJson/10') { JsonOutput.toJson(nativeModel) }

        when:
        def result = script.toolGetDevice('10')

        then:
        result.label == 'Listed Switch'
        result.room == 'Den'
        result.attributes.find { it.name == 'switch' }.value == 'off'
        hubGet.calls.count { it.path == '/device/fullJson/10' } == 1
    }

    def "regression: bypass ON updates a LISTED device through native HTTP with fresh verification"() {
        given: 'the toggle is ON and a listed device must still receive a verified native update'
        settingsMap.bypassDeviceAllowlist = true
        def device = new TestDevice(id: 10, name: 'Listed', label: 'Listed Switch')
        childDevicesList << device
        def posted = null
        script.metaClass.hubInternalPostJson = { String path, String json, int t = 30, boolean r = false ->
            def body = new groovy.json.JsonSlurper().parseText(json);
            posted = [path: path, body: body]; return ''
        }
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"label":"Listed Switch","disabled":false}}' }

        when: 'enable the already-enabled device (no-op flip; the listed read-back stays disabled:false == wanted)'
        def result = script.toolUpdateDevice([deviceId: '10', fields: [enabled: true]])

        then: 'the native write is recorded and an independent read confirms the requested flag'
        posted.path == '/device/disable'
        posted.body == [id: 10, disable: false]
        result.changes.find { it.property == 'enabled' }?.newValue == true
        result.success == true
        hubGet.calls.count { it.path == '/device/fullJson/10' } == 2
    }

    def "regression: bypass ON commands a LISTED device via native runmethod"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/device/fullJson/10') {
            def model = fullJsonModel()
            model.device.id = 10
            JsonOutput.toJson(model)
        }
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

        then: 'the native command fired with metadata and snapshot reads'
        0 * device.on()
        runmethodHit
        hubGet.calls.any { it.path.startsWith('/device/fullJson') }
        result.success == true
    }

    def "regression: bypass ON polls a LISTED device through native fullJson with SDK traps"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        def device = Spy(TestDevice) {
            getId() >> 10
            getLabel() >> { throw new AssertionError('SDK label read is forbidden') }
            getSupportedAttributes() >> { throw new AssertionError('SDK declarations are forbidden') }
            getCurrentStates() >> { throw new AssertionError('SDK state read is forbidden') }
        }
        childDevicesList << device
        def nativeModel = fullJsonModel('on')
        nativeModel.device.id = 10
        nativeModel.device.label = 'Listed Switch'
        hubGet.register('/device/fullJson/10') { JsonOutput.toJson(nativeModel) }

        when:
        def result = script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch',
            expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 50])

        then:
        result.success == true
        result.finalValue == 'on'
        result.polledCount == 1
        hubGet.calls.count { it.path == '/device/fullJson/10' } == 1
    }

    def "regression: _pollMultiDevice with a MIX of listed + unlisted deviceIds (bypass ON) converges both"() {
        given: 'one listed device (10) and one unlisted device (555) reached via the bypass'
        settingsMap.bypassDeviceAllowlist = true
        registerFullJson({ 'on' })
        def listed = Spy(TestDevice) {
            getId() >> 10
            getLabel() >> { throw new AssertionError('SDK label read is forbidden') }
            getSupportedAttributes() >> { throw new AssertionError('SDK declarations are forbidden') }
            getCurrentStates() >> { throw new AssertionError('SDK state read is forbidden') }
        }
        childDevicesList << listed
        def nativeModel = fullJsonModel('on')
        nativeModel.device.id = 10
        nativeModel.device.label = 'Listed Switch'
        hubGet.register('/device/fullJson/10') { JsonOutput.toJson(nativeModel) }

        when: "mode 'all' over both ids"
        def result = script.toolPollUntilAttribute([deviceIds: ['10', UNLISTED_ID], mode: 'all',
            attribute: 'switch', expectedValue: 'on', timeoutMs: 1000, pollIntervalMs: 50])

        then: 'both per-device value readers fired and the aggregate converged'
        result.success == true
        result.mode == 'all'
        result.convergedCount == 2
        result.devices.find { it.deviceId == '10' }?.matched == true
        result.devices.find { it.deviceId == UNLISTED_ID }?.matched == true
        hubGet.calls.count { it.path == "/device/fullJson/${UNLISTED_ID}" } == 1
        hubGet.calls.count { it.path == '/device/fullJson/10' } == 1
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
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("Device not found: ${UNLISTED_ID}")
    }
}
