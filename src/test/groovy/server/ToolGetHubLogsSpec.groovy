package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolGetHubLogs.
 *
 * Covers: most-recent-first ordering (golden), Hub Admin Read gate,
 * appId numeric validation, deviceId/appId mutual exclusion, the
 * pattern/patterns/patternMode/since/until/limit filter pipeline, type-shape
 * validation for all new args, case-insensitivity in both directions,
 * limit truncation, naked-T ISO timestamps parsed as UTC, space-separated
 * hub-native timestamps (no T, no Z) parsed as UTC in the fallback loop, and a
 * kitchen-sink combined-filter scenario.
 *
 * Filter pipeline (in order): scope (hub-side) -> level -> source ->
 * pattern -> patterns -> time-window (since/until) -> limit.
 *
 * Note on regex compile-count testing (Pre2): pinning that Pattern.compile
 * is called once per pattern (not per log entry) via a static metaClass
 * counter on java.util.regex.Pattern is unreliable in this harness -- JDK
 * static methods on bootstrap-classloader classes do not honour per-test
 * Groovy EMC overrides consistently under the eighty20results sandbox loader.
 * The structural guarantee (compile calls precede the for-loop) is verified
 * by code inspection; correctness at scale is covered implicitly by the
 * kitchen-sink and filter scenarios that exercise multi-pattern matching
 * against multi-entry buffers.
 */
class ToolGetHubLogsSpec extends ToolSpecBase {

    // Shared convenience: registers a canned log array with the mock hub
    private void registerLogs(List<String> lines) {
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(lines) }
    }

    def "returns most-recent-first log entries (ordering regression)"() {
        given: 'Hub Admin Read is enabled'
        settingsMap.enableHubAdminRead = true

        and: 'hub returns 3 log lines in chronological order (oldest first)'
        registerLogs([
            'App 1\tinfo\tOldest message\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tMiddle message\t2026-04-19 10:00:01.000\ttype',
            'App 1\tinfo\tNewest message\t2026-04-19 10:00:02.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([:])

        then:
        result.logs.size() == 3
        result.logs[0].message == 'Newest message'
        result.logs[1].message == 'Middle message'
        result.logs[2].message == 'Oldest message'
        result.count == 3
    }

    @spock.lang.Unroll
    def "get_hub_logs via dispatch returns most-recent-first log entries (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tOldest message\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tMiddle message\t2026-04-19 10:00:01.000\ttype',
            'App 1\tinfo\tNewest message\t2026-04-19 10:00:02.000\ttype'
        ])

        when:
        def response = mcpDriver.callTool('get_hub_logs', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.logs.size() == 3
        inner.logs[0].message == 'Newest message'
        inner.logs[1].message == 'Middle message'
        inner.logs[2].message == 'Oldest message'
        inner.count == 3

        where:
        useGateways << [true, false]
    }

    def "throws when Hub Admin Read is disabled"() {
        given:
        settingsMap.enableHubAdminRead = false

        when:
        script.toolGetHubLogs([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    @spock.lang.Unroll
    def "get_hub_logs via dispatch maps Hub-Admin-Read-disabled IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = false

        when:
        def response = mcpDriver.callTool('get_hub_logs', [:])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('Hub Admin Read')

        where:
        useGateways << [true, false]
    }

    def "rejects non-integer appId (numeric-validation regression)"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([appId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    def "rejects deviceId and appId together (mutual-exclusion regression)"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([deviceId: '1', appId: '2'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('mutually exclusive')
    }

    // ==================== pattern filter ====================

    def "pattern keeps only entries whose message matches the regex"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tSomething normal\t2026-04-19 10:00:00.000\ttype',
            'App 1\terror\tERROR: disk full\t2026-04-19 10:00:01.000\ttype',
            'App 1\terror\tERROR: timeout\t2026-04-19 10:00:02.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([pattern: 'ERROR.*'])

        then: 'only the two ERROR entries are kept (newest first after reverse)'
        result.logs.size() == 2
        result.logs.every { it.message.startsWith('ERROR') }
    }

    @spock.lang.Unroll
    def "get_hub_logs via dispatch applies pattern filter (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tSomething normal\t2026-04-19 10:00:00.000\ttype',
            'App 1\terror\tERROR: disk full\t2026-04-19 10:00:01.000\ttype',
            'App 1\terror\tERROR: timeout\t2026-04-19 10:00:02.000\ttype'
        ])

        when:
        def response = mcpDriver.callTool('get_hub_logs', [pattern: 'ERROR.*'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.logs.size() == 2
        inner.logs.every { it.message.startsWith('ERROR') }

        where:
        useGateways << [true, false]
    }

    def "pattern match is case-insensitive"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\terror in device\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tall good\t2026-04-19 10:00:01.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([pattern: 'ERROR'])

        then:
        result.logs.size() == 1
        result.logs[0].message == 'error in device'
    }

    def "invalid pattern throws IllegalArgumentException with helpful message"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([pattern: '[unclosed'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('[unclosed')
        ex.message.toLowerCase().contains('invalid regex pattern')
    }

    @spock.lang.Unroll
    def "get_hub_logs via dispatch maps invalid-regex IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true

        when:
        def response = mcpDriver.callTool('get_hub_logs', [pattern: '[unclosed'])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('[unclosed')
        response.error.message.toLowerCase().contains('invalid regex')

        where:
        useGateways << [true, false]
    }

    // ==================== patterns + patternMode ====================

    def "patterns with default patternMode any keeps entries matching either pattern"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tfoo happened\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tbar happened\t2026-04-19 10:00:01.000\ttype',
            'App 1\tinfo\tbaz happened\t2026-04-19 10:00:02.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([patterns: ['foo', 'bar']])

        then: 'entries with foo OR bar match; baz (newest) is excluded'
        result.logs.size() == 2
        result.logs.every { it.message.contains('foo') || it.message.contains('bar') }
    }

    def "patterns with patternMode all keeps only entries matching every pattern"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tfoo only\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tfoo and bar both\t2026-04-19 10:00:01.000\ttype',
            'App 1\tinfo\tbar only\t2026-04-19 10:00:02.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([patterns: ['foo', 'bar'], patternMode: 'all'])

        then: 'only the entry that has both foo AND bar is kept'
        result.logs.size() == 1
        result.logs[0].message == 'foo and bar both'
    }

    def "pattern and patterns both apply when both are set"() {
        given:
        settingsMap.enableHubAdminRead = true
        // pattern requires 'ERROR', patterns[any] requires 'foo' or 'bar'
        // Only 'ERROR: foo' satisfies both
        registerLogs([
            'App 1\tinfo\tERROR: foo\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tERROR: baz\t2026-04-19 10:00:01.000\ttype',
            'App 1\tinfo\tfoo ok\t2026-04-19 10:00:02.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([pattern: 'ERROR', patterns: ['foo', 'bar']])

        then: 'only the entry satisfying BOTH conditions is returned'
        result.logs.size() == 1
        result.logs[0].message == 'ERROR: foo'
    }

    // ==================== since / until ====================

    def "since relative offset 30m excludes entries older than 30 minutes ago"() {
        given:
        settingsMap.enableHubAdminRead = true
        // now() in harness returns 1234567890000L (millisecond epoch)
        // 30 minutes ago = 1234567890000 - 1800000 = 1234566090000
        // Hub log timestamps are UTC; format fixture strings as UTC so the
        // production UTC parser produces the same epoch we expect.
        long nowMs = 1234567890000L
        long thirtyMinMs = 30 * 60 * 1000L
        def utcSdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        utcSdf.setTimeZone(TimeZone.getTimeZone("UTC"))
        def fmtDate = { long ms -> utcSdf.format(new Date(ms)) }
        def oldTime   = fmtDate(nowMs - thirtyMinMs - 5000)   // 30m 5s ago -- should be excluded
        def recentTime = fmtDate(nowMs - thirtyMinMs + 5000)  // 29m 55s ago -- should be included

        registerLogs([
            "App 1\tinfo\tOld entry\t${oldTime}\ttype",
            "App 1\tinfo\tRecent entry\t${recentTime}\ttype"
        ])

        when:
        def result = script.toolGetHubLogs([since: '30m'])

        then:
        result.logs.size() == 1
        result.logs[0].message == 'Recent entry'
    }

    def "since ISO-8601 timestamp filters correctly"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tBefore cutoff\t2024-01-14 23:59:59.000\ttype',
            'App 1\tinfo\tAfter cutoff\t2024-01-15 00:00:01.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([since: '2024-01-15T00:00:00Z'])

        then:
        result.logs.size() == 1
        result.logs[0].message == 'After cutoff'
    }

    def "since ISO-8601 with milliseconds filters correctly"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tBefore cutoff\t2024-01-14 23:59:59.000\ttype',
            'App 1\tinfo\tAfter cutoff\t2024-01-15 00:00:01.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([since: '2024-01-15T00:00:00.000Z'])

        then:
        result.logs.size() == 1
        result.logs[0].message == 'After cutoff'
    }

    def "since and until define a closed time window"() {
        given:
        settingsMap.enableHubAdminRead = true
        // now() = 1234567890000L
        // Hub log timestamps are UTC; format fixture strings as UTC.
        long nowMs = 1234567890000L
        def utcSdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        utcSdf.setTimeZone(TimeZone.getTimeZone("UTC"))
        def fmtDate = { long ms -> utcSdf.format(new Date(ms)) }

        def tooOld    = fmtDate(nowMs - 3 * 3600 * 1000L)  // 3h ago -- outside since
        def inWindow  = fmtDate(nowMs - 90 * 60 * 1000L)   // 1.5h ago -- inside [2h, 1h]
        def tooRecent = fmtDate(nowMs - 30 * 60 * 1000L)   // 0.5h ago -- outside until

        registerLogs([
            "App 1\tinfo\tToo old\t${tooOld}\ttype",
            "App 1\tinfo\tIn window\t${inWindow}\ttype",
            "App 1\tinfo\tToo recent\t${tooRecent}\ttype"
        ])

        when:
        def result = script.toolGetHubLogs([since: '2h', until: '1h'])

        then:
        result.logs.size() == 1
        result.logs[0].message == 'In window'
    }

    def "bad since value throws IllegalArgumentException with format example"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([since: 'yesterday'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('since')
        ex.message.contains('yesterday')
        // Must include an example so callers know what format to use
        ex.message.contains('ISO-8601') || ex.message.contains('2024') || ex.message.contains('30m')
    }

    def "relative offset beyond 30d throws IllegalArgumentException with ISO-8601 hint"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([since: '31d'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('31d')
        ex.message.toLowerCase().contains('30d')
        ex.message.toLowerCase().contains('iso-8601')
    }

    def "relative offset of exactly 400d also throws (cap is strict, not a clamp)"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([since: '400d'])

        then:
        thrown(IllegalArgumentException)
    }

    def "entries with no parseable time field are passed through (not excluded) when time-window active"() {
        given:
        settingsMap.enableHubAdminRead = true
        // name field contains non-timestamp text so the name-fallback also fails to parse
        registerLogs([
            'App 1\tinfo\tNo time\t\ttype',              // empty time field, name is 'App 1' -- not a timestamp
            'App 1\tinfo\tUnparseable time\tNOT_A_DATE\ttype',
            'App 1\tinfo\tNormal entry\t2024-01-15 01:00:00.000\ttype'
        ])

        when: 'since is set to a recent boundary that normal entry falls after'
        def result = script.toolGetHubLogs([since: '2024-01-15T00:00:00Z'])

        then: 'empty/unparseable-time entries are included (not excluded), normal entry also included'
        result.logs.size() == 3
    }

    def "time-window filter uses entry.name as timestamp when entry.time is empty (firmware 2.5.0.126+ shape)"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Firmware 2.5.0.126+ puts the timestamp in parts[0] (entry.name) and leaves parts[3] (entry.time) empty.
        // Two log lines in that firmware shape: one within the window, one before it.
        registerLogs([
            '2024-01-14 23:59:59.000\tinfo\tBefore cutoff -- should be excluded\t\ttype',
            '2024-01-15 00:00:01.000\tinfo\tAfter cutoff -- should be included\t\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([since: '2024-01-15T00:00:00Z'])

        then: 'timestamp parsed from name field, window filter applied correctly'
        result.logs.size() == 1
        result.logs[0].message == 'After cutoff -- should be included'
    }

    def "time-window filter handles mixed firmware log shapes in the same response"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Mix of documented format (time in parts[3]) and firmware 2.5.0.126+ format (time in parts[0]).
        // Both should be filtered correctly against the same window.
        registerLogs([
            'App 1\tinfo\tDocumented format in window\t2024-01-15 01:00:00.000\ttype',      // time in parts[3]
            'App 1\tinfo\tDocumented format outside window\t2024-01-14 22:00:00.000\ttype', // time in parts[3], too old
            '2024-01-15 02:00:00.000\tinfo\tFirmware shape in window\t\ttype',              // time in parts[0]
            '2024-01-14 23:00:00.000\tinfo\tFirmware shape outside window\t\ttype'          // time in parts[0], too old
        ])

        when:
        def result = script.toolGetHubLogs([since: '2024-01-15T00:00:00Z'])

        then: 'one from each format passes the window; two are excluded as too old'
        result.logs.size() == 2
        result.logs*.message.contains('Documented format in window')
        result.logs*.message.contains('Firmware shape in window')
    }

    // ==================== timezone correctness ====================

    def "hub log entry timestamps (no TZ marker) are parsed as UTC regardless of JVM default timezone"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Harness now() = 1234567890000L (epoch ms).
        // since='5m' -> sinceDate = new Date(1234567890000 - 300000) = 1234567590000L (UTC epoch).
        //
        // Construct two entry timestamp strings using explicit UTC formatting so the test
        // asserts UTC-interpretation correctness even when the JVM default TZ is non-UTC
        // (e.g. America/Denver MDT = UTC-6). With the old Date.parse() code on a hub in MDT,
        // a UTC timestamp string like "2009-02-13 23:27:30.000" would be parsed as MDT local
        // time -> epoch 1234589250000L (6h ahead of the actual UTC epoch 1234567650000L),
        // making entries falsely appear "newer" and defeating the since filter.
        //
        // With the fix (SimpleDateFormat + explicit UTC TZ), "2009-02-13 23:27:30.000" is
        // correctly parsed as 1234567650000L (UTC) = 1 minute after sinceDate -> included.
        def utcTz = TimeZone.getTimeZone("UTC")
        def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        sdf.setTimeZone(utcTz)
        // sinceDate epoch: 1234567590000L
        // Entry just inside window: sinceDate + 60s = 1234567650000L -> "2009-02-13 23:27:30.000" UTC
        def insideWindow  = sdf.format(new Date(1234567590000L + 60000L))
        // Entry just outside window: sinceDate - 60s = 1234567530000L -> "2009-02-13 23:25:30.000" UTC
        def outsideWindow = sdf.format(new Date(1234567590000L - 60000L))

        registerLogs([
            "App 1\tinfo\tOutside window -- should be excluded\t${outsideWindow}\ttype",
            "App 1\tinfo\tInside window -- should be included\t${insideWindow}\ttype"
        ])

        when:
        def result = script.toolGetHubLogs([since: '5m'])

        then: 'UTC parsing: inside-window entry is included; outside-window entry is excluded'
        result.logs.size() == 1
        result.logs[0].message == 'Inside window -- should be included'
    }

    // ==================== patternMode validation ====================

    def "invalid patternMode throws IllegalArgumentException"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([patternMode: 'neither'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('patternMode')
        ex.message.contains('neither')
    }

    // ==================== since/until inversion ====================

    def "since resolving later than until throws IllegalArgumentException with helpful message"() {
        given:
        settingsMap.enableHubAdminRead = true
        // since='1h' -> 1 hour ago, until='2h' -> 2 hours ago: since > until (inverted window)

        when:
        script.toolGetHubLogs([since: '1h', until: '2h'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('window is empty')
    }

    // ==================== type-shape validation (Pre1) ====================

    def "pattern as list throws IllegalArgumentException naming the expected type"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([pattern: ['foo']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pattern')
        ex.message.toLowerCase().contains('string')
    }

    def "patterns as string throws IllegalArgumentException naming the expected type"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([patterns: 'foo'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('patterns')
        ex.message.toLowerCase().contains('list')
    }

    def "since as number throws IllegalArgumentException with format hint"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([since: 30])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('since')
        ex.message.toLowerCase().contains('string')
    }

    def "patterns list containing a non-string element throws IllegalArgumentException naming the index"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        // index 1 (the integer 42) is the bad element
        script.toolGetHubLogs([patterns: ['valid', 42, 'also valid']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('patterns[1]')
        ex.message.toLowerCase().contains('string')
    }

    // ==================== case-insensitivity inverse direction (Pre4) ====================

    def "pattern in lowercase matches log entries with uppercase message text"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tERROR in device\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tall good\t2026-04-19 10:00:01.000\ttype'
        ])

        when: 'lowercase pattern against uppercase message'
        def result = script.toolGetHubLogs([pattern: 'error'])

        then: 'case-insensitive match succeeds in the lowercase-pattern direction'
        result.logs.size() == 1
        result.logs[0].message == 'ERROR in device'
    }

    def "patterns list entries are case-insensitive: mixed-case patterns match opposite-case messages"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tFOO happened\t2026-04-19 10:00:00.000\ttype',   // matches 'foo' pattern
            'App 1\tinfo\tbar happened\t2026-04-19 10:00:01.000\ttype',    // matches 'BAR' pattern
            'App 1\tinfo\tunrelated\t2026-04-19 10:00:02.000\ttype'        // neither
        ])

        when: 'lowercase pattern "foo" vs uppercase message, and uppercase pattern "BAR" vs lowercase message'
        def result = script.toolGetHubLogs([patterns: ['foo', 'BAR']])

        then: 'both directions of case-insensitive matching work in the patterns list'
        result.logs.size() == 2
        result.logs.every { it.message.toLowerCase() =~ /(foo|bar)/ }
    }

    // ==================== kitchen-sink combined filter (Pre3) ====================

    def "kitchen-sink: level + source + pattern + patterns + since applied in pipeline order"() {
        given:
        settingsMap.enableHubAdminRead = true
        // now() in harness = 1234567890000L; "2h" window = entries from 1234560090000L onward
        long nowMs = 1234567890000L
        def utcSdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        utcSdf.setTimeZone(TimeZone.getTimeZone("UTC"))
        def fmtDate = { long ms -> utcSdf.format(new Date(ms)) }
        def recent = fmtDate(nowMs - 1 * 3600 * 1000L)  // 1h ago -- inside 2h window
        def old    = fmtDate(nowMs - 3 * 3600 * 1000L)  // 3h ago -- outside 2h window

        // Six entries. Exactly two should survive all filters:
        //   level=warn, source=testapp (in name), pattern=keyword, patterns any(foo|bar), since=2h, limit=5
        //
        // Entry A: passes all -- warn level, name contains testapp, message has "keyword foo", recent
        // Entry B: passes all -- warn level, name contains testapp, message has "keyword bar", recent
        // Entry C: fails level (info)
        // Entry D: fails source (name is 'otherone')
        // Entry E: fails pattern (message has no "keyword")
        // Entry F: fails since (too old)
        registerLogs([
            "testapp\twarn\tkeyword foo result\t${recent}\tapp",   // A -- passes
            "testapp\twarn\tkeyword bar result\t${recent}\tapp",   // B -- passes
            "testapp\tinfo\tkeyword foo result\t${recent}\tapp",   // C -- wrong level
            "otherone\twarn\tkeyword foo result\t${recent}\tapp",  // D -- wrong source
            "testapp\twarn\tnokeyword here\t${recent}\tapp",       // E -- no keyword
            "testapp\twarn\tkeyword foo result\t${old}\tapp"       // F -- too old
        ])

        when:
        def result = script.toolGetHubLogs([
            level      : 'warn',
            source     : 'testapp',
            pattern    : 'keyword',
            patterns   : ['foo', 'bar'],
            patternMode: 'any',
            since      : '2h',
            limit      : 5
        ])

        then: 'exactly entries A and B survive the full filter pipeline'
        result.logs.size() == 2
        result.logs.every { it.level == 'warn' }
        result.logs.every { it.name?.toLowerCase()?.contains('testapp') }
        result.logs.every { it.message?.toLowerCase()?.contains('keyword') }
        result.logs.every { it.message?.toLowerCase() =~ /(foo|bar)/ }
        result.count == 2
    }

    @spock.lang.Unroll
    def "get_hub_logs via dispatch applies kitchen-sink filter pipeline (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        long nowMs = 1234567890000L
        def utcSdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        utcSdf.setTimeZone(TimeZone.getTimeZone("UTC"))
        def fmtDate = { long ms -> utcSdf.format(new Date(ms)) }
        def recent = fmtDate(nowMs - 1 * 3600 * 1000L)
        def old    = fmtDate(nowMs - 3 * 3600 * 1000L)
        registerLogs([
            "testapp\twarn\tkeyword foo result\t${recent}\tapp".toString(),
            "testapp\twarn\tkeyword bar result\t${recent}\tapp".toString(),
            "testapp\tinfo\tkeyword foo result\t${recent}\tapp".toString(),
            "otherone\twarn\tkeyword foo result\t${recent}\tapp".toString(),
            "testapp\twarn\tnokeyword here\t${recent}\tapp".toString(),
            "testapp\twarn\tkeyword foo result\t${old}\tapp".toString()
        ])

        when:
        def response = mcpDriver.callTool('get_hub_logs', [
            level      : 'warn',
            source     : 'testapp',
            pattern    : 'keyword',
            patterns   : ['foo', 'bar'],
            patternMode: 'any',
            since      : '2h',
            limit      : 5
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.logs.size() == 2
        inner.logs.every { it.level == 'warn' }
        inner.logs.every { it.name?.toLowerCase()?.contains('testapp') }
        inner.count == 2

        where:
        useGateways << [true, false]
    }

    // ==================== combined filter ====================

    def "level plus pattern plus since all apply as intersection"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Hub log timestamps are UTC; format fixture strings as UTC.
        long nowMs = 1234567890000L
        def utcSdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        utcSdf.setTimeZone(TimeZone.getTimeZone("UTC"))
        def fmtDate = { long ms -> utcSdf.format(new Date(ms)) }
        def recent = fmtDate(nowMs - 5 * 60 * 1000L)   // 5 min ago -- within last 30m
        def old    = fmtDate(nowMs - 2 * 3600 * 1000L) // 2 h ago   -- outside since=30m

        registerLogs([
            "App 1\terror\tFAULT: recent issue\t${recent}\ttype",  // passes all 3 (level=error, matches FAULT, recent)
            "App 1\tinfo\tFAULT: recent info\t${recent}\ttype",    // wrong level (info, not error)
            "App 1\terror\tFAULT: old issue\t${old}\ttype",        // outside since (2h ago)
            "App 1\terror\tAll good, no keyword\t${recent}\ttype"  // pattern mismatch (no FAULT)
        ])

        when:
        def result = script.toolGetHubLogs([level: 'error', pattern: 'FAULT', since: '30m'])

        then: 'only the entry passing all three filters is returned'
        result.logs.size() == 1
        result.logs[0].message == 'FAULT: recent issue'
    }

    // ==================== limit truncation ====================

    def "limit truncates result when survivors exceed limit"() {
        given:
        settingsMap.enableHubAdminRead = true
        // 5 WARN entries all pass the level filter; limit=2 should truncate to 2.
        registerLogs([
            'App 1\twarn\tmessage 1\t2026-04-19 10:00:01.000\ttype',
            'App 1\twarn\tmessage 2\t2026-04-19 10:00:02.000\ttype',
            'App 1\twarn\tmessage 3\t2026-04-19 10:00:03.000\ttype',
            'App 1\twarn\tmessage 4\t2026-04-19 10:00:04.000\ttype',
            'App 1\twarn\tmessage 5\t2026-04-19 10:00:05.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([level: 'warn', limit: 2])

        then: 'limit is the binding constraint -- 5 passed the level filter, 2 are returned'
        result.logs.size() == 2
        // count reflects what was returned after limit applied
        result.count == 2
    }

    // ==================== naked-T ISO (no Z suffix) as UTC ====================

    def "since as naked-T ISO timestamp (no Z) is parsed as UTC not JVM default TZ"() {
        given:
        settingsMap.enableHubAdminRead = true
        // since='2024-01-15T10:30:00' (no Z) should be treated as UTC midnight+10:30.
        // UTC epoch for 2024-01-15T10:30:00Z = 1705311000000L.
        // An entry at 2024-01-15 10:30:01 UTC is 1 second inside the window (included).
        // An entry at 2024-01-15 10:29:59 UTC is 1 second before the window (excluded).
        registerLogs([
            'App 1\tinfo\tBefore cutoff\t2024-01-15 10:29:59.000\ttype',
            'App 1\tinfo\tAfter cutoff\t2024-01-15 10:30:01.000\ttype'
        ])

        when: 'naked-T since (no Z suffix) is parsed as UTC'
        def result = script.toolGetHubLogs([since: '2024-01-15T10:30:00'])

        then: 'entry at 10:30:01 UTC is included; entry at 10:29:59 UTC is excluded'
        result.logs.size() == 1
        result.logs[0].message == 'After cutoff'
    }

    def "since as space-separated timestamp (hub log copy-paste shape, no TZ marker) is parsed as UTC -- JVM TZ swap"() {
        given:
        settingsMap.enableHubAdminRead = true
        // This scenario deliberately swaps the JVM default TZ to America/Denver (UTC-6 or -7
        // depending on DST) before making the call. If production code regresses to Date.parse()
        // for the space-separated format, the epoch shifts by 6-7 hours, causing the inside-window
        // entry to appear falsely outside the window. The fix (explicit UTC SimpleDateFormat)
        // must survive a non-UTC JVM default TZ.
        // UTC epoch for 2024-01-15T12:30:45.123Z = 1705319445123L (UTC).
        // An entry 1s inside: 2024-01-15 12:30:46.000 UTC -> included.
        // An entry 1s before: 2024-01-15 12:30:44.000 UTC -> excluded.
        registerLogs([
            'App 1\tinfo\tBefore cutoff\t2024-01-15 12:30:44.000\ttype',
            'App 1\tinfo\tAfter cutoff\t2024-01-15 12:30:46.000\ttype'
        ])
        def origDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))

        when: 'space-separated since (no T, no Z) is parsed as UTC despite non-UTC JVM default TZ'
        def result
        try {
            result = script.toolGetHubLogs([since: '2024-01-15 12:30:45.123'])
        } finally {
            TimeZone.setDefault(origDefault)
        }

        then: 'entry at 12:30:46 UTC is included; entry at 12:30:44 UTC is excluded -- UTC parse is TZ-default-independent'
        result.logs.size() == 1
        result.logs[0].message == 'After cutoff'
    }

    def "TZ-swap asymmetric regression: space-separated UTC entry and explicit-Z entry both resolve to same epoch ms"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Two entries that represent identical points in time, expressed two ways:
        //   (a) space-separated hub-native: '2024-01-15 12:30:44.000' (no TZ marker; production
        //       must parse as UTC via hubLogSdfs)
        //   (b) explicit-Z ISO-8601: '2024-01-15T12:30:44Z' (carries its own TZ; Date.parse handles)
        // Both are 1s BEFORE the since cutoff (2024-01-15T12:30:45.000Z) => both excluded.
        // If production regresses the space-separated parser only (reverts to JVM-default TZ),
        // entry (a) shifts by the JVM offset and may land on the wrong side of the window.
        // Symmetry failure: one entry excluded, the other not => test fails.
        registerLogs([
            'App 1\tinfo\tSpace-sep before\t2024-01-15 12:30:44.000\ttype',
            'App 1\tinfo\tISO-Z before\t2024-01-15T12:30:44Z\ttype',
            'App 1\tinfo\tSpace-sep after\t2024-01-15 12:30:46.000\ttype',
            'App 1\tinfo\tISO-Z after\t2024-01-15T12:30:46Z\ttype'
        ])
        def origDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))

        when: 'since cutoff is between the before and after pairs'
        def result
        try {
            result = script.toolGetHubLogs([since: '2024-01-15T12:30:45.000Z'])
        } finally {
            TimeZone.setDefault(origDefault)
        }

        then: 'both "after" entries are included; both "before" entries are excluded regardless of format'
        result.logs.size() == 2
        result.logs*.message.sort() == ['ISO-Z after', 'Space-sep after'].sort()
    }

    // ==================== B3: timeFilterUnparseable counter ====================

    def "unparseable timestamps are passed through and counted in timeFilterUnparseable"() {
        given:
        settingsMap.enableHubAdminRead = true
        // 5 valid entries after the cutoff + 2 entries with unparseable timestamps.
        // The 2 unparseable entries should be kept (not excluded) and counted separately.
        registerLogs([
            'App 1\tinfo\tValid 1\t2024-01-15 01:00:00.000\ttype',
            'App 1\tinfo\tValid 2\t2024-01-15 02:00:00.000\ttype',
            'App 1\tinfo\tValid 3\t2024-01-15 03:00:00.000\ttype',
            'App 1\tinfo\tValid 4\t2024-01-15 04:00:00.000\ttype',
            'App 1\tinfo\tValid 5\t2024-01-15 05:00:00.000\ttype',
            'App 1\tinfo\tNo timestamp\t\ttype',
            'App 1\tinfo\tBad timestamp\tNOT_A_DATE\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([since: '2024-01-15T00:00:00Z'])

        then: '5 valid + 2 unparseable = 7 total entries returned'
        result.logs.size() == 7
        result.timeFilterUnparseable == 2
    }

    def "timeFilterUnparseable is absent from response when no time filter is active"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tNo timestamp\t\ttype',
            'App 1\tinfo\tBad timestamp\tNOT_A_DATE\ttype'
        ])

        when: 'no since/until -- time-window filter is not active'
        def result = script.toolGetHubLogs([:])

        then: 'unparseable timestamps not counted because time-window is off'
        !result.containsKey('timeFilterUnparseable')
    }

    // ==================== I4: filteredOut and appliedFilters ====================

    def "zero-match result includes filteredOut count and appliedFilters echo"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tSomething normal\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tAnother normal\t2026-04-19 10:00:01.000\ttype',
            'App 1\tinfo\tYet another\t2026-04-19 10:00:02.000\ttype'
        ])

        when: 'pattern matches nothing'
        def result = script.toolGetHubLogs([pattern: 'DEFINITELY_NOT_PRESENT'])

        then: 'count is 0 but filteredOut and appliedFilters reveal what happened'
        result.count == 0
        result.filteredOut == 3
        result.appliedFilters != null
        result.appliedFilters.pattern == 'DEFINITELY_NOT_PRESENT'
    }

    def "partial-match result includes filteredOut count reflecting excluded entries"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tfound: match this\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tunrelated entry here\t2026-04-19 10:00:01.000\ttype',
            'App 1\tinfo\talso found: match\t2026-04-19 10:00:02.000\ttype'
        ])

        when:
        def result = script.toolGetHubLogs([pattern: 'found'])

        then: '2 matched "found", 1 filtered out; filteredOut = 1'
        result.count == 2
        result.filteredOut == 1
        result.appliedFilters.pattern == 'found'
    }

    def "filteredOut counts only filter-excluded entries not limit-truncated ones"() {
        given:
        settingsMap.enableHubAdminRead = true
        // 100 entries: 50 contain 'ALPHA' (match), 50 contain 'BETA' (no match). limit=10.
        // filteredOut must be 50 (the BETA entries excluded by filter), NOT 90 (totalParsed - count).
        // The 40 ALPHA entries beyond the limit are limit-truncated, not filter-excluded.
        def lines = (1..50).collect { "App 1\tinfo\tALPHA entry ${it}\t2026-04-19 10:00:00.000\ttype" } +
                    (1..50).collect { "App 1\tinfo\tBETA entry ${it}\t2026-04-19 10:00:00.000\ttype" }
        registerLogs(lines)

        when:
        def result = script.toolGetHubLogs([pattern: 'ALPHA', limit: 10])

        then: 'count = 10 (limit hit), filteredOut = 50 (filter-excluded), totalParsed = 100'
        result.count == 10
        result.totalParsed == 100
        result.filteredOut == 50
        // limit-truncated entries (the other 40 ALPHA matches) are NOT counted in filteredOut
    }

    def "no filters active means filteredOut and appliedFilters are absent"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tSomething\t2026-04-19 10:00:00.000\ttype'
        ])

        when: 'no filter args'
        def result = script.toolGetHubLogs([:])

        then: 'metadata fields absent when no filters were applied'
        !result.containsKey('filteredOut')
        !result.containsKey('appliedFilters')
    }

    // ==================== I5: empty pattern / empty patterns element ====================

    def "empty pattern string throws IllegalArgumentException with hint to omit"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([pattern: ''])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('pattern')
        ex.message.toLowerCase().contains('empty')
    }

    def "empty string inside patterns list throws IllegalArgumentException naming the index"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([patterns: ['valid', '']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('patterns[1]')
        ex.message.toLowerCase().contains('empty')
    }

    // ==================== I6: pattern length limit ====================

    def "pattern longer than 100 chars throws before compile with length in message"() {
        given:
        settingsMap.enableHubAdminRead = true
        def longPat = 'a' * 101   // 101 chars -- exceeds 100-char limit

        when:
        script.toolGetHubLogs([pattern: longPat])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('101')
        ex.message.toLowerCase().contains('100')
    }

    def "pattern element in patterns list longer than 100 chars throws naming the index"() {
        given:
        settingsMap.enableHubAdminRead = true
        def longPat = 'b' * 101   // 101 chars

        when:
        script.toolGetHubLogs([patterns: ['ok', longPat]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('patterns[1]')
        ex.message.contains('101')
    }

    // ==================== I7: hubInternalGet failure becomes IllegalStateException ====================

    def "hubInternalGet failure throws IllegalStateException surfaceable as JSON-RPC error"() {
        given:
        settingsMap.enableHubAdminRead = true
        // Intentionally no hubGet.register() -- harness throws IllegalStateException("Unstubbed
        // hubInternalGet: ...") when the path is not registered, which production code then
        // wraps and rethrows as IllegalStateException("Failed to fetch hub logs: ...").
        // This verifies the rethrow path reaches the caller rather than returning a success-shape map.

        when:
        script.toolGetHubLogs([:])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.toLowerCase().contains('failed to fetch hub logs')
    }

    @spock.lang.Unroll
    def "get_hub_logs via dispatch wraps IllegalStateException as isError=true envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        // No hubGet.register() -- the unstubbed path throws and production rethrows as
        // IllegalStateException. handleToolsCall maps non-IAE exceptions to isError=true.

        when:
        def response = mcpDriver.callTool('get_hub_logs', [:])

        then:
        response.error == null
        response.result.isError == true
        response.result.content[0].text.toLowerCase().contains('failed to fetch hub logs')

        where:
        useGateways << [true, false]
    }

    // ==================== I8: additional validation scenarios ====================

    def "multi-pattern compile failure throws on the bad element and names its index"() {
        given:
        settingsMap.enableHubAdminRead = true

        when: "patterns list contains one valid and one invalid regex"
        script.toolGetHubLogs([patterns: ['valid', '[unclosed']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('patterns[1]')
        ex.message.toLowerCase().contains('invalid regex')
    }

    def "inverted ISO-8601 window since after until throws IllegalArgumentException"() {
        given:
        settingsMap.enableHubAdminRead = true

        when: 'since is after until -- window contains no entries'
        script.toolGetHubLogs([since: '2024-01-15T12:00:00Z', until: '2024-01-15T08:00:00Z'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('window is empty')
    }

    def "until as Number throws IllegalArgumentException with format hint"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([until: 1234567890000])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('until')
        ex.message.toLowerCase().contains('string')
    }

    def "patternMode as List throws IllegalArgumentException naming the expected type"() {
        given:
        settingsMap.enableHubAdminRead = true

        when: 'patternMode given as a list instead of a string'
        script.toolGetHubLogs([patternMode: ['any']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('patternmode')
    }

    def "empty patterns list is accepted and applies no pattern filter"() {
        given:
        settingsMap.enableHubAdminRead = true
        registerLogs([
            'App 1\tinfo\tfoo\t2026-04-19 10:00:00.000\ttype',
            'App 1\tinfo\tbar\t2026-04-19 10:00:01.000\ttype'
        ])

        when: 'patterns=[] is a no-op -- all entries pass through'
        def result = script.toolGetHubLogs([patterns: []])

        then: 'all entries returned; empty patterns list treated as no-filter'
        result.logs.size() == 2
        // empty patterns list means no filter was applied -- appliedFilters omits patterns key
        !result.containsKey('appliedFilters') || !result.appliedFilters?.containsKey('patterns')
    }
}
