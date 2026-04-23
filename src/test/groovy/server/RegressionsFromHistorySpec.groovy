package server

import groovy.json.JsonOutput
import support.TestDevice
import support.ToolSpecBase

/**
 * Backfill regression tests for server-side bugs that shipped in releases
 * prior to the Groovy/Spock harness being introduced (#69). Each feature
 * method pins behaviour that a specific historical fix restored — so if a
 * future refactor reintroduces the buggy shape, the relevant assertion
 * trips.
 *
 * Scope: bugs reproducible at the unit level. Firmware/sandbox-only
 * issues (e.g. SecurityException from `getClass()`, log.isDebugEnabled()
 * not available in the sandbox) cannot be asserted here — the harness
 * runs with {@code Flags.DontRestrictGroovy} and a PermissiveLog that
 * accepts methods the real hub rejects. Those stay guarded by
 * {@code sandbox_lint.py} (SANDBOX-001) at CI lint time; see PR #103 +
 * PR #107 for the rationale.
 *
 * Source: CHANGELOG.md + packageManifest.json releaseNotes + README
 * Version History. Issue #76 tracks the backfill.
 */
class RegressionsFromHistorySpec extends ToolSpecBase {

    // --- formatAge singular grammar (v0.7.7) --------------------------------
    //
    // Before the fix, formatAge(now - 1h) returned "1 hours ago" because the
    // code unconditionally pluralised. The current helper at
    // hubitat-mcp-server.groovy:4615 branches on `count == 1` for each unit.

    def "formatAge returns 'just now' for an elapsed time under one minute"() {
        expect: 'harness now() == 1234567890000L — pick a timestamp 30s earlier'
        script.formatAge(1234567890000L - 30_000L) == 'just now'
    }

    def "formatAge renders minutes with correct singular and plural grammar"() {
        expect:
        script.formatAge(1234567890000L - 60_000L) == '1 minute ago'
        script.formatAge(1234567890000L - (5 * 60_000L)) == '5 minutes ago'
    }

    def "formatAge renders hours with correct singular and plural grammar"() {
        expect:
        script.formatAge(1234567890000L - 3_600_000L) == '1 hour ago'
        script.formatAge(1234567890000L - (3 * 3_600_000L)) == '3 hours ago'
    }

    def "formatAge renders days with correct singular and plural grammar"() {
        expect:
        script.formatAge(1234567890000L - 86_400_000L) == '1 day ago'
        script.formatAge(1234567890000L - (2 * 86_400_000L)) == '2 days ago'
    }

    def "formatAge returns 'unknown' for a null or zero timestamp"() {
        expect:
        script.formatAge(null) == 'unknown'
        script.formatAge(0L) == 'unknown'
    }

    // --- BigDecimal.round() crash in checkForUpdate (v0.6.1) ----------------
    //
    // Previously `Math.round(msSinceCheck / (1000.0 * 60 * 60))` produced a
    // BigDecimal and Math.round(BigDecimal) is a MissingMethodException in
    // Groovy (the hub sandbox rejected it for a different reason, but the
    // unit harness reproduces the MME directly — so no sandbox flag is
    // required here). The fix at hubitat-mcp-server.groovy:7965 uses
    // integer literals inside the division and casts to int — avoiding
    // both the BigDecimal throw and the (also-buggy) 10x-off-by-unit
    // shape the v0.6.1 notes flagged.
    //
    // checkForUpdate's outer try/catch swallows any throw and routes it
    // through mcpLog("warn", "server", "Version update check failed: ...")
    // — so asserting `noExceptionThrown()` alone is NOT enough: a future
    // regression that reintroduces fractional division would still satisfy
    // that assertion. We intercept mcpLog + asynchttpGet and assert the
    // skip-branch side effects: no warn log, and no asynchttpGet call.

    def "checkForUpdate skip-branch uses integer math and exits cleanly without hitting the outer catch"() {
        given: 'a recent checkedAt timestamp so the skip branch fires'
        stateMap.updateCheck = [checkedAt: 1234567890000L - 2 * 3_600_000L - 1234L]

        and: 'capture mcpLog + asynchttpGet so we can distinguish skip-success from catch-swallow'
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }
        def asyncHttpCalls = []
        script.metaClass.asynchttpGet = { String handler, Map params ->
            asyncHttpCalls << [handler: handler, params: params]
        }

        when:
        script.checkForUpdate()

        then: 'the skip branch fired — no outer-catch warn, no fall-through to doUpdateCheck'
        !mcpLogCalls.any { it.level == 'warn' && it.msg?.contains('Version update check failed') }
        asyncHttpCalls.isEmpty()
    }

    // --- device_health_check hoursAgo formula (v0.7.6; context v0.5.3–v0.5.4)
    //
    // Release history for this formula: v0.5.3 added .round() safety,
    // v0.5.4 swapped to pure integer math, v0.7.6 re-introduced fractional
    // division — with explicit `.0` literals + `*10 / 10.0` — to get one
    // decimal place of precision without re-triggering the BigDecimal
    // crash. The current shape at hubitat-mcp-server.groovy:5918 is the
    // v0.7.6 form: `Math.round((now() - activityTime) / 3600000.0 * 10) / 10.0`.
    //
    // This test only directly guards the v0.7.6 one-decimal-in-hours
    // shape — pre-v0.5.3 BigDecimal.round throws aren't reproducible here
    // for the usual DontRestrictGroovy reason (see class Javadoc). A
    // revert to the v0.7.6-era 10x-off formula would produce 27.0 and
    // trip the assertion.

    def "device_health_check reports hoursAgo as fractional hours with one decimal place (v0.7.6)"() {
        given: 'a selected device whose lastActivity is 2.7h before the harness now()'
        def twoPointSevenHoursAgo = new Date(1234567890000L - (long)(2.7 * 3_600_000L))
        def device = new TestDevice(id: 1, name: 'sensor', label: 'Sensor')
        device.metaClass.getLastActivity = { -> twoPointSevenHoursAgo }
        childDevicesList << device
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolDeviceHealthCheck([staleHours: 24, includeHealthy: true])

        then:
        def entries = (result.healthyDevices ?: []) + (result.staleDevices ?: []) + (result.unknownDevices ?: [])
        entries.size() == 1
        entries[0].hoursAgo != null
        // Regression guard: pre-v0.7.6 this was ~27.0 (10x off); today it
        // should be within a tenth of 2.7.
        Math.abs(entries[0].hoursAgo - 2.7d) < 0.1d
    }

    // --- get_hub_logs source filter applied to the message field (v0.8.5) --
    //
    // The pre-fix code compared the source filter against `entry.time`
    // (timestamp), so a filter like source='Thermostat' never matched any
    // log lines. The fix (hubitat-mcp-server.groovy:5437) checks both
    // `entry.message` and `entry.name`.

    def "get_hub_logs source filter matches against message field, not timestamp"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson([
                'sys\tinfo\tapp|42|Thermostat|turned on\t2026-04-19 10:00:00.000\ttype',
                'sys\tinfo\tdev|99|Porch Light|level changed\t2026-04-19 10:00:01.000\ttype'
            ])
        }

        when: 'source filter matches a word that only appears in the message field'
        def positive = script.toolGetHubLogs([source: 'Thermostat'])

        then: 'only the Thermostat-mentioning entry survives the message-field filter'
        positive.logs.size() == 1
        positive.logs[0].message.contains('Thermostat')

        when: 'source filter matches the timestamp prefix — a pre-fix hit, a post-fix miss'
        def negative = script.toolGetHubLogs([source: '2026-04-19'])

        then: 'zero matches — the fix checks message/name, not the time column'
        negative.logs.size() == 0
    }

    // --- get_hub_logs JSON array parsing + line-split fallback (v0.5.1) ---
    //
    // v0.5.1 fixed JSON array parsing of /logs/past/json. The current code
    // (hubitat-mcp-server.groovy:5389) attempts JsonSlurper first and falls
    // back to newline-splitting when the hub returns a non-JSON body
    // (older firmware shape).

    def "get_hub_logs handles a non-JSON newline-delimited response (older-firmware fallback)"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/past/json') { params ->
            // Unparseable-as-JSON — hits the line-split fallback
            "App 1\tinfo\tFirst\t2026-04-19 10:00:00.000\ttype\n" +
            "App 1\tinfo\tSecond\t2026-04-19 10:00:01.000\ttype"
        }

        when:
        def result = script.toolGetHubLogs([:])

        then: 'both lines parse + reverse-ordering still applies'
        result.logs.size() == 2
        result.logs[0].message == 'Second'
        result.logs[1].message == 'First'
    }

    // --- send_command parameter normalisation (v0.8.2 + v0.8.5) -------------
    //
    // v0.8.2 fixed JSON-string parameters not being parsed to Maps for
    // setColor-style commands. v0.8.5 broadened handling: when Hubitat's
    // JSON parser chokes on a nested object and hands the raw String
    // through, normalizeCommandParams extracts the embedded JSON by
    // brace-matching. Both paths live on hubitat-mcp-server.groovy:2166.

    def "normalizeCommandParams parses a JSON-string element into a Map (v0.8.2 setColor regression)"() {
        when:
        def result = script.normalizeCommandParams(['{"hue":0,"saturation":100,"level":50}'])

        then:
        result.size() == 1
        result[0] instanceof Map
        result[0].hue == 0
        result[0].saturation == 100
        result[0].level == 50
    }

    def "normalizeCommandParams extracts an embedded JSON object from an unparsed-string wrapper (v0.8.5 regression)"() {
        when: 'simulate the Hubitat-parser-failure shape: a raw String instead of a List'
        def result = script.normalizeCommandParams('["{"hue":120,"saturation":50,"level":75}"]')

        then: 'the brace-match fallback pulls the embedded object out'
        result.size() == 1
        result[0] instanceof Map
        result[0].hue == 120
        result[0].saturation == 50
        result[0].level == 75
    }

    def "convertParamElements coerces numeric-string elements to Integer or Double based on the dot"() {
        expect:
        script.normalizeCommandParams(['75']) == [75]
        script.normalizeCommandParams(['3.14']) == [3.14d]
    }
}
