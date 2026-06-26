package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the hub_manage_mcp gateway tool in hubitat-mcp-server.groovy:
 *
 * - toolUpdateMcpSettings -> hub_update_mcp_settings
 *
 * Validation gates fire before any app.updateSetting() call (all-or-nothing):
 *   1. settings.enableDeveloperMode must be true                  -> IllegalArgumentException
 *   2. requireDestructiveConfirm(args.confirm) — 2 sub-checks     -> IllegalArgumentException
 *      (args.confirm, 24h backup). The universal Write master is enforced
 *      centrally in executeTool, not inside this tool.
 *   3. settings map must be a non-empty Map                       -> IllegalArgumentException
 *   4. each setting key must be in the v1 allowlist               -> IllegalArgumentException
 *   5. per-key sub-validation (e.g. mcpLogLevel ∈ getLogLevels()) -> IllegalArgumentException
 *
 * All gates throw IllegalArgumentException so handleToolsCall routes through the clean
 * -32602 Invalid params branch (vs. the broad Exception catch that would generate ERROR
 * + stack trace for what is fundamentally a config refusal).
 *
 * Mocking strategy (see docs/testing.md):
 *   - app.updateSetting -> sharedAppStub (TestChildApp) provides settingsStore.
 *     getApp() is layered onto the @Shared appExecutor mock once in setupSpec
 *     so every call resolves through the same instance. cleanup() clears the
 *     settingsStore between tests.
 *   - mcpLogLevel updates delegate to toolSetLogLevel which also touches
 *     state.debugLogs.config.logLevel — the spec asserts on both.
 */
class ToolUpdateMcpSettingsSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')

    def setupSpec() {
        // Layer getApp() onto the already-built @Shared mock so toolUpdateMcpSettings'
        // app.updateSetting(...) call has a non-null target. Same additive-stub
        // pattern as ToolManageLogsSpec.setupSpec.
        appExecutor.getApp() >> sharedAppStub
    }

    def cleanup() {
        sharedAppStub.settingsStore.clear()
    }

    private void enableDeveloperModeAndAdminWrite() {
        settingsMap.enableDeveloperMode = true
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // -------- Gate: enableDeveloperMode toggle --------

    def "throws IllegalArgumentException when enableDeveloperMode is off (routes through -32602, not generic ERROR catch)"() {
        given: 'Hub Admin Write is enabled but Developer Mode is not'
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L

        when:
        script.toolUpdateMcpSettings([settings: [debugLogging: true], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Developer Mode tools are disabled')
        ex.message.contains("'Developer Mode Tools'")

        and: 'no settings were written'
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Gate: requireDestructiveConfirm (confirm + 24h backup) --------

    def "throws when confirm is not provided"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [debugLogging: true]])

        then:
        thrown(IllegalArgumentException)
        sharedAppStub.settingsStore.isEmpty()
    }

    def "throws when no recent backup exists (24h gate)"() {
        given:
        settingsMap.enableDeveloperMode = true
        settingsMap.enableWrite = true
        // No stateMap.lastBackupTimestamp seeded

        when:
        script.toolUpdateMcpSettings([settings: [debugLogging: true], confirm: true])

        then:
        thrown(IllegalArgumentException)
        sharedAppStub.settingsStore.isEmpty()
    }

    def "throws when no recent backup exists (requireDestructiveConfirm)"() {
        given:
        settingsMap.enableDeveloperMode = true
        // confirm=true passes, but no state.lastBackupTimestamp -> the 24h-backup
        // sub-check of requireDestructiveConfirm refuses (the toggle check is gone --
        // the universal Write master is enforced centrally in executeTool).

        when:
        script.toolUpdateMcpSettings([settings: [debugLogging: true], confirm: true])

        then:
        thrown(IllegalArgumentException)
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Validation: settings shape --------

    def "throws when settings arg is missing"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('settings must be a non-empty map')
    }

    def "throws when settings arg is empty map"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [:], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('settings must be a non-empty map')
    }

    def "throws when settings arg is not a Map"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: 'enableDeveloperMode=true', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('settings must be a non-empty map')
    }

    // -------- Allowlist enforcement --------

    def "throws when setting key is not in the v1 allowlist"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [enableWrite: false], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'enableWrite'")
        ex.message.contains('not allowed')

        and: 'error lists allowed keys to help the caller correct'
        ex.message.contains('mcpLogLevel')
        ex.message.contains('enableCustomRuleEngine')

        and: 'no settings were written when validation fails'
        sharedAppStub.settingsStore.isEmpty()
    }

    def "rejects enableDeveloperMode itself (lockout protection — must remain UI-only to disable)"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [enableDeveloperMode: false], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'enableDeveloperMode'")
    }

    def "accepts selectedDevices as an allowlisted key (the device-access scope routes to _validateMcpDeviceScope)"() {
        // selectedDevices is now an ALLOWED settings key -- it routes to the device-scope path
        // (atomic id validation + lockout guard + capability.* write), NOT the generic scalar
        // coerce path, so it is NOT rejected as a disallowed key. Full device-scope behavior is
        // covered by McpSettingsDeviceScopeSpec; this spec only pins that it isn't allowlist-rejected.
        given:
        enableDeveloperModeAndAdminWrite()
        settingsMap.selectedDevices = []
        // A real id present in the all-hub device list so atomic validation passes.
        hubGet.register('/device/listWithCapabilities/json') { params ->
            groovy.json.JsonOutput.toJson([[id: 81, label: 'D81', capabilities: ['Switch']]])
        }

        when:
        def result = script.toolUpdateMcpSettings([settings: [selectedDevices: ['81']], confirm: true])

        then: 'NOT rejected as a disallowed key -- it applied via the device-scope path'
        result.success == true
        result.selectedDevices?.authorizedDeviceIds == ['81']
        sharedAppStub.settingsStore['selectedDevices'] == [type: 'capability.*', value: ['81']]
    }

    def "rejects all-or-nothing — one bad key blocks the entire batch (no partial writes)"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when: 'first key is allowed, second is not'
        script.toolUpdateMcpSettings([
            settings: [debugLogging: true, enableWrite: false],
            confirm: true
        ])

        then: 'validation rejects the batch BEFORE any updateSetting fires'
        thrown(IllegalArgumentException)
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Golden path: boolean settings --------

    def "writes a single boolean setting via app.updateSetting and returns success"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [enableCustomRuleEngine: false],
            confirm: true
        ])

        then:
        result.success == true
        result.updated == [enableCustomRuleEngine: false]
        sharedAppStub.settingsStore['enableCustomRuleEngine'] == [type: 'bool', value: false]

        and: 'the user-facing message warns about reconnecting to refresh schemas'
        // Pin the sentence-period to distinguish "Updated 1 setting." (singular)
        // from "Updated 1 setting (multi)..." (which would not exist with the count-aware
        // ternary but a naive match would tolerate). Mirrors the multi-setting spec below.
        result.message.contains('Updated 1 setting.')
        !result.message.contains('Updated 1 settings')
        result.message.contains('reconnect')
    }

    def "writes multiple settings in one call"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [
                enableRead: true,
                debugLogging: false
            ],
            confirm: true
        ])

        then:
        result.success == true
        result.updated.size() == 2
        sharedAppStub.settingsStore['enableRead'] == [type: 'bool', value: true]
        sharedAppStub.settingsStore['debugLogging'] == [type: 'bool', value: false]
        // Pin the plural form with sentence-period to make singular vs plural
        // discrimination explicit (W-spec-updateMcpSettings).
        result.message.contains('Updated 2 settings.')
        !result.message.contains('Updated 2 setting.')
    }

    def "enableWrite is rejected (not allowlisted -- footgun)"() {
        // enableWrite is deliberately excluded from the self-admin allowlist: a write tool
        // disabling its own write path mid-session would lock the client out (#113).
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [enableWrite: false], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('enableWrite')

        and: 'nothing was written'
        sharedAppStub.settingsStore.isEmpty()
    }

    def "writes a number setting with the correct type hint"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [maxCapturedStates: 50],
            confirm: true
        ])

        then:
        result.success == true
        sharedAppStub.settingsStore['maxCapturedStates'] == [type: 'number', value: 50]
    }

    // -------- Golden path: useGateways (gateway-mode self-switch) --------

    def "writes useGateways toggle — gateway-mode self-switch is allowlisted (dev-mode gated)"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when: 'the agent switches the server into gateway mode via its own self-admin tool'
        def result = script.toolUpdateMcpSettings([
            settings: [useGateways: true],
            confirm: true
        ])

        then:
        result.success == true
        result.updated == [useGateways: true]
        sharedAppStub.settingsStore['useGateways'] == [type: 'bool', value: true]

        and: 'message still steers the client to reconnect — the tools/list shape just changed'
        result.message.contains('reconnect')
    }

    def "coerces useGateways string 'false' to native false — turning gateway mode OFF must not stay truthy"() {
        given: 'a CI/curl client sends the string "false" to flatten the tool surface'
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [useGateways: 'false'],
            confirm: true
        ])

        then: 'string "false" coerces to native false, not Groovy-truthy "false"'
        result.success == true
        sharedAppStub.settingsStore['useGateways'] == [type: 'bool', value: false]
    }

    // -------- Golden path: publishOutputSchemas (outputSchema opt-in, issue #290) --------

    def "writes publishOutputSchemas toggle — outputSchema opt-in is allowlisted (dev-mode gated)"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when: 'the agent opts into advertising outputSchema via its own self-admin tool'
        def result = script.toolUpdateMcpSettings([
            settings: [publishOutputSchemas: true],
            confirm: true
        ])

        then:
        result.success == true
        result.updated == [publishOutputSchemas: true]
        sharedAppStub.settingsStore['publishOutputSchemas'] == [type: 'bool', value: true]

        and: 'message steers the client to reconnect — the advertised tool schema just changed'
        result.message.contains('reconnect')
    }

    def "coerces publishOutputSchemas string 'true' to native true — opting in must not stay a truthy string"() {
        given: 'a CI/curl client sends the string "true"'
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [publishOutputSchemas: 'true'],
            confirm: true
        ])

        then: 'string "true" coerces to native true'
        result.success == true
        sharedAppStub.settingsStore['publishOutputSchemas'] == [type: 'bool', value: true]
    }

    def "coerces publishOutputSchemas string 'false' to native false — opting out must not stay truthy"() {
        given: 'a CI/curl client sends the string "false"'
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [publishOutputSchemas: 'false'],
            confirm: true
        ])

        then: 'string "false" coerces to native false, not Groovy-truthy "false"'
        result.success == true
        sharedAppStub.settingsStore['publishOutputSchemas'] == [type: 'bool', value: false]
    }

    // -------- Golden path: mcpLogLevel delegates to toolSetLogLevel --------

    def "mcpLogLevel updates state.debugLogs.config AND settings (via toolSetLogLevel delegation)"() {
        given:
        enableDeveloperModeAndAdminWrite()
        stateMap.debugLogs = [entries: [], config: [logLevel: 'info', maxEntries: 100]]

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [mcpLogLevel: 'warn'],
            confirm: true
        ])

        then:
        result.success == true

        and: 'the runtime log threshold cache is updated'
        stateMap.debugLogs.config.logLevel == 'warn'

        and: 'the persisted setting is updated so the UI stays in sync'
        sharedAppStub.settingsStore['mcpLogLevel'] == [type: 'enum', value: 'warn']
    }

    // -------- Type coercion (string-encoded values from non-Claude clients) --------

    def "coerces string-encoded boolean #stringValue to native #expected for bool settings"() {
        given: 'a CI script (e.g. curl) sends a string instead of a JSON bool'
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [debugLogging: stringValue],
            confirm: true
        ])

        then: 'the value is coerced to the right native type before app.updateSetting fires'
        result.success == true
        sharedAppStub.settingsStore['debugLogging'] == [type: 'bool', value: expected]

        where:
        stringValue | expected
        'true'      | true
        'TRUE'      | true
        'True'      | true
        'false'     | false
        'FALSE'     | false
        'False'     | false
    }

    def "rejects #badValue as a boolean (no silent truthiness)"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [debugLogging: badValue], confirm: true])

        then: 'a string like "yes" would silently become truthy if not validated — fail loudly instead'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'debugLogging'")
        ex.message.contains('boolean')

        and: 'no setting was written'
        sharedAppStub.settingsStore.isEmpty()

        where:
        badValue << ['yes', 'no', '1', '0', 'maybe', '']
    }

    def "coerces string-encoded number #stringValue to native int"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [maxCapturedStates: stringValue],
            confirm: true
        ])

        then:
        result.success == true
        sharedAppStub.settingsStore['maxCapturedStates'] == [type: 'number', value: expected]

        where:
        stringValue | expected
        '50'        | 50
        '0'         | 0
        '999'       | 999
    }

    def "rejects non-numeric strings for number settings"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [maxCapturedStates: 'abc'], confirm: true])

        then: 'better to fail loudly than silently substitute 0'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'maxCapturedStates'")
        ex.message.contains('number')

        and:
        sharedAppStub.settingsStore.isEmpty()
    }

    def "rejects null setting values"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [debugLogging: null], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'debugLogging'")
        ex.message.contains('cannot be null')
    }

    def "native Number is preserved when passed for a number setting"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [loopGuardMax: 25],
            confirm: true
        ])

        then:
        result.success == true
        sharedAppStub.settingsStore['loopGuardMax'] == [type: 'number', value: 25]
    }

    // -------- Per-key sub-validation (catches errors that would otherwise leak into apply phase) --------

    def "rejects mcpLogLevel='blarg' during validation (no apply-phase leakage)"() {
        // Without per-key pre-validation, this would only fail INSIDE toolSetLogLevel
        // during apply — by which time prior keys in the batch had already been written
        // by app.updateSetting(). Asserts validation now happens up front.
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [mcpLogLevel: 'blarg'], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('mcpLogLevel')
        ex.message.contains('blarg')

        and: 'no settings were written — validation rejected the batch up front'
        sharedAppStub.settingsStore.isEmpty()
    }

    def "mixed batch with bad mcpLogLevel rejects ALL keys (atomic validation)"() {
        // The critical safety property — without per-key validation, debugLogging would
        // have landed before mcpLogLevel's enum check fired inside toolSetLogLevel.
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([
            settings: [debugLogging: true, mcpLogLevel: 'blarg'],
            confirm: true
        ])

        then:
        thrown(IllegalArgumentException)

        and: 'critical: debugLogging is NOT in the settingsStore — no partial write'
        !sharedAppStub.settingsStore.containsKey('debugLogging')
        sharedAppStub.settingsStore.isEmpty()
    }

    // -------- Apply-phase failure handling --------

    def "apply order is sequential — non-mcpLogLevel keys before mcpLogLevel last"() {
        // Documents the apply-phase ordering contract. mcpLogLevel goes through
        // toolSetLogLevel (which has its own state.debugLogs.config side-effect),
        // and is intentionally applied LAST so any prior app.updateSetting() writes
        // have landed before the log-level cache flip. With the per-key validation
        // upstream of apply, mcpLogLevel='blarg' can no longer partially apply (see
        // the "rejects mcpLogLevel='blarg'" + "mixed batch" specs above) — this spec
        // captures the behavioral contract that makes the apply phase safe even if
        // Hubitat itself rejects a write later.
        //
        // Apply-time failure interception (e.g. Hubitat rejecting at apply for a value
        // the MCP allowlist permits) is documented behavior: sequential apply with no
        // rollback, audit log captures attempted= before apply and applied= after.
        // Not unit-testable in the @Shared mock harness without modifying TestChildApp;
        // covered by the live-hub BAT scenarios in tests/BAT-v2.md Section 12.
        given:
        enableDeveloperModeAndAdminWrite()
        stateMap.debugLogs = [entries: [], config: [logLevel: 'info', maxEntries: 100]]

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [
                debugLogging: true,
                mcpLogLevel: 'warn'
            ],
            confirm: true
        ])

        then: 'both keys applied via their respective paths'
        result.success == true
        sharedAppStub.settingsStore['debugLogging'] == [type: 'bool', value: true]
        sharedAppStub.settingsStore['mcpLogLevel'] == [type: 'enum', value: 'warn']
        stateMap.debugLogs.config.logLevel == 'warn'
    }

    // -------- Dispatcher-level LogWrapper regression guard --------

    // -------- Dispatch-envelope counterparts (#187, #121) --------
    // Parallel coverage exercising callTool() so the JSON-RPC envelope, gateway
    // routing toggles, and error mapping (IAE -> -32602, generic -> isError) are
    // verified end-to-end alongside the direct-call golden paths above.

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch writes setting (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDeveloperModeAndAdminWrite()

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [enableCustomRuleEngine: false],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.updated == [enableCustomRuleEngine: false]
        sharedAppStub.settingsStore['enableCustomRuleEngine'] == [type: 'bool', value: false]

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch maps developer-mode-off to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [debugLogging: true], confirm: true
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('Developer Mode tools are disabled')
        sharedAppStub.settingsStore.isEmpty()

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch maps missing-confirm to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDeveloperModeAndAdminWrite()

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [debugLogging: true]
        ])

        then:
        response.error?.code == -32602
        sharedAppStub.settingsStore.isEmpty()

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch maps empty-settings to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDeveloperModeAndAdminWrite()

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [:], confirm: true
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('settings must be a non-empty map')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch maps disallowed-key to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDeveloperModeAndAdminWrite()

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [enableWrite: false], confirm: true
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains("'enableWrite'")
        response.error.message.contains('not allowed')
        sharedAppStub.settingsStore.isEmpty()

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch maps null-value to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDeveloperModeAndAdminWrite()

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [debugLogging: null], confirm: true
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains("'debugLogging'")
        response.error.message.contains('cannot be null')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch coerces string-bool 'true' (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDeveloperModeAndAdminWrite()

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [debugLogging: 'true'], confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        sharedAppStub.settingsStore['debugLogging'] == [type: 'bool', value: true]

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch rejects bad-bool 'yes' to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDeveloperModeAndAdminWrite()

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [debugLogging: 'yes'], confirm: true
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains("'debugLogging'")
        response.error.message.contains('boolean')
        sharedAppStub.settingsStore.isEmpty()

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch applies mcpLogLevel to state cache (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDeveloperModeAndAdminWrite()
        stateMap.debugLogs = [entries: [], config: [logLevel: 'info', maxEntries: 100]]

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [mcpLogLevel: 'warn'], confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        stateMap.debugLogs.config.logLevel == 'warn'
        sharedAppStub.settingsStore['mcpLogLevel'] == [type: 'enum', value: 'warn']

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_mcp_settings via dispatch maps bad mcpLogLevel enum to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDeveloperModeAndAdminWrite()

        when:
        def response = mcpDriver.callTool('hub_update_mcp_settings', [
            settings: [mcpLogLevel: 'blarg'], confirm: true
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('mcpLogLevel')
        response.error.message.contains('blarg')
        sharedAppStub.settingsStore.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "toggle-off path through handleToolsCall returns clean -32602, never cascades"() {
        // Regression guard for the LogWrapper bug: previously, the dispatcher's broad
        // Exception catch called `log.error "msg", throwable`, which Hubitat's
        // LogWrapper.error(String) doesn't accept — triggered MissingMethodException
        // cascade and produced a generic "An unexpected error occurred" response,
        // hiding the real exception's message. Two failure modes locked in here:
        //   (1) gate exception is IllegalArgumentException (routes through -32602, not
        //       the broad catch that hosted the cascade), AND
        //   (2) IF anything ever does fall into the broad catch, the log.error call is
        //       single-string form so no MissingMethodException re-emerges.
        // This spec exercises (1) directly via handleToolsCall. (2) is locked in by
        // sandbox lint + the source-line itself.
        given: 'Hub Admin Write enabled but Developer Mode toggle is off'
        settingsMap.useGateways = true  // calling hub_manage_mcp requires gateway mode on
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L

        def msg = [
            jsonrpc: '2.0',
            id: 99,
            method: 'tools/call',
            params: [
                name: 'hub_manage_mcp',
                arguments: [tool: 'hub_update_mcp_settings', args: [settings: [debugLogging: true], confirm: true]]
            ]
        ]

        when:
        def response = script.handleToolsCall(msg)

        then: 'JSON-RPC error envelope, NOT generic "An unexpected error occurred"'
        response.error?.code == -32602
        response.error?.message?.contains('Developer Mode tools are disabled')

        and: 'verbatim message reaches the caller — proves the broad-catch cascade is not in the path'
        response.error.message.contains("'Developer Mode Tools'")
    }
}
