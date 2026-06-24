package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestChildApp
import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for the selectedDevices device-access scope path of hub_update_mcp_settings
 * (toolUpdateMcpSettings -> _validateMcpDeviceScope) in libraries/mcp-self-admin-lib.groovy.
 *
 * The device-access scope (selectedDevices) is one of the self-admin settings. Its value is a
 * structured object {mode: "replace"|"add"|"remove" (default "replace"), ids: [...], allowEmpty},
 * or a bare array shorthand (== {mode:"replace", ids:[...]}). It routes to _validateMcpDeviceScope
 * (mode validation + atomic id validation + lockout guard + the capability.* List write) rather
 * than the scalar coerce/allowlist path.
 *
 * Gates fire before any app.updateSetting() call (all-or-nothing):
 *   1. settings.enableDeveloperMode must be true                   -> IllegalArgumentException
 *   2. requireDestructiveConfirm(args.confirm) -- confirm + 24h backup -> IllegalArgumentException
 *   3. mode in {replace, add, remove}; ids an array of scalar ids   -> IllegalArgumentException
 *   4. every id (replace/add) must resolve to a real hub device    -> IllegalArgumentException
 *   5. self-lockout guard: empty resulting set w/o allowEmpty       -> IllegalArgumentException
 *
 * Mocking strategy (mirrors ToolUpdateMcpSettingsSpec + Issue257DeviceAppMeshSpec):
 *   - app.updateSetting       -> sharedAppStub (TestChildApp.settingsStore); getApp() layered
 *     onto the @Shared appExecutor in setupSpec. settingsStore is the assertion surface for
 *     the WRITE (selectedDevices -> [type: 'capability.*', value: <List of id strings>]).
 *   - hubInternalGet          -> hubGet.register('/device/listWithCapabilities/json'){...} for
 *     the all-hub validation read.
 *   - settings.selectedDevices -> settingsMap.selectedDevices (current authorized set).
 */
class McpSettingsDeviceScopeSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
    }

    def cleanup() {
        sharedAppStub.settingsStore.clear()
    }

    private TestDevice dev(id) {
        new TestDevice(id: id, name: "D${id}", label: "Device ${id}", capabilities: [])
    }

    private void enableDevModeAndWrite() {
        settingsMap.enableDeveloperMode = true
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // Register the full hub device set used by id validation.
    private void registerHubDevices(List ids) {
        hubGet.register('/device/listWithCapabilities/json') { params ->
            JsonOutput.toJson(ids.collect { [id: it, label: "Device ${it}", capabilities: ["Switch"]] })
        }
    }

    // Convenience: set the device scope via the settings batch using the bare-array shorthand
    // (== {mode:"replace", ids:[...]}).
    private setScope(deviceIds, Map extra = [:]) {
        script.toolUpdateMcpSettings([settings: [selectedDevices: deviceIds] + extra, confirm: true])
    }

    // Convenience: set the device scope via the structured {mode, ids, allowEmpty} value.
    private setScopeStructured(Map scope, Map extra = [:]) {
        script.toolUpdateMcpSettings([settings: [selectedDevices: scope] + extra, confirm: true])
    }

    // -------- Gate: enableDeveloperMode --------

    def "throws when Developer Mode is off (routes through -32602)"() {
        given: 'Write enabled + backup, but Developer Mode off'
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L

        when:
        setScope(['42'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Developer Mode tools are disabled')

        and: 'nothing written'
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Gate: requireDestructiveConfirm --------

    def "throws when confirm not provided"() {
        given:
        enableDevModeAndWrite()

        when:
        script.toolUpdateMcpSettings([settings: [selectedDevices: ['42']]])

        then:
        thrown(IllegalArgumentException)
        sharedAppStub.settingsStore.isEmpty()
    }

    def "throws when no recent backup exists"() {
        given:
        settingsMap.enableDeveloperMode = true
        settingsMap.enableWrite = true
        // no lastBackupTimestamp seeded

        when:
        setScope(['42'])

        then:
        thrown(IllegalArgumentException)
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Validation: selectedDevices shape --------

    def "throws when selectedDevices is neither an array nor an object"() {
        given:
        enableDevModeAndWrite()

        when:
        setScope('42')  // a bare String -- not the array shorthand nor a {mode, ids} object

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('selectedDevices must be an array')
        sharedAppStub.settingsStore.isEmpty()
    }

    def "throws on a non-scalar selectedDevices element"() {
        given:
        enableDevModeAndWrite()

        when:
        setScope([[nested: true]])

        then:
        thrown(IllegalArgumentException)
        sharedAppStub.settingsStore.isEmpty()
    }

    def "throws on a blank-after-trim selectedDevices element (before any hub fetch)"() {
        // ' ' trims to empty -- element validation precedes the fetch, so no hub call is needed.
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        // deliberately DON'T register the hub list -- validation must reject before fetching

        when:
        setScope([' '])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('non-empty')

        and: 'nothing written'
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Validation: unknown device id (atomic, nothing written) --------

    def "rejects an unknown device id naming the offender; writes nothing"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 99])

        when: 'one real id (80) and a bogus id (777) -- atomic validation must reject the batch'
        setScope(['80', '777'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('777')
        ex.message.contains('Unknown device id')

        and: 'no write landed (the valid 80 did not partially apply)'
        sharedAppStub.settingsStore.isEmpty()
    }

    def "one unknown id in selectedDevices rejects the WHOLE batch -- scalar keys do not land either"() {
        // Atomicity across the mixed batch: selectedDevices is applied FIRST and throws on a bad id,
        // so a sibling scalar key (debugLogging) must NOT be written.
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80])

        when:
        script.toolUpdateMcpSettings([settings: [debugLogging: true, selectedDevices: ['777']], confirm: true])

        then:
        thrown(IllegalArgumentException)
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Self-lockout guard --------

    def "refuses to empty the scope (replace with an empty id list, no allowEmpty)"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]

        when:
        setScope([])  // bare-array shorthand == {mode:replace, ids:[]}

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Refusing to empty')
        ex.message.contains('allowEmpty')
        sharedAppStub.settingsStore.isEmpty()
    }

    def "remove of every authorized id also triggers the lockout guard"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80), dev(81)]
        // remove does not fetch/validate hub devices

        when:
        setScopeStructured([mode: 'remove', ids: ['80', '81']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Refusing to empty')
        sharedAppStub.settingsStore.isEmpty()
    }

    def "allowEmpty:true bypasses the lockout guard and writes an empty set"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]

        when:
        def result = setScopeStructured([mode: 'replace', ids: [], allowEmpty: true])

        then:
        result.success == true
        result.selectedDevices.authorizedCount == 0
        result.selectedDevices.authorizedDeviceIds == []
        result.selectedDevices.removed == ['80']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: []]
    }

    // -------- Golden path: replace semantics --------

    def "selectedDevices replaces the authorized set to exactly the given ids (correct wire format)"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81, 99])

        when:
        def result = setScope(['81', '99'])

        then:
        result.success == true
        result.selectedDevices.mode == 'replace'
        result.selectedDevices.authorizedDeviceIds == ['81', '99']
        result.selectedDevices.authorizedCount == 2
        (result.selectedDevices.added as Set) == (['81', '99'] as Set)
        result.selectedDevices.removed == ['80']

        and: 'the WRITE is selectedDevices -> [type: capability.*, value: <List of id strings>]'
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['81', '99']]

        and: 'message notes the visibility change'
        result.message.contains('device-access scope')
        result.message.toLowerCase().contains('reconnect')
    }

    def "selectedDevices is counted in the update count even when it is the only key"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81])

        when:
        def result = setScope(['81'])

        then:
        result.success == true
        // updated holds only scalar keys (empty here); selectedDevices is reported under its own key.
        result.updated == [:]
        result.selectedDevices.authorizedDeviceIds == ['81']
        result.message.contains('Updated 1 setting.')
    }

    def "scalar key and selectedDevices apply together in one batch"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81])

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [debugLogging: true, selectedDevices: ['81']],
            confirm: true
        ])

        then:
        result.success == true
        sharedAppStub.settingsStore['debugLogging'] == [type: 'bool', value: true]
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['81']]
        result.updated == [debugLogging: true]
        result.selectedDevices.authorizedDeviceIds == ['81']
        result.message.contains('Updated 2 settings.')
    }

    def "schema-affecting scalar + selectedDevices carries BOTH reconnect advisories, distinct, no doubling"() {
        // Conditional-reconnect TRUE branch: a schema-affecting scalar (useGateways) keeps the
        // base tool-schema reconnect clause, AND the device-scope message contributes its own
        // device-visibility reconnect advisory. Both present, distinct, exactly once each.
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81])

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [useGateways: true, selectedDevices: ['81']],
            confirm: true
        ])

        then:
        result.success == true

        and: 'the base tool-schema reconnect clause is present (schema scalar touched)'
        result.message.contains('reconnect to refresh cached tool schemas')

        and: 'the device-visibility reconnect advisory is present (from the device-scope message)'
        result.message.contains('reconnect to refresh which devices are exposed')

        and: 'no doubling -- each distinct advisory appears exactly once'
        result.message.count('reconnect to refresh cached tool schemas') == 1
        result.message.count('reconnect to refresh which devices are exposed') == 1
    }

    def "bare array shorthand is equivalent to {mode:replace, ids:[...]}"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81])

        when:
        def result = setScopeStructured([mode: 'replace', ids: ['81']])

        then:
        result.success == true
        result.selectedDevices.mode == 'replace'
        result.selectedDevices.authorizedDeviceIds == ['81']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['81']]
    }

    def "mode defaults to replace when omitted from the structured value"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81])

        when: 'structured object with ids but NO mode key'
        def result = setScopeStructured([ids: ['81']])

        then: 'treated as replace -- the set becomes exactly the given ids'
        result.success == true
        result.selectedDevices.mode == 'replace'
        result.selectedDevices.authorizedDeviceIds == ['81']
        result.selectedDevices.removed == ['80']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['81']]
    }

    // -------- Golden path: add (union) --------

    def "add unions ids with the current set, de-duping"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80), dev(81)]
        registerHubDevices([80, 81, 99])

        when: 'add 81 (already present) + 99 (new)'
        def result = setScopeStructured([mode: 'add', ids: ['81', '99']])

        then:
        result.success == true
        result.selectedDevices.mode == 'add'
        result.selectedDevices.authorizedDeviceIds == ['80', '81', '99']
        result.selectedDevices.added == ['99']
        result.selectedDevices.removed == []
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['80', '81', '99']]
    }

    def "add rejects an unknown device id (atomic validation fires on the add arm too)"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 99])

        when: 'add a bogus id -- the replace/add atomic-validation arm must reject it'
        setScopeStructured([mode: 'add', ids: ['777']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('777')
        ex.message.contains('Unknown device id')

        and: 'nothing written'
        sharedAppStub.settingsStore.isEmpty()
    }

    def "add DOES fetch the hub list (validates against it) -- mirror of the remove-doesn't-fetch test"() {
        // The remove-no-op test proves remove never fetches; this proves add DOES. If add did not
        // fetch, an unknown id would slip through (the negation of the reject test above).
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81])

        when:
        def result = setScopeStructured([mode: 'add', ids: ['81']])

        then: 'the id validated against the registered hub list and the add landed'
        result.success == true
        result.selectedDevices.added == ['81']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['80', '81']]

        and: 'the all-hub device list endpoint was actually hit'
        hubGet.calls.any { it.path == '/device/listWithCapabilities/json' }
    }

    def "empty add is a no-op (skips the fetch, resulting set == current)"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        // deliberately DON'T register the hub list -- an empty add must skip the fetch

        when:
        def result = setScopeStructured([mode: 'add', ids: []])

        then:
        result.success == true
        result.selectedDevices.authorizedDeviceIds == ['80']
        result.selectedDevices.added == []
        result.selectedDevices.removed == []
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['80']]

        and: 'no fetch happened (the requestedIds.isEmpty() guard skipped it)'
        !hubGet.calls.any { it.path == '/device/listWithCapabilities/json' }
    }

    // -------- Golden path: remove (subtract) --------

    def "remove subtracts ids from the current set"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80), dev(81), dev(99)]

        when:
        def result = setScopeStructured([mode: 'remove', ids: ['81']])

        then:
        result.success == true
        result.selectedDevices.mode == 'remove'
        result.selectedDevices.authorizedDeviceIds == ['80', '99']
        result.selectedDevices.added == []
        result.selectedDevices.removed == ['81']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['80', '99']]
    }

    def "remove of an id not currently authorized is a no-op (no hub fetch, no membership error)"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        // deliberately DON'T register the hub device list -- remove must not fetch it

        when:
        def result = setScopeStructured([mode: 'remove', ids: ['555']])

        then:
        result.success == true
        result.selectedDevices.authorizedDeviceIds == ['80']
        result.selectedDevices.removed == []
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['80']]
    }

    def "current set built from a raw String selectedDevices list (defensive String/Number branch)"() {
        // settings.selectedDevices is normally a List<DeviceWrapper>, but the currentIds collect
        // tolerates raw String/Number ids. Seed a bare String list and assert add/remove arithmetic
        // resolves the current set correctly through that defensive branch.
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = ['80']   // raw String, not a DeviceWrapper
        registerHubDevices([80, 81])

        when: 'add 81 to a current set read as the raw String "80"'
        def result = setScopeStructured([mode: 'add', ids: ['81']])

        then:
        result.success == true
        result.selectedDevices.authorizedDeviceIds == ['80', '81']
        result.selectedDevices.added == ['81']
        result.selectedDevices.removed == []
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['80', '81']]
    }

    def "remove arithmetic over a raw String current set"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = ['80', '81']  // raw String list

        when:
        def result = setScopeStructured([mode: 'remove', ids: ['81']])

        then:
        result.success == true
        result.selectedDevices.authorizedDeviceIds == ['80']
        result.selectedDevices.removed == ['81']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['80']]
    }

    def "FAILS LOUD on an unrecognized current-scope element (no silent scope shrink)"() {
        // A stored selectedDevices element that is neither a device-with-id nor a String/Number id
        // (e.g. a bare Map -- corrupt stored state) must THROW, not be silently dropped (which would
        // shrink the scope without telling anyone). Guards against corrupt persisted state.
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [[foo: 'bar']]  // no .id, not a scalar
        registerHubDevices([81])

        when:
        setScopeStructured([mode: 'add', ids: ['81']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('selectedDevices contains an unrecognized element')

        and: 'nothing written'
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Mode validation --------

    def "throws on an unknown mode"() {
        given:
        enableDevModeAndWrite()

        when:
        setScopeStructured([mode: 'merge', ids: ['81']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('mode must be one of')
        ex.message.contains('merge')
        sharedAppStub.settingsStore.isEmpty()
    }

    def "throws when the structured value omits ids"() {
        given:
        enableDevModeAndWrite()

        when:
        setScopeStructured([mode: 'add'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('ids is required')
        sharedAppStub.settingsStore.isEmpty()
    }

    def "a bare array passed as the selectedDevices value resolves to mode replace"() {
        // Equivalence proof for the bare-array shorthand routed through the same settings key as the
        // structured form: a plain array == {mode:'replace', ids:<array>}.
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81])

        when:
        def result = setScope(['81'])

        then:
        result.success == true
        result.selectedDevices.mode == 'replace'
        result.selectedDevices.authorizedDeviceIds == ['81']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['81']]
    }

    def "a padded-but-invalid mode is trimmed then rejected"() {
        // ' merge ' trims to 'merge', which is not a valid mode -- the trim runs, then the enum
        // check fails (so the error names the trimmed value, not the padded one).
        given:
        enableDevModeAndWrite()

        when:
        setScopeStructured([mode: ' merge ', ids: ['81']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('mode must be one of')
        ex.message.contains('merge')
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Normalization positive triggers: the .trim() sites actually fire --------

    def "whitespace-padded selectedDevices entries are trimmed before validation and write"() {
        // Asserts the trim on raw.toString().trim() -- ' 81 ' must validate against the hub list
        // (which carries '81') and land in the write as the trimmed '81'.
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = []
        registerHubDevices([81])

        when:
        def result = setScope([' 81 '])

        then:
        result.success == true
        result.selectedDevices.authorizedDeviceIds == ['81']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['81']]
    }

    def "whitespace-padded mode is trimmed before validation"() {
        // Asserts the trim on scopeValue.mode.toString().trim() -- ' replace ' must be accepted
        // as 'replace' (the un-padded mode tests are the negation).
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81])

        when:
        def result = setScopeStructured([mode: ' replace ', ids: ['81']])

        then:
        result.success == true
        result.selectedDevices.mode == 'replace'
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['81']]
    }

    // -------- ints coerce to id strings --------

    def "integer selectedDevices entries coerce to string ids in the write + response"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = []
        registerHubDevices([42, 43])

        when:
        def result = setScope([42, 43])

        then:
        result.selectedDevices.authorizedDeviceIds == ['42', '43']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['42', '43']]
    }

    // -------- Runtime error: device-list fetch failure (structured, not thrown) --------

    def "returns a structured error when the all-hub device list is unavailable"() {
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException('boom') }

        when:
        def result = setScope(['81'])

        then: 'a runtime failure returns [success:false] rather than throwing -- nothing written'
        result.success == false
        result.error.contains('listWithCapabilities')
        result.note != null

        and: 'isError:true so handleToolsCall hoists it onto the JSON-RPC envelope (not a quiet result)'
        result.isError == true

        and:
        sharedAppStub.settingsStore.isEmpty()
    }

    def "returns a structured error when the all-hub device list is not a JSON array"() {
        // The endpoint parsed to a non-List (firmware contract drift -- a Map instead of an array).
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> JsonOutput.toJson([oops: 'not an array']) }

        when:
        def result = setScope(['81'])

        then:
        result.success == false
        result.error.contains('expected a JSON array')
        result.note != null

        and: 'isError:true so the client sees this no-op AS an error'
        result.isError == true

        and:
        !sharedAppStub.settingsStore.containsKey('selectedDevices')
    }

    def "a device-list fetch failure aborts the whole batch -- scalar keys do not land"() {
        // selectedDevices is applied first; a [success:false] fetch envelope short-circuits before
        // the scalar app.updateSetting() loop, so debugLogging must NOT be written.
        given:
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        hubGet.register('/device/listWithCapabilities/json') { params -> throw new RuntimeException('boom') }

        when:
        def result = script.toolUpdateMcpSettings([settings: [debugLogging: true, selectedDevices: ['81']], confirm: true])

        then:
        result.success == false
        !sharedAppStub.settingsStore.containsKey('debugLogging')
    }

    // -------- Dispatch-envelope counterparts --------

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch replaces device scope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]
        registerHubDevices([80, 81])

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [selectedDevices: ['81']], confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.selectedDevices.authorizedDeviceIds == ['81']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['81']]

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch maps unknown device id to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDevModeAndWrite()
        settingsMap.selectedDevices = []
        registerHubDevices([80])

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [selectedDevices: ['777']], confirm: true
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('777')
        sharedAppStub.settingsStore.isEmpty()

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch maps lockout refusal to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDevModeAndWrite()
        settingsMap.selectedDevices = [dev(80)]

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [selectedDevices: []], confirm: true
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('Refusing to empty')
        sharedAppStub.settingsStore.isEmpty()

        where:
        useGateways << [true, false]
    }
}
