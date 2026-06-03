package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestChildApp
import support.TestDevice
import support.TestLocation
import support.ToolSpecBase

/**
 * Spec for the hub_manage_logs gateway tools (hubitat-mcp-server.groovy):
 *
 * - toolGetHubLogs         -> hub_get_logs          (additional coverage beyond ToolGetHubLogsSpec)
 * - toolGetDeviceHistory   -> hub_list_device_events (core tool; windowed/location branch)
 * - toolGetPerformanceStats-> hub_get_performance_stats
 * - toolGetHubJobs         -> hub_get_jobs
 * - toolGetDebugLogs       -> hub_get_debug_logs
 * - toolClearDebugLogs     -> hub_delete_debug_logs
 * - toolSetLogLevel        -> hub_set_log_level
 * - toolGetLoggingStatus   -> hub_get_debug_logs(mode:'status')
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet     -> HarnessSpec's hubGet.register(path) closures
 *   - eventsSince(...)   -> per-test metaClass stub on the TestDevice instance
 *   - app.updateSetting  -> the @Shared appExecutor Mock has getApp() stubbed
 *                           once in setupSpec to return sharedAppStub (a
 *                           TestChildApp). HubitatAppScript's @Delegate to
 *                           AppExecutor resolves script.app through this stub
 *                           rather than returning null and NPE'ing when the
 *                           server calls app.updateSetting(...). cleanup()
 *                           clears the settingsStore between tests.
 *   - debug-log state    -> state.debugLogs is a plain Map, seeded in given:
 *
 * Additional hub_get_logs coverage — this spec complements ToolGetHubLogsSpec
 * with level/source/limit/empty-response/deviceId/appId/truncation cases.
 */
class ToolManageLogsSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        // HarnessSpec.setupSpec runs first (Spock auto-invokes superclass
        // fixtures in declaration order). Layering getApp() onto the
        // already-built mock here gives toolSetLogLevel's app.updateSetting
        // call a non-null target. Same additive-stub pattern that
        // ToolRoomsSpec uses for appExecutor.httpPost(_, _).
        appExecutor.getApp() >> sharedAppStub
        // toolGetDeviceHistory's location-scope branch reads location.eventsSince(...);
        // wire it through appExecutor so the server's `location` reference resolves.
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanup() {
        sharedAppStub.settingsStore.clear()
    }

    // -------- toolGetHubLogs (additional coverage) --------

    def "hub_get_logs filters by level=#level"() {
        given:
        settingsMap.enableHubAdminRead = true
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
        result.logs.size() == 1
        result.logs[0].level == level
        result.logs[0].message == expectedMsg

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
        settingsMap.enableHubAdminRead = true
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
        inner.logs.size() == 1
        inner.logs[0].level == 'warn'
        inner.logs[0].message == 'Warn msg'

        where:
        useGateways << [true, false]
    }

    def "hub_get_logs filters by source substring (case-insensitive)"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson([
                'Kitchen Light\tinfo\tSwitch turned on\t2026-04-19 10:00:00.000\ttype',
                'Bedroom Fan\tinfo\tSpeed set\t2026-04-19 10:00:01.000\ttype',
                'Kitchen Motion\tinfo\tMotion detected\t2026-04-19 10:00:02.000\ttype'
            ])
        }

        when:
        def result = script.toolGetHubLogs([source: 'kitchen'])

        then: 'case-insensitive substring match against message or name'
        result.logs.size() == 2
        result.logs.every { it.name.toLowerCase().contains('kitchen') }
    }

    def "hub_get_logs trims to the limit argument (keeping newest)"() {
        given:
        settingsMap.enableHubAdminRead = true
        def lines = (1..10).collect { i ->
            "App 1\tinfo\tMessage ${i}\t2026-04-19 10:00:0${i}.000\ttype".toString()
        }
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(lines) }

        when:
        def result = script.toolGetHubLogs([limit: 3])

        then:
        result.logs.size() == 3
        result.count == 3
        result.logs[0].message == 'Message 10'
        result.logs[1].message == 'Message 9'
        result.logs[2].message == 'Message 8'
    }

    def "hub_get_logs scopes to a selected device and passes type=dev&id=X in query"() {
        given: 'a selected device so findDevice succeeds'
        settingsMap.enableHubAdminRead = true
        def device = new TestDevice(id: 42, name: 'K', label: 'K')
        settingsMap.selectedDevices = [device]
        def capturedParams = null
        hubGet.register('/logs/past/json') { params ->
            capturedParams = params
            JsonOutput.toJson(['K\tinfo\thi\t2026-04-19 10:00:00.000\ttype'])
        }

        when:
        def result = script.toolGetHubLogs([deviceId: '42'])

        then:
        capturedParams == [type: 'dev', id: '42']
        result.logs.size() == 1
    }

    def "hub_get_logs rejects an unknown deviceId before hitting the hub"() {
        given: 'no selected device with id 999'
        settingsMap.enableHubAdminRead = true
        // If the tool ever called hubInternalGet, HubInternalGetMock would throw
        // (unstubbed), so the IllegalArgumentException below proves the pre-HTTP
        // validation fired.

        when:
        script.toolGetHubLogs([deviceId: '999'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Device not found')
    }

    @spock.lang.Unroll
    def "hub_get_logs via dispatch maps unknown-deviceId IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true

        when:
        def response = mcpDriver.callTool('hub_get_logs', [deviceId: '999'])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('Device not found')

        where:
        useGateways << [true, false]
    }

    def "hub_get_logs scopes to an app and passes type=app&id=X in query"() {
        given:
        settingsMap.enableHubAdminRead = true
        def capturedParams = null
        hubGet.register('/logs/past/json') { params ->
            capturedParams = params
            JsonOutput.toJson(['App 7\tinfo\thi\t2026-04-19 10:00:00.000\ttype'])
        }

        when:
        def result = script.toolGetHubLogs([appId: '7'])

        then:
        capturedParams == [type: 'app', id: '7']
        result.logs.size() == 1
    }

    def "hub_get_logs truncates messages when estimated JSON exceeds the 120KB cloud limit"() {
        given: 'many entries with long messages so estimatedJsonSize trips the 120000-byte truncation guard'
        settingsMap.enableHubAdminRead = true
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
        result.logs.every { it.message.length() <= 200 }
    }

    def "hub_get_logs handles empty hub response gracefully"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/past/json') { params -> null }

        when:
        def result = script.toolGetHubLogs([:])

        then:
        result.logs == []
        result.count == 0
        result.message.contains('No log data')
    }

    // -------- toolGetDeviceHistory --------

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
        result.source == 'location'
        result.error.contains('failed')
        result.error.contains('location event store unavailable')
    }

    def "hub_list_device_events throws when device is not found"() {
        when:
        script.toolGetDeviceHistory([deviceId: '999'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Device not found')
    }

    def "hub_list_device_events returns events for a selected device"() {
        given: 'a selected device whose eventsSince returns 2 events'
        def fixedDate = new Date(1234567880000L)
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        def capturedSince = null
        def capturedOpts = null
        device.metaClass.eventsSince = { Date since, Map opts ->
            capturedSince = since
            capturedOpts = opts
            [
                [name: 'switch', value: 'on', unit: null, descriptionText: 'turned on', date: fixedDate, isStateChange: true],
                [name: 'level', value: '75', unit: '%', descriptionText: 'dimmed', date: fixedDate, isStateChange: true]
            ]
        }
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolGetDeviceHistory([deviceId: '42', hoursBack: 12, limit: 50])

        then: 'eventsSince receives the hoursBack-derived sinceDate and the limit via opts.max'
        capturedSince != null
        capturedSince.time == 1234567890000L - (12 * 3600000L)
        capturedOpts == [max: 50]

        and:
        result.deviceId == '42'
        result.device == 'Kitchen Light'
        result.hoursBack == 12
        result.count == 2
        result.events[0].name == 'switch'
        result.events[0].value == 'on'
        result.events[1].name == 'level'
    }

    @spock.lang.Unroll
    def "hub_list_device_events via dispatch returns events for a selected device (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def fixedDate = new Date(1234567880000L)
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        device.metaClass.eventsSince = { Date since, Map opts ->
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
        given: 'no hoursBack and no attribute -> executeTool routes deviceId to toolGetDeviceEvents (device.events(max:))'
        settingsMap.useGateways = useGateways
        def fixedDate = new Date(1234567880000L)
        def device = new TestDevice(id: 42, name: 'Kitchen Light', label: 'Kitchen Light')
        // toolGetDeviceEvents reads device.events(max: limit); TestDevice exposes
        // events only as a List field, so stub the Map-arg call per-instance.
        device.metaClass.events = { Map opts ->
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

        where:
        useGateways << [true, false]
    }

    def "hub_list_device_events applies attribute filter"() {
        given:
        def fixedDate = new Date(1234567880000L)
        def device = new TestDevice(id: 42, name: 'Multi', label: 'Multi')
        device.metaClass.eventsSince = { Date since, Map opts ->
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

    def "hub_list_device_events returns an error map when eventsSince throws"() {
        given:
        def device = new TestDevice(id: 42, name: 'Broken', label: 'Broken')
        device.metaClass.eventsSince = { Date since, Map opts ->
            throw new RuntimeException('event store unavailable')
        }
        settingsMap.selectedDevices = [device]

        when:
        def result = script.toolGetDeviceHistory([deviceId: '42'])

        then: 'the thrown message is preserved in the "failed" half so a caller can debug'
        result.error.contains('failed')
        result.error.contains('event store unavailable')
        result.deviceId == '42'
    }

    // -------- toolGetPerformanceStats --------

    def "hub_get_performance_stats returns device stats sorted by pct (default)"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([
                uptime: '5d 2h',
                totalDevicesRuntime: 1000,
                devicePct: 12.5,
                totalAppsRuntime: 500,
                appPct: 6.25,
                deviceStats: [
                    [id: 1, name: 'SlowDevice', pct: 15.0, count: 100, total: 500, stateSize: 1000, formattedPct: '15.00', formattedPctTotal: '50.00'],
                    [id: 2, name: 'FastDevice', pct: 2.0, count: 5, total: 10, stateSize: 100, formattedPct: '2.00', formattedPctTotal: '1.00']
                ],
                appStats: []
            ])
        }

        when:
        def result = script.toolGetPerformanceStats([:])

        then: 'sorted descending by pct'
        result.deviceStats.size() == 2
        result.deviceStats[0].name == 'SlowDevice'
        result.deviceStats[1].name == 'FastDevice'
        result.deviceSummary.deviceCount == 2
        result.uptime == '5d 2h'
    }

    @spock.lang.Unroll
    def "hub_get_performance_stats via dispatch returns sorted device stats (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([
                uptime: '5d 2h',
                deviceStats: [
                    [id: 1, name: 'SlowDevice', pct: 15.0, count: 100, total: 500, stateSize: 1000, formattedPct: '15.00'],
                    [id: 2, name: 'FastDevice', pct: 2.0, count: 5, total: 10, stateSize: 100, formattedPct: '2.00']
                ],
                appStats: []
            ])
        }

        when:
        def response = mcpDriver.callTool('hub_get_performance_stats', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.deviceStats.size() == 2
        inner.deviceStats[0].name == 'SlowDevice'
        inner.deviceStats[1].name == 'FastDevice'

        where:
        useGateways << [true, false]
    }

    def "hub_get_performance_stats sortBy=count reorders results"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([
                uptime: 'x',
                deviceStats: [
                    [id: 1, name: 'Few', count: 2, pct: 50.0, total: 100, formattedPct: '50.00'],
                    [id: 2, name: 'Many', count: 200, pct: 1.0, total: 50, formattedPct: '1.00']
                ],
                appStats: []
            ])
        }

        when:
        def result = script.toolGetPerformanceStats([sortBy: 'count'])

        then:
        result.deviceStats[0].name == 'Many'
        result.deviceStats[1].name == 'Few'
    }

    def "hub_get_performance_stats type=app returns only app stats"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([
                uptime: 'x',
                totalAppsRuntime: 100,
                appPct: 5.0,
                deviceStats: [[id: 1, name: 'D', pct: 1.0, formattedPct: '1.00']],
                appStats: [[id: 99, name: 'MyApp', pct: 5.0, count: 10, total: 200, formattedPct: '5.00']]
            ])
        }

        when:
        def result = script.toolGetPerformanceStats([type: 'app'])

        then:
        result.appStats.size() == 1
        result.appStats[0].name == 'MyApp'
        result.deviceStats == null
        result.appSummary.appCount == 1
    }

    def "hub_get_performance_stats surfaces the largeState flag on flagged entries"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([
                uptime: 'x',
                deviceStats: [
                    [id: 1, name: 'FatState', pct: 10.0, count: 50, total: 100, stateSize: 50000, formattedPct: '10.00', largeState: true],
                    [id: 2, name: 'LeanState', pct: 1.0, count: 5, total: 10, stateSize: 100, formattedPct: '1.00']
                ],
                appStats: []
            ])
        }

        when:
        def result = script.toolGetPerformanceStats([:])

        then:
        result.deviceStats[0].name == 'FatState'
        result.deviceStats[0].largeState == true
        result.deviceStats[1].name == 'LeanState'
        !result.deviceStats[1].containsKey('largeState')
    }

    def "hub_get_performance_stats limit=0 returns all entries with the unlimited-response note"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([
                uptime: 'x',
                deviceStats: [[id: 1, name: 'A', pct: 1.0, formattedPct: '1.00']],
                appStats: [[id: 2, name: 'B', pct: 1.0, formattedPct: '1.00']]
            ])
        }

        when:
        def result = script.toolGetPerformanceStats([type: 'both', limit: 0])

        then:
        result.note?.contains('Returning all')
        result.note?.contains('2 entries')
    }

    def "hub_get_performance_stats returns an error map when fetchLogsJson throws (Hub Admin Read gate is indirect)"() {
        // toolGetPerformanceStats has no direct requireHubAdminRead() call;
        // the gate fires deeper inside fetchLogsJson (server ~line 5508)
        // and the IllegalArgumentException is caught and wrapped into the
        // returned error map at ~line 5524. Asserting "Hub Admin Read"
        // in the error pins the current wrapping, not a public-surface gate.
        when:
        def result = script.toolGetPerformanceStats([:])

        then:
        result.error.contains('Hub Admin Read')
    }

    // -------- toolGetHubJobs --------

    def "hub_get_jobs returns scheduled + running + hubActions"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([
                uptime: '1d',
                jobs: [
                    [id: 1, name: 'NightlyBackup', recurring: true, methodName: 'runBackup', nextRun: '2026-04-21 02:00:00'],
                    [id: 2, name: 'Poll',          recurring: true, methodName: 'poll',      nextRun: '2026-04-21 10:00:00']
                ],
                runningJobs: [
                    [id: 3, name: 'HubCheck', methodName: 'checkHealth']
                ],
                hubCommands: ['cmd1', 'cmd2', 'cmd3']
            ])
        }

        when:
        def result = script.toolGetHubJobs([:])

        then:
        result.scheduledJobs.count == 2
        result.scheduledJobs.jobs[0].name == 'NightlyBackup'
        result.runningJobs.count == 1
        result.hubActions.count == 3
        result.uptime == '1d'
    }

    @spock.lang.Unroll
    def "hub_get_jobs via dispatch returns scheduled + running + hubActions (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/json') { params ->
            JsonOutput.toJson([
                uptime: '1d',
                jobs: [
                    [id: 1, name: 'NightlyBackup', recurring: true, methodName: 'runBackup', nextRun: '2026-04-21 02:00:00']
                ],
                runningJobs: [
                    [id: 3, name: 'HubCheck', methodName: 'checkHealth']
                ],
                hubCommands: ['cmd1', 'cmd2']
            ])
        }

        when:
        def response = mcpDriver.callTool('hub_get_jobs', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.scheduledJobs.count == 1
        inner.scheduledJobs.jobs[0].name == 'NightlyBackup'
        inner.runningJobs.count == 1
        inner.hubActions.count == 2
        inner.uptime == '1d'

        where:
        useGateways << [true, false]
    }

    def "hub_get_jobs returns an error map when fetchLogsJson fails"() {
        when: 'Hub Admin Read disabled — fetchLogsJson throws inside the tool'
        def result = script.toolGetHubJobs([:])

        then:
        result.error.contains('Hub Admin Read')
    }

    // -------- toolGetDebugLogs --------

    def "hub_get_debug_logs returns recent entries with metadata"() {
        given:
        stateMap.debugLogs = [
            entries: [
                [timestamp: 1234567880000L, level: 'info',  component: 'server', message: 'old'],
                [timestamp: 1234567890000L, level: 'error', component: 'rules',  message: 'boom', ruleId: '5', stackTrace: 'trace']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

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
    def "hub_get_debug_logs via dispatch returns recent entries with metadata (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        stateMap.debugLogs = [
            entries: [
                [timestamp: 1234567880000L, level: 'info',  component: 'server', message: 'old'],
                [timestamp: 1234567890000L, level: 'error', component: 'rules',  message: 'boom', ruleId: '5', stackTrace: 'trace']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def response = mcpDriver.callTool('hub_get_debug_logs', [:])

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

    def "hub_get_debug_logs filters by level"() {
        given:
        stateMap.debugLogs = [
            entries: [
                [timestamp: 1L, level: 'debug', component: 'c', message: 'd1'],
                [timestamp: 2L, level: 'info',  component: 'c', message: 'i1'],
                [timestamp: 3L, level: 'error', component: 'c', message: 'e1']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def result = script.toolGetDebugLogs([level: 'error'])

        then:
        result.count == 1
        result.entries[0].message == 'e1'
    }

    def "hub_get_debug_logs filters by ruleId"() {
        given:
        stateMap.debugLogs = [
            entries: [
                [timestamp: 1L, level: 'info', component: 'rules', message: 'r42a', ruleId: '42'],
                [timestamp: 2L, level: 'info', component: 'rules', message: 'r99',  ruleId: '99'],
                [timestamp: 3L, level: 'info', component: 'rules', message: 'r42b', ruleId: '42']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def result = script.toolGetDebugLogs([ruleId: '42'])

        then:
        result.count == 2
        result.entries*.message == ['r42a', 'r42b']
    }

    def "hub_get_debug_logs filters by component substring"() {
        given:
        stateMap.debugLogs = [
            entries: [
                [timestamp: 1L, level: 'info', component: 'server',      message: 's1'],
                [timestamp: 2L, level: 'info', component: 'rules',       message: 'r1'],
                [timestamp: 3L, level: 'info', component: 'rules-eval',  message: 'r2']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def result = script.toolGetDebugLogs([component: 'rules'])

        then:
        result.count == 2
        result.entries*.message == ['r1', 'r2']
    }

    def "hub_get_debug_logs caps at limit argument (most recent)"() {
        given:
        stateMap.debugLogs = [
            entries: (1..10).collect { i ->
                [timestamp: i as Long, level: 'info', component: 'c', message: "m${i}".toString()]
            },
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def result = script.toolGetDebugLogs([limit: 3])

        then:
        result.count == 3
        result.entries*.message == ['m8', 'm9', 'm10']
        result.totalStored == 10
    }

    // -------- toolClearDebugLogs --------

    def "hub_delete_debug_logs empties state.debugLogs.entries and reports count"() {
        given: 'logLevel=debug so the post-clear confirmation mcpLog write is retained'
        stateMap.debugLogs = [
            entries: [
                [timestamp: 1L, level: 'info', component: 'c', message: 'a'],
                [timestamp: 2L, level: 'info', component: 'c', message: 'b']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def result = script.toolClearDebugLogs([:])

        then:
        result.success == true
        result.clearedCount == 2

        and: 'the tool logs a confirmation entry AFTER clearing — `entries` now holds only that line, not the 2 pre-clear entries'
        stateMap.debugLogs.entries.size() == 1
        stateMap.debugLogs.entries[0].level == 'info'
        stateMap.debugLogs.entries[0].message.contains('cleared')
        stateMap.debugLogs.entries[0].message.contains('(2 entries removed)')
    }

    @spock.lang.Unroll
    def "hub_delete_debug_logs via dispatch empties entries and reports count (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        stateMap.debugLogs = [
            entries: [
                [timestamp: 1L, level: 'info', component: 'c', message: 'a'],
                [timestamp: 2L, level: 'info', component: 'c', message: 'b']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def response = mcpDriver.callTool('hub_delete_debug_logs', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.clearedCount == 2

        where:
        useGateways << [true, false]
    }

    def "hub_delete_debug_logs succeeds when there are no entries"() {
        given: 'explicit logLevel=error so the post-clear info log is below threshold — pinned against initDebugLogs default drift'
        stateMap.debugLogs = [entries: [], config: [logLevel: 'error', maxEntries: 100]]

        when:
        def result = script.toolClearDebugLogs([:])

        then:
        result.success == true
        result.clearedCount == 0
        stateMap.debugLogs.entries == []
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
    def "hub_set_log_level via dispatch maps invalid-level IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_log_level', [level: 'trace'])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('Invalid log level')

        where:
        useGateways << [true, false]
    }

    def "hub_set_log_level updates state + setting and returns previous level"() {
        given: 'prior log level is info and app.updateSetting routes through the shared TestChildApp stub'
        stateMap.debugLogs = [entries: [], config: [logLevel: 'info', maxEntries: 100]]

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
        stateMap.debugLogs = [entries: [], config: [logLevel: 'info', maxEntries: 100]]

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

    def "hub_get_debug_logs(mode:status) reports counts by level + current config"() {
        given:
        stateMap.debugLogs = [
            entries: [
                [timestamp: 100L, level: 'debug', component: 'c', message: 'd'],
                [timestamp: 200L, level: 'info',  component: 'c', message: 'i'],
                [timestamp: 300L, level: 'warn',  component: 'c', message: 'w1'],
                [timestamp: 400L, level: 'warn',  component: 'c', message: 'w2'],
                [timestamp: 500L, level: 'error', component: 'c', message: 'e']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

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
    def "hub_get_debug_logs(mode:status) via dispatch reports counts by level + current config (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        stateMap.debugLogs = [
            entries: [
                [timestamp: 100L, level: 'debug', component: 'c', message: 'd'],
                [timestamp: 200L, level: 'info',  component: 'c', message: 'i'],
                [timestamp: 300L, level: 'warn',  component: 'c', message: 'w1'],
                [timestamp: 400L, level: 'warn',  component: 'c', message: 'w2'],
                [timestamp: 500L, level: 'error', component: 'c', message: 'e']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def response = mcpDriver.callTool('hub_get_debug_logs', [mode: 'status'])

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

    def "hub_get_debug_logs(mode:status) handles empty buffer"() {
        when:
        def result = script.toolGetLoggingStatus([:])

        then:
        result.totalEntries == 0
        result.oldestEntry == null
        result.newestEntry == null
    }

    // get_rule_diagnostics is in the DIAGNOSTICS gateway, covered by
    // ToolManageDiagnosticsSpec rather than this spec.
}
