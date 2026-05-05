package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the manage_mcp_self gateway tool in hubitat-mcp-server.groovy:
 *
 * - toolUpdateMcpSettings -> update_mcp_settings
 *
 * Four validation gates fire before any app.updateSetting() call (all-or-nothing):
 *   1. settings.enableDeveloperMode must be true                  -> IllegalArgumentException
 *   2. requireHubAdminWrite(args.confirm) — 3 sub-checks          -> IllegalArgumentException
 *      (settings.enableHubAdminWrite, args.confirm, 24h backup)
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
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // -------- Gate: enableDeveloperMode toggle --------

    def "throws IllegalArgumentException when enableDeveloperMode is off (routes through -32602, not generic ERROR catch)"() {
        given: 'Hub Admin Write is enabled but Developer Mode is not'
        settingsMap.enableHubAdminWrite = true
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

    // -------- Gate: requireHubAdminWrite --------

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
        settingsMap.enableHubAdminWrite = true
        // No stateMap.lastBackupTimestamp seeded

        when:
        script.toolUpdateMcpSettings([settings: [debugLogging: true], confirm: true])

        then:
        thrown(IllegalArgumentException)
        sharedAppStub.settingsStore.isEmpty()
    }

    def "throws when enableHubAdminWrite setting is off"() {
        given:
        settingsMap.enableDeveloperMode = true
        // No enableHubAdminWrite seed

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
        script.toolUpdateMcpSettings([settings: [enableHubAdminWrite: false], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'enableHubAdminWrite'")
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

    def "rejects selectedDevices (different wire format, separate tool planned)"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        script.toolUpdateMcpSettings([settings: [selectedDevices: []], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'selectedDevices'")
    }

    def "rejects all-or-nothing — one bad key blocks the entire batch (no partial writes)"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when: 'first key is allowed, second is not'
        script.toolUpdateMcpSettings([
            settings: [debugLogging: true, enableHubAdminWrite: false],
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
        result.message.contains('Updated 1 setting')
        result.message.contains('reconnect')
    }

    def "writes multiple settings in one call"() {
        given:
        enableDeveloperModeAndAdminWrite()

        when:
        def result = script.toolUpdateMcpSettings([
            settings: [
                enableHubAdminRead: true,
                enableBuiltinApp: true,
                debugLogging: false
            ],
            confirm: true
        ])

        then:
        result.success == true
        result.updated.size() == 3
        sharedAppStub.settingsStore['enableHubAdminRead'] == [type: 'bool', value: true]
        sharedAppStub.settingsStore['enableBuiltinApp'] == [type: 'bool', value: true]
        sharedAppStub.settingsStore['debugLogging'] == [type: 'bool', value: false]
        result.message.contains('Updated 3 setting')
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
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L

        def msg = [
            jsonrpc: '2.0',
            id: 99,
            method: 'tools/call',
            params: [
                name: 'manage_mcp_self',
                arguments: [tool: 'update_mcp_settings', args: [settings: [debugLogging: true], confirm: true]]
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
