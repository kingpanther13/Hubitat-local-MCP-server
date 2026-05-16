package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.NetworkUtilsMock
import support.TestChildApp
import support.TestDevice
import support.TestHub
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
 *                            individual tests set `sharedLocation.hub = new
 *                            TestHub(zwaveVersion: ..., uptime: ...)` to drive
 *                            location.hub reads. TestLocation's `hub` field
 *                            must be typed Hub (not Object) because
 *                            Location.getHub()'s declared return type is Hub —
 *                            Groovy's override check rejects `def hub`.
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
        sharedLocation.hub = new TestHub(uptime: 172800G)  // 2 days
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
        result.current.uptimeSeconds == 172800G
        result.current.uptimeFormatted == '2d 0h 0m'
        result.historyFile == 'mcp-performance-history.csv'
        result.trendPointsAvailable == 1

        and: 'the CSV snapshot was written to File Manager'
        uploaded['mcp-performance-history.csv']?.contains('freeMemoryKB')
        uploaded['mcp-performance-history.csv']?.contains('123456')
    }

    @spock.lang.Unroll
    def "get_set_hub_metrics via dispatch snapshots memory/temp/db (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = new TestHub(uptime: 172800G)
        hubGet.register('/hub/advanced/freeOSMemory') { params -> '123456' }
        hubGet.register('/hub/advanced/internalTempCelsius') { params -> '45.5' }
        hubGet.register('/hub/advanced/databaseSize') { params -> '200000' }
        script.metaClass.downloadHubFile = { String fileName -> null }
        script.metaClass.uploadHubFile = { String fileName, byte[] content -> }

        when:
        def response = mcpDriver.callTool('get_set_hub_metrics', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.current.freeMemoryKB == '123456'
        inner.current.internalTempC == '45.5'
        inner.current.uptimeFormatted == '2d 0h 0m'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "get_set_hub_metrics via dispatch maps Hub-Admin-Read-disabled IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('get_set_hub_metrics', [:])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('Hub Admin Read')

        where:
        useGateways << [true, false]
    }

    def "get_set_hub_metrics warns on high temperature"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = new TestHub(uptime: 3600G)
        hubGet.register('/hub/advanced/freeOSMemory') { params -> '200000' }
        hubGet.register('/hub/advanced/internalTempCelsius') { params -> '75' }  // >70 triggers warning
        hubGet.register('/hub/advanced/databaseSize') { params -> '100000' }
        script.metaClass.downloadHubFile = { String fileName -> null }
        script.metaClass.uploadHubFile = { String fileName, byte[] content -> }

        when:
        def result = script.toolGetHubPerformance([recordSnapshot: false])

        then:
        result.current.temperatureWarning.contains('HIGH TEMPERATURE')
        result.current.temperatureWarning.contains('75')
    }

    def "get_set_hub_metrics warns on large database"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = new TestHub(uptime: 3600G)
        hubGet.register('/hub/advanced/freeOSMemory') { params -> '200000' }
        hubGet.register('/hub/advanced/internalTempCelsius') { params -> '40' }
        hubGet.register('/hub/advanced/databaseSize') { params -> '600000' }  // >500000 triggers warning
        script.metaClass.downloadHubFile = { String fileName -> null }
        script.metaClass.uploadHubFile = { String fileName, byte[] content -> }

        when:
        def result = script.toolGetHubPerformance([recordSnapshot: false])

        then:
        result.current.databaseWarning.contains('LARGE DATABASE')
    }

    def "get_set_hub_metrics warns on low memory"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = new TestHub(uptime: 3600G)
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
        sharedLocation.hub = new TestHub(uptime: 0G)
        hubGet.register('/hub/advanced/freeOSMemory') { params -> '100000' }
        hubGet.register('/hub/advanced/internalTempCelsius') { params -> '50' }
        hubGet.register('/hub/advanced/databaseSize') { params -> '100000' }
        def downloadCalled = false
        script.metaClass.downloadHubFile = { String fileName -> downloadCalled = true; null }
        def uploadCalled = false
        script.metaClass.uploadHubFile = { String fileName, byte[] content -> uploadCalled = true }

        when:
        def result = script.toolGetHubPerformance([recordSnapshot: false])

        then: 'the CSV read still runs (trend points come from prior runs), but no write'
        downloadCalled
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

    @spock.lang.Unroll
    def "get_memory_history via dispatch parses CSV + summary (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        def csv = [
            'Date/time,Free OS,5m CPU avg,Total Java,Free Java,Direct Java',
            '2026-04-19 10:00,200000,0.5,500000,100000,2000',
            '2026-04-19 10:10,100000,0.9,500000, 80000,2200'
        ].join('\n')
        hubGet.register('/hub/advanced/freeOSMemoryHistory') { params -> csv }

        when:
        def response = mcpDriver.callTool('get_memory_history', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.entries.size() == 2
        inner.summary.totalEntries == 2
        inner.summary.currentMemoryKB == 100000

        where:
        useGateways << [true, false]
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
        given: 'two freeOSMemory reads pre + post, one forceGC between. pauseExecution is a no-op on the AppExecutor mock.'
        settingsMap.enableHubAdminRead = true
        def beforeAfter = ['90000', '120000']
        def idx = 0
        hubGet.register('/hub/advanced/freeOSMemory') { params -> beforeAfter[idx++] }
        def gcCalled = false
        hubGet.register('/hub/forceGC') { params -> gcCalled = true; '' }

        when:
        def result = script.toolForceGarbageCollection([:])

        then: 'both memory reads fired (tool must probe before AND after), and GC was triggered between'
        idx == 2
        gcCalled
        result.beforeFreeMemoryKB == 90000
        result.afterFreeMemoryKB == 120000
        result.deltaKB == 30000
        result.memoryReclaimed == true
        result.summary.contains('90000KB → 120000KB')
        result.summary.contains('+30000KB')
    }

    @spock.lang.Unroll
    def "force_garbage_collection via dispatch triggers GC and reports before/after (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        def beforeAfter = ['90000', '120000']
        def idx = 0
        hubGet.register('/hub/advanced/freeOSMemory') { params -> beforeAfter[idx++] }
        hubGet.register('/hub/forceGC') { params -> '' }

        when:
        def response = mcpDriver.callTool('force_garbage_collection', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        idx == 2
        inner.beforeFreeMemoryKB == 90000
        inner.afterFreeMemoryKB == 120000
        inner.deltaKB == 30000
        inner.memoryReclaimed == true

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "device_health_check via dispatch classifies devices (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def nowMs = 1234567890000L
        def fresh = new TestDevice(id: 1, name: 'Fresh', label: 'Fresh Sensor')
        fresh.metaClass.getLastActivity = { -> new Date(nowMs - 3600000L) }
        def stale = new TestDevice(id: 2, name: 'Stale', label: 'Stale Sensor')
        stale.metaClass.getLastActivity = { -> new Date(nowMs - (48 * 3600000L)) }
        settingsMap.selectedDevices = [fresh, stale]

        when:
        def response = mcpDriver.callTool('device_health_check', [staleHours: 24])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.summary.totalDevices == 2
        inner.summary.healthyCount == 1
        inner.summary.staleCount == 1

        where:
        useGateways << [true, false]
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

    def "device_health_check cursor paginates staleDevices and emits nextCursor (#174)"() {
        given: '150 stale devices -> 2 pages of 100 + 50'
        def nowMs = 1234567890000L
        def staleDevs = (0..<150).collect { i ->
            def d = new TestDevice(id: 1000 + i, name: "S${i}", label: "Stale Sensor ${i}")
            d.metaClass.getLastActivity = { -> new Date(nowMs - (48 * 3600000L)) }
            d
        }
        settingsMap.selectedDevices = staleDevs

        when: 'first page (cursor="")'
        def page1 = script.toolDeviceHealthCheck([staleHours: 24, cursor: ''])

        then: 'page is bounded but the summary still reflects the full stale count'
        page1.summary.staleCount == 150
        page1.staleDevices.size() == 100
        page1.nextCursor == '100'

        when: 'second page (cursor=100)'
        def page2 = script.toolDeviceHealthCheck([staleHours: 24, cursor: '100'])

        then: 'remaining 50 stale devices, no nextCursor'
        page2.staleDevices.size() == 50
        !page2.containsKey('nextCursor')
    }

    def "device_health_check without cursor returns the full staleDevices list (backward compatible)"() {
        given:
        def nowMs = 1234567890000L
        def stale = new TestDevice(id: 1, name: 'S', label: 'Stale')
        stale.metaClass.getLastActivity = { -> new Date(nowMs - (48 * 3600000L)) }
        settingsMap.selectedDevices = [stale]

        when:
        def result = script.toolDeviceHealthCheck([staleHours: 24])

        then: 'no cursor=null leaks pagination fields'
        result.staleDevices.size() == 1
        result.containsKey('nextCursor') == false
    }

    def "device_health_check cursor rejects non-numeric values"() {
        given:
        def nowMs = 1234567890000L
        def stale = new TestDevice(id: 1, name: 'S', label: 'Stale')
        stale.metaClass.getLastActivity = { -> new Date(nowMs - (48 * 3600000L)) }
        settingsMap.selectedDevices = [stale]

        when:
        script.toolDeviceHealthCheck([cursor: 'banana'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('cursor')
        ex.message.contains('device_health_check')
    }

    def "device_health_check pingHosts: happy + failure paths populate pingResults"() {
        given:
        def network = new NetworkUtilsMock()
        network.pingResponses['192.168.1.1'] = [packetsTransmitted: 3, packetsReceived: 3, packetLoss: 0, rttAvg: 1.2, rttMin: 1.0, rttMax: 1.5]
        network.pingResponses['192.0.2.1'] = [packetsTransmitted: 3, packetsReceived: 0, packetLoss: 100, rttAvg: 0.0, rttMin: 0.0, rttMax: 0.0]
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: ['192.168.1.1', '192.0.2.1']])

        then:
        network.pingCalls == [[ipAddress: '192.168.1.1', count: 3], [ipAddress: '192.0.2.1', count: 3]]
        result.pingResults.size() == 2
        result.pingResults[0].ipAddress == '192.168.1.1'
        result.pingResults[0].reachable == true
        result.pingResults[0].rttAvg == 1.2
        result.pingResults[0].packetLoss == 0
        result.pingResults[1].ipAddress == '192.0.2.1'
        result.pingResults[1].reachable == false
        result.pingResults[1].packetLoss == 100

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: invalid IPv4 strings short-circuit without calling NetworkUtils"() {
        given:
        def network = new NetworkUtilsMock()
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: ['not-an-ip', '1.2.3', '999.999.999.999.x', '999.999.999.999', '256.0.0.1']])

        then:
        network.pingCalls.isEmpty()
        result.pingResults.size() == 5
        result.pingResults.every { it.reachable == false }
        result.pingResults.every { it.error.contains('dotted-quad') && it.error.contains('hostnames not supported') }

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: trims whitespace before validation and echo"() {
        given:
        def network = new NetworkUtilsMock()
        network.pingResponses['10.0.0.1'] = [packetsTransmitted: 3, packetsReceived: 3, packetLoss: 0]
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: ['  10.0.0.1  ', '  bad host  ']])

        then:
        network.pingCalls*.ipAddress == ['10.0.0.1']
        result.pingResults*.ipAddress == ['10.0.0.1', 'bad host']

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: null and non-string entries report a distinct error"() {
        given:
        def network = new NetworkUtilsMock()
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: [null, 12345, ['nested']]])

        then:
        network.pingCalls.isEmpty()
        result.pingResults.size() == 3
        result.pingResults.every { it.reachable == false && it.error == 'missing or non-string host' }

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: pingCount is forwarded to NetworkUtils"() {
        given:
        def network = new NetworkUtilsMock()
        network.install()

        when:
        script.toolDeviceHealthCheck([pingHosts: ['10.0.0.1'], pingCount: 5])

        then:
        network.pingCalls == [[ipAddress: '10.0.0.1', count: 5]]

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: NetworkUtils exception is captured in pingResults"() {
        given:
        def network = new NetworkUtilsMock()
        network.pingResponses['10.0.0.1'] = new RuntimeException('boom')
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: ['10.0.0.1']])

        then:
        result.pingResults.size() == 1
        result.pingResults[0].reachable == false
        result.pingResults[0].error == 'boom'
        result.pingResults[0].errorType == 'other'

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: errorType discriminates UnknownHostException, SocketException, SecurityException"() {
        given:
        def network = new NetworkUtilsMock()
        network.pingResponses['10.0.0.1'] = new java.net.UnknownHostException('no dns')
        network.pingResponses['10.0.0.2'] = new java.net.SocketException('Network is unreachable')
        network.pingResponses['10.0.0.3'] = new SecurityException('blocked')
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: ['10.0.0.1', '10.0.0.2', '10.0.0.3']])

        then:
        result.pingResults*.errorType == ['unknown_host', 'socket', 'security']
        result.pingResults.every { it.reachable == false }

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: omitted pingCount forwards 3 to NetworkUtils"() {
        given:
        def network = new NetworkUtilsMock()
        network.install()

        when:
        script.toolDeviceHealthCheck([pingHosts: ['10.0.0.1']])

        then:
        network.pingCalls == [[ipAddress: '10.0.0.1', count: 3]]

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: mixed valid and invalid hosts preserve input order"() {
        given:
        def network = new NetworkUtilsMock()
        network.pingResponses['10.0.0.1'] = [packetsTransmitted: 3, packetsReceived: 3, packetLoss: 0, rttAvg: 1.0, rttMin: 1.0, rttMax: 1.0]
        network.pingResponses['10.0.0.2'] = [packetsTransmitted: 3, packetsReceived: 3, packetLoss: 0, rttAvg: 2.0, rttMin: 2.0, rttMax: 2.0]
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: ['10.0.0.1', 'garbage', '10.0.0.2']])

        then:
        network.pingCalls*.ipAddress == ['10.0.0.1', '10.0.0.2']
        result.pingResults*.ipAddress == ['10.0.0.1', 'garbage', '10.0.0.2']
        result.pingResults[0].reachable == true
        result.pingResults[1].reachable == false
        result.pingResults[1].error.contains('dotted-quad')
        result.pingResults[2].reachable == true

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: null PingData is treated as unreachable with transmitted defaulted to count"() {
        given:
        def network = new NetworkUtilsMock()
        network.pingResponses['10.0.0.1'] = { ip, n -> null }
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: ['10.0.0.1'], pingCount: 4])

        then:
        result.pingResults.size() == 1
        result.pingResults[0].reachable == false
        result.pingResults[0].packetsTransmitted == 4
        result.pingResults[0].packetsReceived == 0

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: zero packetsTransmitted from platform is preserved (not coerced to count)"() {
        given:
        def network = new NetworkUtilsMock()
        network.pingResponses['10.0.0.1'] = [packetsTransmitted: 0, packetsReceived: 0, packetLoss: 100]
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: ['10.0.0.1'], pingCount: 5])

        then:
        result.pingResults[0].packetsTransmitted == 0
        result.pingResults[0].reachable == false

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: rejects more than 5 hosts and pingCount out of range"() {
        when:
        script.toolDeviceHealthCheck([pingHosts: ['1.1.1.1','2.2.2.2','3.3.3.3','4.4.4.4','5.5.5.5','6.6.6.6']])

        then:
        def tooMany = thrown(IllegalArgumentException)
        tooMany.message.contains('5 entries')

        // Locks in the null-vs-0 distinction: with the prior `?: 3` bug, 0 would silently
        // become 3 and skip this check.
        when:
        script.toolDeviceHealthCheck([pingHosts: ['1.1.1.1'], pingCount: 0])

        then:
        def low = thrown(IllegalArgumentException)
        low.message.contains('between 1 and 5')
        low.message.contains('got 0')

        when:
        script.toolDeviceHealthCheck([pingHosts: ['1.1.1.1'], pingCount: 6])

        then:
        def high = thrown(IllegalArgumentException)
        high.message.contains('between 1 and 5')
    }

    // -------- device_health_check identifyHub --------

    def "device_health_check identifyHub=true fires /hub/advanced/blinkLED and surfaces identifyHubTriggered=true"() {
        given:
        def nowMs = 1234567890000L
        def fresh = new TestDevice(id: 1, name: 'Fresh', label: 'Fresh Sensor')
        fresh.metaClass.getLastActivity = { -> new Date(nowMs - 600000L) }
        settingsMap.selectedDevices = [fresh]
        hubGet.register('/hub/advanced/blinkLED') { params -> 'true' }

        when:
        def result = script.toolDeviceHealthCheck([identifyHub: true])

        then:
        result.identifyHubTriggered == true
        !result.containsKey('identifyHubError')
        hubGet.calls.any { it.path == '/hub/advanced/blinkLED' }
    }

    def "device_health_check identifyHub=true with no devices selected still triggers blink and reports it"() {
        given:
        // settingsMap.selectedDevices intentionally absent — triggers the early-return path
        hubGet.register('/hub/advanced/blinkLED') { params -> 'true' }

        when:
        def result = script.toolDeviceHealthCheck([identifyHub: true])

        then:
        result.message.contains('No devices selected')
        result.identifyHubTriggered == true
        !result.containsKey('identifyHubError')
        hubGet.calls.any { it.path == '/hub/advanced/blinkLED' }
    }

    def "device_health_check without identifyHub does not hit the blinkLED endpoint or emit the field"() {
        given:
        def nowMs = 1234567890000L
        def fresh = new TestDevice(id: 1, name: 'Fresh', label: 'Fresh Sensor')
        fresh.metaClass.getLastActivity = { -> new Date(nowMs - 600000L) }
        settingsMap.selectedDevices = [fresh]

        when:
        def result = script.toolDeviceHealthCheck([:])

        then:
        !result.containsKey('identifyHubTriggered')
        !result.containsKey('identifyHubError')
        !hubGet.calls.any { it.path == '/hub/advanced/blinkLED' }
    }

    def "device_health_check identifyHub=true with endpoint failure surfaces identifyHubTriggered=false plus identifyHubError"() {
        given:
        hubGet.register('/hub/advanced/blinkLED') { params -> throw new RuntimeException('LED unavailable') }

        when:
        def result = script.toolDeviceHealthCheck([identifyHub: true])

        then:
        result.identifyHubTriggered == false
        result.identifyHubError == 'LED unavailable'
    }

    def "device_health_check identifyHub=true with null-message exception falls back to e.toString()"() {
        given:
        hubGet.register('/hub/advanced/blinkLED') { params -> throw new IOException() }

        when:
        def result = script.toolDeviceHealthCheck([identifyHub: true])

        then:
        result.identifyHubTriggered == false
        result.identifyHubError != null
        result.identifyHubError.toLowerCase().contains('ioexception')
    }

    def "device_health_check identifyHub=true and pingHosts together both surface in one response"() {
        given:
        def network = new NetworkUtilsMock()
        network.pingResponses['10.0.0.1'] = [packetsTransmitted: 3, packetsReceived: 3, packetLoss: 0, rttAvg: 1.0, rttMin: 1.0, rttMax: 1.0]
        network.install()
        hubGet.register('/hub/advanced/blinkLED') { params -> 'true' }

        when:
        def result = script.toolDeviceHealthCheck([identifyHub: true, pingHosts: ['10.0.0.1']])

        then:
        result.identifyHubTriggered == true
        result.pingResults.size() == 1
        result.pingResults[0].reachable == true
        hubGet.calls.findAll { it.path == '/hub/advanced/blinkLED' }.size() == 1

        cleanup:
        network.uninstall()
    }

    def "device_health_check pingHosts: non-numeric pingCount surfaces friendly IllegalArgumentException"() {
        when:
        script.toolDeviceHealthCheck([pingHosts: ['1.1.1.1'], pingCount: 'three'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('integer between 1 and 5')
        ex.message.contains('three')
    }

    def "device_health_check pingHosts work even when no devices are selected"() {
        given:
        def network = new NetworkUtilsMock()
        network.pingResponses['192.168.1.1'] = [packetsTransmitted: 3, packetsReceived: 3, packetLoss: 0, rttAvg: 1.0, rttMin: 1.0, rttMax: 1.0]
        network.install()

        when:
        def result = script.toolDeviceHealthCheck([pingHosts: ['192.168.1.1']])

        then:
        result.message.contains('No devices selected')
        result.pingResults.size() == 1
        result.pingResults[0].reachable == true

        cleanup:
        network.uninstall()
    }

    // -------- toolGetRuleDiagnostics --------

    def "get_rule_diagnostics throws when the ruleId is not found"() {
        when:
        script.toolGetRuleDiagnostics([ruleId: 'nope'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found')
    }

    @spock.lang.Unroll
    def "get_rule_diagnostics via dispatch maps ruleId-not-found IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        // The tool name in executeTool dispatch is custom_get_rule_diagnostics; the
        // custom_* engine gate at the top of executeTool requires enableCustomRuleEngine=true
        // (or enableBuiltinApp=true for readonly tools like this one) before the dispatch
        // reaches the tool body and its own validation.
        settingsMap.enableCustomRuleEngine = true

        when:
        def response = mcpDriver.callTool('custom_get_rule_diagnostics', [ruleId: 'nope'])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('Rule not found')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "get_rule_diagnostics via dispatch returns rule structure + recent logs + errors (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        // custom_* gate
        settingsMap.enableCustomRuleEngine = true
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
        stateMap.debugLogs = [
            entries: [
                [timestamp: 1L, level: 'info',  component: 'rules', message: 'ran', ruleId: '42'],
                [timestamp: 2L, level: 'error', component: 'rules', message: 'boom', ruleId: '42', stackTrace: 't1']
            ],
            config: [logLevel: 'debug', maxEntries: 100]
        ]

        when:
        def response = mcpDriver.callTool('custom_get_rule_diagnostics', [ruleId: '42'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rule.id == 42L
        inner.rule.name == 'My Rule'
        inner.structure.triggerCount == 2
        inner.structure.conditionCount == 1
        inner.structure.actionCount == 3
        inner.logs.errorCount == 1

        where:
        useGateways << [true, false]
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
        sharedLocation.hub = new TestHub(zwaveVersion: '7.17.1')
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

    @spock.lang.Unroll
    def "get_zwave_details via dispatch returns combined zwaveVersion + zwaveData (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = new TestHub(zwaveVersion: '7.17.1')
        hubGet.register('/hub/zwaveDetails/json') { params ->
            JsonOutput.toJson([firmware: '7.17.1', sdkVersion: '6.82', deviceCount: 12])
        }

        when:
        def response = mcpDriver.callTool('get_zwave_details', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.zwaveVersion == '7.17.1'
        inner.source == 'hub_api'
        inner.zwaveData.firmware == '7.17.1'

        where:
        useGateways << [true, false]
    }

    def "get_zwave_details falls back to sdk_only when all zwave endpoints fail"() {
        given: 'both zwave endpoints throw realistic hub errors (404 on older firmware)'
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = new TestHub(zwaveVersion: '7.0.0')
        hubGet.register('/hub/zwaveDetails/json') { params -> throw new RuntimeException('404 Not Found') }
        hubGet.register('/hub2/zwaveInfo')        { params -> throw new RuntimeException('404 Not Found') }

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
        sharedLocation.hub = new TestHub(zwaveVersion: '7.0.0')
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
        sharedLocation.hub = new TestHub(zigbeeChannel: 25, zigbeeId: '0x1234')
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

    @spock.lang.Unroll
    def "get_zigbee_details via dispatch returns channel + zigbeeId + parsed details (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = new TestHub(zigbeeChannel: 25, zigbeeId: '0x1234')
        hubGet.register('/hub/zigbeeDetails/json') { params ->
            JsonOutput.toJson([panId: '0xABCD', channel: 25, deviceCount: 7])
        }

        when:
        def response = mcpDriver.callTool('get_zigbee_details', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.zigbeeChannel == 25
        inner.zigbeeId == '0x1234'
        inner.source == 'hub_api'
        inner.zigbeeData.panId == '0xABCD'

        where:
        useGateways << [true, false]
    }

    def "get_zigbee_details falls back to sdk_only when all endpoints fail"() {
        given: 'both zigbee endpoints throw realistic hub errors'
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = new TestHub(zigbeeChannel: 20, zigbeeId: '0xAAAA')
        hubGet.register('/hub/zigbeeDetails/json') { params -> throw new RuntimeException('404 Not Found') }
        hubGet.register('/hub2/zigbeeInfo')        { params -> throw new RuntimeException('404 Not Found') }

        when:
        def result = script.toolGetZigbeeDetails([:])

        then:
        result.source == 'sdk_only'
        result.zigbeeChannel == 20
    }

    def "get_zigbee_details captures raw response when endpoint returns non-JSON"() {
        given:
        settingsMap.enableHubAdminRead = true
        sharedLocation.hub = new TestHub(zigbeeChannel: 25, zigbeeId: '0x1234')
        hubGet.register('/hub/zigbeeDetails/json') { params -> '<html>not json</html>' }

        when:
        def result = script.toolGetZigbeeDetails([:])

        then:
        result.source == 'hub_api_raw'
        result.rawResponse.contains('not json')
        result.note.contains('not JSON')
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

    @spock.lang.Unroll
    def "zwave_repair via dispatch posts and reports success (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        def postedPath = null
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            postedPath = path
            'repair started'
        }

        when:
        def response = mcpDriver.callTool('zwave_repair', [confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        postedPath == '/hub/zwaveRepair'
        inner.success == true
        inner.message.contains('Z-Wave network repair')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "zwave_repair via dispatch maps confirm-missing IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('zwave_repair', [:])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')
        response.error.message.contains('confirm=true')

        where:
        useGateways << [true, false]
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
        result.note.contains('Z-Wave Details')
    }

    // -------- toolListCapturedStates --------

    def "list_captured_states returns an empty list with count when state is empty"() {
        when: 'no confirm arg — pins the current no-gate behaviour; a future confirm gate would flip this'
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

    @spock.lang.Unroll
    def "list_captured_states via dispatch returns entries sorted newest-first (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        stateMap.capturedDeviceStates = [
            older: [devices: [[id: '1', name: 'D1']], timestamp: 1000L, deviceCount: 1],
            newer: [devices: [[id: '2', name: 'D2'], [id: '3', name: 'D3']], timestamp: 5000L, deviceCount: 2]
        ]

        when:
        def response = mcpDriver.callTool('list_captured_states', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 2
        inner.capturedStates[0].stateId == 'newer'
        inner.capturedStates[1].stateId == 'older'

        where:
        useGateways << [true, false]
    }

    def "list_captured_states surfaces an at-capacity warning"() {
        given: 'override the cap to 5 so filling capacity only takes 5 seed entries'
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

    def "list_captured_states surfaces the approaching-limit warning at max-4 slots"() {
        given: 'max=10, 7 entries → count >= max-4 branch (source hubitat-mcp-server.groovy:3174)'
        settingsMap.maxCapturedStates = 10
        stateMap.capturedDeviceStates = (1..7).collectEntries { i ->
            ["s${i}".toString(), [devices: [], timestamp: (i * 1000L), deviceCount: 0]]
        }

        when:
        def result = script.toolListCapturedStates()

        then:
        result.count == 7
        result.warning.contains('Approaching limit')
        result.warning.contains('7/10')
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

    @spock.lang.Unroll
    def "delete_captured_state via dispatch removes the specified entry (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        stateMap.capturedDeviceStates = [
            keep: [devices: [], timestamp: 1000L, deviceCount: 0],
            gone: [devices: [], timestamp: 2000L, deviceCount: 0]
        ]

        when:
        def response = mcpDriver.callTool('delete_captured_state', [stateId: 'gone'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.remaining == 1
        stateMap.capturedDeviceStates.containsKey('keep')
        !stateMap.capturedDeviceStates.containsKey('gone')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "clear_captured_states via dispatch empties state and returns count (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        stateMap.capturedDeviceStates = [
            a: [devices: [], timestamp: 1000L, deviceCount: 0],
            b: [devices: [], timestamp: 2000L, deviceCount: 0],
            c: [devices: [], timestamp: 3000L, deviceCount: 0]
        ]

        when:
        def response = mcpDriver.callTool('clear_captured_states', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.cleared == 3
        stateMap.capturedDeviceStates == [:]

        where:
        useGateways << [true, false]
    }

    def "clear_captured_states is idempotent on empty state"() {
        when:
        def result = script.toolClearCapturedStates()

        then:
        result.success == true
        result.cleared == 0
    }
}
