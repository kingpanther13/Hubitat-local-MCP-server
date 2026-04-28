package server

import support.TestLocation
import support.ToolSpecBase
import spock.lang.Shared

/**
 * Unit-level spec for {@code _rmNormalizeAtTime} -- the helper that coerces
 * various ISO 8601 datetime strings into RM's required canonical form:
 *
 *   yyyy-MM-dd'T'HH:mm:ss.SSSZ   (e.g. 2026-04-28T08:00:00.000+0000)
 *
 * The Z in the output pattern is Java SimpleDateFormat's numeric offset
 * format -- no colon, always 5 characters (+0000 / -0500 / etc.).
 *
 * Motivation: RM 5.1's parser hard-requires the full form. Saving a short
 * ISO variant (no millis, Zulu suffix, or no timezone at all) causes a
 * ParseException on every page render, silently blocking further trigger
 * additions with no recovery path short of deleting the rule. The
 * normalizer runs before the atTime value is written so the stored form is
 * always canonical.
 *
 * Normalization semantics:
 *   - Inputs with an explicit numeric offset (e.g. -0500, +0000) are
 *     normalized to UTC: same instant, +0000 representation. The hub
 *     displays the trigger in its own local tz regardless of the stored
 *     offset, so the original offset carries no display value.
 *   - Inputs with a Zulu 'Z' suffix are also normalized to +0000.
 *   - Inputs with no timezone component have the hub's configured
 *     location.timeZone applied, preserving the wall-clock reading.
 *
 * Tests drive the real {@code script._rmNormalizeAtTime()} method directly.
 * The method was originally declared {@code private}; the {@code private}
 * modifier was removed (replaced with a package-private comment) to allow
 * this spec to call it without MOP reflection -- the {@code _rm} prefix
 * is the established convention for internal helpers. See the Javadoc
 * comment on the method in hubitat-mcp-server.groovy for the rationale.
 *
 * Location/timezone wiring: {@code _rmNormalizeAtTime} reads
 * {@code location.timeZone} when the input has no timezone component.
 * The spec wires {@code sharedLocation.timeZone = UTC} (the default on
 * {@link TestLocation}) so the timezone-inference tests produce
 * deterministic +0000 output regardless of the CI host's local zone.
 */
class RmNormalizeAtTimeSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        // Route location reads in the script to our shared TestLocation stub.
        // sharedLocation.timeZone defaults to UTC via TestLocation, giving
        // deterministic output for the "no tz in input" test cases.
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanupSpec() {
        // Belt-and-suspenders: reset to UTC after all features in this spec
        // so a spec-level failure that skips a test's cleanup: block cannot
        // leak a non-UTC timezone into unrelated specs loaded in the same JVM.
        sharedLocation.timeZone = TimeZone.getTimeZone("UTC")
    }

    // ---------------------------------------------------------
    // Explicit-offset inputs -- normalized to UTC equivalent
    // ---------------------------------------------------------

    def "explicit -0500 offset input is normalized to UTC equivalent"() {
        // Explicit-offset inputs are normalized to the same instant in UTC.
        // 2026-04-28T03:00:00.000-0500 == 08:00:00 UTC.
        // The hub renders the trigger in its own local tz regardless of the
        // stored offset value, so normalizing to UTC is lossless for display.
        when:
        def result = script._rmNormalizeAtTime("2026-04-28T03:00:00.000-0500")

        then:
        result == "2026-04-28T08:00:00.000+0000"
    }

    def "explicit +0500 no-millis offset input is normalized to UTC equivalent"() {
        // Parser 3 (no millis, numeric offset). 2026-04-28T12:34:56+0500
        // == 07:34:56 UTC. Millis added as .000.
        when:
        def result = script._rmNormalizeAtTime("2026-04-28T12:34:56+0500")

        then:
        result == "2026-04-28T07:34:56.000+0000"
    }

    // ---------------------------------------------------------
    // Inputs with no timezone -- hub local tz (UTC in test) applied
    // ---------------------------------------------------------

    def "no-millis no-tz form is normalized using hub local timezone (UTC test default)"() {
        // Input: 2026-04-28T03:00:00  (no millis, no tz)
        // Expected: hub tz (UTC) applied -- output ends +0000 with millis added.
        // Wall-clock 03:00 is preserved; only millis and offset suffix are added.
        when:
        def result = script._rmNormalizeAtTime("2026-04-28T03:00:00")

        then:
        result == "2026-04-28T03:00:00.000+0000"
    }

    def "millis-but-no-tz form is normalized using hub local timezone (UTC test default)"() {
        // Input: 2026-04-28T03:00:00.000  (millis present, no tz)
        // Expected: hub tz (UTC) applied -- output ends +0000.
        when:
        def result = script._rmNormalizeAtTime("2026-04-28T03:00:00.000")

        then:
        result == "2026-04-28T03:00:00.000+0000"
    }

    def "no-millis no-tz form uses a non-UTC hub tz when location.timeZone is overridden"() {
        // Confirm the hub-tz branch honours whatever TimeZone is configured,
        // not just UTC. Use a fixed-offset zone (GMT+5) for a deterministic
        // offset that doesn't DST-shift. The input wall-clock is preserved;
        // only the suffix and millis change.
        given:
        sharedLocation.timeZone = TimeZone.getTimeZone("GMT+5")

        when:
        def result = script._rmNormalizeAtTime("2026-04-28T03:00:00")

        then:
        result == "2026-04-28T03:00:00.000+0500"

        cleanup:
        // Restore default UTC so other tests are unaffected.
        sharedLocation.timeZone = TimeZone.getTimeZone("UTC")
    }

    // ---------------------------------------------------------
    // Zulu / Z-suffix inputs -- normalized to +0000 numeric form
    // ---------------------------------------------------------

    def "Z-suffix with no millis is normalized to numeric +0000 offset"() {
        // Input: 2026-04-28T03:00:00Z  (no millis, Zulu suffix)
        // Expected: millis added, Z replaced with +0000.
        when:
        def result = script._rmNormalizeAtTime("2026-04-28T03:00:00Z")

        then:
        result == "2026-04-28T03:00:00.000+0000"
    }

    def "Z-suffix with millis is normalized to numeric +0000 offset"() {
        // Input: 2026-04-28T03:00:00.000Z  (millis present, Zulu suffix)
        // Expected: Z replaced with +0000; millis preserved.
        when:
        def result = script._rmNormalizeAtTime("2026-04-28T03:00:00.000Z")

        then:
        result == "2026-04-28T03:00:00.000+0000"
    }

    // ---------------------------------------------------------
    // Error cases
    // ---------------------------------------------------------

    def "completely invalid string throws IllegalArgumentException echoing the bad value"() {
        when:
        script._rmNormalizeAtTime("not-a-date")

        then:
        def ex = thrown(IllegalArgumentException)
        // The bad value must be echoed so callers can surface it to the AI.
        ex.message.contains("not-a-date")
        // The message must list at least one accepted form so callers know
        // what to send instead.
        ex.message.contains("YYYY-MM-DD") || ex.message.contains("Accepted")
    }

    def "truncated datetime with no seconds component throws IllegalArgumentException echoing the bad value"() {
        // "2026-04-28T03:00" is missing the seconds field -- none of the six
        // supported parsers accept HH:mm without ss, so every parse attempt
        // throws ParseException and the method reaches the IAE throw.
        when:
        script._rmNormalizeAtTime("2026-04-28T03:00")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("2026-04-28T03:00")
    }

    def "out-of-range month throws IllegalArgumentException echoing the bad value"() {
        // Month 13 is invalid. setLenient(false) on each SDF ensures this is
        // rejected with ParseException rather than silently rolling over into
        // January of the following year (which lenient mode would allow).
        when:
        script._rmNormalizeAtTime("2026-13-01T03:00:00")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("2026-13-01T03:00:00")
    }

    def "empty string throws with the 'atTime is required' message"() {
        when:
        script._rmNormalizeAtTime("")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("atTime is required")
    }

    def "null string throws with the 'atTime is required' message"() {
        when:
        script._rmNormalizeAtTime(null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("atTime is required")
    }

    // ---------------------------------------------------------
    // Output format contract
    // ---------------------------------------------------------

    def "output always matches the canonical yyyy-MM-ddTHH:mm:ss.SSS+HHMM shape"() {
        // SimpleDateFormat 'SSS' always emits exactly 3 digits. The offset is
        // always 5 characters with no colon. Guard that none of the six parsers
        // accidentally produce fewer millis digits, a missing offset, or a
        // colon-separated offset (which would break RM's fixed-width parser).
        when:
        def result = script._rmNormalizeAtTime(input)

        then:
        result ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{4}/

        where:
        input << [
            "2026-04-28T12:34:56",           // no millis, no tz   (parser 6)
            "2026-04-28T12:34:56.000",        // millis, no tz      (parser 5)
            "2026-04-28T12:34:56Z",           // no millis, Zulu    (parser 4)
            "2026-04-28T12:34:56.000Z",       // millis, Zulu       (parser 2)
            "2026-04-28T12:34:56.000+0000",   // millis, offset     (parser 1)
            "2026-04-28T12:34:56+0500",       // no millis, offset  (parser 3)
        ]
    }
}
