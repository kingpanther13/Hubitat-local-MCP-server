package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.TestDevice
import support.TestLocation
import support.ToolSpecBase

/**
 * Spec for the hub_manage_logs gateway tools (libraries/mcp-diagnostics-lib.groovy, mcp-debug-logging-lib.groovy):
 *
 * - toolGetHubLogs         -> hub_get_logs          (additional coverage beyond ToolGetHubLogsSpec)
 * - toolGetHubLogs         -> hub_get_logs (Logs page tabs: events, stats, jobs, hub actions)
 * - toolGetDeviceHistory   -> hub_list_device_events (core tool; windowed device and app branches)
 * - toolGetDebugLogs       -> hub_get_logs(mode:'mcp')
 * - toolDeleteLogs         -> hub_delete_logs
 * - toolSetLogLevel        -> hub_set_log_level
 * - toolGetLoggingStatus   -> hub_get_logs(mode:'status')
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet     -> HarnessSpec's hubGet.register(path) closures
 *   - device events     -> native fullJson/eventsJson HTTP fixtures with SDK traps
 *   - app.updateSetting  -> the @Shared appExecutor Mock has getApp() stubbed
 *                           once in setupSpec to return sharedAppStub (a
 *                           TestChildApp). HubitatAppScript's @Delegate to
 *                           AppExecutor resolves script.app through this stub
 *                           rather than returning null and NPE'ing when the
 *                           server calls app.updateSetting(...). cleanup()
 *                           clears the settingsStore between tests.
 *   - MCP debug logs     -> seedDebugLogHistory populates the in-memory history
 *
 * Additional hub_get_logs coverage — this spec complements ToolGetHubLogsSpec
 * with level/source/limit/empty-response/deviceId/appId/truncation cases.
 */
class ToolManageLogsSpec extends ToolSpecBase {

    private void nativeDeviceEvents(TestDevice device, Closure rows) {
        hubGet.register("/device/fullJson/${device.id}") {
            JsonOutput.toJson([device: [id: device.id, name: device.name, label: device.label, currentStates: [:]]])
        }
        hubGet.register("/device/eventsJson/${device.id}") { Map params ->
            assert params == [:]
            JsonOutput.toJson(rows().collect { row ->
                row + [date: row.date instanceof Date ? row.date.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ") : row.date]
            })
        }
        device.metaClass.eventsSince = { Date since, Map opts -> throw new AssertionError('SDK event history is forbidden') }
        device.metaClass.events = { Map opts -> throw new AssertionError('SDK recent events are forbidden') }
    }

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        // HarnessSpec.setupSpec runs first (Spock auto-invokes superclass
        // fixtures in declaration order). Layering getApp() onto the
        // already-built mock here gives toolSetLogLevel's app.updateSetting
        // call a non-null target. Same additive-stub pattern that
        // ToolRoomsSpec uses for appExecutor.httpPost(_, _).
        appExecutor.getApp() >> sharedAppStub
        // Keep location context available to the non-device monitoring tools.
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanup() {
        sharedAppStub.settingsStore.clear()
    }

    // -------- toolGetHubLogs (additional coverage) --------

    def "hub_get_logs filters by level=#level"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson([
                'App 1\tdebug\tDebug msg\t2026-04-19 10:00:00.000\ttype',
                'App 1\tinfo\tInfo msg\t2026-04-19 10:00:01.000\ttype',
                'App 1\twarn\tWarn msg\t2026-04-19 10:00:02.000\ttype',
                'App 1\terror\tError msg\t2026-04-19 10:00:03.000\ttype'
            ])
        }

        when:
        def result = script.toolGetHubLogs([level: level])

        then:
        result.entries.size() == 1
        result.entries[0].level == level
        result.entries[0].message == expectedMsg

        where:
        level   | expectedMsg
        'debug' | 'Debug msg'
        'info'  | 'Info msg'
        'warn'  | 'Warn msg'
        'error' | 'Error msg'
    }

    @spock.lang.Unroll
    def "hub_get_logs via dispatch filters by level=warn (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson([
                'App 1\tdebug\tDebug msg\t2026-04-19 10:00:00.000\ttype',
                'App 1\tinfo\tInfo msg\t2026-04-19 10:00:01.000\ttype',
                'App 1\twarn\tWarn msg\t2026-04-19 10:00:02.000\ttype',
                'App 1\terror\tError msg\t2026-04-19 10:00:03.000\ttype'
            ])
        }

        when:
        def response = mcpDriver.callTool('hub_get_logs', [level: 'warn'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.entries.size() == 1
        inner.entries[0].level == 'warn'
        inner.entries[0].message == 'Warn msg'

        where:
        useGateways << [true, false]
    }

    def "hub_get_logs pattern searches the source name and the message (case-insensitive)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson([
                'Kitchen Light\tinfo\tSwitch turned on\t2026-04-19 10:00:00.000\ttype',
                'Bedroom Fan\tinfo\tSpeed set\t2026-04-19 10:00:01.000\ttype',
                'Kitchen Motion\tinfo\tMotion detected\t2026-04-19 10:00:02.000\ttype'
            ])
        }

        when:
        def result = script.toolGetHubLogs([pattern: 'kitchen'])

        then: 'case-insensitive match against name or message'
        result.entries.size() == 2
        result.entries.every { it.name.toLowerCase().contains('kitchen') }
    }

    def "hub_get_logs trims to the limit argument (keeping newest)"() {
        given:
        settingsMap.enableRead = true
        def lines = (1..10).collect { i ->
            "App 1\tinfo\tMessage ${i}\t2026-04-19 10:00:0${i}.000\ttype".toString()
        }
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(lines) }

        when:
        def result = script.toolGetHubLogs([limit: 3])

        then:
        result.entries.size() == 3
        result.count == 3
        result.entries[0].message == 'Message 10'
        result.entries[1].message == 'Message 9'
        result.entries[2].message == 'Message 8'
    }

    def "hub_get_logs scopes to a selected device and passes type=dev&id=X in query"() {
        given: 'selected ownership and matching native identity'
        settingsMap.enableRead = true
        def device = new TestDevice(id: 42, name: 'K', label: 'K')
        settingsMap.selectedDevices = [device]
        hubGet.register('/device/fullJson/42') { JsonOutput.toJson([device: [id: 42, name: 'K']]) }
        def capturedParams = null
        hubGet.register('/logs/past/json') { params ->
            capturedParams = params
            JsonOutput.toJson(['K\tinfo\thi\t2026-04-19 10:00:00.000\ttype'])
        }

        when:
        def result = script.toolGetHubLogs([deviceId: '42'])

        then:
        capturedParams == [type: 'dev', id: '42']
        result.entries.size() == 1
    }

    def "hub_get_logs rejects a malformed deviceId before hitting the hub"() {
        given:
        settingsMap.enableRead = true
        // If the tool ever called hubInternalGet, HubInternalGetMock would throw
        // (unstubbed), so the IllegalArgumentException below proves the pre-HTTP
        // validation fired.

        when:
        script.toolGetHubLogs([deviceId: '../999'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceId')
        hubGet.calls.empty
    }

    @spock.lang.Unroll
    def "hub_get_logs via dispatch maps malformed-deviceId IAE to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_get_logs', [deviceId: '../999'])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('deviceId')
        hubGet.calls.empty

        where:
        useGateways << [true, false]
    }

    def "hub_get_logs scopes to an app and passes type=app&id=X in query"() {
        given:
        settingsMap.enableRead = true
        def capturedParams = null
        hubGet.register('/logs/past/json') { params ->
            capturedParams = params
            JsonOutput.toJson(['App 7\tinfo\thi\t2026-04-19 10:00:00.000\ttype'])
        }

        when:
        def result = script.toolGetHubLogs([appId: '7'])

        then:
        capturedParams == [type: 'app', id: '7']
        result.entries.size() == 1
    }

    def "hub_get_logs truncates messages when estimated JSON exceeds the 120KB cloud limit"() {
        given: 'many entries with long messages so estimatedJsonSize trips the 120000-byte truncation guard'
        settingsMap.enableRead = true
        def longMsg = 'x' * 2000
        def lines = (1..200).collect { i ->
            "App 1\tinfo\t${longMsg}\t2026-04-19 10:00:${String.format('%02d', (i % 60))}.000\ttype".toString()
        }
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(lines) }

        when: 'limit=200 means ~200 entries × (2000 message + overhead) > 120000'
        def result = script.toolGetHubLogs([limit: 200])

        then:
        result.truncated == true
        result.note.contains('truncated')
        result.entries.every { it.message.length() <= 200 }
    }

    def "hub_get_logs handles empty hub response gracefully"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/logs/past/json') { params -> null }

        when:
        def result = script.toolGetHubLogs([:])

        then:
        result.entries == []
        result.count == 0
        result.total == 0
    }

    // -------- toolGetDeviceHistory --------

    // -------- hub_get_logs: location and hub events --------

    private static final String LOCATION_ROWS = '''[
      {"name":"mode","value":"Night","unit":null,"descriptionText":"Mode changed to Night","isStateChange":true,"type":"API","date":"2009-02-13T22:30:00.000+0000"},
      {"name":"hsmStatus","value":"armedAway","unit":null,"descriptionText":"HSM armed away","isStateChange":true,"type":"API","date":"2009-02-13T22:00:00.000+0000"},
      {"name":"variable:guestCount","value":"3","unit":null,"descriptionText":"hub variable updated","isStateChange":true,"type":"HUB_VARIABLE_SET","date":"2009-02-13T21:00:00.000+0000"}
    ]'''

    def "hub_get_logs locationEvents returns the Logs page's rows with the hub's own field names"() {
        given:
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }

        when:
        def result = script.toolGetHubLogs([mode: 'locationEvents'])

        then: 'it reads the endpoint the Logs page uses, once'
        hubGet.calls*.path == ['/logs/eventsJson']

        and: 'rows are the native rows, in the hub\'s order'
        result.mode == 'locationEvents'
        result.total == 3
        result.matched == 3
        result.count == 3
        result.entries*.name == ['mode', 'hsmStatus', 'variable:guestCount']
        result.entries[0] == [name: 'mode', value: 'Night', descriptionText: 'Mode changed to Night',
                              isStateChange: true, type: 'API', date: '2009-02-13T22:30:00.000+0000']

        and: 'the null unit is left out of each row and named once'
        result.nullFields == ['unit']
        result.entries.every { !it.containsKey('unit') }
    }

    def "hub_get_logs names a field in nullFields when it is null on some rows and keeps it where it is set"() {
        given:
        hubGet.register('/logs/eventsJson') { params ->
            '[{"name":"temp","value":"70","unit":"F","deviceId":null},{"name":"mode","value":"Day","unit":null,"deviceId":null}]'
        }

        when:
        def result = script.toolGetHubLogs([mode: 'locationEvents'])

        then:
        result.nullFields == ['deviceId', 'unit']
        result.entries == [[name: 'temp', value: '70', unit: 'F'], [name: 'mode', value: 'Day']]
    }

    def "hub_get_logs leaves nullFields out when no row has a null field"() {
        given:
        hubGet.register('/hub/eventsJson') { params -> '[{"name":"systemStart","value":"","date":"2009-02-13T22:15:00.000+0000"}]' }

        when:
        def result = script.toolGetHubLogs([mode: 'hubEvents'])

        then:
        !result.containsKey('nullFields')
        result.entries == [[name: 'systemStart', value: '', date: '2009-02-13T22:15:00.000+0000']]
    }

    def "hub_get_logs with a list of modes answers every tab under its own key in one call"() {
        given:
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }
        hubGet.register('/hub/eventsJson') { params -> '[{"name":"lowMemory","value":"","date":"2009-02-13T22:15:00.000+0000"}]' }

        when:
        def result = script.toolGetHubLogs([mode: ['locationEvents', 'hubEvents'], since: '2009-02-13T21:30:00.000+0000'])

        then: 'each tab is read once and filtered by the shared arguments'
        hubGet.calls*.path == ['/logs/eventsJson', '/hub/eventsJson']
        result.modes.keySet() as List == ['locationEvents', 'hubEvents']
        result.modes.locationEvents.entries*.name == ['mode', 'hsmStatus']
        result.modes.hubEvents.entries*.name == ['lowMemory']
        !result.modes.locationEvents.containsKey('mode')
        !result.containsKey('failedModes')
    }

    def "hub_get_logs with a list of job modes serves all three from one /logs/json fetch"() {
        given:
        def fetches = new java.util.concurrent.atomic.AtomicInteger(0)
        hubGet.register('/logs/json') { params -> fetches.incrementAndGet(); logsJsonWithJobs(2) }

        when:
        def result = script.toolGetHubLogs([mode: ['scheduledJobs', 'runningJobs', 'hubActions'], deviceId: '900'])

        then:
        fetches.get() == 1
        result.modes.keySet() as List == ['scheduledJobs', 'runningJobs', 'hubActions']
        result.modes.runningJobs.entries*.owner == [[type: 'device', id: 900]]
        result.modes.hubActions.entries*.owner == [[type: 'device', id: 900]]
        result.modes.scheduledJobs.matched == 0

        and: 'the shared fetch is stated once'
        result.uptime == '1d'
        result.snapshot.fetchedAt instanceof Number
        result.modes.values().every { !it.containsKey('uptime') && !it.containsKey('snapshot') }
    }

    def "hub_get_logs with a list of modes keeps the other tabs when one fails"() {
        given:
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }
        hubGet.register('/hub/eventsJson') { params -> '{"a":1}' }

        when:
        def result = script.toolGetHubLogs([mode: ['locationEvents', 'hubEvents']])

        then:
        result.failedModes == ['hubEvents']
        result.modes.hubEvents.isError == true
        result.modes.hubEvents.error.contains('unexpected shape')
        result.modes.locationEvents.count == 3
    }

    def "hub_get_logs with a list of modes returns each tab's first page with its own cursor"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(150) }
        hubGet.register('/hub/eventsJson') { params -> '[]' }

        when:
        def result = script.toolGetHubLogs([mode: ['scheduledJobs', 'hubEvents']])
        def sized = script.toolGetHubLogs([mode: ['scheduledJobs', 'hubEvents'], pageSize: 70])

        then: '50 rows per tab by default'
        result.modes.scheduledJobs.count == 50
        result.modes.scheduledJobs.matched == 150
        result.modes.scheduledJobs.nextCursor == '50'
        result.modes.hubEvents.count == 0
        !result.modes.hubEvents.containsKey('nextCursor')

        and: 'pageSize sets it'
        sized.modes.scheduledJobs.count == 70
        sized.modes.scheduledJobs.nextCursor == '70'
    }

    def "hub_get_logs pageSize pages a single tab, alone or with a cursor"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(150) }

        when:
        def first = script.toolGetHubLogs([mode: 'scheduledJobs', pageSize: 40])
        def next = script.toolGetHubLogs([mode: 'scheduledJobs', pageSize: 40, cursor: first.nextCursor])
        def whole = script.toolGetHubLogs([mode: 'scheduledJobs'])

        then:
        first.entries*.id == (1..40).collect { "app${it}Recur.run".toString() }
        first.nextCursor == '40'
        next.entries*.id == (41..80).collect { "app${it}Recur.run".toString() }
        next.nextCursor == '80'
        whole.count == 150
        !whole.containsKey('nextCursor')
    }

    def "hub_get_logs with a list of modes validates every tab before reading any"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(1) }

        when: 'the MCP log comes first, so only the up-front check can catch the bad pageSize for the jobs tab'
        script.toolGetHubLogs([mode: ['mcp', 'scheduledJobs'], pageSize: 500])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('pageSize must be a whole number from 1 to 100')
        hubGet.calls.empty
    }

    def "hub_get_logs with a list of modes is an error when every tab fails, and keeps the other tabs when one does"() {
        given:
        hubGet.register('/logs/past/json') { params -> throw new RuntimeException('store down') }
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }
        hubGet.register('/hub/eventsJson') { params -> '{"a":1}' }

        when:
        def one = script.toolGetHubLogs([mode: ['past', 'locationEvents']])
        def all = script.toolGetHubLogs([mode: ['past', 'hubEvents']])

        then:
        one.failedModes == ['past']
        !one.containsKey('isError')
        one.modes.past.note.contains("hub_get_logs(mode='past')")
        one.modes.locationEvents.count == 3
        all.isError == true
        all.success == false
        all.error.contains('Every listed tab failed')
    }

    def "hub_get_logs with past and mcp listed returns both, each as its first page"() {
        given:
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(['2026-09-06 12:00:00.000\tINFO\tapp|1|A|hello']) }

        when:
        def result = script.toolGetHubLogs([mode: ['past', 'mcp']])

        then:
        result.modes.keySet() as List == ['past', 'mcp']
        result.modes.past.count == 1
        result.modes.mcp.entries instanceof List
    }

    def "hub_get_logs with a list of modes takes pageSize for every tab, the MCP log as its limit"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(3) }
        3.times { script.mcpLog('error', 'server', "entry ${it}") }

        when:
        def result = script.toolGetHubLogs([mode: ['scheduledJobs', 'mcp', 'status'], pageSize: 2])

        then:
        !result.containsKey('isError')
        result.modes.keySet() as List == ['scheduledJobs', 'mcp', 'status']
        result.modes.scheduledJobs.count == 2
        result.modes.scheduledJobs.nextCursor != null
        result.modes.mcp.entries.size() == 2
        !result.modes.status.containsKey('isError')
    }

    @Unroll
    def "hub_get_logs with a list of modes refuses #args before reading anything"() {
        when:
        script.toolGetHubLogs(args)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(says)
        hubGet.calls.empty

        where:
        args                                                              || says
        [mode: ['locationEvents', 'scheduledJobs'], deviceId: '5']        || "do not apply to log mode 'locationEvents'"
        [mode: ['hubEvents', 'locationEvents'], cursor: '']               || 'cursor pages one mode'
        [mode: ['hubEvents', 'nope']]                                     || 'Invalid log mode: nope'
        [mode: []]                                                        || 'at least one tab'
        [mode: ['mcp', 'status'], pageSize: 0]                            || 'pageSize must be a whole number from 1 to 100'
        [mode: ['mcp', 'status'], pageSize: 101]                          || 'pageSize must be a whole number from 1 to 100'
        [mode: ['mcp', 'status'], pageSize: '20']                         || 'pageSize must be a whole number from 1 to 100'
        [mode: 'mcp', limit: -1]                                          || 'limit must be a whole number'
        [mode: 'mcp', limit: 'abc']                                       || 'limit must be a whole number'
        [mode: 'mcp', limit: 4294967301L]                                 || 'limit must be a whole number'
        [mode: 'mcp', level: 'bogus']                                     || 'level must be one of'
    }

    @Unroll
    def "hub_get_logs via dispatch takes a list of modes (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }
        hubGet.register('/hub/eventsJson') { params -> '[]' }

        when:
        def inner = useGateways ?
            mcpDriver.parseInner(mcpDriver.callTool('hub_read_diagnostics', [tool: 'hub_get_logs', args: [mode: ['locationEvents', 'hubEvents']]])) :
            mcpDriver.parseInner(mcpDriver.callTool('hub_get_logs', [mode: ['locationEvents', 'hubEvents']]))

        then:
        inner.modes.locationEvents.count == 3
        inner.modes.hubEvents.count == 0

        where:
        useGateways << [false, true]
    }

    def "hub_get_logs hubEvents reads the Hub events tab"() {
        given:
        hubGet.register('/hub/eventsJson') { params ->
            '[{"name":"lowMemory","value":"","descriptionText":"Hub memory is critically low, rebooting now","date":"2009-02-13T22:15:00.000+0000"}]'
        }

        when:
        def result = script.toolGetHubLogs([mode: 'hubEvents', pattern: 'lowMemory'])

        then:
        hubGet.calls*.path == ['/hub/eventsJson']
        result.count == 1
        result.entries[0].descriptionText.contains('rebooting now')
    }

    @Unroll
    def "hub_get_logs locationEvents pattern #pattern searches name, descriptionText and value"() {
        given:
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }

        when:
        def result = script.toolGetHubLogs([mode: 'locationEvents', pattern: pattern])

        then:
        result.entries*.name == names
        result.matched == names.size()
        result.total == 3

        where:
        pattern        || names
        '^mode$'       || ['mode']
        'armed'        || ['hsmStatus']
        '^3$'          || ['variable:guestCount']
        'HUB VARIABLE' || ['variable:guestCount']
    }

    def "hub_get_logs event since excludes the bookmarked instant and until bounds the window"() {
        given:
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }

        when: 'since is the hsmStatus row\'s own date'
        def after = script.toolGetHubLogs([mode: 'locationEvents', since: '2009-02-13T22:00:00.000+0000'])
        def slice = script.toolGetHubLogs([mode: 'locationEvents', since: '2009-02-13T20:00:00.000+0000', until: '2009-02-13T22:00:00.000+0000'])
        def relative = script.toolGetHubLogs([mode: 'locationEvents', since: '2h'])

        then:
        after.entries*.name == ['mode']
        slice.entries*.name == ['hsmStatus', 'variable:guestCount']
        relative.entries*.name == ['mode', 'hsmStatus']
    }

    def "hub_get_logs event rows whose date cannot be read stay and are counted"() {
        given:
        hubGet.register('/logs/eventsJson') { params ->
            '''[
              {"name":"mode","value":"Night","descriptionText":"in window","date":"2009-02-13T22:00:00.000+0000"},
              {"name":"mode","value":"Day","descriptionText":"no date"}
            ]'''
        }

        when:
        def result = script.toolGetHubLogs([mode: 'locationEvents', since: '6h'])

        then:
        result.count == 2
        result.timeFilterUnparseable == 1
    }

    def "hub_get_logs event limit keeps the first rows and cursor pages them"() {
        given:
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }

        when:
        def limited = script.toolGetHubLogs([mode: 'locationEvents', limit: 2])
        def firstPage = script.toolGetHubLogs([mode: 'locationEvents', cursor: ''])

        then:
        limited.entries*.name == ['mode', 'hsmStatus']
        limited.matched == 3
        firstPage.count == 3
        !firstPage.containsKey('nextCursor')
    }

    def "hub_get_logs event cursor neither repeats nor skips a row when an event arrives between pages"() {
        given: 'the hub lists one more event, newest first, by the time the second page is read'
        String newer = '{"name":"mode","value":"Day","descriptionText":"Mode changed to Day","date":"2009-02-13T23:00:00.000+0000"},'
        def reads = 0
        hubGet.register('/logs/eventsJson') { params -> reads++ == 0 ? LOCATION_ROWS : LOCATION_ROWS.replaceFirst(/\[/, '[' + newer) }

        when:
        def first = script.toolGetHubLogs([mode: 'locationEvents', pageSize: 2])
        def second = script.toolGetHubLogs([mode: 'locationEvents', pageSize: 2, cursor: first.nextCursor])
        def fresh = script.toolGetHubLogs([mode: 'locationEvents', pageSize: 2])

        then: 'the second page holds the row after the first page, not the first page\'s last row again'
        first.entries*.name == ['mode', 'hsmStatus']
        second.entries*.name == ['variable:guestCount']
        second.total == 4
        !second.containsKey('nextCursor')

        and: 'a new first page starts from the newest event'
        fresh.entries*.value == ['Day', 'Night']
    }

    @Unroll
    def "hub_get_logs #mode reports an unreadable endpoint as an error result"() {
        given:
        hubGet.register(path) { params -> body() }

        when:
        def result = script.toolGetHubLogs([mode: mode])

        then:
        result.success == false
        result.isError == true
        result.error.contains(says)

        where:
        mode             | path                    | body                                         || says
        'locationEvents' | '/logs/eventsJson'      | { throw new RuntimeException('store down') } || 'store down'
        'hubEvents'      | '/hub/eventsJson'       | { '{"a":1}' }                                || 'unexpected shape'
        'cloudCalls'     | '/logs/cloudCalls/json' | { '[1]' }                                    || 'unexpected shape'
    }

    @Unroll
    def "hub_get_logs refuses #args for mode #mode before reading anything"() {
        when:
        script.toolGetHubLogs([mode: mode] + args)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(says)
        hubGet.calls.empty

        where:
        mode             | args                            || says
        'locationEvents' | [deviceId: '5']                 || 'do not apply'
        'hubEvents'      | [level: 'warn']                 || 'do not apply'
        'devices'        | [since: '1h']                   || 'do not apply'
        'scheduledJobs'  | [sortBy: 'pct']                 || 'do not apply'
        'status'         | [limit: 5]                      || 'do not apply'
        'devices'        | [sortBy: 'totalMs']             || 'sortBy must be one of'
        'past'           | [since: '31d']                  || '30d cap'
        'past'           | [since: '43201m']               || '30d cap'
        'locationEvents' | [since: '999999999999999d']     || '30d cap'
        'past'           | [until: '9999999999999999999m'] || '30d cap'
        'past'           | [since: 'yesterday']            || 'Cannot parse since'
        'past'           | [since: '1h', until: '2h']      || 'until must be later than since'
        'past'           | [deviceId: '5', appId: '6']     || 'mutually exclusive'
        'scheduledJobs'  | [appId: 'x']                    || 'appId must be numeric'
        'past'           | [patterns: ['ok', '(']]         || 'Invalid regex patterns[1]'
        'nope'           | [:]                             || 'Invalid log mode'
        'scheduledJobs'  | [pageSize: 0]                   || 'pageSize must be a whole number from 1 to 100'
        'scheduledJobs'  | [pageSize: 101]                 || 'pageSize must be a whole number from 1 to 100'
        'scheduledJobs'  | [pageSize: '20']                || 'pageSize must be a whole number from 1 to 100'
        'scheduledJobs'  | [pageSize: 20.5]                || 'pageSize must be a whole number from 1 to 100'
        'mcp'            | [pageSize: 20]                  || 'do not apply'
        'past'           | [source: 'Lock']                || 'Unknown parameter for hub_get_logs: source'
        'devices'        | [type: 'app', includeCloudCalls: true] || 'Unknown parameters for hub_get_logs: type, includeCloudCalls'
        'past'           | [limit: 'abc']                  || 'limit must be a whole number'
        'past'           | [limit: -1]                     || 'limit must be a whole number'
        'scheduledJobs'  | [pageSize: 4294967301L]         || 'pageSize must be a whole number from 1 to 100'
        'past'           | [since: '2026-02-30 10:00:00']  || 'no such date and time'
        'past'           | [since: '2026-13-01T00:00:00Z'] || 'no such date and time'
        'past'           | [since: '30']                   || "Cannot read since='30' as a time"
        'past'           | [until: 30]                     || "Cannot read until='30' as a time"
    }

    def "hub_list_device_events returns location events when deviceId is omitted (reads /logs/eventsJson)"() {
        given: '/logs/eventsJson stubbed with mode + HSM + hub-variable rows (real wire shape)'
        hubGet.register('/logs/eventsJson') { params ->
            '''[
              {"name":"mode","value":"Night","unit":null,"descriptionText":"Mode changed to Night","isStateChange":true,"type":"API","date":"2026-06-02T20:19:00.073-0400"},
              {"name":"hsmStatus","value":"armedAway","unit":null,"descriptionText":"HSM armed away","isStateChange":true,"type":"API","date":"2026-06-02T20:18:00.000-0400"},
              {"name":"variable:guestCount","value":"3","unit":null,"descriptionText":"hub variable updated","isStateChange":true,"type":"HUB_VARIABLE_SET","date":"2026-06-02T20:17:00.000-0400"}
            ]'''
        }

        when:
        def result = script.toolGetDeviceHistory([hoursBack: 6, limit: 25])

        then: 'it reads the same endpoint the hub Logs page uses'
        hubGet.calls.any { it.path == '/logs/eventsJson' }

        and:
        result.source == 'location'
        !result.containsKey('device')
        !result.containsKey('deviceId')
        result.hoursBack == 6
        result.count == 3
        result.events*.name == ['mode', 'hsmStatus', 'variable:guestCount']
        result.events[0].value == 'Night'
        result.events[0].description == 'Mode changed to Night'
    }

    def "hub_list_device_events location mode merges the hub events newest first and tags each row's kind"() {
        given: 'a reboot the Logs page lists under Hub events, between two location events'
        hubGet.register('/logs/eventsJson') { params ->
            '''[
              {"name":"mode","value":"Night","descriptionText":"m1","isStateChange":true,"type":"API","date":"2026-06-02T20:19:00.000-0400"},
              {"name":"mode","value":"Day","descriptionText":"m2","isStateChange":true,"type":"API","date":"2026-06-02T20:10:00.000-0400"}
            ]'''
        }
        hubGet.register('/hub/eventsJson') { params ->
            '[{"name":"lowMemory","value":"","descriptionText":"Hub memory is critically low, rebooting now","date":"2026-06-02T20:15:00.000-0400"}]'
        }

        when:
        def all = script.toolGetDeviceHistory([hoursBack: 168])
        def reboots = script.toolGetDeviceHistory([hoursBack: 168, attribute: 'lowMemory'])

        then:
        all.events*.name == ['mode', 'lowMemory', 'mode']
        all.events*.kind == ['location', 'hub', 'location']
        !all.containsKey('hubEventsError')
        reboots.count == 1
        reboots.events[0].description.contains('rebooting now')
    }

    def "hub_list_device_events location mode keeps the location events when the hub events read fails"() {
        given:
        hubGet.register('/logs/eventsJson') { params ->
            '[{"name":"mode","value":"Night","descriptionText":"m1","isStateChange":true,"type":"API","date":"2026-06-02T20:19:00.000-0400"}]'
        }
        hubGet.register('/hub/eventsJson') { params -> throw new RuntimeException('hub events unavailable') }

        when:
        def result = script.toolGetDeviceHistory([hoursBack: 168])

        then:
        result.count == 1
        result.events[0].kind == 'location'
        result.hubEventsError.contains('hub events unavailable')
    }

    @spock.lang.Unroll
    def "hub_list_device_events location mode reports a hub events read that is #label"() {
        given:
        hubGet.register('/logs/eventsJson') { params ->
            '[{"name":"mode","value":"Night","descriptionText":"m1","isStateChange":true,"type":"API","date":"2026-06-02T20:19:00.000-0400"}]'
        }
        hubGet.register('/hub/eventsJson') { params -> body }

        when:
        def result = script.toolGetDeviceHistory([hoursBack: 168])

        then:
        result.count == 1
        result.hubEventsError.contains(says)

        where:
        label            | body          || says
        'empty'          | ''            || 'empty response'
        'not an array'   | '{"a":1}'     || 'not a JSON array'
    }

    def "hub_list_device_events limit keeps the newest rows across both kinds and says it cut"() {
        given:
        hubGet.register('/logs/eventsJson') { params ->
            '''[
              {"name":"mode","value":"Night","descriptionText":"m1","isStateChange":true,"type":"API","date":"2026-06-02T20:19:00.000-0400"},
              {"name":"mode","value":"Day","descriptionText":"m2","isStateChange":true,"type":"API","date":"2026-06-02T20:10:00.000-0400"}
            ]'''
        }
        hubGet.register('/hub/eventsJson') { params ->
            '[{"name":"lowMemory","value":"","descriptionText":"low","date":"2026-06-02T20:15:00.000-0400"}]'
        }

        when:
        def result = script.toolGetDeviceHistory([hoursBack: 168, limit: 2])

        then:
        result.events*.description == ['m1', 'low']
        result.truncated == true
        result.matchedCount == 3
    }

    def "hub_list_device_events since drops hub events at or before the bookmark"() {
        given:
        hubGet.register('/logs/eventsJson') { params -> '[]' }
        hubGet.register('/hub/eventsJson') { params ->
            '''[
              {"name":"systemStart","value":"","descriptionText":"after","date":"2026-06-02T20:16:00.000-0400"},
              {"name":"lowMemory","value":"","descriptionText":"at","date":"2026-06-02T20:15:00.000-0400"}
            ]'''
        }

        when:
        def result = script.toolGetDeviceHistory([since: '2026-06-02T20:15:00.000-0400'])

        then:
        result.events*.description == ['after']
        result.events[0].kind == 'hub'
        !result.containsKey('truncated')
    }

    def "hub_list_device_events applies attribute filter to location events"() {
        given:
        hubGet.register('/logs/eventsJson') { params ->
            '''[
              {"name":"mode","value":"Night","descriptionText":"m1","isStateChange":true,"type":"API","date":"2026-06-02T20:19:00.000-0400"},
              {"name":"hsmStatus","value":"armedAway","descriptionText":"h1","isStateChange":true,"type":"API","date":"2026-06-02T20:18:00.000-0400"},
              {"name":"mode","value":"Day","descriptionText":"m2","isStateChange":true,"type":"API","date":"2026-06-02T20:17:00.000-0400"}
            ]'''
        }

        when:
        def result = script.toolGetDeviceHistory([attribute: 'mode'])

        then:
        result.source == 'location'
        result.attributeFilter == 'mode'
        result.count == 2
        result.events.every { it.name == 'mode' }
    }

    def "hub_list_device_events returns an error map when /logs/eventsJson fetch fails"() {
        given:
        hubGet.register('/logs/eventsJson') { params ->
            throw new RuntimeException('location event store unavailable')
        }

        when:
        def result = script.toolGetDeviceHistory([:])

        then:
        result.success == false
        result.source == 'location'
        result.error.contains('failed')
        result.error.contains('location event store unavailable')
        result.note.contains('retry')
    }

    @Unroll
    def "hub_list_device_events validates supplied #idCase instead of reading location history"() {
        given:
        hubGet.register('/logs/eventsJson') { '[]' }

        when:
        script.toolGetDeviceHistory([deviceId: inputId, hoursBack: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expectedError)
        hubGet.calls.empty

        where:
        idCase          | inputId | expectedError
        'integer zero'  | 0       | 'Device not found: 0'
        'string zero'   | '0'     | 'Device not found: 0'
        'empty string'  | ''      | 'deviceId must be a non-empty string or integral number'
        'blank string'  | ' '     | 'deviceId must be a non-empty string or integral number'
        'boolean false' | false   | 'deviceId must be a non-empty string or integral number'
    }

    @Unroll
    def "hub_list_device_events via dispatch preserves location history for #idCase deviceId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/logs/eventsJson') { '[]' }

        when:
        def response = mcpDriver.callTool('hub_list_device_events', args)

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.source == 'location'
        inner.events == []
        hubGet.calls*.path == ['/logs/eventsJson', '/hub/eventsJson']

        where:
        useGateways | idCase    | args
        false       | 'omitted' | [hoursBack: 1]
        false       | 'null'    | [deviceId: null, hoursBack: 1]
        true        | 'omitted' | [hoursBack: 1]
        true        | 'null'    | [deviceId: null, hoursBack: 1]
    }

    def "hub_list_device_events location mode surfaces timeFilterUnparseable the same way"() {
        given:
        hubGet.register('/logs/eventsJson') { params ->
            '''[
              {"name":"mode","value":"Night","descriptionText":"in window","isStateChange":true,"type":"API","date":"2009-02-13T22:00:00.000+0000"},
              {"name":"mode","value":"Day","descriptionText":"no date","isStateChange":true,"type":"API"}
            ]'''
        }

        when:
        def result = script.toolGetDeviceHistory([hoursBack: 6])

        then: 'the dateless row survives and is counted'
        result.count == 2
        result.timeFilterUnparseable == 1
    }

    def "hub_list_device_events location mode: since drops pre-bookmark rows and echoes the bookmark"() {
        given: 'no deviceId/appId -> location branch; one row after the bookmark, one before it'
        hubGet.register('/logs/eventsJson') { params ->
            '''[
              {"name":"mode","value":"Night","descriptionText":"after bookmark","isStateChange":true,"type":"API","date":"2009-02-13T22:30:00.000+0000"},
              {"name":"mode","value":"Day","descriptionText":"before bookmark","isStateChange":true,"type":"API","date":"2009-02-13T21:00:00.000+0000"}
            ]'''
        }

        when:
        def result = script.toolGetDeviceHistory([since: '2009-02-13T22:00:00.000+0000'])

        then: 'location source; only the post-bookmark row survives'
        result.source == 'location'
        result.count == 1
        result.events*.description == ['after bookmark']

        and: 'the explicit window is reported (caller string echoed verbatim); no hoursBack as if it bounded the result'
        result.sinceMode == 'explicit'
        result.since == '2009-02-13T22:00:00.000+0000'
        !result.containsKey('hoursBack')
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == 1234562400000L
    }

    def "hub_list_device_events location mode without since reports sinceMode=relative and echoes hoursBack"() {
        given:
        hubGet.register('/logs/eventsJson') { params ->
            '[{"name":"mode","value":"Night","descriptionText":"in window","isStateChange":true,"type":"API","date":"2009-02-13T22:00:00.000+0000"}]'
        }

        when:
        def result = script.toolGetDeviceHistory([hoursBack: 6])

        then: 'the relative path is labelled and hoursBack is the bounding field; no spurious since'
        result.sinceMode == 'relative'
        result.hoursBack == 6
        !result.containsKey('since')
        result.sinceTimestamp == new Date(1234567890000L - (6 * 3600000L)).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    }

    def "hub_list_device_events location mode: the bookmarked instant is EXCLUDED (strictly after)"() {
        given: 'one row exactly AT the bookmark, one strictly after it'
        hubGet.register('/logs/eventsJson') { params ->
            '''[
              {"name":"mode","value":"Night","descriptionText":"after bookmark","isStateChange":true,"type":"API","date":"2009-02-13T22:30:00.000+0000"},
              {"name":"mode","value":"Day","descriptionText":"at the bookmark","isStateChange":true,"type":"API","date":"2009-02-13T22:00:00.000+0000"}
            ]'''
        }

        when:
        def result = script.toolGetDeviceHistory([since: '2009-02-13T22:00:00.000+0000'])

        then: 'the at-bookmark row is dropped; only the strictly-newer row survives'
        result.source == 'location'
        result.count == 1
        result.events*.description == ['after bookmark']
    }

    def "hub_list_device_events throws when device is not found"() {
        when:
        script.toolGetDeviceHistory([deviceId: '999'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Device not found')
    }

    @Unroll
    def "hub_list_device_events validates supplied #idCase before any read"() {
        given:
        when:
        script.toolGetDeviceHistory([deviceId: inputId, hoursBack: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expectedError)
        hubGet.calls.empty

        where:
        idCase          | inputId | expectedError
        'integer zero'  | 0       | 'Device not found: 0'
        'string zero'   | '0'     | 'Device not found: 0'
        'empty string'  | ''      | 'deviceId must be a non-empty string or integral number'
        'blank string'  | ' '     | 'deviceId must be a non-empty string or integral number'
        'boolean false' | false   | 'deviceId must be a non-empty string or integral number'
    }

    @Unroll
    def "hub_list_device_events via dispatch rejects #idCase in history mode (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_list_device_events', [deviceId: inputId, hoursBack: 1])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Device not found: 0')
        hubGet.calls.empty

        where:
        useGateways | idCase         | inputId
        false       | 'integer zero' | 0
        false       | 'string zero'  | '0'
        true        | 'integer zero' | 0
        true        | 'string zero'  | '0'
    }

    @Unroll
    def "hub_list_device_events with bypass refuses #inputId at native metadata (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/device/fullJson/0') { '{"device":null}' }

        when:
        def response = mcpDriver.callTool('hub_list_device_events', [deviceId: inputId, hoursBack: 1])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error == 'Device metadata fetch failed (/device/fullJson/0)'
        hubGet.calls*.path == ['/device/fullJson/0']

        where:
        useGateways | inputId
        false       | 0
        false       | '0'
        true        | 0
        true        | '0'
    }

    // -------- toolGetDeviceHistory: per-app events (appId) --------
    // Pinned now() is 1234567890000L = 2009-02-13T23:31:30Z; in-window event
    // dates below are chosen relative to that.

    def "hub_list_device_events returns app events when appId is given (reads /installedapp/eventsJson)"() {
        given: '/installedapp/eventsJson stubbed with app-emitted rows (real wire shape: name/value/descriptionText/date) plus one row outside the 6h window'
        hubGet.register('/installedapp/eventsJson/974') { params ->
            '''[
              {"name":"pushed","value":"1","descriptionText":"Rule triggered","date":"2009-02-13T22:00:00.000+0000"},
              {"name":"switch","value":"on","descriptionText":"Rule turned on Kitchen","date":"2009-02-13T21:00:00.000+0000"},
              {"name":"switch","value":"off","descriptionText":"too old","date":"2009-02-10T00:00:00.000+0000"}
            ]'''
        }

        when:
        def result = script.toolGetDeviceHistory([appId: 974, hoursBack: 6, limit: 25])

        then: 'it reads the per-app events endpoint'
        hubGet.calls.any { it.path == '/installedapp/eventsJson/974' }

        and: 'source/appId identify the mode; no device keys leak in'
        result.source == 'app'
        result.appId == 974
        !result.containsKey('device')
        !result.containsKey('deviceId')
        result.hoursBack == 6

        and: 'relative mode is labelled and does not echo a since'
        result.sinceMode == 'relative'
        !result.containsKey('since')

        and: 'rows are normalized to {name, value, description, date} and the out-of-window row is dropped'
        result.count == 2
        result.events*.name == ['pushed', 'switch']
        result.events[0].value == '1'
        result.events[0].description == 'Rule triggered'
        result.events[0].date == '2009-02-13T22:00:00.000+0000'
        result.events[0].keySet() == ['name', 'value', 'description', 'date'] as Set

        and: 'every date parsed, so the unparseable counter stays absent'
        !result.containsKey('timeFilterUnparseable')
    }

    def "hub_list_device_events app mode: rows with malformed or missing dates survive the window and are counted in timeFilterUnparseable"() {
        given: 'one in-window row, one garbage date, one missing date, one parseable out-of-window row'
        hubGet.register('/installedapp/eventsJson/974') { params ->
            '''[
              {"name":"pushed","value":"1","descriptionText":"in window","date":"2009-02-13T22:00:00.000+0000"},
              {"name":"pushed","value":"2","descriptionText":"garbage date","date":"not-a-date"},
              {"name":"pushed","value":"3","descriptionText":"missing date"},
              {"name":"pushed","value":"4","descriptionText":"too old","date":"2009-02-10T00:00:00.000+0000"}
            ]'''
        }

        when:
        def result = script.toolGetDeviceHistory([appId: 974, hoursBack: 6])

        then: 'unparseable-date rows pass through the always-active window; only the parseable out-of-window row is dropped'
        result.count == 3
        result.events*.value == ['1', '2', '3']

        and: 'the silent escape is surfaced with the affected row count'
        result.timeFilterUnparseable == 2
    }

    def "hub_list_device_events applies attribute and limit filters to app events client-side"() {
        given: 'the endpoint takes no query params, so filtering must happen client-side; 5 switch rows exceed the limit of 3'
        hubGet.register('/installedapp/eventsJson/974') { params ->
            JsonOutput.toJson((1..10).collect { i ->
                [name: (i % 2 == 0 ? 'switch' : 'level'), value: "${i}".toString(),
                 descriptionText: "evt ${i}".toString(), date: '2009-02-13T22:00:00.000+0000']
            })
        }

        when:
        def result = script.toolGetDeviceHistory([appId: 974, attribute: 'switch', limit: 3])

        then: 'limit is enforced while collecting (before the response is built)'
        result.count == 3
        result.events.size() == 3
        result.events.every { it.name == 'switch' }
        result.attributeFilter == 'switch'
    }

    def "hub_list_device_events returns an error map when /installedapp/eventsJson fetch fails"() {
        given:
        hubGet.register('/installedapp/eventsJson/974') { params ->
            throw new RuntimeException('app event store unavailable')
        }

        when:
        def result = script.toolGetDeviceHistory([appId: 974])

        then:
        result.success == false
        result.source == 'app'
        result.appId == 974
        result.error.contains('failed')
        result.error.contains('app event store unavailable')
        result.note.contains('retry')
    }

    def "hub_list_device_events throws when appId is non-numeric (no HTTP call made)"() {
        // No endpoint registered: reaching HTTP would surface the unstubbed-path
        // error instead of the expected validation exception.
        when:
        script.toolGetDeviceHistory([appId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('numeric')
    }

    @spock.lang.Unroll
    def "hub_list_device_events via dispatch returns app events for appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/installedapp/eventsJson/974') { params ->
            '[{"name":"pushed","value":"1","descriptionText":"Rule triggered","date":"2009-02-13T22:00:00.000+0000"}]'
        }

        when:
        def response = mcpDriver.callTool('hub_list_device_events', [appId: 974])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.source == 'app'
        inner.appId == 974
        inner.count == 1
        inner.events[0].name == 'pushed'
        inner.events[0].description == 'Rule triggered'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_list_device_events via dispatch rejects deviceId+appId together with isError validation result (useGateways=#useGateways)"() {
        given: 'no endpoint registered and no device needed -- the exclusivity check fires before any routing'
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_list_device_events', [deviceId: '42', appId: 974])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('mutually exclusive')

        where:
        useGateways << [true, false]
    }

    def "hub_list_device_events returns events for a selected device"() {
        given: 'a selected device whose native event endpoint returns 2 events'
        def fixedDate = new Date(1234567880000L)
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on', unit: null, descriptionText: 'turned on', date: fixedDate, isStateChange: true],
                [name: 'level', value: '75', unit: '%', descriptionText: 'dimmed', date: fixedDate, isStateChange: true]
            ]
        }
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolGetDeviceHistory([deviceId: '42', hoursBack: 12, limit: 50])

        then: 'the native endpoint is read and the response uses the hoursBack-derived window'
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == 1234567890000L - (12 * 3600000L)
        hubGet.calls.findAll { it.path == '/device/eventsJson/42' }.size() == 1

        and:
        result.deviceId == '42'
        result.device == 'Kitchen Light'
        result.hoursBack == 12
        result.count == 2
        result.events[0].name == 'switch'
        result.events[0].value == 'on'
        result.events[1].name == 'level'

        and: 'relative mode is labelled and does not echo a since'
        result.sinceMode == 'relative'
        !result.containsKey('since')
    }

    @spock.lang.Unroll
    def "hub_list_device_events via dispatch returns events for a selected device (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def fixedDate = new Date(1234567880000L)
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on', unit: null, descriptionText: 'turned on', date: fixedDate, isStateChange: true],
                [name: 'level', value: '75', unit: '%', descriptionText: 'dimmed', date: fixedDate, isStateChange: true]
            ]
        }
        settingsMap.selectedDevices = [device]

        when:
        def response = mcpDriver.callTool('hub_list_device_events', [deviceId: '42', hoursBack: 12, limit: 50])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.deviceId == '42'
        inner.device == 'Kitchen Light'
        inner.count == 2
        inner.events[0].name == 'switch'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_list_device_events via dispatch returns recent-N events when no window/filter is given (useGateways=#useGateways)"() {
        given: 'no hoursBack and no attribute -> executeTool routes deviceId to toolGetDeviceEvents (native recent-N history)'
        settingsMap.useGateways = useGateways
        def fixedDate = new Date(1234567880000L)
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on',  unit: null, descriptionText: 'turned on', date: fixedDate, isStateChange: true],
                [name: 'level',  value: '75',  unit: '%',  descriptionText: 'dimmed',    date: fixedDate, isStateChange: true]
            ]
        }
        settingsMap.selectedDevices = [device]

        when:
        def response = mcpDriver.callTool('hub_list_device_events', [deviceId: '42', limit: 5])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.device == 'Kitchen Light'
        inner.count == 2
        inner.events.size() == 2
        inner.events[0].name == 'switch'
        inner.events[0].value == 'on'
        inner.events[1].name == 'level'

        and: 'the recent-N path is NOT history mode -- no sinceMode/sinceTimestamp leak in'
        !inner.containsKey('sinceMode')
        !inner.containsKey('sinceTimestamp')

        where:
        useGateways << [true, false]
    }

    def "hub_list_device_events applies attribute filter"() {
        given:
        def fixedDate = new Date(1234567880000L)
        def device = new TestDevice(id: 42, name: 'Multi', label: 'Multi')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on',  date: fixedDate, isStateChange: true],
                [name: 'level',  value: '50',  date: fixedDate, isStateChange: true],
                [name: 'switch', value: 'off', date: fixedDate, isStateChange: true]
            ]
        }
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolGetDeviceHistory([deviceId: '42', attribute: 'switch'])

        then:
        result.count == 2
        result.events.every { it.name == 'switch' }
        result.attributeFilter == 'switch'
    }

    def "hub_list_device_events applies the limit after native time and attribute filtering"() {
        given:
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'level', value: '50', date: new Date(1234562500000L)],
                [name: 'switch', value: 'on', date: new Date(1234562490000L)],
                [name: 'switch', value: 'off', date: new Date(1234562480000L)],
                [name: 'switch', value: 'on', date: new Date(1234562470000L)],
                [name: 'switch', value: 'off', date: new Date(1234562300000L)]
            ]
        }
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolGetDeviceHistory([deviceId: '42', since: 1234562400000L, attribute: 'switch', limit: 2])

        then:
        result.count == 2
        result.events*.name == ['switch', 'switch']
        result.events*.value == ['on', 'off']
        result.events*.date == [1234562490000L, 1234562480000L].collect {
            new Date(it).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        }
    }

    def "hub_list_device_events returns an error map when the native event endpoint throws"() {
        given:
        def device = new TestDevice(id: 42, name: 'Broken', label: 'Broken')
        nativeDeviceEvents(device) { ->
            throw new RuntimeException('event store unavailable; access_token=TRANSPORT_SECRET')
        }
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolGetDeviceHistory([deviceId: '42'])

        then: 'the native read failure is explicit, recoverable, and redacts transport details'
        result.success == false
        result.error.contains('Device event history fetch failed')
        result.note
        !JsonOutput.toJson(result).contains('TRANSPORT_SECRET')
        result.deviceId == '42'
    }

    // -------- toolGetDeviceHistory: since (absolute bookmark) --------
    // Pinned now() is 1234567890000L = 2009-02-13T23:31:30Z. A `since` of
    // 2009-02-13T22:00:00.000+0000 is ~1.5h in the past; rows are placed on
    // either side of it to prove the filter.

    def "hub_list_device_events device mode: since drives the window, sinceMode=explicit, hoursBack omitted"() {
        given: 'a selected device; verify the native history window'
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [[name: 'switch', value: 'on', date: new Date(1234567880000L), isStateChange: true]]
        }
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolGetDeviceHistory([deviceId: '42', since: '2009-02-13T22:00:00.000+0000'])

        then: 'the history window starts at the explicit since'
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", '2009-02-13T22:00:00.000+0000').time

        and: 'the response echoes the caller string verbatim and omits hoursBack'
        result.sinceMode == 'explicit'
        result.since == '2009-02-13T22:00:00.000+0000'
        !result.containsKey('hoursBack')
        // sinceTimestamp is the canonical-formatted window start; assert the INSTANT
        // (its TZ rendering follows the JVM-local zone, so a string match is fragile).
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == 1234562400000L
        result.count == 1
    }

    def "hub_list_device_events: since takes precedence over hoursBack when both are given"() {
        given:
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on', date: new Date(1234562460000L)],
                [name: 'switch', value: 'off', date: new Date(1234562340000L)]
            ]
        }
        settingsMap.selectedDevices = [device]

        when: 'both since and a relative hoursBack are supplied'
        def result = script.toolGetDeviceHistory([deviceId: '42', since: '2009-02-13T22:00:00.000+0000', hoursBack: 6])

        then: 'the absolute since wins -- the window start is the since, not now()-6h'
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", '2009-02-13T22:00:00.000+0000').time
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time != 1234567890000L - (6 * 3600000L)
        result.events*.value == ['on']
        result.sinceMode == 'explicit'
        !result.containsKey('hoursBack')
    }

    def "hub_list_device_events app mode: since drops pre-bookmark rows and echoes the bookmark"() {
        given: 'one row after the bookmark, one before it'
        hubGet.register('/installedapp/eventsJson/974') { params ->
            '''[
              {"name":"switch","value":"on","descriptionText":"after bookmark","date":"2009-02-13T22:30:00.000+0000"},
              {"name":"switch","value":"off","descriptionText":"before bookmark","date":"2009-02-13T21:00:00.000+0000"}
            ]'''
        }

        when:
        def result = script.toolGetDeviceHistory([appId: 974, since: '2009-02-13T22:00:00.000+0000'])

        then: 'only the post-bookmark row survives'
        result.count == 1
        result.events*.description == ['after bookmark']

        and: 'the explicit window is reported (caller string echoed verbatim); no hoursBack as if it bounded the result'
        result.sinceMode == 'explicit'
        result.since == '2009-02-13T22:00:00.000+0000'
        !result.containsKey('hoursBack')
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == 1234562400000L
    }

    def "hub_list_device_events: a future since yields an empty list, not an error"() {
        given: 'native events include an existing row before the future window'
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on', date: new Date(1234562460000L)],
                [name: 'switch', value: 'off', date: new Date(1234562400000L)],
                [name: 'switch', value: 'off', date: new Date(1234562340000L)]
            ]
        }
        settingsMap.selectedDevices = [device]

        when: '2030 is well past the pinned now() of 2009'
        def result = script.toolGetDeviceHistory([deviceId: '42', since: '2030-01-01T00:00:00.000+0000'])

        then: 'a valid empty result, not a thrown error'
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time > 1234567890000L
        result.count == 0
        result.events == []
        result.sinceMode == 'explicit'
    }

    def "hub_list_device_events: since as epoch milliseconds parses"() {
        given:
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on', date: new Date(1234562460000L)],
                [name: 'switch', value: 'off', date: new Date(1234562400000L)],
                [name: 'switch', value: 'off', date: new Date(1234562340000L)]
            ]
        }
        settingsMap.selectedDevices = [device]

        when: 'since is an epoch-ms integer (2009-02-13T22:00:00Z)'
        def result = script.toolGetDeviceHistory([deviceId: '42', since: 1234562400000L])

        then: 'it parses to the same instant and drives an explicit window'
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == 1234562400000L
        result.events*.value == ['on']
        result.sinceMode == 'explicit'
        result.since == new Date(1234562400000L).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    }

    @spock.lang.Unroll
    def "hub_list_device_events: schema and handler accept #declaredType deviceId"() {
        given:
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { -> [[name: 'switch', value: 'on', date: new Date(1234562460000L)]] }
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolGetDeviceHistory([deviceId: inputId, since: 1234562400000L])

        then:
        result.deviceId == '42'
        result.events*.value == ['on']
        script.getAllToolDefinitions().find { it.name == 'hub_list_device_events' }.inputSchema.properties.deviceId.type.contains(declaredType)

        where:
        inputId | declaredType
        42      | 'integer'
        '42'    | 'string'
    }

    def "hub_list_device_events: a fractional deviceId is refused"() {
        when:
        script.toolGetDeviceHistory([deviceId: 42.5])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceId must be a non-empty string or integral number')
    }

    def "hub_list_device_events: a returned date round-trips as since"() {
        given: 'capture an emitted date, then feed it straight back as since'
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        def emittedDate = new Date(1234567880000L)
        nativeDeviceEvents(device) { ->
            [[name: 'switch', value: 'on', date: emittedDate, isStateChange: true]]
        }
        settingsMap.selectedDevices = [device]
        def first = script.toolGetDeviceHistory([deviceId: '42', hoursBack: 1])
        def bookmark = first.events[0].date

        when: 'the emitted ISO date is passed back verbatim as since'
        def result = script.toolGetDeviceHistory([deviceId: '42', since: bookmark])

        then: 'it parses cleanly (no exception) to the original instant'
        bookmark == emittedDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == emittedDate.time
        result.sinceMode == 'explicit'
        result.count == 0
        result.events == []
    }

    def "hub_list_device_events: a trailing-Z since is parsed as UTC, not hub-local"() {
        given: 'capture the sinceDate; Z means Zulu/UTC regardless of the JVM default zone'
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on', date: new Date(1234562460000L)],
                [name: 'switch', value: 'off', date: new Date(1234562400000L)],
                [name: 'switch', value: 'off', date: new Date(1234562340000L)]
            ]
        }
        settingsMap.selectedDevices = [device]

        when: 'a Zulu bookmark (literal Z) is supplied'
        def result = script.toolGetDeviceHistory([deviceId: '42', since: '2009-02-13T22:00:00.000Z'])

        then: 'it resolves to the UTC instant (22:00Z == epoch 1234562400000), not 22:00 hub-local'
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == 1234562400000L
        result.events*.value == ['on']
        result.sinceMode == 'explicit'
        // echoed verbatim (a real ISO string, not epoch digits)
        result.since == '2009-02-13T22:00:00.000Z'
    }

    def "hub_list_device_events: a millis-less ISO since (no .SSS) parses via the fallback format"() {
        given: 'exercises the second probe format -- yyyy-MM-dd\'T\'HH:mm:ssZ -- which has no other spec'
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on', date: new Date(1234562460000L)],
                [name: 'switch', value: 'off', date: new Date(1234562400000L)],
                [name: 'switch', value: 'off', date: new Date(1234562340000L)]
            ]
        }
        settingsMap.selectedDevices = [device]

        when: 'since omits the milliseconds'
        def result = script.toolGetDeviceHistory([deviceId: '42', since: '2009-02-13T22:00:00+0000'])

        then: 'it resolves to the same instant as the with-millis form (22:00:00Z == epoch 1234562400000)'
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == 1234562400000L
        result.events*.value == ['on']
        result.sinceMode == 'explicit'
        result.since == '2009-02-13T22:00:00+0000'
    }

    def "hub_list_device_events: an epoch since passed as a digit STRING echoes canonical ISO (parity with the Number form)"() {
        given:
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on', date: new Date(1234562460000L)],
                [name: 'switch', value: 'off', date: new Date(1234562400000L)],
                [name: 'switch', value: 'off', date: new Date(1234562340000L)]
            ]
        }
        settingsMap.selectedDevices = [device]

        when: 'since is the epoch-ms as a String'
        def result = script.toolGetDeviceHistory([deviceId: '42', since: '1234562400000'])

        then: 'it parses to the same instant and the echo is canonical ISO, not the raw digits'
        Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", result.sinceTimestamp).time == 1234562400000L
        result.events*.value == ['on']
        result.sinceMode == 'explicit'
        result.since == new Date(1234562400000L).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        result.since != '1234562400000'
    }

    def "hub_list_device_events app mode: the bookmarked instant is EXCLUDED (strictly after, not at-or-after)"() {
        given: 'one row exactly AT the bookmark, one strictly after it'
        hubGet.register('/installedapp/eventsJson/974') { params ->
            '''[
              {"name":"switch","value":"on","descriptionText":"after bookmark","date":"2009-02-13T22:30:00.000+0000"},
              {"name":"switch","value":"off","descriptionText":"at the bookmark","date":"2009-02-13T22:00:00.000+0000"}
            ]'''
        }

        when: 'since equals the second row\'s timestamp'
        def result = script.toolGetDeviceHistory([appId: 974, since: '2009-02-13T22:00:00.000+0000'])

        then: 'the at-bookmark row is dropped (re-passing a returned date must not replay it); only the strictly-newer row survives'
        result.count == 1
        result.events*.description == ['after bookmark']
    }

    def "hub_list_device_events device mode: the bookmarked instant is EXCLUDED (strictly after)"() {
        given: 'the native endpoint returns a row AT the bookmark and one after it; the filter must drop the at-bookmark row'
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [
                [name: 'switch', value: 'on',  unit: null, descriptionText: 'after bookmark', date: new Date(1234562460000L), isStateChange: true],
                [name: 'switch', value: 'off', unit: null, descriptionText: 'at the bookmark', date: new Date(1234562400000L), isStateChange: true]
            ]
        }
        settingsMap.selectedDevices = [device]

        when: 'since == the at-bookmark row instant (1234562400000)'
        def result = script.toolGetDeviceHistory([deviceId: '42', since: 1234562400000L])

        then: 'only the strictly-after row survives the native history filter'
        result.count == 1
        result.events*.description == ['after bookmark']
    }

    @spock.lang.Unroll
    def "hub_list_device_events: an unparseable since (#desc) throws isError validation result-style IllegalArgumentException"() {
        given: 'a selected device so routing reaches the since parse before any HTTP'
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        settingsMap.selectedDevices = [device]

        when:
        script.toolGetDeviceHistory([deviceId: '42', since: badSince])

        then: 'the clean caller-error path -- never a StringIndexOutOfBounds from stripping the trailing Z off a 1-char input'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('since is not a valid timestamp')
        ex.message.contains('epoch milliseconds')

        where:
        desc            | badSince
        'plain garbage' | 'not-a-timestamp'
        'bare Z'        | 'Z'   // 1-char Z-strip: old s[0..-2] threw StringIndexOutOfBounds; substring yields a clean caller error
    }

    @spock.lang.Unroll
    def "hub_list_device_events via dispatch routes since to history mode (useGateways=#useGateways)"() {
        given: 'since alone (no hoursBack/attribute) must NOT take the recent-N path'
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        nativeDeviceEvents(device) { ->
            [[name: 'switch', value: 'on', date: new Date(1234567880000L), isStateChange: true]]
        }
        settingsMap.selectedDevices = [device]

        when:
        def response = mcpDriver.callTool('hub_list_device_events', [deviceId: '42', since: '2009-02-13T22:00:00.000+0000'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.source == 'device'
        inner.sinceMode == 'explicit'
        inner.since == '2009-02-13T22:00:00.000+0000'

        where:
        useGateways << [true, false]
    }

    // -------- hub_get_logs: device/app stats --------

    private String logsJsonStats(Map extra = [:]) {
        JsonOutput.toJson([
            uptime: '5d 2h', devicesUptime: '5d 2h', totalDevicesRuntime: '0m 9s', devicePct: '0.1%',
            appsUptime: '5d 2h', totalAppsRuntime: '3m 44s', appPct: '0.1%',
            deviceStats: [
                [id: 1, name: 'SlowDevice', pct: 15.0, count: 100, total: 500, average: 5.0, stateSize: 1000, largeState: true,
                 formattedPct: '15.0', formattedCount: '100', customAttributes: [eventsCount: 8, statesCount: 9]],
                [id: 2, name: 'FastDevice', pct: 2.0, count: 900, total: 10, average: 0.1, stateSize: 100, largeState: false,
                 formattedPct: '2.0', formattedCount: '900']
            ],
            appStats: [[id: 99, name: 'MyApp', pct: 5.0, count: 10, total: 200, stateSize: 50, formattedPct: '5.0']],
            jobs: [], runningJobs: [], hubCommands: []
        ] + extra)
    }

    def "hub_get_logs devices returns the native stat rows by % busy with the page summary"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonStats() }

        when:
        def result = script.toolGetHubLogs([mode: 'devices'])

        then: 'native rows, highest pct first; the formatted display copies are dropped'
        result.mode == 'devices'
        result.entries*.name == ['SlowDevice', 'FastDevice']
        result.entries[0] == [id: 1, name: 'SlowDevice', pct: 15.0, count: 100, total: 500, average: 5.0, stateSize: 1000,
                              largeState: true, customAttributes: [eventsCount: 8, statesCount: 9]]
        result.summary == [devicesUptime: '5d 2h', totalDevicesRuntime: '0m 9s', devicePct: '0.1%']
        result.uptime == '5d 2h'
        result.total == 2
    }

    @Unroll
    def "hub_get_logs devices via dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/logs/json') { params -> logsJsonStats() }

        when:
        def response = mcpDriver.callTool('hub_get_logs', [mode: 'devices', sortBy: 'count'])

        then:
        !response.result.isError
        mcpDriver.parseInner(response).entries*.name == ['FastDevice', 'SlowDevice']

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_get_logs devices sortBy=#sortBy"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonStats() }

        expect:
        script.toolGetHubLogs([mode: 'devices', sortBy: sortBy]).entries*.id == ids

        where:
        sortBy      || ids
        'pct'       || [1, 2]
        'count'     || [2, 1]
        'total'     || [1, 2]
        'stateSize' || [1, 2]
        'name'      || [2, 1]
    }

    def "hub_get_logs apps returns only app rows with the apps summary"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonStats() }

        when:
        def result = script.toolGetHubLogs([mode: 'apps'])

        then:
        result.entries*.name == ['MyApp']
        result.summary == [appsUptime: '5d 2h', totalAppsRuntime: '3m 44s', appPct: '0.1%']
    }

    def "hub_get_logs stats limit defaults to 20, 0 returns every row, and an id keeps one row"() {
        given:
        def many = (1..25).collect { [id: it, name: "D${it}".toString(), pct: it as double] }
        hubGet.register('/logs/json') { params -> logsJsonStats(deviceStats: many) }

        when:
        def byDefault = script.toolGetHubLogs([mode: 'devices'])
        def all = script.toolGetHubLogs([mode: 'devices', limit: 0])
        def one = script.toolGetHubLogs([mode: 'devices', deviceId: '7'])
        def app = script.toolGetHubLogs([mode: 'apps', appId: '99'])

        then:
        byDefault.count == 20
        byDefault.matched == 25
        byDefault.entries[0].id == 25
        all.count == 25
        one.entries*.id == [7]
        app.entries*.name == ['MyApp']
    }

    def "hub_get_logs is blocked by the central Read master gate"() {
        given:
        settingsMap.enableRead = false

        when:
        script.executeTool("hub_get_logs", [mode: 'scheduledJobs'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Read tools are disabled")
    }

    // -------- hub_get_logs: scheduled jobs, running jobs, hub actions --------

    private static final String NO_JOBS = JsonOutput.toJson([uptime: '1d', jobs: [], runningJobs: [], hubCommands: []])

    private String logsJsonWithJobs(int jobCount) {
        JsonOutput.toJson([
            uptime: '1d',
            jobs: (1..jobCount).collect {
                [id: "app${it}Recur.run".toString(), name: "Job${it}".toString(), link: "/installedapp/configure/${it}".toString(),
                 recurring: true, methodName: 'run', nextRun: '2026-04-21 02:00:00 EDT', nextRunDt: '2026-04-21T06:00:00+0000']
            },
            runningJobs: [[name: 'HubCheck', link: '/device/edit/900', methodName: 'checkHealth', runningFor: '2s']],
            hubCommands: [[type: 'dev', id: 900, name: 'Porch', link: '/device/edit/900']]
        ])
    }

    def "hub_get_logs scheduledJobs keeps the native link and nextRunDt and adds the owner from the link"() {
        given: 'one app job, one device job, one system job (empty link), as the Logs page reports them'
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([uptime: '1d', runningJobs: [], hubCommands: [], jobs: [
                [id: 'app2Recur.checkForUpdate', name: 'MCP', link: '/installedapp/configure/2', recurring: true,
                 methodName: 'checkForUpdate', nextRun: '2026-10-23 03:00:00 EDT', nextRunDt: '2026-10-23T07:00:00+0000'],
                [id: 'dev54Recur.deviceHealthCheck', name: 'Sensor', link: '/device/edit/54', recurring: true,
                 methodName: 'deviceHealthCheck', nextRun: '2026-10-22 22:46:54 EDT', nextRunDt: '2026-10-23T02:46:54+0000'],
                [id: 'system.sunJob', name: 'sunJob', link: '', recurring: false, methodName: null,
                 nextRun: '2026-10-22 23:00:00 EDT', nextRunDt: '2026-10-23T03:00:00+0000']
            ]])
        }

        when:
        def result = script.toolGetHubLogs([mode: 'scheduledJobs'])

        then:
        result.entries[0] == [id: 'app2Recur.checkForUpdate', name: 'MCP', link: '/installedapp/configure/2', recurring: true,
                              methodName: 'checkForUpdate', nextRun: '2026-10-23 03:00:00 EDT', nextRunDt: '2026-10-23T07:00:00+0000',
                              owner: [type: 'app', id: 2]]
        result.entries[1].owner == [type: 'device', id: 54]
        !result.entries[2].containsKey('owner')
        result.entries[2].link == ''
        result.uptime == '1d'
    }

    def "hub_get_logs job modes filter by owner and search name and methodName"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(3) }

        when:
        def byApp = script.toolGetHubLogs([mode: 'scheduledJobs', appId: '2'])
        def byMethod = script.toolGetHubLogs([mode: 'scheduledJobs', pattern: '^run$'])
        def running = script.toolGetHubLogs([mode: 'runningJobs', deviceId: '900'])
        def actions = script.toolGetHubLogs([mode: 'hubActions'])
        def wrongKind = script.toolGetHubLogs([mode: 'runningJobs', appId: '900'])

        then:
        byApp.entries*.id == ['app2Recur.run']
        byMethod.count == 3
        running.entries == [[name: 'HubCheck', link: '/device/edit/900', methodName: 'checkHealth', runningFor: '2s',
                             owner: [type: 'device', id: 900]]]
        actions.entries == [[type: 'dev', id: 900, name: 'Porch', link: '/device/edit/900', owner: [type: 'device', id: 900]]]
        wrongKind.count == 0
    }

    @Unroll
    def "hub_get_logs scheduledJobs via dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(1) }

        when:
        def response = mcpDriver.callTool('hub_get_logs', [mode: 'scheduledJobs'])

        then:
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.entries[0].owner == [type: 'app', id: 1]
        inner.uptime == '1d'

        where:
        useGateways << [true, false]
    }

    def "hub_get_logs scheduledJobs cursor pages 100 at a time"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(150) }

        when:
        def full = script.toolGetHubLogs([mode: 'scheduledJobs'])
        def first = script.toolGetHubLogs([mode: 'scheduledJobs', cursor: ''])
        def second = script.toolGetHubLogs([mode: 'scheduledJobs', cursor: first.nextCursor])

        then:
        full.count == 150
        !full.containsKey('nextCursor')
        first.count == 100
        first.entries*.name == (1..100).collect { "Job${it}".toString() }
        first.nextCursor == '100'
        second.count == 50
        second.entries*.name == (101..150).collect { "Job${it}".toString() }
        !second.containsKey('nextCursor')
    }

    // -------- /logs/json snapshot --------

    def "every Logs-page mode shares one /logs/json fetch inside the snapshot TTL and refetches after it"() {
        given:
        def fetches = new java.util.concurrent.atomic.AtomicInteger(0)
        hubGet.register('/logs/json') { params -> fetches.incrementAndGet(); logsJsonWithJobs(2) }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })

        when:
        def jobs = script.toolGetHubLogs([mode: 'scheduledJobs'])
        def stats = script.toolGetHubLogs([mode: 'apps'])
        def running = script.toolGetHubLogs([mode: 'runningJobs'])
        def paged = script.toolGetHubLogs([mode: 'scheduledJobs', cursor: ''])

        then: 'one inline fetch served all four; no worker on an unbudgeted LAN request'
        fetches.get() == 1
        jobs.count == 2
        jobs.snapshot.background == false
        jobs.snapshot.budgeted == false
        jobs.snapshot.ageMs == 0L
        stats.snapshot.fetchedAt == jobs.snapshot.fetchedAt
        stats.entries == []
        running.count == 1
        paged.count == 2
        runInMillisCalls.isEmpty()

        when: 'the TTL passes'
        virtualNow.addAndGet(script._logsJsonSnapshotTtlMs() as Long)
        script.toolGetHubLogs([mode: 'hubActions'])

        then:
        fetches.get() == 2
    }

    def "a relay-bound legacy hub_get_logs job read schedules the background fetch, answers in_progress, then serves the landed snapshot"() {
        given:
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        def fetches = new java.util.concurrent.atomic.AtomicInteger(0)
        hubGet.register('/logs/json') { params -> fetches.incrementAndGet(); logsJsonWithJobs(3) }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        def waitedMs = new java.util.concurrent.atomic.AtomicLong(0L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> waitedMs.addAndGet(ms); virtualNow.addAndGet(ms) })

        when: 'the legacy dispatch path runs the read'
        def response = mcpDriver.callTool('hub_get_logs', [mode: 'scheduledJobs'])
        def inner = mcpDriver.parseInner(response)

        then: 'nothing was fetched on the request thread; one worker was scheduled; the call waited out its observe window'
        response.error == null
        !response.result.isError
        inner.status == 'in_progress'
        inner.tool == 'hub_get_logs'
        inner.retryable == true
        fetches.get() == 0
        runInMillisCalls.size() == 1
        runInMillisCalls[0][0..1] == [50, 'runLogsJsonFetch']
        runInMillisCalls[0][2].overwrite == false
        waitedMs.get() == 4500L   // 6000 ms budget - 1500 ms headroom, capped at 4500 over the cloud

        when: 'another mode arrives while the worker is still queued'
        def again = mcpDriver.parseInner(mcpDriver.callTool('hub_get_logs', [mode: 'devices']))

        then: 'no second worker'
        again.status == 'in_progress'
        runInMillisCalls.size() == 1

        when: 'Hubitat runs the worker and the client repeats the identical call'
        script.runLogsJsonFetch(runInMillisCalls[0][2].data as Map)
        def served = mcpDriver.parseInner(mcpDriver.callTool('hub_get_logs', [mode: 'scheduledJobs']))

        then:
        fetches.get() == 1
        served.count == 3
        served.snapshot.background == true
        served.snapshot.budgeted == true
        served.uptime == '1d'
        runInMillisCalls.size() == 1
    }

    def "a relay-bound hub_get_logs list of modes answers in_progress without reading the inline tabs, then serves every tab"() {
        given:
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        def fetches = new java.util.concurrent.atomic.AtomicInteger(0)
        hubGet.register('/logs/json') { params -> fetches.incrementAndGet(); logsJsonWithJobs(3) }
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> virtualNow.addAndGet(ms) })

        when:
        def first = mcpDriver.parseInner(mcpDriver.callTool('hub_get_logs', [mode: ['scheduledJobs', 'locationEvents']]))

        then: 'the Logs page fetch is scheduled and the inline tab waits for the repeat'
        first.status == 'in_progress'
        first.tool == 'hub_get_logs'
        runInMillisCalls.size() == 1
        hubGet.calls.findAll { it.path == '/logs/eventsJson' }.empty

        when:
        script.runLogsJsonFetch(runInMillisCalls[0][2].data as Map)
        def served = mcpDriver.parseInner(mcpDriver.callTool('hub_get_logs', [mode: ['scheduledJobs', 'locationEvents']]))

        then:
        fetches.get() == 1
        served.modes.scheduledJobs.count == 3
        served.modes.locationEvents.count == 3
    }

    def "an inline tab listed before a tab that goes pending is not read on the pending leg, and keeps its place in the answer"() {
        given:
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(3) }
        hubGet.register('/logs/eventsJson') { params -> LOCATION_ROWS }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> virtualNow.addAndGet(ms) })

        when:
        def first = script.toolGetHubLogs([mode: ['locationEvents', 'scheduledJobs'], __reqT0: virtualNow.get()])

        then:
        first.status == 'in_progress'
        hubGet.calls.findAll { it.path == '/logs/eventsJson' }.empty

        when:
        script.runLogsJsonFetch(runInMillisCalls[0][2].data as Map)
        def served = script.toolGetHubLogs([mode: ['locationEvents', 'scheduledJobs'], __reqT0: virtualNow.get()])

        then:
        hubGet.calls.findAll { it.path == '/logs/eventsJson' }.size() == 1
        served.modes.keySet() as List == ['locationEvents', 'scheduledJobs']
        served.modes.locationEvents.count == 3
        served.modes.scheduledJobs.count == 3
    }

    def "a non-object hub actions row fails the Logs page read instead of being counted and dropped"() {
        given:
        hubGet.register('/logs/json') { params -> JsonOutput.toJson([uptime: '1d', jobs: [], runningJobs: [], hubCommands: ['Porch']]) }

        when:
        def result = script.toolGetHubLogs([mode: 'hubActions'])

        then:
        result.isError == true
        result.error.contains("'hubCommands' contains a non-object entry")
    }

    @Unroll
    def "a LAN request with lanBudgetMs=#lanBudget uses the background fetch and waits #expectedWait"() {
        given: 'budget - 1500 below the 6000 LAN cap (distinct from the 4500 cloud cap), the cap above it'
        settingsMap.lanBudgetMs = lanBudget
        def fetches = new java.util.concurrent.atomic.AtomicInteger(0)
        hubGet.register('/logs/json') { params -> fetches.incrementAndGet(); logsJsonWithJobs(2) }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        def waitedMs = new java.util.concurrent.atomic.AtomicLong(0L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> waitedMs.addAndGet(ms); virtualNow.addAndGet(ms) })

        when:
        def first = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])
        script.runLogsJsonFetch(runInMillisCalls[0][2].data as Map)
        def second = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])

        then:
        script._isCloudRequest() == false
        script._mrtrReadContinuationActive() == true
        first.status == 'in_progress'
        waitedMs.get() == expectedWait
        runInMillisCalls.size() == 1
        fetches.get() == 1
        second.count == 2

        where:
        lanBudget | expectedWait
        6000      | 4500L
        7499      | 5999L
        10000     | 6000L
    }

    def "a failed background /logs/json fetch is reported as an error result with a retry already scheduled"() {
        given:
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        def attempts = new java.util.concurrent.atomic.AtomicInteger(0)
        hubGet.register('/logs/json') { params ->
            if (attempts.incrementAndGet() == 1) throw new RuntimeException('logs page timed out')
            logsJsonWithJobs(1)
        }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> virtualNow.addAndGet(ms) })

        when: 'the first call schedules the worker, which fails; the repeat observes the failure'
        def first = script.toolGetHubLogs([mode: 'devices', __reqT0: virtualNow.get()])
        script.runLogsJsonFetch(runInMillisCalls[0][2].data as Map)
        def second = script.toolGetHubLogs([mode: 'devices', __reqT0: virtualNow.get()])

        then: 'the runtime-error contract, not another in_progress, and a replacement worker is queued'
        first.status == 'in_progress'
        second.success == false
        second.isError == true
        second.error == "hub_get_logs could not read the hub's Logs page: logs page timed out"
        second.note.contains('Repeat the identical call once')
        runInMillisCalls.size() == 2

        when: 'a second caller arrives before the retry lands'
        def third = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])

        then: 'it sees the same failure (not a silent in_progress) and does not stack a third worker'
        third.isError == true
        third.error.contains('logs page timed out')
        runInMillisCalls.size() == 2

        when: 'the retry worker succeeds'
        script.runLogsJsonFetch(runInMillisCalls[1][2].data as Map)
        def fourth = script.toolGetHubLogs([mode: 'devices', __reqT0: virtualNow.get()])

        then: 'the failure is cleared by the successful publish'
        attempts.get() == 2
        fourth.uptime == '1d'
        fourth.isError == null
    }

    def "a worker failure with no exception message still reports a failure, naming the exception class"() {
        given:
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        hubGet.register('/logs/json') { params -> throw new IllegalStateException() }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> virtualNow.addAndGet(ms) })

        when:
        script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])
        script.runLogsJsonFetch(runInMillisCalls[0][2].data as Map)
        def result = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])

        then:
        result.isError == true
        result.error.contains('IllegalStateException while fetching /logs/json')
    }

    def "a fetch failure reaches the legacy dispatch envelope as isError"() {
        given:
        hubGet.register('/logs/json') { params -> throw new RuntimeException('boom') }

        when:
        def response = mcpDriver.callTool('hub_get_logs', [mode: 'scheduledJobs'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('boom')
    }

    def "an unexpected /logs/json shape is an error, not an empty hub"() {
        given:
        hubGet.register('/logs/json') { params -> JsonOutput.toJson(body) }

        when:
        def result = script.toolGetHubLogs([mode: 'scheduledJobs'])

        then:
        result.isError == true
        result.error.contains(expected)

        where:
        body                                               | expected
        [error: 'login required']                          | 'no jobs or stats tables'
        [uptime: '1d', jobs: 'nope']                       | "'jobs' is not an array"
        [uptime: '1d', jobs: [[id: 1], 'stray-string']]    | "'jobs' contains a non-object entry"
    }

    def "the snapshot keeps the page's tables and summary fields, without display copies or other top-level keys"() {
        given:
        hubGet.register('/logs/json') { params ->
            logsJsonStats(unusedTopLevel: 'SENTINEL-TOP', maxEvents: 11,
                          deviceStats: [[id: 1, name: 'D', pct: 3.5, formattedPct: 'SENTINEL-FORMATTED', junk: 'native-kept']])
        }

        when:
        script.toolGetHubLogs([mode: 'devices'])
        def cached = (scriptStaticField('LOGS_JSON_SNAPSHOT') as Map).snapshot as Map
        def json = JsonOutput.toJson(cached)

        then:
        cached.deviceStats == [[id: 1, name: 'D', pct: 3.5, junk: 'native-kept']]
        !json.contains('SENTINEL')
        !cached.containsKey('maxEvents')
        cached.keySet().containsAll(['uptime', 'devicesUptime', 'appsUptime', 'jobs', 'runningJobs', 'hubCommands'])
    }

    def "sorting a stats read never mutates the shared cached list"() {
        given:
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([uptime: '1d', appStats: [], jobs: [],
                deviceStats: [[id: 1, name: 'B', count: 1, pct: 9.0], [id: 2, name: 'A', count: 5, pct: 1.0]]])
        }

        when:
        def byPct = script.toolGetHubLogs([mode: 'devices'])
        def byName = script.toolGetHubLogs([mode: 'devices', sortBy: 'name'])
        def byCount = script.toolGetHubLogs([mode: 'devices', sortBy: 'count'])
        def cached = ((scriptStaticField('LOGS_JSON_SNAPSHOT') as Map).snapshot as Map).deviceStats as List

        then:
        byPct.entries*.id == [1, 2]
        byName.entries*.id == [2, 1]
        byCount.entries*.id == [2, 1]
        cached*.id == [1, 2]
    }

    // -------- hub_delete_logs: scheduled jobs --------

    def "hub_delete_logs deletes one scheduled job through the Logs page's own request and drops the cached page"() {
        given:
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(1) }
        def sent = []
        hubGet.register('/hub/advanced/deleteScheduledJob') { params -> sent << params; 'done' }
        script.toolGetHubLogs([mode: 'scheduledJobs'])

        when:
        def result = script.toolDeleteLogs([mode: 'scheduledJobs', jobId: 'app40Recur.5updateData', confirm: true])

        then:
        sent == [[id: 'app40Recur.5updateData']]
        result.success == true
        result.jobId == 'app40Recur.5updateData'
        result.owner == [type: 'app', id: 40]
        result.note.contains('Only this scheduled run is gone')
        !(scriptStaticField('LOGS_JSON_SNAPSHOT') as Map).containsKey('snapshot')
    }

    @Unroll
    def "hub_delete_logs refuses #label before sending anything"() {
        when:
        script.toolDeleteLogs(args)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(says)
        hubGet.calls.empty

        where:
        label                | args                                                                || says
        'no mode'            | [jobId: 'app1Once.run', confirm: true]                              || "mode must be 'mcp'"
        'no jobId'           | [mode: 'scheduledJobs', confirm: true]                              || 'jobId is required'
        'a non-string jobId' | [mode: 'scheduledJobs', jobId: 5, confirm: true]                    || 'jobId must be a string'
        'a malformed jobId'  | [mode: 'scheduledJobs', jobId: 'nodot', confirm: true]              || 'is not a scheduled-job id'
        'a system job'       | [mode: 'scheduledJobs', jobId: 'system.sunJob', confirm: true]      || 'System jobs cannot be deleted'
        'no confirm'         | [mode: 'scheduledJobs', jobId: 'app12Once.run']                     || 'needs confirm=true'
        'jobId on mcp'       | [mode: 'mcp', jobId: 'app1Once.run']                                || "apply to mode='scheduledJobs' only"
        'an owner id too long' | [mode: 'scheduledJobs', jobId: 'app99999999999Recur.x', confirm: true] || 'names no app or device on this hub'
    }

    def "hub_delete_logs refuses a protected app's job, and a client token the MCP app's own, before sending anything"() {
        given:
        settingsMap.protectedAppIds = ['42']
        atomicStateMap.protectedAppsPolicy = [ids: ['42']]

        when:
        script.toolDeleteLogs([mode: 'scheduledJobs', jobId: 'app42Recur.poll', confirm: true])

        then:
        def protectedEx = thrown(IllegalArgumentException)
        protectedEx.message.contains('App 42 is protected')

        when:
        script.callerClient = [id: 'c1', label: 'client']
        script.toolDeleteLogs([mode: 'scheduledJobs', jobId: "app${script.app.id}Once.runLogsJsonFetch".toString(), confirm: true])

        then:
        def selfEx = thrown(IllegalArgumentException)
        selfEx.message.contains("own scheduled jobs")
        hubGet.calls.empty

        cleanup:
        script.callerClient = null
    }

    @Unroll
    def "hub_delete_logs drops the cached Logs page when the hub answers #label, so the read its note names is fresh"() {
        given:
        def deleted = new java.util.concurrent.atomic.AtomicBoolean(false)
        hubGet.register('/logs/json') { params -> deleted.get() ? NO_JOBS : logsJsonWithJobs(1) }
        hubGet.register('/hub/advanced/deleteScheduledJob') { params -> deleted.set(true); reply() }
        script.toolGetHubLogs([mode: 'scheduledJobs'])

        when:
        def result = script.toolDeleteLogs([mode: 'scheduledJobs', jobId: 'app40Recur.poll', confirm: true])
        def listed = script.toolGetHubLogs([mode: 'scheduledJobs'])

        then:
        result.success == false
        result.error.contains(says)
        result.note.contains("hub_get_logs(mode='scheduledJobs')")
        listed.entries == []

        where:
        label                | reply                                     || says
        'with an exception'  | { throw new RuntimeException('timeout') } || 'The delete request failed'
        'job does not exist' | { 'job does not exist' }                  || 'No scheduled job app40Recur.poll exists'
    }

    def "a delete fences off a Logs page fetch already in flight, so its pre-delete copy is never served"() {
        given:
        settingsMap.enableRead = true
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        def gone = new java.util.concurrent.atomic.AtomicBoolean(false)
        hubGet.register('/logs/json') { params -> gone.get() ? NO_JOBS : logsJsonWithJobs(1) }
        hubGet.register('/hub/advanced/deleteScheduledJob') { params -> gone.set(true); 'done' }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> virtualNow.addAndGet(ms) })

        when: 'a read schedules a worker, which fetches before the delete and publishes after it'
        script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])
        Map inFlight = runInMillisCalls[0][2].data as Map
        def deleted = script.toolDeleteLogs([mode: 'scheduledJobs', jobId: 'app40Recur.poll', confirm: true])
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(1) }
        script.runLogsJsonFetch(inFlight)
        hubGet.register('/logs/json') { params -> NO_JOBS }
        def next = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])
        Map replacement = runInMillisCalls[1][2].data as Map
        script.runLogsJsonFetch(replacement)
        def listed = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])

        then: 'the in-flight copy is not published, and the next read schedules a fresh fetch at once'
        deleted.success == true
        next.status == 'in_progress'
        runInMillisCalls.size() == 2
        replacement.fetchId == (inFlight.fetchId as Long) + 2L
        listed.entries == []
    }

    def "a delete during an inline Logs page fetch keeps that fetch's pre-delete copy out of the cache"() {
        given:
        def gone = new java.util.concurrent.atomic.AtomicBoolean(false)
        hubGet.register('/hub/advanced/deleteScheduledJob') { params -> gone.set(true); 'done' }
        hubGet.register('/logs/json') { params ->
            if (gone.get()) return NO_JOBS
            script.toolDeleteLogs([mode: 'scheduledJobs', jobId: 'app40Recur.poll', confirm: true])
            logsJsonWithJobs(1)
        }

        when: 'the delete lands while an unbudgeted read is fetching the page'
        def during = script.toolGetHubLogs([mode: 'scheduledJobs'])
        def next = script.toolGetHubLogs([mode: 'scheduledJobs'])

        then: 'that read answers from its own fetch, and the next read fetches again'
        during.count == 1
        next.entries == []
        runInMillisCalls.isEmpty()
    }

    def "a read waiting on a fetch that a delete fences off schedules the replacement within the same wait"() {
        given:
        settingsMap.enableRead = true
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        hubGet.register('/logs/json') { params -> NO_JOBS }
        hubGet.register('/hub/advanced/deleteScheduledJob') { params -> 'done' }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        def pauses = new java.util.concurrent.atomic.AtomicInteger(0)
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms ->
            virtualNow.addAndGet(ms)
            if (pauses.incrementAndGet() == 1) script.toolDeleteLogs([mode: 'scheduledJobs', jobId: 'app40Recur.poll', confirm: true])
        })

        when:
        script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])

        then:
        runInMillisCalls.size() == 2
        (runInMillisCalls[1][2].data as Map).fetchId == ((runInMillisCalls[0][2].data as Map).fetchId as Long) + 2L
    }

    def "hub_delete_logs refuses a job of a device outside the server's device access, and allows a selected one"() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 5, name: 'Lamp', label: 'Lamp')]
        hubGet.register('/hub/advanced/deleteScheduledJob') { params -> 'done' }

        when:
        script.toolDeleteLogs([mode: 'scheduledJobs', jobId: 'dev615Once.off', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Job dev615Once.off belongs to device 615, which is outside this server's device access")
        hubGet.calls.empty

        when:
        def allowed = script.toolDeleteLogs([mode: 'scheduledJobs', jobId: 'dev5Once.off', confirm: true])

        then:
        allowed.success == true
        allowed.owner == [type: 'device', id: 5]
    }

    @Unroll
    def "hub_delete_logs reports the hub's #reply reply as an error"() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 5, name: 'Lamp', label: 'Lamp')]
        hubGet.register('/hub/advanced/deleteScheduledJob') { params -> reply }

        when:
        def result = script.toolDeleteLogs([mode: 'scheduledJobs', jobId: 'dev5Once.poll', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error.contains(says)

        where:
        reply                || says
        'job does not exist' || 'No scheduled job dev5Once.poll exists'
        'invalid id'         || 'did not delete dev5Once.poll: invalid id'
    }

    @Unroll
    def "hub_delete_logs via dispatch is a write (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/hub/advanced/deleteScheduledJob') { params -> 'done' }

        when:
        def allowed = mcpDriver.callTool('hub_delete_logs', [mode: 'scheduledJobs', jobId: 'app3Recur.poll', confirm: true])
        settingsMap.enableWrite = false
        def blocked = mcpDriver.callTool('hub_delete_logs', [mode: 'scheduledJobs', jobId: 'app3Recur.poll', confirm: true])

        then:
        mcpDriver.parseInner(allowed).success == true
        blocked.result.isError == true
        mcpDriver.parseInner(blocked).error.contains('Write tools are disabled')
        hubGet.calls.count { it.path == '/hub/advanced/deleteScheduledJob' } == 1

        where:
        useGateways << [true, false]
    }

    def "a stale in-flight marker is replaced after 45 s, and the stale worker's late publish is ignored"() {
        given:
        settingsMap.enableRead = true
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        def served = new java.util.concurrent.atomic.AtomicInteger(0)
        hubGet.register('/logs/json') { params -> logsJsonWithJobs(served.incrementAndGet()) }
        def virtualNow = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> virtualNow.addAndGet(ms) })

        when: 'the first worker is scheduled and never runs; 45 s pass; a call schedules a replacement'
        script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])
        Map staleJob = runInMillisCalls[0][2].data as Map
        virtualNow.addAndGet(45000L)
        script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])
        Map freshJob = runInMillisCalls[1][2].data as Map

        then:
        runInMillisCalls.size() == 2
        freshJob.fetchId == (staleJob.fetchId as Long) + 1L

        when: 'the fresh worker lands first, then the stale one finally runs'
        script.runLogsJsonFetch(freshJob)
        def afterFresh = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])
        script.runLogsJsonFetch(staleJob)
        def afterStale = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: virtualNow.get()])
        def cache = scriptStaticField('LOGS_JSON_SNAPSHOT') as Map

        then: 'the stale worker fetched but did not overwrite the fresh snapshot or clear its marker state'
        served.get() == 2
        afterFresh.count == 1
        afterStale.count == 1
        !cache.containsKey('fetchStartedAt')
    }

    def "a scheduler failure rolls back the in-flight marker and surfaces as the tool's error"() {
        given:
        settingsMap.enableRead = true
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        def fetches = new java.util.concurrent.atomic.AtomicInteger(0)
        hubGet.register('/logs/json') { params -> fetches.incrementAndGet(); logsJsonWithJobs(1) }
        def throwOnce = new java.util.concurrent.atomic.AtomicBoolean(true)
        RUN_IN_MILLIS_OVERRIDE.set({ List call ->
            if (throwOnce.getAndSet(false)) throw new IllegalStateException('scheduler saturated')
            runInMillisCalls << call
        })

        when:
        def failed = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: 1234567890000L])
        def cache = scriptStaticField('LOGS_JSON_SNAPSHOT') as Map

        then: 'the failure is reported and no phantom worker is left in flight'
        failed.isError == true
        failed.error.contains('scheduler saturated')
        !cache.containsKey('fetchStartedAt')

        when: 'the next call schedules normally'
        def next = script.toolGetHubLogs([mode: 'scheduledJobs', __reqT0: 1234567890000L])

        then:
        next.status == 'in_progress'
        runInMillisCalls.size() == 1
        runInMillisCalls[0][1] == 'runLogsJsonFetch'
    }

    // -------- toolGetDebugLogs --------

    def "hub_get_logs returns recent entries with metadata"() {
        given:
        seedDebugLogHistory([
            entries: [
                [timestamp: 1234567880000L, level: 'info',  component: 'server', message: 'old'],
                [timestamp: 1234567890000L, level: 'error', component: 'rules',  message: 'boom', ruleId: '5', stackTrace: 'trace']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ])

        when:
        def result = script.toolGetDebugLogs([:])

        then:
        result.count == 2
        result.totalStored == 2
        result.entries[-1].message == 'boom'
        result.entries[-1].ruleId == '5'
        result.entries[-1].stackTrace == 'trace'
        result.currentLogLevel != null
    }

    @spock.lang.Unroll
    def "hub_get_logs via dispatch returns recent entries with metadata (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        seedDebugLogHistory([
            entries: [
                [timestamp: 1234567880000L, level: 'info',  component: 'server', message: 'old'],
                [timestamp: 1234567890000L, level: 'error', component: 'rules',  message: 'boom', ruleId: '5', stackTrace: 'trace']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ])

        when:
        def response = mcpDriver.callTool('hub_get_logs', [mode: 'mcp'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 2
        inner.totalStored == 2
        inner.entries[-1].message == 'boom'

        where:
        useGateways << [true, false]
    }

    def "hub_get_logs filters by level"() {
        given:
        seedDebugLogHistory([
            entries: [
                [timestamp: 1L, level: 'debug', component: 'c', message: 'd1'],
                [timestamp: 2L, level: 'info',  component: 'c', message: 'i1'],
                [timestamp: 3L, level: 'error', component: 'c', message: 'e1']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ])

        when:
        def result = script.toolGetDebugLogs([level: 'error'])

        then:
        result.count == 1
        result.entries[0].message == 'e1'
    }

    def "hub_get_logs filters by ruleId"() {
        given:
        seedDebugLogHistory([
            entries: [
                [timestamp: 1L, level: 'info', component: 'rules', message: 'r42a', ruleId: '42'],
                [timestamp: 2L, level: 'info', component: 'rules', message: 'r99',  ruleId: '99'],
                [timestamp: 3L, level: 'info', component: 'rules', message: 'r42b', ruleId: '42']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ])

        when:
        def result = script.toolGetDebugLogs([ruleId: '42'])

        then:
        result.count == 2
        result.entries*.message == ['r42a', 'r42b']
    }

    def "hub_get_logs filters by component substring"() {
        given:
        seedDebugLogHistory([
            entries: [
                [timestamp: 1L, level: 'info', component: 'server',      message: 's1'],
                [timestamp: 2L, level: 'info', component: 'rules',       message: 'r1'],
                [timestamp: 3L, level: 'info', component: 'rules-eval',  message: 'r2']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ])

        when:
        def result = script.toolGetDebugLogs([component: 'rules'])

        then:
        result.count == 2
        result.entries*.message == ['r1', 'r2']
    }

    def "hub_get_logs caps at limit argument (most recent)"() {
        given:
        seedDebugLogHistory([
            entries: (1..10).collect { i ->
                [timestamp: i as Long, level: 'info', component: 'c', message: "m${i}".toString()]
            },
            config: [logLevel: 'debug', maxEntries: 100]
        ])

        when:
        def result = script.toolGetDebugLogs([limit: 3])

        then:
        result.count == 3
        result.entries*.message == ['m8', 'm9', 'm10']
        result.totalStored == 10
    }

    // -------- mcpLog per-entry payload cap (Q6-reserialize) --------

    def "mcpLog caps stored message at 500 chars and stackTrace at 1000"() {
        given: 'logLevel=debug so an error entry is retained, and an oversized payload'
        settingsMap.mcpLogLevel = 'debug'
        seedDebugLogHistory([entries: [], config: [logLevel: 'debug', maxEntries: 100]])

        when:
        script.mcpLog('error', 'server', 'x' * 2000, null, [stackTrace: 'z' * 5000])

        then: 'the stored entry is bounded at store time'
        def stored = script.getDebugLogEntries()[-1]
        stored.message.length() == 500
        stored.stackTrace.length() == 1000
    }

    def "mcpLog leaves a short message and absent stackTrace untouched"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        seedDebugLogHistory([entries: [], config: [logLevel: 'debug', maxEntries: 100]])

        when:
        script.mcpLog('error', 'server', 'short message')

        then:
        def stored = script.getDebugLogEntries()[-1]
        stored.message == 'short message'
        !stored.containsKey('stackTrace')
    }

    def "hub_delete_logs points past-log deletion at hub_reboot instead of rebooting itself"() {
        when:
        script.toolDeleteLogs([mode: 'past', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('hub_reboot(confirm=true, purgeLogs=true)')
        hubGet.calls.empty
    }

    // -------- hub_delete_logs: the MCP log --------

    def "hub_get_logs mode=mcp reads level in any case"() {
        given:
        script.mcpLog('error', 'server', 'an error')
        script.mcpLog('warn', 'server', 'a warning')

        when:
        def lower = script.toolGetHubLogs([mode: 'mcp', level: 'error', limit: 0])
        def upper = script.toolGetHubLogs([mode: 'mcp', level: 'ERROR', limit: 0])

        then:
        lower.entries*.message == ['an error']
        upper.entries*.message == ['an error']
    }

    def "hub_get_logs mode=mcp with limit 0 returns every stored entry"() {
        given:
        seedDebugLogHistory([
            entries: (1..60).collect { [timestamp: it as Long, level: 'info', component: 'c', message: "m${it}".toString()] },
            config: [logLevel: 'info', maxEntries: 100]
        ])

        when:
        def all = script.toolGetHubLogs([mode: 'mcp', limit: 0])
        def some = script.toolGetHubLogs([mode: 'mcp', limit: 5])

        then:
        all.count == 60
        some.count == 5
    }

    def "hub_delete_logs mode=mcp clears loaded MCP history and reports the count"() {
        given: 'logLevel=debug so the post-clear confirmation mcpLog write is retained'
        seedDebugLogHistory([
            entries: [
                [timestamp: 1L, level: 'info', component: 'c', message: 'a'],
                [timestamp: 2L, level: 'info', component: 'c', message: 'b']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ])

        when:
        def result = script.toolDeleteLogs([mode: 'mcp'])

        then:
        result.success == true
        result.clearedCount == 2
        hubGet.calls.empty

        and: 'the confirmation line is the only entry after the clear'
        script.getDebugLogEntries().size() == 1
        script.getDebugLogEntries()[0].message.contains('MCP log cleared (2 entries)')
    }

    @spock.lang.Unroll
    def "hub_delete_logs mode=mcp via dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        seedDebugLogHistory([
            entries: [[timestamp: 1L, level: 'info', component: 'c', message: 'a']],
            config: [logLevel: 'error', maxEntries: 100]
        ])

        when:
        def response = mcpDriver.callTool('hub_delete_logs', [mode: 'mcp'])

        then:
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        // The seeded entry plus this call's own "write started" audit record; its "finished"
        // record lands after the clear, and the clear's own info line is below the error level.
        inner.clearedCount == 2
        def left = script.getDebugLogEntries()
        left*.component == ['write']
        left[0].details.phase == 'finished'

        where:
        useGateways << [true, false]
    }

    def "hub_delete_logs mode=mcp before history loads reads nothing and leaves old lines unrecoverable"() {
        given: 'a fresh JVM whose MCP history has not been read back from the native log yet'
        seedDebugLogHistory([entries: [], config: [logLevel: 'error', maxEntries: 100]])
        def buffer = script.initDebugLogs()
        String oldGeneration = buffer.generation
        buffer.hydrated = false
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson(["2026-04-19 10:00:00.000\tinfo\tapp|1|MCP|[MCP1] " + JsonOutput.toJson(
                [appId: '1', generation: oldGeneration, id: 'old-1', entry: [timestamp: 1L, level: 'info', component: 'c', message: 'stale']])])
        }

        when:
        def result = script.toolDeleteLogs([mode: 'mcp'])
        def callsAtClear = hubGet.calls.size()

        then:
        result.success == true
        result.clearedCount == null
        callsAtClear == 0
        script.getDebugLogEntries() == []
    }

    // -------- toolSetLogLevel --------

    def "hub_set_log_level throws for an invalid level"() {
        when:
        script.toolSetLogLevel([level: 'trace'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Invalid log level')
    }

    @spock.lang.Unroll
    def "hub_set_log_level via dispatch maps invalid-level IAE to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_log_level', [level: 'trace'])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Invalid log level')

        where:
        useGateways << [true, false]
    }

    def "hub_set_log_level updates state + setting and returns previous level"() {
        given: 'prior log level is info and app.updateSetting routes through the shared TestChildApp stub'
        seedDebugLogHistory([entries: [], config: [logLevel: 'info', maxEntries: 100]])

        when:
        def result = script.toolSetLogLevel([level: 'warn'])

        then:
        result.success == true
        result.previousLevel == 'info'
        result.newLevel == 'warn'
        stateMap.debugLogs.config.logLevel == 'warn'
        sharedAppStub.settingsStore['mcpLogLevel'] == [type: 'enum', value: 'warn']
    }

    @spock.lang.Unroll
    def "hub_set_log_level via dispatch updates state + setting (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        seedDebugLogHistory([entries: [], config: [logLevel: 'info', maxEntries: 100]])

        when:
        def response = mcpDriver.callTool('hub_set_log_level', [level: 'warn'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.previousLevel == 'info'
        inner.newLevel == 'warn'
        stateMap.debugLogs.config.logLevel == 'warn'

        where:
        useGateways << [true, false]
    }

    // -------- toolGetLoggingStatus --------

    def "hub_get_logs(mode:status) reports counts by level + current config"() {
        given:
        seedDebugLogHistory([
            entries: [
                [timestamp: 100L, level: 'debug', component: 'c', message: 'd'],
                [timestamp: 200L, level: 'info',  component: 'c', message: 'i'],
                [timestamp: 300L, level: 'warn',  component: 'c', message: 'w1'],
                [timestamp: 400L, level: 'warn',  component: 'c', message: 'w2'],
                [timestamp: 500L, level: 'error', component: 'c', message: 'e']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ])

        when:
        def result = script.toolGetLoggingStatus([:])

        then:
        result.totalEntries == 5
        result.entriesByLevel.debug == 1
        result.entriesByLevel.info == 1
        result.entriesByLevel.warn == 2
        result.entriesByLevel.error == 1
        result.currentLogLevel == 'debug'
        result.availableLevels == ['debug', 'info', 'warn', 'error']
        result.oldestEntry != null
        result.newestEntry != null
    }

    @spock.lang.Unroll
    def "hub_get_logs(mode:status) via dispatch reports counts by level + current config (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        seedDebugLogHistory([
            entries: [
                [timestamp: 100L, level: 'debug', component: 'c', message: 'd'],
                [timestamp: 200L, level: 'info',  component: 'c', message: 'i'],
                [timestamp: 300L, level: 'warn',  component: 'c', message: 'w1'],
                [timestamp: 400L, level: 'warn',  component: 'c', message: 'w2'],
                [timestamp: 500L, level: 'error', component: 'c', message: 'e']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ])

        when:
        def response = mcpDriver.callTool('hub_get_logs', [mode: 'status'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.totalEntries == 5
        inner.entriesByLevel.debug == 1
        inner.entriesByLevel.warn == 2
        inner.currentLogLevel == 'debug'

        where:
        useGateways << [true, false]
    }

    def "hub_get_logs(mode:status) handles empty buffer"() {
        when:
        def result = script.toolGetLoggingStatus([:])

        then:
        result.totalEntries == 0
        result.oldestEntry == null
        result.newestEntry == null
    }

    def "hub_get_logs(mode:status) omits updateAvailable when the stored flag is stale, surfaces it when a newer version is known"() {
        given:
        stateMap.updateCheck = [latestVersion: "0.0.1", updateAvailable: true]   // older than installed, stale true

        when:
        def result = script.toolGetLoggingStatus([:])

        then:
        !result.containsKey('updateAvailable')

        when: "the update check records a genuinely newer version"
        script.handleUpdateCheckResponse([status: 200, data: '{"version":"999.0.0"}'], null)
        def result2 = script.toolGetLoggingStatus([:])

        then:
        result2.updateAvailable == "999.0.0"
    }

    // get_rule_diagnostics is in the DIAGNOSTICS gateway, covered by
    // ToolManageDiagnosticsSpec rather than this spec.
}
