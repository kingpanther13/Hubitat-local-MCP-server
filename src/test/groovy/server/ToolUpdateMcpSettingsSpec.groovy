package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the manage_mcp_self gateway tool in hubitat-mcp-server.groovy:
 *
 * - toolUpdateMcpSettings -> update_mcp_settings
 *
 * Three layers of access control are enforced before the write:
 *   1. settings.enableDeveloperMode must be true                  -> IllegalStateException
 *   2. requireHubAdminWrite(args.confirm) — 3 sub-checks          -> IllegalArgumentException
 *      (settings.enableHubAdminWrite, args.confirm, 24h backup)
 *   3. settings map must be a non-empty Map                       -> IllegalArgumentException
 *   4. each setting key must be in the v1 allowlist               -> IllegalArgumentException
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

    def "throws IllegalStateException when enableDeveloperMode is off"() {
        given: 'Hub Admin Write is enabled but Developer Mode is not'
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L

        when:
        script.toolUpdateMcpSettings([settings: [debugLogging: true], confirm: true])

        then:
        def ex = thrown(IllegalStateException)
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
        ex.message.contains('enableRuleEngine')

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
            settings: [enableRuleEngine: false],
            confirm: true
        ])

        then:
        result.success == true
        result.updated == [enableRuleEngine: false]
        sharedAppStub.settingsStore['enableRuleEngine'] == [type: 'bool', value: false]

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
                enableBuiltinAppRead: true,
                debugLogging: false
            ],
            confirm: true
        ])

        then:
        result.success == true
        result.updated.size() == 3
        sharedAppStub.settingsStore['enableHubAdminRead'] == [type: 'bool', value: true]
        sharedAppStub.settingsStore['enableBuiltinAppRead'] == [type: 'bool', value: true]
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
}
