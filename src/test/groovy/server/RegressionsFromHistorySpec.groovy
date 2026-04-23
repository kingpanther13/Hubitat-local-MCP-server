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

    // --- formatAge singular grammar (v0.7.7 — code review round 2) ----------
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
    // BigDecimal on the sandbox's fractional-division path and Math.round
    // rejected it. The fix (hubitat-mcp-server.groovy:7965) does integer
    // division with a cast — that path must not throw for any fractional
    // elapsed time.

    def "checkForUpdate skip-branch uses integer math and never throws"() {
        given: 'a recent checkedAt timestamp so the skip branch fires'
        stateMap.updateCheck = [checkedAt: 1234567890000L - 2 * 3_600_000L - 1234L]

        when:
        script.checkForUpdate()

        then: 'no exception escapes — the logDebug line used to raise BigDecimal.round'
        noExceptionThrown()
    }

    // --- device_health_check hoursAgo formula (v0.5.3 / v0.5.4 / v0.7.6) ---
    //
    // v0.5.3 fixed BigDecimal.round() crashes in device_health_check. v0.5.4
    // rewrote division to use pure integer math. v0.7.6 corrected a 10x
    // off-by-order-of-magnitude in the hoursAgo value. The current formula
    // (hubitat-mcp-server.groovy:5918) is
    // `Math.round((now() - activityTime) / 3600000.0 * 10) / 10.0` which
    // both avoids BigDecimal issues AND produces a value in hours with one
    // decimal place.

    def "device_health_check reports hoursAgo as fractional hours with one decimal place"() {
        given: 'a selected device whose lastActivity is 2.7h before the harness now()'
        def twoPointSevenHoursAgo = new Date(1234567890000L - (long)(2.7 * 3_600_000L))
        def device = new TestDevice(id: 1, name: 'sensor', label: 'Sensor')
        device.metaClass.getLastActivity = { -> twoPointSevenHoursAgo }
        childDevicesList << device
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolDeviceHealthCheck([staleHours: 24, includeHealthy: true])

        then: 'hoursAgo reflects the elapsed time in hours (not minutes, not 10x)'
        noExceptionThrown()
        def entries = (result.healthyDevices ?: []) + (result.staleDevices ?: []) + (result.unknownDevices ?: [])
        entries.size() == 1
        entries[0].hoursAgo != null
        // One-decimal precision in hours — regression guard: pre-v0.7.6 this
        // was ~27.0 (10x off); pre-v0.5.3 it would have thrown on round().
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

        when:
        def result = script.toolGetHubLogs([source: 'Thermostat'])

        then: 'only the Thermostat-mentioning entry survives the message-field filter'
        result.logs.size() == 1
        result.logs[0].message.contains('Thermostat')
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

    def "normalizeCommandParams passes through a non-JSON numeric string as Integer (convertParamElements)"() {
        expect:
        script.normalizeCommandParams(['75']) == [75]
        script.normalizeCommandParams(['3.14']) == [3.14d]
    }
}
