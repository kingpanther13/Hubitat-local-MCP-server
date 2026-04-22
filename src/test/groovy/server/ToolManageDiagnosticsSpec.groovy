package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestChildApp
import support.TestDevice
import support.TestLocation
import support.ToolSpecBase

/**
 * Spec for the manage_diagnostics gateway tools (hubitat-mcp-server.groovy):
 *
 * - toolGetHubPerformance   -> get_set_hub_metrics
 * - toolGetMemoryHistory    -> get_memory_history
 * - toolForceGarbageCollection -> force_garbage_collection
 * - toolDeviceHealthCheck   -> device_health_check
 * - toolGetRuleDiagnostics  -> get_rule_diagnostics
 * - toolGetZwaveDetails     -> get_zwave_details
 * - toolGetZigbeeDetails    -> get_zigbee_details
 * - toolZwaveRepair         -> zwave_repair        (Hub Admin Write + confirm)
 * - toolListCapturedStates  -> list_captured_states
 * - toolDeleteCapturedState -> delete_captured_state
 * - toolClearCapturedStates -> clear_captured_states
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet      -> HarnessSpec's hubGet.register(path) closures
 *   - hubInternalPost     -> stubbed per-test on script.metaClass
 *   - downloadHubFile /
 *     uploadHubFile       -> stubbed per-test on script.metaClass
 *   - location.hub        -> appExecutor.getLocation() returns sharedLocation;
 *                            individual tests set `sharedLocation.hub = [...]`
 *                            to drive location.hub reads (e.g. zwaveVersion,
 *                            zigbeeChannel, uptime)
 *   - app (for completeness; this gateway doesn't call app.updateSetting)
 *
 * NOTE on clear_captured_states / delete_captured_state: the current server
 * source (hubitat-mcp-server.groovy) does not gate these on confirm=true —
 * issue #74 lists them as "test confirm gating" but the dispatch is direct
 * through clearAllCapturedStates() / deleteCapturedState(stateId). Tests
 * here pin the existing no-confirm behaviour; adding a confirm gate would
 * be a separate design change.
 */
class ToolManageDiagnosticsSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanup() {
        sharedLocation.hub = null
    }

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
    }

    // -------- toolGetHubPerformance (get_set_hub_metrics) --------

    def "get_set_hub_metrics throws when Hub Admin Read is disabled"() {
        when:
        script.toolGetHubPerformance([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "get_set_hub_metrics snapshots memory/temp/db and records a CSV row"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = [uptime: 172800L]  // 2 days
        hubGet.register('/hub/advanced/freeOSMemory') { params -> '123456' }
        hubGet.register('/hub/advanced/internalTempCelsius') { params -> '45.5' }
        hubGet.register('/hub/advanced/databaseSize') { params -> '200000' }

        and: 'no existing CSV history on disk'
        script.metaClass.downloadHubFile = { String fileName -> null }

        and: 'uploadHubFile captures what we would have written'
        def uploaded = [:]
        script.metaClass.uploadHubFile = { String fileName, byte[] content ->
            uploaded[fileName] = new String(content, 'UTF-8')
        }

        when:
        def result = script.toolGetHubPerformance([:])

        then:
        result.current.freeMemoryKB == '123456'
        result.current.internalTempC == '45.5'
        result.current.databaseSizeKB == '200000'
        result.current.uptimeSeconds == 172800L
        result.current.uptimeFormatted == '2d 0h 0m'
        result.historyFile == 'mcp-performance-history.csv'
        result.trendPointsAvailable == 1

        and: 'the CSV snapshot was written to File Manager'
        uploaded['mcp-performance-history.csv']?.contains('freeMemoryKB')
        uploaded['mcp-performance-history.csv']?.contains('123456')
    }

    def "get_set_hub_metrics warns on low memory"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = [uptime: 3600L]
        hubGet.register('/hub/advanced/freeOSMemory') { params -> '40000' }  // <50000 triggers warning
        hubGet.register('/hub/advanced/internalTempCelsius') { params -> '40' }
        hubGet.register('/hub/advanced/databaseSize') { params -> '100000' }
        script.metaClass.downloadHubFile = { String fileName -> null }
        script.metaClass.uploadHubFile = { String fileName, byte[] content -> }

        when:
        def result = script.toolGetHubPerformance([recordSnapshot: false])

        then:
        result.current.memoryWarning.contains('LOW MEMORY')
        result.current.memoryWarning.contains('40000')
    }

    def "get_set_hub_metrics respects recordSnapshot=false (no CSV write)"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = [uptime: 0L]
        hubGet.register('/hub/advanced/freeOSMemory') { params -> '100000' }
        hubGet.register('/hub/advanced/internalTempCelsius') { params -> '50' }
        hubGet.register('/hub/advanced/databaseSize') { params -> '100000' }
        script.metaClass.downloadHubFile = { String fileName -> null }
        def uploadCalled = false
        script.metaClass.uploadHubFile = { String fileName, byte[] content -> uploadCalled = true }

        when:
        def result = script.toolGetHubPerformance([recordSnapshot: false])

        then:
        !uploadCalled
        result.trendPointsAvailable == 0
    }

    // -------- toolGetMemoryHistory --------

    def "get_memory_history throws when Hub Admin Read is disabled"() {
        when:
        script.toolGetMemoryHistory([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "get_memory_history parses CSV rows and computes summary"() {
        given:
        settingsMap.enableHubAdminRead = true
        def csv = [
            'Date/time,Free OS,5m CPU avg,Total Java,Free Java,Direct Java',
            '2026-04-19 10:00,200000,0.5,500000,100000,2000',
            '2026-04-19 10:05,150000,0.6,500000, 90000,2100',
            '2026-04-19 10:10,100000,0.9,500000, 80000,2200'
        ].join('\n')
        hubGet.register('/hub/advanced/freeOSMemoryHistory') { params -> csv }

        when:
        def result = script.toolGetMemoryHistory([:])

        then:
        result.entries.size() == 3
        result.summary.totalEntries == 3
        result.summary.currentMemoryKB == 100000
        result.summary.minMemoryKB == 100000
        result.summary.maxMemoryKB == 200000
        result.summary.avgMemoryKB == 150000
        result.summary.totalJavaKB == 500000
        result.summary.freeJavaKB == 80000
        result.summary.directJavaKB == 2200
        result.summary.directJavaMinKB == 2000
        result.summary.directJavaMaxKB == 2200
    }

    def "get_memory_history emits low-memory warning when current is below 50000"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub/advanced/freeOSMemoryHistory') { params ->
            'Date/time,Free OS,5m CPU avg\n2026-04-19 10:00,30000,0.5\n'
        }

        when:
        def result = script.toolGetMemoryHistory([:])

        then:
        result.summary.memoryWarning.contains('LOW MEMORY')
        result.summary.memoryWarning.contains('30000')
    }

    def "get_memory_history applies limit to entries but keeps summary on full set"() {
        given:
        settingsMap.enableHubAdminRead = true
        def rows = (1..10).collect { i -> "2026-04-19 10:0${i},${100000 + i * 1000},0.${i}".toString() }
        hubGet.register('/hub/advanced/freeOSMemoryHistory') { params ->
            (['Date/time,Free OS,5m CPU avg'] + rows).join('\n')
        }

        when:
        def result = script.toolGetMemoryHistory([limit: 3])

        then:
        result.entries.size() == 3
        result.summary.totalEntries == 10
        result.summary.truncated == true
        result.summary.showing.contains('3 of 10')
    }

    def "get_memory_history handles empty response"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub/advanced/freeOSMemoryHistory') { params -> null }

        when:
        def result = script.toolGetMemoryHistory([:])

        then:
        result.entries == []
        result.summary.message.contains('No memory history')
    }

    // -------- toolForceGarbageCollection --------

    def "force_garbage_collection throws when Hub Admin Read is disabled"() {
        when:
        script.toolForceGarbageCollection([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "force_garbage_collection triggers GC and reports before/after"() {
        given:
        settingsMap.enableHubAdminRead = true
        def beforeAfter = ['90000', '120000']
        def idx = 0
        hubGet.register('/hub/advanced/freeOSMemory') { params -> beforeAfter[idx++] }
        def gcCalled = false
        hubGet.register('/hub/forceGC') { params -> gcCalled = true; '' }

        when:
        def result = script.toolForceGarbageCollection([:])

        then:
        gcCalled
        result.beforeFreeMemoryKB == 90000
        result.afterFreeMemoryKB == 120000
        result.deltaKB == 30000
        result.memoryReclaimed == true
        result.summary.contains('90000KB → 120000KB')
        result.summary.contains('+30000KB')
    }

    def "force_garbage_collection reports 'could not read' summary when memory probes fail"() {
        given:
        settingsMap.enableHubAdminRead = true
        hubGet.register('/hub/advanced/freeOSMemory') { params -> 'not a number' }
        hubGet.register('/hub/forceGC') { params -> '' }

        when:
        def result = script.toolForceGarbageCollection([:])

        then:
        result.beforeFreeMemoryKB == null
        result.afterFreeMemoryKB == null
        result.summary.contains('could not read memory values')
    }

    // -------- toolDeviceHealthCheck --------

    def "device_health_check returns empty summary when no devices are selected"() {
        when:
        def result = script.toolDeviceHealthCheck([:])

        then:
        result.message.contains('No devices selected')
        result.summary.totalDevices == 0
    }

    def "device_health_check classifies devices as healthy, stale, and unknown"() {
        given:
        def nowMs = 1234567890000L
        def fresh = new TestDevice(id: 1, name: 'Fresh', label: 'Fresh Sensor')
        fresh.metaClass.getLastActivity = { -> new Date(nowMs - 3600000L) }  // 1h ago
        def stale = new TestDevice(id: 2, name: 'Stale', label: 'Stale Sensor')
        stale.metaClass.getLastActivity = { -> new Date(nowMs - (48 * 3600000L)) }  // 48h ago
        def never = new TestDevice(id: 3, name: 'Never', label: 'Never-reported')
        never.metaClass.getLastActivity = { -> null }
        settingsMap.selectedDevices = [fresh, stale, never]

        when:
        def result = script.toolDeviceHealthCheck([staleHours: 24])

        then:
        result.summary.totalDevices == 3
        result.summary.healthyCount == 1
        result.summary.staleCount == 1
        result.summary.unknownCount == 1
        result.staleDevices[0].name == 'Stale Sensor'
        result.unknownDevices[0].lastActivity == 'never'
        result.recommendation.contains('1 stale and 1 unknown')
    }

    def "device_health_check includeHealthy=true attaches healthyDevices list"() {
        given:
        def nowMs = 1234567890000L
        def fresh = new TestDevice(id: 1, name: 'Fresh', label: 'Fresh Sensor')
        fresh.metaClass.getLastActivity = { -> new Date(nowMs - 600000L) }
        settingsMap.selectedDevices = [fresh]

        when:
        def result = script.toolDeviceHealthCheck([includeHealthy: true])

        then:
        result.healthyDevices.size() == 1
        result.healthyDevices[0].name == 'Fresh Sensor'
    }

    // -------- toolGetRuleDiagnostics --------

    def "get_rule_diagnostics throws when the ruleId is not found"() {
        when:
        script.toolGetRuleDiagnostics([ruleId: 'nope'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found')
    }

    def "get_rule_diagnostics returns rule structure + recent logs + errors"() {
        given: 'a MCP rule child app with 2 triggers / 1 condition / 3 actions'
        def rule = new TestChildApp(id: 42L, label: 'My Rule')
        rule.ruleData = [
            id: 42L, name: 'My Rule', description: 'desc',
            enabled: true, createdAt: 1000L, updatedAt: 2000L,
            executionCount: 5, lastTriggered: 3000L,
            triggers: [[type: 'device'], [type: 'time']],
            conditions: [[op: '>']],
            actions: [[cmd: 'on'], [cmd: 'off'], [cmd: 'setLevel']],
            conditionLogic: 'all',
            localVariables: [counter: 3]
        ]
        childAppsList << rule

        and: 'some recent + error debug logs for this rule'
        stateMap.debugLogs = [
            entries: [
                [timestamp: 1L, level: 'info',  component: 'rules', message: 'ran', ruleId: '42'],
                [timestamp: 2L, level: 'error', component: 'rules', message: 'boom', ruleId: '42', stackTrace: 't1'],
                [timestamp: 3L, level: 'info',  component: 'rules', message: 'other', ruleId: '99']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def result = script.toolGetRuleDiagnostics([ruleId: '42'])

        then:
        result.rule.id == 42L
        result.rule.name == 'My Rule'
        result.execution.count == 5
        result.structure.triggerCount == 2
        result.structure.conditionCount == 1
        result.structure.actionCount == 3
        result.structure.conditionLogic == 'all'
        result.logs.recentCount == 2
        result.logs.errorCount == 1
        result.logs.errors[0].message == 'boom'
    }

    // -------- toolGetZwaveDetails --------

    def "get_zwave_details throws when Hub Admin Read is disabled"() {
        when:
        script.toolGetZwaveDetails([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "get_zwave_details combines hub-SDK zwaveVersion with parsed /hub/zwaveDetails/json"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = [zwaveVersion: '7.17.1']
        hubGet.register('/hub/zwaveDetails/json') { params ->
            JsonOutput.toJson([firmware: '7.17.1', sdkVersion: '6.82', deviceCount: 12])
        }

        when:
        def result = script.toolGetZwaveDetails([:])

        then:
        result.zwaveVersion == '7.17.1'
        result.source == 'hub_api'
        result.endpoint == '/hub/zwaveDetails/json'
        result.zwaveData.firmware == '7.17.1'
        result.zwaveData.deviceCount == 12
    }

    def "get_zwave_details falls back to sdk_only when all zwave endpoints fail"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = [zwaveVersion: '7.0.0']
        // No registered endpoints for /hub/zwaveDetails/json or /hub2/zwaveInfo —
        // HubInternalGetMock throws, which the tool catches per endpoint.

        when:
        def result = script.toolGetZwaveDetails([:])

        then:
        result.source == 'sdk_only'
        result.note.contains('unavailable')
        result.zwaveVersion == '7.0.0'
    }

    def "get_zwave_details captures raw response when endpoint returns non-JSON"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = [zwaveVersion: '7.0.0']
        hubGet.register('/hub/zwaveDetails/json') { params -> '<html>not json</html>' }

        when:
        def result = script.toolGetZwaveDetails([:])

        then:
        result.source == 'hub_api_raw'
        result.rawResponse.contains('not json')
        result.note.contains('not JSON')
    }

    // -------- toolGetZigbeeDetails --------

    def "get_zigbee_details throws when Hub Admin Read is disabled"() {
        when:
        script.toolGetZigbeeDetails([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "get_zigbee_details returns channel + zigbeeId + parsed details"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = [zigbeeChannel: 25, zigbeeId: '0x1234']
        hubGet.register('/hub/zigbeeDetails/json') { params ->
            JsonOutput.toJson([panId: '0xABCD', channel: 25, deviceCount: 7])
        }

        when:
        def result = script.toolGetZigbeeDetails([:])

        then:
        result.zigbeeChannel == 25
        result.zigbeeId == '0x1234'
        result.source == 'hub_api'
        result.zigbeeData.panId == '0xABCD'
        result.zigbeeData.deviceCount == 7
    }

    def "get_zigbee_details falls back to sdk_only when all endpoints fail"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = [zigbeeChannel: 20, zigbeeId: '0xAAAA']

        when:
        def result = script.toolGetZigbeeDetails([:])

        then:
        result.source == 'sdk_only'
        result.zigbeeChannel == 20
    }

    // -------- toolZwaveRepair (DESTRUCTIVE: confirm + Hub Admin Write gate) --------

    def "zwave_repair throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolZwaveRepair([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "zwave_repair throws when Hub Admin Write is disabled"() {
        when:
        script.toolZwaveRepair([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "zwave_repair throws when no recent backup exists"() {
        given:
        settingsMap.enableHubAdminWrite = true
        // No stateMap.lastBackupTimestamp

        when:
        script.toolZwaveRepair([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BACKUP REQUIRED')
    }

    def "zwave_repair posts to /hub/zwaveRepair and reports success with warning"() {
        given:
        enableHubAdminWrite()
        def postedPath = null
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            postedPath = path
            'repair started'
        }

        when:
        def result = script.toolZwaveRepair([confirm: true])

        then:
        postedPath == '/hub/zwaveRepair'
        result.success == true
        result.message.contains('Z-Wave network repair')
        result.warning.contains('unresponsive')
        result.duration.contains('5-30 minutes')
        result.response == 'repair started'
    }

    def "zwave_repair reports failure without throwing when POST throws"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            throw new RuntimeException('hub unreachable')
        }

        when:
        def result = script.toolZwaveRepair([confirm: true])

        then:
        result.success == false
        result.error.contains('Z-Wave repair failed')
        result.error.contains('hub unreachable')
    }

    // -------- toolListCapturedStates --------

    def "list_captured_states returns an empty list with count when state is empty"() {
        when:
        def result = script.toolListCapturedStates()

        then:
        result.capturedStates == []
        result.count == 0
        result.maxLimit != null
    }

    def "list_captured_states returns entries sorted newest-first"() {
        given:
        stateMap.capturedDeviceStates = [
            older: [devices: [[id: '1', name: 'D1']], timestamp: 1000L, deviceCount: 1],
            newer: [devices: [[id: '2', name: 'D2'], [id: '3', name: 'D3']], timestamp: 5000L, deviceCount: 2]
        ]

        when:
        def result = script.toolListCapturedStates()

        then:
        result.count == 2
        result.capturedStates[0].stateId == 'newer'
        result.capturedStates[0].deviceCount == 2
        result.capturedStates[1].stateId == 'older'
    }

    def "list_captured_states surfaces an at-capacity warning"() {
        given: 'maxCapturedStates default is 20; seed exactly 20 entries'
        settingsMap.maxCapturedStates = 5
        stateMap.capturedDeviceStates = (1..5).collectEntries { i ->
            ["s${i}".toString(), [devices: [], timestamp: (i * 1000L), deviceCount: 0]]
        }

        when:
        def result = script.toolListCapturedStates()

        then:
        result.count == 5
        result.warning.contains('maximum capacity')
    }

    // -------- toolDeleteCapturedState --------

    def "delete_captured_state throws when stateId is missing"() {
        when:
        script.toolDeleteCapturedState(null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('stateId is required')
    }

    def "delete_captured_state removes the specified entry"() {
        given:
        stateMap.capturedDeviceStates = [
            keep: [devices: [], timestamp: 1000L, deviceCount: 0],
            gone: [devices: [], timestamp: 2000L, deviceCount: 0]
        ]

        when:
        def result = script.toolDeleteCapturedState('gone')

        then:
        result.success == true
        result.remaining == 1
        stateMap.capturedDeviceStates.containsKey('keep')
        !stateMap.capturedDeviceStates.containsKey('gone')
    }

    def "delete_captured_state reports not-found without throwing"() {
        given:
        stateMap.capturedDeviceStates = [keep: [devices: [], timestamp: 1000L, deviceCount: 0]]

        when:
        def result = script.toolDeleteCapturedState('absent')

        then:
        result.success == false
        result.message.contains("'absent' not found")
    }

    // -------- toolClearCapturedStates --------

    def "clear_captured_states empties state.capturedDeviceStates and returns count"() {
        given:
        stateMap.capturedDeviceStates = [
            a: [devices: [], timestamp: 1000L, deviceCount: 0],
            b: [devices: [], timestamp: 2000L, deviceCount: 0],
            c: [devices: [], timestamp: 3000L, deviceCount: 0]
        ]

        when:
        def result = script.toolClearCapturedStates()

        then:
        result.success == true
        result.cleared == 3
        stateMap.capturedDeviceStates == [:]
    }

    def "clear_captured_states is idempotent on empty state"() {
        when:
        def result = script.toolClearCapturedStates()

        then:
        result.success == true
        result.cleared == 0
    }
}
