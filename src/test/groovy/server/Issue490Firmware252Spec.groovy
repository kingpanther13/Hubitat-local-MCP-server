package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Unroll
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Firmware 2.5.2 adoption (issue #490): alert feed + dismissal, pending-update detection,
 * subscriptions, cloud calls, Zigbee last-message, Z-Wave JS reads and writes, Z-Wave network
 * backup, the 2.5.2 Matter pairing request, firmware-service and batch flashes, full local
 * backups, network-share backups, cloud backup downloads, File Manager folders, deprecated
 * apps, platform app-usage lookup, bounded event windows, typed variable listing + atomic
 * increment, and the on-hub API documentation served through hub_get_tool_guide.
 */
class Issue490Firmware252Spec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()
    // asynchttpGet and httpPost are AppExecutor API methods: a script.metaClass override never
    // intercepts them, so record them through the shared mock seam instead.
    @Shared List asyncPaths = []
    @Shared Closure httpPostHook = null
    @Shared Closure exceptionLineHook = null
    @Shared Closure httpGetHook = null
    @Shared Closure stackTraceHook = null

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
        appExecutor.asynchttpGet(*_) >> { args -> asyncPaths << args[1]?.path }
        appExecutor.httpPost(*_) >> { args -> httpPostHook?.call(args[0], args[1]) }
        appExecutor.getExceptionMessageWithLine(_) >> { args -> exceptionLineHook?.call(args[0]) }
        appExecutor.getStackTrace(_) >> { args -> stackTraceHook?.call(args[0]) }
        appExecutor.httpGet(*_) >> { args -> httpGetHook?.call(args[0], args[1]) }
    }

    def setup() {
        asyncPaths.clear()
        httpPostHook = null
        exceptionLineHook = null
        stackTraceHook = null
        httpGetHook = null
    }

    def cleanup() {
        sharedLocation.hub = null
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    /** Carries an HTTP status the way HttpResponseException does (duck-typed e.response.status). */
    private static class FakeHttpException extends RuntimeException {
        final Map response
        FakeHttpException(int status) {
            super("status code: ${status}")
            this.response = [status: status]
        }
    }

    private static byte[] bigGzip() {
        def b = new byte[16 * 1024 * 1024 + 2]
        b[0] = (byte) 0x1f
        b[1] = (byte) 0x8b
        return b
    }

    private TestHub hubOnFirmware(String fw) {
        def h = new TestHub()
        h.firmwareVersionString = fw
        return h
    }

    private List<Map> jsonPosts() {
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null ->
            posts << [path: path, body: new JsonSlurper().parseText(body)]
            return [success: true, jobId: 'job-1', importId: 'imp-1', nodeId: 7, report: [missingKeys: []]]
        }
        return posts
    }

    // ---------------- alerts, pending update, subscriptions ----------------

    static final String HUB_DATA_252 = JsonOutput.toJson([version: '2.5.2.129', safeMode: false,
        alerts: [alertItems: [[key: 'PLATFORM_UPDATE_AVAILABLE', version: '2.5.2.134', dismissible: true, message: 'Platform update 2.5.2.134 available.']],
                 alertMessages: [:], headerMessages: [], databaseSize: 24]])

    def "platformUpdate on 2.5.2.129 comes from the PLATFORM_UPDATE_AVAILABLE alert item"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.2.129')
        hubGet.register('/hub2/hubData') { p -> HUB_DATA_252 }

        when:
        def r = script.toolGetHubInfo([:])

        then:
        r.platformUpdate.available == true
        r.platformUpdate.availableVersion == '2.5.2.134'
        r.platformUpdate.currentVersion == '2.5.2.129'
    }

    @Unroll
    def "without the alert item platformUpdate asks the platform's latest-version check (latest=#latest)"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.2.129')
        hubGet.register('/hub2/hubData') { p -> JsonOutput.toJson([version: '2.5.2.129', alerts: [alertItems: []]]) }
        script.metaClass.getLatestAvailablePlatformVersion = { -> latest }

        when:
        def r = script.toolGetHubInfo([:])

        then:
        r.platformUpdate.available == available
        r.platformUpdate.availableVersion == version

        where:
        latest      | available | version
        '2.5.2.134' | true      | '2.5.2.134'
        '2.5.2.129' | false     | null
        '2.5.2.120' | false     | null
        '2.5.10.1'  | true      | '2.5.10.1'
    }

    def "an unparseable latest version leaves platformUpdate unknown rather than guessing"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.2.129')
        hubGet.register('/hub2/hubData') { p -> JsonOutput.toJson([version: '2.5.2.129', alerts: [alertItems: []]]) }
        script.metaClass.getLatestAvailablePlatformVersion = { -> '2.5.2.134-beta' }

        when:
        def r = script.toolGetHubInfo([:])

        then:
        r.platformUpdate.available == null
        r.platformUpdate.note.contains('Check for Updates')
    }

    def "platformUpdate stays null with a note when neither the alert nor the platform check answers"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.2.129')
        hubGet.register('/hub2/hubData') { p -> JsonOutput.toJson([version: '2.5.2.129', alerts: [alertItems: []]]) }
        script.metaClass.getLatestAvailablePlatformVersion = { -> throw new RuntimeException('cloud down') }

        when:
        def r = script.toolGetHubInfo([:])

        then:
        r.platformUpdate.available == null
        r.platformUpdate.note.contains('Check for Updates')
    }

    def "healthAlerts prefer the /hub/alertsJson feed: items with dismissal handles and spammyDeviceDetails"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.2.129')
        hubGet.register('/hub2/hubData') { p -> HUB_DATA_252 }
        hubGet.register('/hub/alertsJson') { p ->
            JsonOutput.toJson([alertItems: [[key: 'SPAMMY_DEVICES', message: 'Too many events', dismissible: true, version: '3']],
                               spammyDeviceDetails: [[id: 12, name: 'Chatty', count: '900']], maxEvents: 11, maxStates: '20'])
        }

        when:
        def r = script.toolGetHubInfo([includeHealthAlerts: true])

        then:
        r.healthAlerts.active == ['SPAMMY_DEVICES']
        r.healthAlerts.items == [[key: 'SPAMMY_DEVICES', message: 'Too many events', version: '3', dismissible: true]]
        r.healthAlerts.details.spammyDeviceDetails[0].name == 'Chatty'
        r.healthAlerts.details.maxEvents == 11
    }

    def "healthAlerts fall back to the hub data alert block when the feed is unreadable"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.2.129')
        hubGet.register('/hub2/hubData') { p -> HUB_DATA_252 }

        when:
        def r = script.toolGetHubInfo([includeHealthAlerts: true])

        then:
        r.healthAlerts.active == ['PLATFORM_UPDATE_AVAILABLE']
        r.healthAlerts.items[0].version == '2.5.2.134'
    }

    def "includeSubscriptions projects the four subscriptions"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.2.129')
        hubGet.register('/hub2/hubData') { p -> HUB_DATA_252 }
        hubGet.register('/hub/subscriptions/json') { p ->
            JsonOutput.toJson([loaded: true, updatedAt: 1791442961968L,
                hubProtect: [isActive: true, pendingCancellation: false, end_ts: '2027-08-13T20:51:56.000Z', trialAvailable: false],
                remoteAdmin: [isActive: true, end_ts: '2027-08-13T20:51:56.000Z'],
                cloudBackup: [isActive: false, end_ts: null, trialAvailable: true],
                fullLocalBackup: [isActive: true, end_ts: '2027-08-13T20:51:56.000Z'],
                hasAvailableTrial: true, fullLocalBackupSupported: false])
        }

        when:
        def r = script.toolGetHubInfo([includeSubscriptions: true])

        then:
        r.subscriptions.hubProtect == [active: true, pendingCancellation: false, endsAt: '2027-08-13T20:51:56.000Z', trialAvailable: false]
        r.subscriptions.cloudBackup.active == false
        r.subscriptions.cloudBackup.trialAvailable == true
        r.subscriptions.fullLocalBackup.active == true
        r.subscriptions.fullLocalBackupSupported == false
    }

    def "hub_get_info leaves subscriptions out unless asked"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.2.129')
        hubGet.register('/hub2/hubData') { p -> HUB_DATA_252 }

        when:
        def r = script.toolGetHubInfo([:])

        then:
        !r.containsKey('subscriptions')
        !hubGet.calls.any { it.path == '/hub/subscriptions/json' }
    }

    def "dismissAlert sends key and version to /hub/dismissAlert"() {
        given:
        hubGet.register('/hub/dismissAlert') { p -> '' }

        when:
        def r = script.toolSetSystemSettings([dismissAlert: [key: 'PLATFORM_UPDATE_AVAILABLE', version: '2.5.2.134']])

        then:
        r.success == true
        r.applied == ['dismissAlert']
        hubGet.calls.find { it.path == '/hub/dismissAlert' }.params == [key: 'PLATFORM_UPDATE_AVAILABLE', version: '2.5.2.134']
    }

    def "a failed dismissal says the network settings in the same call were not sent"() {
        given:
        enableWrite()
        hubGet.register('/hub/dismissAlert') { p -> throw new FakeHttpException(400) }

        when:
        def r = script.toolSetSystemSettings([dismissAlert: [key: 'NOPE'], network: [ipMode: 'dhcp'], confirm: true])

        then:
        r.success == false
        r.note.contains('network settings')
    }

    def "healthAlerts mark an unreadable 2.5.2.129 alert feed"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.2.129')
        hubGet.register('/hub2/hubData') { p -> HUB_DATA_252 }

        when:
        def r = script.toolGetHubInfo([includeHealthAlerts: true])

        then:
        r.healthAlerts.details.feed == 'unavailable'
    }

    def "dismissAlert without a key is refused before any hub call"() {
        when:
        script.toolSetSystemSettings([dismissAlert: [version: '1']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('dismissAlert must be {key')
        hubGet.calls.isEmpty()
    }

    @Unroll
    def "dismissAlert reaches the hub through dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/hub/dismissAlert') { p -> '' }

        when:
        def response = mcpDriver.callTool('hub_set_system_settings', [dismissAlert: [key: 'POWER_LOSS_RECOVERED']])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.applied == ['dismissAlert']
        hubGet.calls.find { it.path == '/hub/dismissAlert' }.params == [key: 'POWER_LOSS_RECOVERED']

        where:
        useGateways << [true, false]
    }

    def "hub_update_firmware drops the owner's account email from the check payload"() {
        expect:
        !script._parseFirmwareCheck('{"version":"2.5.2.134","accountEmails":["a@b.c"]}').containsKey('accountEmails')
        script._parseFirmwareCheck('{"version":"2.5.2.134","accountEmails":["a@b.c"]}').version == '2.5.2.134'
    }

    def "hub_update_firmware never returns an update-check answer it cannot redact"() {
        when:
        def r = script._parseFirmwareCheck(raw)

        then:
        r.parseError
        !r.toString().contains('a@b.c')

        where:
        raw << ['["accountEmails","a@b.c"]', '{"accountEmails":["a@b.c"]', 'accountEmails=a@b.c']
    }

    // ---------------- performance: cloud calls ----------------

    def "includeCloudCalls groups the hourly series per app, newest first, sorted by total"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/logs/json') { p -> JsonOutput.toJson([uptime: '1d', deviceStats: [[id: 1, name: 'D', pct: 1.0, cloudCallCount: 4]], appStats: []]) }
        hubGet.register('/logs/cloudCalls/json') { p ->
            JsonOutput.toJson([apps: [[id: 72, name: 'Google Home', installed: true, total: 57, currentHour: 2],
                                      [id: 194, name: 'MCP Rule Server', installed: true, total: 159, currentHour: 0]],
                               hours: [[appId: 72, hourStart: 1000L, count: 2], [appId: 72, hourStart: 2000L, count: 3], [appId: 194, hourStart: 1000L, count: 4]],
                               timeZone: 'US/Eastern', startedAt: 500L])
        }

        when:
        def r = script.toolGetPerformanceStats([includeCloudCalls: true])

        then:
        r.deviceStats[0].cloudCalls == 4
        r.cloudCalls.apps*.name == ['MCP Rule Server', 'Google Home']
        r.cloudCalls.apps[1].hourly*.count == [3, 2]
        r.cloudCalls.timeZone == 'US/Eastern'
    }

    def "a cloud-calls answer of an unexpected shape is reported as such, and hourly keeps 48 hours"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/logs/json') { p -> JsonOutput.toJson([uptime: '1d', deviceStats: [], appStats: []]) }
        int calls = 0
        hubGet.register('/logs/cloudCalls/json') { p ->
            calls++
            calls == 1 ? JsonOutput.toJson([apps: [[id: 1, name: 'A', total: 1]], hours: [[appId: 1, hourStart: 'soon', count: 1]]]) :
                         JsonOutput.toJson([apps: [[id: 1, name: 'A', total: 99]], hours: (1..60).collect { [appId: 1, hourStart: it * 3600000L, count: 1] }])
        }

        when:
        def bad = script.toolGetPerformanceStats([includeCloudCalls: true])
        def good = script.toolGetPerformanceStats([includeCloudCalls: true])

        then:
        bad.cloudCalls.error.contains('unexpected shape')
        good.cloudCalls.apps[0].hourly.size() == 48
    }

    def "an unreadable cloud-calls endpoint is an error block, not a failed stats read"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/logs/json') { p -> JsonOutput.toJson([uptime: '1d', deviceStats: [], appStats: []]) }

        when:
        def r = script.toolGetPerformanceStats([includeCloudCalls: true])

        then:
        r.uptime == '1d'
        r.cloudCalls.error.contains('/logs/cloudCalls/json')
    }

    // ---------------- radio reads ----------------

    def "include_devices lists Zigbee devices with their last message, silent ones first"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zigbeeDetails/json') { p -> JsonOutput.toJson([channel: 25]) }
        long nowMs = script.now()
        hubGet.register('/hub/zigbee/getDevicesJson') { p ->
            JsonOutput.toJson([status: true, devices: [[id: 1, zigbeeId: 'AA', name: 'Fresh', lastMessage: nowMs - 60000L],
                                                       [id: 2, zigbeeId: 'BB', name: 'Silent', lastMessage: null],
                                                       [id: 3, zigbeeId: 'CC', name: 'Stale', lastMessage: nowMs - 7200000L]]])
        }

        when:
        def r = script.toolGetRadioDetails([radio: 'zigbee', include_devices: true])

        then:
        r.zigbeeDevices.devices*.name == ['Silent', 'Stale', 'Fresh']
        r.zigbeeDevices.devices[1].minutesSinceLastMessage == 120
        r.zigbeeDevices.devices[0].lastMessage == null
    }

    def "include_status adds the Z-Wave JS, Z-Wave local backup and batch firmware pollers"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { p -> JsonOutput.toJson([enabled: true, zwaveJS: false]) }
        hubGet.register('/hub/zwave2/updateStatus') { p -> JsonOutput.toJson([updateInProgress: false, zwaveJSReady: false]) }
        hubGet.register('/hub/zwave/localBackup/status') { p -> JsonOutput.toJson([available: false, entitled: true, firmwareVersion: '7.18']) }
        hubGet.register('/hub/zwave/deviceFirmware/batchProgress') { p -> JsonOutput.toJson([success: false, message: 'Batch firmware updates require Z-Wave JS']) }

        when:
        def r = script.toolGetRadioDetails([radio: 'zwave', include_status: true])

        then:
        r.status.zwaveJs.zwaveJSReady == false
        r.status.zwaveLocalBackup.entitled == true
        r.status.zwaveFirmwareBatch.message.contains('Z-Wave JS')
    }

    @Unroll
    def "node_id adds Z-Wave JS node details only on a Z-Wave JS hub (zwaveJS=#js)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { p -> JsonOutput.toJson([enabled: true, zwaveJS: js]) }
        hubGet.register('/hub/zwave2/getNodeState') { p -> 'Done' }
        hubGet.register('/hub/zwave2/nodeDetails') { p -> JsonOutput.toJson([nodeId: 5, commandClasses: []]) }
        hubGet.register('/hub/zwave2/linkReliability/status') { p -> JsonOutput.toJson([running: false]) }

        when:
        def r = script.toolGetRadioDetails([radio: 'zwave', node_id: '5'])

        then:
        r.containsKey('nodeDetails') == js
        r.containsKey('linkReliability') == js
        r.containsKey('nodeDetailsNote') == !js

        where:
        js << [true, false]
    }

    def "include_firmware with node_id reads the node's targets, offered updates, progress and batch candidates"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { p -> JsonOutput.toJson([enabled: true]) }
        ['/hub/zwave/deviceFirmware/devices', '/hub/zwave/deviceFirmware/files', '/hub/zwave/deviceFirmware/details',
         '/hub/zwave/deviceFirmware/available', '/hub/zwave/deviceFirmware/progress', '/hub/zwave/deviceFirmware/batchCandidates'].each { ep ->
            hubGet.register(ep) { p -> JsonOutput.toJson([success: true, path: ep]) }
        }

        when:
        def r = script.toolGetRadioDetails([radio: 'zwave', include_firmware: true, node_id: '9'])

        then:
        r.firmware.node.keySet() == ['details', 'available', 'progress', 'batchCandidates'] as Set
        hubGet.calls.findAll { it.path == '/hub/zwave/deviceFirmware/available' }*.params == [[nodeId: '9']]
    }

    def "radio='matter' attaches the hub's Matter Wi-Fi credentials (never a password)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/matterDetails/json') { p -> JsonOutput.toJson([enabled: true]) }
        hubGet.register('/hub/matter/wifiCredentials') { p -> JsonOutput.toJson([selectedSsid: 'Home', storedSsid: 'Home', hasStoredPassword: true, passwordPlaceholder: '********', availableNetworks: ['Home']]) }

        when:
        def r = script.toolGetRadioDetails([radio: 'matter'])

        then:
        r.wifiCredentials.storedSsid == 'Home'
        !JsonOutput.toJson(r).contains('"password"')
    }

    def "backup_job_id reads the Z-Wave backup job"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { p -> '{}' }
        hubGet.register('/hub/zwave/localBackup/job/job-1') { p -> JsonOutput.toJson([stage: 'DONE', percent: 100]) }

        when:
        def r = script.toolGetRadioDetails([radio: 'zwave', backup_job_id: 'job-1'])

        then:
        r.zwaveBackupJob.stage == 'DONE'
    }

    // ---------------- Z-Wave writes ----------------

    def "hub_set_zwave zwave_js switches the stack and is confirm-gated and sent alone"() {
        given:
        enableWrite()
        hubGet.register('/hub/zwaveDetails/json') { p -> '{"zwaveJS":false}' }
        hubGet.register('/hub/zwave2/enable') { p -> '{"success":true}' }

        when:
        def r = script.toolSetZwave([zwave_js: true, confirm: true])

        then:
        r.success == true
        r.changed == true
        r.rebooting == true
        hubGet.calls*.path == ['/hub/zwaveDetails/json', '/hub/zwave2/enable']
    }

    def "hub_set_zwave zwave_js does nothing when the hub already runs that stack"() {
        given:
        enableWrite()
        hubGet.register('/hub/zwaveDetails/json') { p -> '{"zwaveJS":true}' }

        when:
        def r = script.toolSetZwave([zwave_js: true, confirm: true])

        then:
        r.success == true
        r.changed == false
        !hubGet.calls.any { it.path == '/hub/zwave2/enable' }
    }

    @Unroll
    def "hub_set_zwave zwave_js reports #label"() {
        given:
        enableWrite()
        hubGet.register('/hub/zwaveDetails/json') { p -> details }
        hubGet.register('/hub/zwave2/enable') { p -> if (failure != null) throw failure; answer }

        when:
        def r = script.toolSetZwave([zwave_js: true, confirm: true])

        then:
        r.success == false
        (r.outcome == 'unknown') == unknown
        r.error.contains(expected)

        where:
        label                           | details             | answer                | failure                            | unknown | expected
        'an unreadable stack'           | '<html>'            | null                  | null                               | false   | 'Could not read'
        'a non-JSON answer as refused'  | '{"zwaveJS":false}' | 'ok'                  | null                               | false   | 'refused'
        'an HTTP error as a refusal'    | '{"zwaveJS":false}' | null                  | new FakeHttpException(404)         | false   | 'refused'
        'no answer as unknown'          | '{"zwaveJS":false}' | null                  | new RuntimeException('Read timed out') | true | 'no answer'
    }

    @Unroll
    def "hub_set_zwave zwave_js is refused before any hub call: #label"() {
        given:
        enableWrite()

        when:
        script.toolSetZwave(args)

        then:
        thrown(IllegalArgumentException)
        hubGet.calls.isEmpty()

        where:
        label                 | args
        'no confirm'          | [zwave_js: false]
        'combined with region'| [zwave_js: true, region: 'US', confirm: true]
        'not a boolean'       | [zwave_js: 'yes', confirm: true]
    }

    def "hub_set_zwave reports the hub's refusal of the stack switch"() {
        given:
        enableWrite()
        hubGet.register('/hub/zwaveDetails/json') { p -> '{"zwaveJS":true}' }
        hubGet.register('/hub/zwave2/disable') { p -> '{"success":false,"message":"not available"}' }

        when:
        def r = script.toolSetZwave([zwave_js: false, confirm: true])

        then:
        r.success == false
        r.error.contains('not available')
    }

    def "reinterview, link test and cc_command send the 2.5.2 node-state requests"() {
        given:
        enableWrite()
        def posts = jsonPosts()
        hubGet.register('/hub/zwave2/reinterview') { p -> 'ok' }

        when:
        def re = script.toolCallZwave([action: 'reinterview', node_id: '12'])
        def lt = script.toolCallZwave([action: 'link_test_start', node_id: '12', link_test: [rounds: 5], confirm: true])
        def ls = script.toolCallZwave([action: 'link_test_stop', node_id: '12'])
        def cc = script.toolCallZwave([action: 'cc_command', node_id: '12', cc: [command_class: 37, method_name: 'set', args: [true]], confirm: true])

        then:
        re.success && lt.success && ls.success && cc.success
        hubGet.calls.find { it.path == '/hub/zwave2/reinterview' }.params == [node: '12']
        posts[0] == [path: '/hub/zwave2/linkReliability/start', body: [nodeId: 12, rounds: 5, intervalMs: 1000]]
        posts[1] == [path: '/hub/zwave2/linkReliability/abort', body: [nodeId: 12]]
        posts[2] == [path: '/hub/zwave2/ccCommand', body: [nodeId: 12, endpoint: 0, commandClass: 37, methodName: 'set', args: [true]]]
    }

    def "cc_command takes a 0x hex command class"() {
        given:
        enableWrite()
        def posts = jsonPosts()

        when:
        def r = script.toolCallZwave([action: 'cc_command', node_id: '12', cc: [command_class: '0x25', method_name: 'get', endpoint: '1'], confirm: true])

        then:
        r.success == true
        posts[0].body.commandClass == 37
        posts[0].body.endpoint == 1
    }

    def "an unreadable radio answer is not reported as success"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null -> [_unparseable: true, message: '<html>login</html>'] }

        when:
        def cc = script.toolCallZwave([action: 'cc_command', node_id: '12', cc: [command_class: 37, method_name: 'get'], confirm: true])
        def fw = script.toolCallDestructiveOps([target: 'zwave', action: 'device_firmware_start_available', node_id: '4', update_id: 'u-1', confirm: true])

        then:
        cc.success == false
        fw.success == false
    }

    def "link_test_start is refused without confirm, before anything is sent"() {
        given:
        enableWrite()
        def posts = jsonPosts()

        when:
        script.toolCallZwave([action: 'link_test_start', node_id: '12'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('confirm')
        posts.isEmpty()
    }

    @Unroll
    def "cc_command is refused before anything is sent: #label"() {
        given:
        enableWrite()
        def posts = jsonPosts()

        when:
        script.toolCallZwave([action: 'cc_command', node_id: node] + extra)

        then:
        thrown(IllegalArgumentException)
        posts.isEmpty()

        where:
        label               | node  | extra
        'no confirm'        | '12'  | [cc: [command_class: 37, method_name: 'get']]
        'no method'         | '12'  | [cc: [command_class: 37], confirm: true]
        'node not a number' | 'abc' | [cc: [command_class: 37, method_name: 'get'], confirm: true]
        'node checked first'| 'abc' | [cc: [command_class: 37, method_name: 'get']]
        'args not a list'   | '12'  | [cc: [command_class: 37, method_name: 'get', args: 'x'], confirm: true]
        'class not a number'| '12'  | [cc: [command_class: 'zz', method_name: 'get'], confirm: true]
    }

    def "local_backup_create starts a job and local_backup_keys normalizes the keys"() {
        given:
        enableWrite()
        def posts = jsonPosts()
        script.metaClass.hubInternalPost = { String path, Map body = null, int t = 30, boolean r = false -> '{"success":true,"jobId":"job-9"}' }

        when:
        def c = script.toolCallZwave([action: 'local_backup_create'])
        def k = script.toolCallZwave([action: 'local_backup_keys', import_id: 'imp-1',
                                      security_keys: [S0_Legacy: ' 0xAABBCCDDEEFF00112233445566778899 ', S2_Unauthenticated: 'c' * 32, S2_Authenticated: 'd' * 32,
                                                      S2_AccessControl: 'e' * 32, long_range: [S2_Authenticated: '0x' + '1' * 32]]])

        then:
        c.success == true
        c.jobId == 'job-9'
        k.success == true
        posts[0].path == '/hub/zwave/localBackup/securityKeys/imp-1'
        posts[0].body.securityKeys.S0_Legacy == 'AABBCCDDEEFF00112233445566778899'
        posts[0].body.securityKeysLongRange == [S2_Authenticated: '1' * 32]
        !posts[0].body.securityKeys.containsKey('long_range')
    }

    @Unroll
    def "Z-Wave argument objects refuse unknown keys and bad numbers: #label"() {
        given:
        enableWrite()
        def posts = jsonPosts()

        when:
        script.toolCallZwave([node_id: '12', confirm: true] + args)

        then:
        thrown(IllegalArgumentException)
        posts.isEmpty()

        where:
        label                      | args
        'camelCase link_test key'  | [action: 'link_test_start', link_test: [intervalMs: 5000]]
        'negative endpoint'        | [action: 'cc_command', cc: [command_class: 37, method_name: 'get', endpoint: -1]]
        'oversized command class'  | [action: 'cc_command', cc: [command_class: '99999999999', method_name: 'get']]
        'unknown cc key'           | [action: 'cc_command', cc: [command_class: 37, method_name: 'get', cmd: 'x']]
    }

    def "local_backup_download without confirm is refused, and the hub's own error is kept"() {
        given:
        enableWrite()
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map f = null, int t = 300 -> [status: 200, error: 'Job not complete'] }

        when:
        script.toolCallZwave([action: 'local_backup_download', job_id: 'job-9'])

        then:
        thrown(IllegalArgumentException)

        when:
        def r = script.toolCallZwave([action: 'local_backup_download', job_id: 'job-9', confirm: true])

        then:
        r.success == false
        r.error.contains('Job not complete')
    }

    def "a single-device firmware start reports the hub's refusal and defaults the target to 0"() {
        given:
        enableWrite()
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null ->
            posts << new JsonSlurper().parseText(body); [success: false, message: 'Node busy']
        }

        when:
        def r = script.toolCallDestructiveOps([target: 'zwave', action: 'device_firmware_start', node_id: '12', file_name: 'fw.otz', confirm: true])

        then:
        r.success == false
        r.error.contains('Node busy')
        posts[0].target == 0
    }

    @Unroll
    def "local_backup_keys refuses keys that are not network keys: #label"() {
        given:
        enableWrite()
        def posts = jsonPosts()

        when:
        script.toolCallZwave([action: 'local_backup_keys', import_id: 'imp-1', security_keys: keys])

        then:
        thrown(IllegalArgumentException)
        posts.isEmpty()

        where:
        label            | keys
        'grant booleans' | [S2_Authenticated: true]
        'short hex'      | [S0_Legacy: 'aabb']
        'unknown name'   | [S2Authenticated: 'a' * 32]
        'bad long range' | [S0_Legacy: 'a' * 32, long_range: [S0_Legacy: 'a' * 32]]
        'long range text'| [S0_Legacy: 'a' * 32, long_range: 'b' * 32]
    }

    def "local_backup_download saves the finished archive to File Manager"() {
        given:
        enableWrite()
        def saved = [:]
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map f = null, int t = 300 -> saved.path = path; [status: 200, bytes: ([0x1f, 0x8b, 8, 0] as byte[])] }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> saved.name = name; saved.size = bytes.length }

        when:
        def r = script.toolCallZwave([action: 'local_backup_download', job_id: 'job-9', confirm: true])

        then:
        r.success == true
        saved.path == '/hub/zwave/localBackup/download/job-9'
        saved.name == 'zwave-backup-job-9.tar.gz'
        saved.size == 4
    }

    def "local_backup_download refuses a reply that is not an archive"() {
        given:
        enableWrite()
        def saved = []
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map f = null, int t = 300 -> [status: 200, bytes: '<html>Login</html>'.getBytes('UTF-8')] }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> saved << name }

        when:
        def r = script.toolCallZwave([action: 'local_backup_download', job_id: 'job-9', confirm: true])

        then:
        r.success == false
        r.error.contains('Login')
        saved.isEmpty()
    }

    def "local_backup_import uploads the fetched backup and returns the importId"() {
        given:
        enableWrite()
        def up = [:]
        script.metaClass._fetchBytesFromUrl = { String url -> 'NVM'.getBytes('UTF-8') }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes -> up.path = path; up.fileName = fileName; [success: true, importId: 'imp-7'] }

        when:
        def r = script.toolCallZwave([action: 'local_backup_import', backup_url: 'https://files.example.com/dir/net.tar.gz?dl=1'])

        then:
        r.success == true
        r.importId == 'imp-7'
        up == [path: '/hub/zwave/localBackup/upload', fileName: 'net.tar.gz']
    }

    def "local_backup_restore, firmware-service and batch flashes go through the destructive tool"() {
        given:
        enableWrite()
        def posts = jsonPosts()

        when:
        def rs = script.toolCallDestructiveOps([target: 'zwave', action: 'local_backup_restore', import_id: 'imp-1', confirm: true])
        def sa = script.toolCallDestructiveOps([target: 'zwave', action: 'device_firmware_start_available', node_id: '4', update_id: 'u-1', confirm: true])
        def sb = script.toolCallDestructiveOps([target: 'zwave', action: 'device_firmware_batch_start', node_id: '4', batch: [node_ids: ['5', '6']], file_name: 'fw.otz', confirm: true])
        def sba = script.toolCallDestructiveOps([target: 'zwave', action: 'device_firmware_batch_start_available', node_id: '4', batch: [node_ids: ['5'], inactivity_timeout_seconds: 120], update_id: 'u-1', confirm: true])
        def ab = script.toolCallDestructiveOps([target: 'zwave', action: 'device_firmware_batch_abort', confirm: true])

        then:
        [rs, sa, sb, sba, ab].every { it.success == true }
        posts*.path == ['/hub/zwave/localBackup/restore/imp-1', '/hub/zwave/deviceFirmware/startAvailable',
                        '/hub/zwave/deviceFirmware/startBatch', '/hub/zwave/deviceFirmware/startAvailableBatch', '/hub/zwave/deviceFirmware/abortBatch']
        posts[0].body == [confirmation: 'RESTORE']
        posts[1].body == [nodeId: 4, updateId: 'u-1']
        posts[2].body == [sourceNodeId: 4, nodeIds: [5, 6], inactivityTimeoutSeconds: 600, target: 0, fileName: 'fw.otz']
        posts[3].body == [sourceNodeId: 4, nodeIds: [5], inactivityTimeoutSeconds: 120, updateId: 'u-1']
        posts[4].body == [:]
    }

    def "a refused firmware start surfaces the hub's message"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null -> [success: false, message: 'Not allowed over Remote Admin'] }

        when:
        def r = script.toolCallDestructiveOps([target: 'zwave', action: 'device_firmware_start_available', node_id: '4', update_id: 'u-1', confirm: true])

        then:
        r.success == false
        r.error.contains('Remote Admin')
    }

    // ---------------- Matter ----------------

    def "pair sends the 2.5.2 network-credentials request with the hub's stored Wi-Fi by default"() {
        given:
        enableWrite()
        def posts = jsonPosts()
        hubGet.register('/hub/matter/wifiCredentials') { p -> JsonOutput.toJson([selectedSsid: 'Home', storedSsid: 'Home', hasStoredPassword: true, passwordPlaceholder: '********']) }

        when:
        def r = script.toolCallMatter([action: 'pair', setup_code: ' 12345678901 '])

        then:
        r.success == true
        r.nodeId == '7'
        posts == [[path: '/hub/matter/pairWithNetworkCredentials', body: [setupCode: '12345678901', ssid: 'Home', password: '********']]]
        !hubGet.calls.any { it.path == '/hub/matter/pair' }
    }

    def "pair is refused when the hub's Wi-Fi network cannot be read"() {
        given:
        enableWrite()
        def posts = jsonPosts()

        when:
        def r = script.toolCallMatter([action: 'pair', setup_code: '1'])

        then:
        r.success == false
        r.error.contains('Wi-Fi')
        posts.isEmpty()
    }

    def "pair on a network without a stored password needs wifi_password"() {
        given:
        enableWrite()
        def posts = jsonPosts()
        hubGet.register('/hub/matter/wifiCredentials') { p -> JsonOutput.toJson([selectedSsid: 'Guest', storedSsid: 'Home', hasStoredPassword: true, passwordPlaceholder: '********']) }

        when:
        script.toolCallMatter([action: 'pair', setup_code: '1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('wifi_password')
        posts.isEmpty()
    }

    def "pair on firmware before 2.5.2 uses the setup-code request"() {
        given:
        enableWrite()
        sharedLocation.hub = hubOnFirmware('2.5.1.181')
        def posts = jsonPosts()
        hubGet.register('/hub/matter/pair') { p -> '{"success":true}' }

        when:
        def r = script.toolCallMatter([action: 'pair', setup_code: '123'])

        then:
        r.success == true
        hubGet.calls.find { it.path == '/hub/matter/pair' }.params == [setupCode: '123']
        posts.isEmpty()
    }

    def "pair with an explicit network sends it and skips the stored credentials"() {
        given:
        enableWrite()
        def posts = jsonPosts()

        when:
        script.toolCallMatter([action: 'pair', setup_code: '1', wifi_ssid: 'Guest', wifi_password: 'pw'])

        then:
        posts[0].body == [setupCode: '1', ssid: 'Guest', password: 'pw']
        !hubGet.calls.any { it.path == '/hub/matter/wifiCredentials' }
    }

    @Unroll
    def "pair fails when the hub returns #label"() {
        given:
        enableWrite()
        hubGet.register('/hub/matter/wifiCredentials') { p -> '{}' }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null -> answer }

        when:
        def r = script.toolCallMatter([action: 'pair', setup_code: '1'])

        then:
        r.success == false

        where:
        label         | answer
        'nodeId 0'    | [nodeId: 0]
        'no node'     | [:]
    }

    def "pair with the stored network's SSID and no password sends the placeholder"() {
        given:
        enableWrite()
        def posts = jsonPosts()
        hubGet.register('/hub/matter/wifiCredentials') { p -> JsonOutput.toJson([selectedSsid: 'Guest', storedSsid: 'Home', hasStoredPassword: true, passwordPlaceholder: '********']) }

        when:
        script.toolCallMatter([action: 'pair', setup_code: '1', wifi_ssid: 'Home'])

        then:
        posts[0].body == [setupCode: '1', ssid: 'Home', password: '********']
    }

    def "wifi_password without wifi_ssid is refused"() {
        when:
        script.toolCallMatter([action: 'pair', setup_code: '1', wifi_password: 'pw'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('wifi_ssid')
    }

    def "a nodeId of 0 is a failed pairing; cancel_pair calls cancelPair"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null -> [nodeId: '0', error: 'bad code'] }
        hubGet.register('/hub/matter/wifiCredentials') { p -> '{}' }
        hubGet.register('/hub/matter/cancelPair') { p -> '{"success":true}' }

        when:
        def failed = script.toolCallMatter([action: 'pair', setup_code: '1'])
        def cancel = script.toolCallMatter([action: 'cancel_pair', node_id: '42'])

        then:
        failed.success == false
        failed.error.contains('bad code')
        cancel.success == true
        hubGet.calls.find { it.path == '/hub/matter/cancelPair' }.params == [nodeId: '42']
    }

    // ---------------- backups ----------------

    private void backupStubs() {
        hubGet.register('/hub2/backup/json') { p -> '{"localBackupFrequency":1,"cloudBackupFrequency":0,"databaseCleanupTimeHour":3,"databaseCleanupJobMinute":0,"hasFullLocalBackup":true,"fullLocalBackupSupported":false,"fileManagerBackupExcludedCount":0,"lastNetworkBackupMessage":"","zwaveJsEnabled":false}' }
        hubGet.register('/hub2/networkBackup/settings') { p -> '{"enabled":true,"networkPath":"//nas/b","username":"u","password":"secret"}' }
        hubGet.register('/hub/backup/statusJson') { p -> '{"backupInProgress":false,"cloudBackupInProgress":false,"fullLocalBackupInProgress":false}' }
        script.metaClass.pauseExecution = { long ms -> null }
        script.metaClass.getHubSecurityCookie = { -> null }
    }

    def "hub_list_backups surfaces the 2.5.2 schedule fields and the network share without its password"() {
        given:
        backupStubs()
        hubGet.register('/hub2/localBackups') { p -> '[]' }

        when:
        def r = script.toolListItemBackups([scope: 'hub_local'])

        then:
        r.schedule.hasFullLocalBackup == true
        r.schedule.fileManagerBackupExcludedCount == 0
        r.networkBackup == [enabled: true, networkPath: '//nas/b', username: 'u', passwordSet: true]
        !JsonOutput.toJson(r).contains('secret')
        !r.partial
    }

    def "a hub without full local backups lists network backups as unavailable without asking"() {
        given:
        backupStubs()
        hubGet.register('/hub2/localBackups') { p -> '[]' }
        hubGet.register('/hub2/backup/json') { p -> '{"localBackupFrequency":1,"cloudBackupFrequency":0,"databaseCleanupTimeHour":3,"databaseCleanupJobMinute":0,"hasFullLocalBackup":false}' }

        when:
        def r = script.toolListItemBackups([scope: 'hub_local'])

        then:
        r.networkBackup.available == false
        !hubGet.calls.any { it.path == '/hub2/networkBackup/settings' }
        !r.partial
    }

    def "an increment refused by the load limiter says so"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [type: 'integer', value: 1] }
        script.metaClass.addValueToGlobalVar = { String n, Object v -> throw new RuntimeException('App 38 generates excessive hub load') }

        when:
        def r = script.toolSetVariable([name: 'counter', increment: 1])

        then:
        r.success == false
        r.note.contains('load limiter')
        !r.note.contains('Hub Mesh')
    }

    def "an unreadable network-share block does not mark the backup listing partial"() {
        given:
        backupStubs()
        hubGet.register('/hub2/localBackups') { p -> '[]' }
        hubGet.register('/hub2/networkBackup/settings') { p -> throw new RuntimeException('HTTP 500') }

        when:
        def r = script.toolListItemBackups([scope: 'hub_local'])

        then:
        r.networkBackup.error.contains('network backup settings')
        !r.partial
        !r.containsKey('hubBackupErrors')
    }

    def "full=true creates a full backup and confirms it by a NEW full entry"() {
        given:
        backupStubs()
        int reads = 0
        hubGet.register('/hub2/localBackups') { p ->
            reads++
            reads == 1 ? '[{"name":"full_old.tar.gz","fullBackup":true,"createTimeOrig":"2026-10-01T07:00:00+0000"}]' :
                         '[{"name":"full_new.tar.gz","fullBackup":true,"createTimeOrig":"2026-10-08T07:00:00+0000"}]'
        }

        when:
        def r = script.toolCreateHubBackup([full: true, confirm: true])

        then:
        r.success == true
        r.full == true
        r.confirmed == true
        stateMap.lastBackupTimestamp != null
        asyncPaths == ['/hub2/createFullLocalBackup']
    }

    def "mock=true with full=true stamps the gate without a real backup"() {
        given:
        backupStubs()
        settingsMap.enableDeveloperMode = true

        when:
        def r = script.toolCreateHubBackup([full: true, mock: true, confirm: true])

        then:
        r.success == true
        asyncPaths.isEmpty()
        stateMap.lastBackupTimestamp != null
    }

    def "an unconfirmed full backup does not stamp the destructive-op gate"() {
        given:
        backupStubs()
        hubGet.register('/hub2/localBackups') { p -> '[{"name":"full_old.tar.gz","fullBackup":true,"createTimeOrig":"2026-10-01T07:00:00+0000"}]' }

        when:
        def r = script.toolCreateHubBackup([full: true, confirm: true])

        then:
        r.success == false
        r.confirmed == false
        stateMap.lastBackupTimestamp == null
    }

    def "full=true on a hub without full backups is refused before the schedule is written"() {
        given:
        backupStubs()
        hubGet.register('/hub2/backup/json') { p -> '{"hasFullLocalBackup":false}' }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null -> posts << path; [success: true] }

        when:
        def r = script.toolCreateHubBackup([full: true, confirm: true, schedule: [hour: 3]])

        then:
        r.success == false
        r.scheduleUpdated == false
        posts.isEmpty()
        asyncPaths.isEmpty()
    }

    def "full=true on firmware before 2.5.2 is refused up front"() {
        given:
        backupStubs()
        sharedLocation.hub = hubOnFirmware('2.5.1.181')

        when:
        def r = script.toolCreateHubBackup([full: true, confirm: true])

        then:
        r.success == false
        r.error.contains('2.5.2')
        asyncPaths.isEmpty()
    }

    def "full=true is refused when the hub does not offer full local backups"() {
        given:
        backupStubs()
        hubGet.register('/hub2/backup/json') { p -> '{"hasFullLocalBackup":false}' }

        when:
        def r = script.toolCreateHubBackup([full: true, confirm: true])

        then:
        r.success == false
        r.error.contains('hasFullLocalBackup')
        !hubGet.calls.any { it.path == '/hub2/localBackups' }
    }

    def "a database backup is not confirmed by a full backup that lands meanwhile"() {
        given:
        backupStubs()
        hubGet.register('/hub/backup/statusJson') { p -> '{"backupInProgress":true,"cloudBackupInProgress":false}' }
        int reads = 0
        hubGet.register('/hub2/localBackups') { p ->
            reads++
            reads == 1 ? '[{"name":"db.lzf","fullBackup":false,"createTimeOrig":"2026-10-01T07:00:00+0000"}]' :
                         '[{"name":"db.lzf","fullBackup":false,"createTimeOrig":"2026-10-01T07:00:00+0000"},{"name":"full.tar.gz","fullBackup":true,"createTimeOrig":"2099-01-01T00:00:00+0000"}]'
        }

        when:
        def r = script.toolCreateHubBackup([confirm: true])

        then:
        r.success == false
        r.confirmed == false
    }

    def "networkBackup read-merges and keeps the saved password; testNetworkBackup tests the share"() {
        given:
        backupStubs()
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null ->
            posts << [path: path, body: new JsonSlurper().parseText(body)]; [success: true, message: 'Connection successful']
        }

        when:
        def r = script.toolCreateHubBackup([networkBackup: [networkPath: '//nas/new'], testNetworkBackup: true, scheduleOnly: true])

        then:
        r.success == true
        posts*.path == ['/hub2/networkBackup/settings', '/hub2/networkBackup/test']
        posts[0].body == [enabled: true, networkPath: '//nas/new', username: 'u', password: 'secret']
        r.networkBackup.passwordSet == true
        !r.networkBackup.containsKey('password')
        !hubGet.calls.any { it.path == '/hub2/localBackups' }
    }

    def "enabling the network share with no path is refused before the schedule is written"() {
        given:
        backupStubs()
        hubGet.register('/hub2/networkBackup/settings') { p -> '{"enabled":false,"networkPath":"","username":"","password":""}' }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null -> posts << path; [success: true] }

        when:
        script.toolCreateHubBackup([schedule: [hour: 3], networkBackup: [enabled: true], scheduleOnly: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('networkPath')
        posts.isEmpty()
    }

    @Unroll
    def "a failed network-share step says what was saved: #label"() {
        given:
        backupStubs()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false, Map q = null ->
            path.endsWith('updateBackupSchedule') ? [success: true] :
                path.endsWith('/settings') ? [success: saveOk, message: 'denied'] : [success: false, message: 'share unreachable']
        }

        when:
        def r = script.toolCreateHubBackup(args)

        then:
        r.success == false
        r.note.startsWith(saved)
        (r.backupCreated == false) == noBackup
        asyncPaths.isEmpty()

        where:
        label                                 | args                                                                                      | saveOk | saved                                                         | noBackup
        'schedule saved, share refused'       | [schedule: [hour: 3], networkBackup: [networkPath: '//n/b'], scheduleOnly: true]          | false  | 'Saved: the backup schedule.'                                 | false
        'settings saved, test failed'         | [networkBackup: [networkPath: '//n/b'], testNetworkBackup: true, scheduleOnly: true]      | true   | 'Saved: the network share settings.'                          | false
        'backup asked for, test failed'       | [testNetworkBackup: true, confirm: true]                                                  | true   | 'Nothing was saved. No backup was created.'                   | true
    }

    def "an unknown networkBackup field is refused before anything is written"() {
        given:
        backupStubs()

        when:
        script.toolCreateHubBackup([networkBackup: [share: 'x'], scheduleOnly: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Unknown networkBackup field')
    }

    def "cloudDownload copies a cloud backup into File Manager without creating a backup"() {
        given:
        backupStubs()
        hubGet.register('/hub2/cloudBackups') { p -> '{"backups":[{"path":"cloud/abc.lzf","fileSize":"7 MB"}]}' }
        def seen = [:]
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map form = null, int t = 300 -> seen.req = [m: m, path: path, form: form]; [status: 200, bytes: '-- H2 0.5/B -- \nDATA'.getBytes('UTF-8')] }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> seen.name = name }

        when:
        def r = script.toolCreateHubBackup([cloudDownload: [path: 'cloud/abc.lzf', cloudBackupPassword: 'pw'], confirm: true])

        then:
        r.success == true
        seen.req == [m: 'POST', path: '/hub2/downloadCloudDatabaseBackup', form: [fileName: 'cloud/abc.lzf', password: 'pw']]
        seen.name ==~ /cloud-backup-database-\d{8}-\d{6}\.lzf/
        !hubGet.calls.any { it.path == '/hub2/localBackups' }
    }

    def "cloudDownload refuses to save a reply that is not a backup"() {
        given:
        hubGet.register('/hub2/cloudBackups') { p -> '{"backups":[{"path":"cloud/abc","fileSize":"7 MB"}]}' }
        def saved = []
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map form = null, int t = 300 -> [status: 200, error: 'Invalid password'] }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> saved << name }

        when:
        def r = script.toolCreateHubBackup([cloudDownload: [path: 'cloud/abc', cloudBackupPassword: 'pw'], confirm: true])

        then:
        r.success == false
        r.error.contains('Invalid password')
        saved.isEmpty()
    }

    @Unroll
    def "cloudDownload refuses a reply that is not a #part backup"() {
        given:
        hubGet.register('/hub2/cloudBackups') { p -> '{"backups":[{"path":"cloud/abc","fileSize":"7 MB"}]}' }
        def saved = []
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map form = null, int t = 300 -> [status: 200, bytes: '<html>Sign in</html>'.getBytes('UTF-8')] }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> saved << name }

        when:
        def r = script.toolCreateHubBackup([cloudDownload: [path: 'cloud/abc', cloudBackupPassword: 'pw', part: part], confirm: true])

        then:
        r.success == false
        r.error.contains('Sign in')
        saved.isEmpty()

        where:
        part << ['database', 'files']
    }

    def "cloudDownload part=files saves the archive as .tar.gz"() {
        given:
        hubGet.register('/hub2/cloudBackups') { p -> '{"backups":[{"path":"cloud/abc","fileSize":"7 MB"}]}' }
        def seen = [:]
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map form = null, int t = 300 -> seen.path = path; [status: 200, bytes: ([0x1f, 0x8b, 8, 0] as byte[])] }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> seen.name = name }

        when:
        def r = script.toolCreateHubBackup([cloudDownload: [path: 'cloud/abc', cloudBackupPassword: 'pw', part: 'files'], confirm: true])

        then:
        r.success == true
        seen.path == '/hub2/downloadCloudFilesBackup'
        seen.name ==~ /cloud-backup-files-\d{8}-\d{6}\.tar\.gz/
    }

    def "cloudDownload without confirm is refused"() {
        when:
        script.toolCreateHubBackup([cloudDownload: [path: 'cloud/abc', cloudBackupPassword: 'pw']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('confirm=true')
    }

    def "a missing cloud backup is named as missing, not as having no size"() {
        given:
        hubGet.register('/hub2/cloudBackups') { p -> '{"backups":[]}' }

        when:
        def r = script.toolCreateHubBackup([cloudDownload: [path: 'cloud/nope', cloudBackupPassword: 'pw'], confirm: true])

        then:
        r.success == false
        r.error.contains('No backup')
    }

    @Unroll
    def "cloudDownload is refused before downloading when the cloud list gives #label"() {
        given:
        hubGet.register('/hub2/cloudBackups') { p -> listBody }
        def downloads = []
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map form = null, int t = 300 -> downloads << path; [status: 200, bytes: ([0x1f, 0x8b, 8, 0] as byte[])] }

        when:
        def r = script.toolCreateHubBackup([cloudDownload: [path: 'cloud/abc', cloudBackupPassword: 'pw', part: 'files'], confirm: true])

        then:
        r.success == false
        r.error.contains(expected)
        downloads.isEmpty()

        where:
        label            | listBody                                                 | expected
        'a large backup' | '{"backups":[{"path":"cloud/abc","fileSize":"40 MB"}]}'  | '16 MB'
        'no size'        | '{"backups":[{"path":"cloud/abc"}]}'                     | 'no size'
    }

    def "cloudDownload refuses to combine with other backup settings"() {
        when:
        script.toolCreateHubBackup([cloudDownload: [path: 'p', cloudBackupPassword: 'x'], full: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('cloudDownload runs on its own')
    }

    def "scope=hub_uploaded with a .tar.gz URL runs the full-restore flow"() {
        given:
        enableWrite()
        def up = [:]
        script.metaClass._fetchBytesFromUrl = { String url -> ([0x1f, 0x8b, 8, 0] as byte[]) }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes -> up.path = path; up.fileName = fileName; [success: true] }
        hubGet.register('/hub2/restoreFullLocalBackup') { p -> '{"success":true}' }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://host/x/full_backup.tar.gz?dl=1', confirm: true])

        then:
        r.success == true
        r.type == 'hub-full'
        up == [path: '/hub2/uploadFullLocalBackup', fileName: 'full_backup.tar.gz']
        !hubGet.calls.any { it.path == '/hub2/restoreUploadedBackup' }
    }

    @Unroll
    def "fullRestore is refused before anything is fetched: #label"() {
        given:
        enableWrite()
        def fetched = []
        script.metaClass._fetchBytesFromUrl = { String url -> fetched << url; ([0x1f, 0x8b, 8, 0] as byte[]) }

        when:
        script.toolRestoreItemBackup([confirm: true] + args)

        then:
        thrown(IllegalArgumentException)
        fetched.isEmpty()
        hubGet.calls.isEmpty()

        where:
        label              | args
        'cloud scope'      | [scope: 'hub_cloud', path: 'c/1', cloudBackupPassword: 'pw', fullRestore: [restoreZwave: true]]
        'database URL'     | [scope: 'hub_uploaded', backupUrl: 'https://h/x/db.lzf', fullRestore: [:]]
        'not a boolean'    | [scope: 'hub_uploaded', backupUrl: 'https://h/x/full.tar.gz', fullRestore: [restoreZwave: 'true']]
    }

    def "a full local backup over the in-app limit is refused before it is downloaded"() {
        given:
        enableWrite()
        hubGet.register('/hub2/localBackups') { p -> '[{"name":"full_big.tar.gz","fullBackup":true,"fileSize":"40 MB"}]' }
        def downloads = []
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map f = null, int t = 300 -> downloads << path; [status: 200, bytes: ([0x1f, 0x8b, 8, 0] as byte[])] }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_local', fileName: 'full_big.tar.gz', confirm: true])

        then:
        r.success == false
        r.error.contains('16 MB')
        downloads.isEmpty()
    }

    def "a full restore request the hub never answers is an unknown outcome, not a failure"() {
        given:
        enableWrite()
        script.metaClass._fetchBytesFromUrl = { String url -> ([0x1f, 0x8b, 8, 0] as byte[]) }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes -> [success: true] }
        hubGet.register('/hub2/restoreFullLocalBackup') { p -> throw new RuntimeException('Read timed out') }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://h/x/full.tar.gz', confirm: true])

        then:
        r.success == false
        r.outcome == 'unknown'
        r.note.contains('Do not resend')
    }

    def "_postMultipartBackup sends the file between the multipart header and trailer"() {
        given:
        def sent = [:]
        httpPostHook = { Map params, Closure c -> sent.params = params; c([status: 200, data: [success: true]]) }

        when:
        script._postMultipartBackup('/hub2/uploadBackup', 'uploadFile', 'x.lzf', [1, 2, 3] as byte[])

        then:
        def body = new String(sent.params.body as byte[], 'ISO-8859-1')
        def boundary = (sent.params.requestContentType =~ /boundary=(.+)$/)[0][1]
        body.startsWith("--${boundary}\r\nContent-Disposition: form-data; name=\"uploadFile\"; filename=\"x.lzf\"\r\n")
        body.endsWith("\r\n\u0001\u0002\u0003\r\n--${boundary}--\r\n")
    }

    // ---------------- files ----------------

    def "hub_list_files lists a folder with entry types, backup flags and free space"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/fileManager/json') { p ->
            JsonOutput.toJson([backupSelection: [excludedFiles: 1], freeSpace: 991530715,
                               files: [[type: 'dir', name: 'sub', size: '0'], [type: 'file', name: 'a.js', size: '10', date: '1', backupIncluded: false]]])
        }

        when:
        def r = script.toolListFiles([folder: '/webcore/'])

        then:
        hubGet.calls[0].params == [folder: 'webcore']
        r.folder == 'webcore'
        r.freeSpaceBytes == 991530715
        r.filesExcludedFromFullBackup == 1
        r.files.find { it.name == 'sub' } == [name: 'sub', type: 'dir']
        r.files.find { it.name == 'a.js' }.directDownload == 'http://<HUB_IP>/local/webcore/a.js'
        r.files.find { it.name == 'a.js' }.backupIncluded == false
    }

    def "an empty or missing folder lists no files, not the response's other fields"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/fileManager/json') { p -> JsonOutput.toJson([backupSelection: [excludedFiles: 0], files: [], path: 'nope', freeSpace: 5]) }

        when:
        def r = script.toolListFiles([folder: 'nope'])

        then:
        r.files == []
        r.total == 0
    }

    def "an empty folder listing says the folder may not exist"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/fileManager/json') { p -> JsonOutput.toJson([files: [], freeSpace: 5]) }

        when:
        def r = script.toolListFiles([folder: 'webcoer'])

        then:
        r.note.contains('does not exist')
    }

    def "hub_list_files refuses a folder on firmware before 2.5.2"() {
        given:
        sharedLocation.hub = hubOnFirmware('2.5.1.181')

        when:
        script.toolListFiles([folder: 'webcore'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('2.5.2')
        hubGet.calls.isEmpty()
    }

    @Unroll
    def "hub_list_files refuses an unsafe folder: #folder"() {
        when:
        script.toolListFiles([folder: folder])

        then:
        thrown(IllegalArgumentException)
        hubGet.calls.isEmpty()

        where:
        folder << ['../etc', 'a/../b', 'a//b', 'x;rm']
    }

    // ---------------- apps, dependents, events ----------------

    def "hub_list_apps instances carry the hub's deprecated flag"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub2/appsList') { p ->
            JsonOutput.toJson([apps: [[data: [id: 5, name: 'Sonos Integration', type: 'Sonos', disabled: false, user: false, hidden: false, deprecated: true], children: []],
                                      [data: [id: 6, name: 'Rule Machine', type: 'RM', disabled: false, user: false, hidden: false], children: []]]])
        }

        when:
        def r = script.toolListInstalledApps([:])

        then:
        r.apps.find { it.id == 5 }.deprecated == true
        r.apps.find { it.id == 6 }.deprecated == false
    }

    def "hub_list_device_dependents adds the apps only getAppsUsingDevice reports"() {
        given:
        childDevicesList << [id: '42', label: 'Kitchen', name: 'Switch']
        hubGet.register('/device/fullJson/42') { p ->
            JsonOutput.toJson([name: 'Switch', appsUsing: [[id: 100, name: 'Rule-5.1', label: 'R', disabled: false]], appsUsingCount: 1])
        }
        script.metaClass.getAppsUsingDevice = { Long id ->
            [[id: 100L, name: 'Rule-5.1', label: 'R'], [id: 3L, name: 'Easy Mobile Dashboard', label: 'Favorites', disabled: false]]
        }

        when:
        def r = script.toolGetDeviceInUseBy([deviceId: '42'])

        then:
        r.appsUsing*.id == [100, 3L]
        r.appsUsing[1].name == 'Easy Mobile Dashboard'
        r.count == 2
        !r.containsKey('countMismatch')
        !r.containsKey('platformLookup')
    }

    def "hub_list_device_dependents keeps only the parent app's identity"() {
        given:
        childDevicesList << [id: '42', label: 'Kitchen', name: 'Switch']
        hubGet.register('/device/fullJson/42') { p ->
            JsonOutput.toJson([name: 'Switch', appsUsing: [], appsUsingCount: 0,
                               parentApp: [id: 194, name: 'MCP Rule Server', label: 'MCP', parentAppId: null, appType: [oauthClientSecret: 's3cret']]])
        }
        script.metaClass.getAppsUsingDevice = { Long id -> [] }

        when:
        def r = script.toolGetDeviceInUseBy([deviceId: '42'])

        then:
        r.parentApp == [id: 194, name: 'MCP Rule Server', label: 'MCP', parentAppId: null]
        !JsonOutput.toJson(r).contains('s3cret')
    }

    def "hub_list_device_dependents keeps its own list when the platform lookup is missing"() {
        given:
        childDevicesList << [id: '42', label: 'Kitchen', name: 'Switch']
        hubGet.register('/device/fullJson/42') { p -> JsonOutput.toJson([name: 'Switch', appsUsing: [[id: 100, name: 'Rule-5.1', label: 'R']], appsUsingCount: 1]) }

        when:
        def r = script.toolGetDeviceInUseBy([deviceId: '42'])

        then:
        r.appsUsing*.id == [100]
        r.count == 1
        r.platformLookup == 'unavailable'
    }

    def "until bounds the location history window"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/logs/eventsJson') { p ->
            JsonOutput.toJson([[name: 'mode', value: 'Night', date: '2026-10-08T03:00:00.000+0000'],
                               [name: 'mode', value: 'Day', date: '2026-10-08T02:10:00.000+0000'],
                               [name: 'mode', value: 'Away', date: '2026-10-08T01:00:00.000+0000']])
        }

        when:
        def r = script.toolGetDeviceHistory([since: '2026-10-08T02:00:00.000+0000', until: '2026-10-08T02:15:00.000+0000'])

        then:
        r.events*.value == ['Day']
        r.untilTimestamp != null
    }

    def "until on its own routes a device read to the bounded history, not the latest events"() {
        given:
        settingsMap.enableRead = true
        def routed = []
        script.metaClass.toolGetDeviceEvents = { Object id, Object limit -> routed << 'recent'; [events: []] }
        script.metaClass.toolGetDeviceHistory = { Map a -> routed << 'history'; [events: [], untilTimestamp: 'x'] }

        when:
        def r = mcpDriver.callTool('hub_list_device_events', [deviceId: '42', until: '2026-10-08T02:15:00.000+0000'])

        then:
        r.error == null
        routed == ['history']
    }

    def "until earlier than the window start is refused"() {
        when:
        script.toolGetDeviceHistory([since: '2026-10-08T02:00:00.000+0000', until: '2026-10-08T01:00:00.000+0000'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('until must be later')
    }

    // ---------------- variables ----------------

    @Unroll
    def "hub_list_variables type filter keeps one hub type and drops rule-engine variables (type=#type)"() {
        given:
        settingsMap.enableRead = true
        stateMap.ruleVariables = [r: 1]
        script.metaClass.getAllGlobalVars = { -> [n: [type: 'integer', value: 1], s: [type: 'string', value: 'x']] }

        when:
        def r = script.toolListVariables([type: type])

        then:
        r.hubVariables*.name == ['n']
        r.ruleVariables == []

        where:
        type << ['Number', 'integer']
    }

    def "the type filter maps Decimal and refuses an unknown type"() {
        given:
        settingsMap.enableRead = true
        script.metaClass.getAllGlobalVars = { -> [d: [type: 'bigdecimal', value: 1.5], n: [type: 'integer', value: 1]] }

        when:
        def r = script.toolListVariables([type: 'Decimal'])

        then:
        r.hubVariables*.name == ['d']

        when:
        script.toolListVariables([type: 'Float'])

        then:
        thrown(IllegalArgumentException)
    }

    def "increment adds to a Decimal variable"() {
        given:
        def current = [value: 1.5]
        script.metaClass.getGlobalVar = { String n -> [type: 'bigdecimal', value: current.value] }
        script.metaClass.addValueToGlobalVar = { String n, Object v -> current.value = current.value + (v as BigDecimal); true }

        when:
        def r = script.toolSetVariable([name: 'temp', increment: 0.25])

        then:
        r.success == true
        r.value == 1.75
        !r.containsKey('note')
    }

    def "an increment the hub refuses or throws on is a failed write, not a validation error"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [type: 'integer', value: 1] }
        script.metaClass.addValueToGlobalVar = { String n, Object v -> if (v == 1) return false; throw new RuntimeException('linked variable') }

        when:
        def refused = script.toolSetVariable([name: 'counter', increment: 1])
        def threw = script.toolSetVariable([name: 'counter', increment: 2])

        then:
        refused.success == false
        refused.error.contains('did not apply')
        threw.success == false
        threw.error.contains('linked variable')
    }

    def "a Number variable given a fractional increment says the hub rounded it"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [type: 'integer', value: 1] }
        script.metaClass.addValueToGlobalVar = { String n, Object v -> true }

        when:
        def r = script.toolSetVariable([name: 'counter', increment: 1.5])

        then:
        r.success == true
        r.note.contains('rounded')
    }

    def "an unreadable variable is a failed increment with nothing written"() {
        given:
        def adds = []
        script.metaClass.getGlobalVar = { String n -> throw new RuntimeException('busy') }
        script.metaClass.addValueToGlobalVar = { String n, Object v -> adds << v; true }

        when:
        def r = script.toolSetVariable([name: 'counter', increment: 1])

        then:
        r.success == false
        r.note.contains('Nothing was changed')
        adds.isEmpty()
    }

    def "an increment whose read-back fails says the value is unverified"() {
        given:
        int reads = 0
        script.metaClass.getGlobalVar = { String n -> if (++reads > 1) throw new RuntimeException('busy'); [type: 'integer', value: 1] }
        script.metaClass.addValueToGlobalVar = { String n, Object v -> true }

        when:
        def r = script.toolSetVariable([name: 'counter', increment: 1])

        then:
        r.success == true
        r.verified == false
        r.note.contains('hub_get_variable')
    }

    def "increment adds atomically through addValueToGlobalVar"() {
        given:
        def adds = []
        def current = [value: 10]
        script.metaClass.getGlobalVar = { String n -> [type: 'integer', value: current.value] }
        script.metaClass.addValueToGlobalVar = { String n, Object v -> adds << [n, v]; current.value = current.value + (v as int); true }

        when:
        def r = script.toolSetVariable([name: 'counter', increment: 5])

        then:
        r.success == true
        adds == [['counter', 5]]
        r.previousValue == 10
        r.value == 15
    }

    @Unroll
    def "increment is refused before any write: #label"() {
        given:
        script.metaClass.getGlobalVar = { String n -> var }
        def adds = []
        script.metaClass.addValueToGlobalVar = { String n, Object v -> adds << v; true }

        when:
        script.toolSetVariable([name: 'v'] + args)

        then:
        thrown(IllegalArgumentException)
        adds.isEmpty()

        where:
        label              | var                              | args
        'with value'       | [type: 'integer', value: 1]      | [increment: 1, value: '2']
        'not a number'     | [type: 'integer', value: 1]      | [increment: 'abc']
        'string variable'  | [type: 'string', value: 'x']     | [increment: 1]
        'no hub variable'  | null                             | [increment: 1]
    }

    // ---------------- platform API docs ----------------

    static final String DOCS_INDEX = JsonOutput.toJson([schemaVersion: 1, contentRevision: '64', guides: [[id: 'allowed-imports', title: 'Allowed imports', summary: 'Check which external imports are available.', url: 'https://docs2.hubitat.com/x']],
        pages: [[id: 'api-com-hubitat-app-devicewrapper', className: 'com.hubitat.app.DeviceWrapper', label: 'Device wrapper', section: 'shared', topic: 'Device wrapper',
                 methods: [[kind: 'method', name: 'eventsBetween', signature: 'List<Event> eventsBetween(Date startDate, Date endDate, Map options = null)', summary: 'Read recent events within a date range.'],
                           [kind: 'method', name: 'eventsSince', signature: 'List<Event> eventsSince(Date startDate, Map options = null)', summary: 'Read recent events after a date.']]],
                [id: 'api-hubitat-zwave-x', className: 'hubitat.zwave.X', label: 'X', section: 'protocols', topic: 'Z-Wave',
                 methods: [[kind: 'method', name: 'events', signature: 'void events()', summary: 'protocol events between frames']]]]])

    def "platform_api_search pages 25 matches at a time"() {
        given:
        hubGet.register('/developer-docs/index.json') { p ->
            JsonOutput.toJson([contentRevision: '1', guides: [], pages: [[id: 'api-x', className: 'X', label: 'X', section: 'shared',
                methods: (1..30).collect { [kind: 'method', name: "events${it}", signature: "void events${it}()", summary: 'events'] }]]])
        }

        when:
        def first = script.toolGetToolGuide(null, null, [platform_api_search: 'events'])
        def second = script.toolGetToolGuide(null, first.nextCursor, [platform_api_search: 'events'])

        then:
        first.total == 30
        first.matches.size() == 25
        second.matches.size() == 5
        !second.containsKey('nextCursor')
    }

    def "platform_api_search finds a method on its class page"() {
        given:
        hubGet.register('/developer-docs/index.json') { p -> DOCS_INDEX }

        when:
        def r = script.toolGetToolGuide(null, null, [platform_api_search: 'eventsBetween'])

        then:
        r.success == true
        r.total == 1
        r.matches[0].name == 'eventsBetween'
        r.matches[0].pageId == 'api-com-hubitat-app-devicewrapper'
        !r.containsKey('nextCursor')
    }

    def "platform_api_search matches a class name and a method name together"() {
        given:
        hubGet.register('/developer-docs/index.json') { p -> DOCS_INDEX }

        when:
        def r = script.toolGetToolGuide(null, null, [platform_api_search: 'DeviceWrapper eventsBetween'])

        then:
        r.total == 1
        r.matches[0].name == 'eventsBetween'
    }

    def "a broad platform_api_search puts shared-API hits ahead of protocol pages"() {
        given:
        hubGet.register('/developer-docs/index.json') { p -> DOCS_INDEX }

        when:
        def r = script.toolGetToolGuide(null, null, [platform_api_search: 'events'])

        then:
        r.matches.findIndexOf { it.section == 'shared' } < r.matches.findIndexOf { it.section == 'protocols' }
    }

    def "platform_api_page returns a class's methods with full descriptions"() {
        given:
        hubGet.register('/developer-docs/api-com-hubitat-app-devicewrapper.json') { p ->
            JsonOutput.toJson([id: 'api-com-hubitat-app-devicewrapper', className: 'com.hubitat.app.DeviceWrapper', label: 'Device wrapper', section: 'shared',
                               methods: (1..45).collect { [kind: 'method', name: "m${it}", signature: "void m${it}()", descriptionMarkdown: "Does ${it}."] }])
        }

        when:
        def first = script.toolGetToolGuide(null, null, [platform_api_page: 'api-com-hubitat-app-devicewrapper'])
        def second = script.toolGetToolGuide(null, first.nextCursor, [platform_api_page: 'api-com-hubitat-app-devicewrapper'])

        then:
        first.totalMethods == 45
        first.methods.size() == 40
        first.methods[0].description == 'Does 1.'
        second.methods.size() == 5
        !second.containsKey('nextCursor')
    }

    def "platform_api_page shortens a page whose descriptions would run past ~60 KB"() {
        given:
        hubGet.register('/developer-docs/api-big.json') { p ->
            JsonOutput.toJson([id: 'api-big', className: 'Big', label: 'Big', section: 'shared',
                               methods: (1..40).collect { [kind: 'method', name: "m${it}", signature: "void m${it}()", descriptionMarkdown: 'x' * 3000] }])
        }

        when:
        def first = script.toolGetToolGuide(null, null, [platform_api_page: 'api-big'])

        then:
        first.methods.size() < 40
        JsonOutput.toJson(first.methods).length() <= 60000
        first.nextCursor == first.methods.size().toString()
    }

    def "platform_api_page cuts a single oversized description and flags it"() {
        given:
        hubGet.register('/developer-docs/api-huge.json') { p ->
            JsonOutput.toJson([id: 'api-huge', className: 'Huge', label: 'Huge', section: 'shared',
                               methods: [[kind: 'method', name: 'm', signature: 'void m()', descriptionMarkdown: 'y' * 150000]]])
        }

        when:
        def r = script.toolGetToolGuide(null, null, [platform_api_page: 'api-huge'])

        then:
        r.methods[0].description.length() == 20000
        r.methods[0].descriptionTruncated == true
    }

    @Unroll
    def "a backup URL over the in-app limit is refused before it is fetched: #label"() {
        given:
        enableWrite()
        def fetched = []
        script.metaClass._probeUrl = { String url, long cap -> [size: size] }
        script.metaClass._fetchBytesFromUrl = { String url -> fetched << url; ([0x1f, 0x8b, 8, 0] as byte[]) }

        when:
        def r = invoke.call(script)

        then:
        r.success == false
        r.error.contains('MB')
        fetched.isEmpty()

        where:
        label            | size              | invoke
        'full restore'   | 40L * 1024 * 1024 | { s -> s.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://h/x/full.tar.gz', confirm: true]) }
        'database'       | 9L * 1024 * 1024  | { s -> s.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://h/x/db.lzf', confirm: true]) }
        'Z-Wave import'  | 9L * 1024 * 1024  | { s -> s.toolCallZwave([action: 'local_backup_import', backup_url: 'https://h/x/net.tar.gz']) }
    }

    def "_probeUrl reads the total from a ranged answer"() {
        given:
        def sent = [:]
        httpGetHook = { Map params, Closure c -> sent.range = params.headers?.Range; c([status: 206, headers: ['Content-Range': 'bytes 0-0/12345']]) }

        expect:
        script._probeUrl('https://h/x/full.tar.gz', 16L * 1024 * 1024) == [size: 12345L]
        sent.range == 'bytes=0-0'
    }

    def "_probeUrl keeps the whole body from a host that ignores the range"() {
        given:
        httpGetHook = { Map params, Closure c -> c([status: 200, data: [1, 2, 3] as byte[]]) }

        when:
        def p = script._probeUrl('https://h/x/db.lzf', 16L * 1024 * 1024)

        then:
        p.size == 3L
        p.bytes == ([1, 2, 3] as byte[])
    }

    def "_probeUrl gives nothing when the request fails"() {
        given:
        httpGetHook = { Map params, Closure c -> throw new RuntimeException('connection refused') }

        expect:
        script._probeUrl('https://h/x/db.lzf', 16L * 1024 * 1024) == [:]
    }

    def "_probeUrl keeps only the size of a range-ignoring answer over the cap"() {
        given:
        httpGetHook = { Map params, Closure c -> c([status: 200, headers: headers, data: [1, 2, 3, 4, 5, 6] as byte[]]) }

        expect:
        script._probeUrl('https://h/x/full.tar.gz', 4L) == [size: size]

        where:
        headers                      || size
        ['Content-Length': '99999'] || 99999L
        [:]                          || 6L
    }

    def "a backup URL's whole body from a range-ignoring host is restored without a second fetch"() {
        given:
        enableWrite()
        def fetched = []
        script.metaClass._probeUrl = { String url, long cap -> [size: 4L, bytes: ([0x1f, 0x8b, 8, 0] as byte[])] }
        script.metaClass._fetchBytesFromUrl = { String url -> fetched << url; null }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes -> [success: true] }
        hubGet.register('/hub2/restoreFullLocalBackup') { p -> '{"success":true}' }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://h/x/full.tar.gz', confirm: true])

        then:
        r.success == true
        fetched.isEmpty()
    }

    @Unroll
    def "hub_uploaded routes on the fetched bytes: #label"() {
        given:
        enableWrite()
        def uploads = []
        script.metaClass._fetchBytesFromUrl = { String u -> body }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes -> uploads << path; [success: true] }
        hubGet.register('/hub2/restoreFullLocalBackup') { p -> '{"success":true}' }
        hubGet.register('/hub2/restoreUploadedBackup') { p -> '{"success":true}' }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: url, confirm: true] + extra)

        then:
        r.success == ok
        uploads == expected

        where:
        label                                  | url                     | body                                     | extra                           | ok    | expected
        'an extension-less gzip is a full one' | 'https://h/dl?id=5'     | ([0x1f, 0x8b, 8, 0] as byte[])                         | [:]                             | true  | ['/hub2/uploadFullLocalBackup']
        'an H2 file is a database backup'      | 'https://h/dl?id=6'     | '-- H2 0.5/B -- \nDATA'.getBytes('UTF-8') | [:]                             | true  | ['/hub2/uploadBackup']
        'anything else is refused'             | 'https://h/x/b.lzf'     | '<html>login</html>'.getBytes('UTF-8')   | [:]                             | false | []
        'fullRestore on a database file'       | 'https://h/dl?id=7'     | '-- H2 0.5/B -- \nDATA'.getBytes('UTF-8') | [fullRestore: [restoreZwave: true]] | false | []
    }

    def "a full backup from a URL with no path is uploaded under a file name, not the host"() {
        given:
        enableWrite()
        def up = [:]
        script.metaClass._fetchBytesFromUrl = { String u -> ([0x1f, 0x8b, 8, 0] as byte[]) }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes -> up.fileName = fileName; [success: true] }
        hubGet.register('/hub2/restoreFullLocalBackup') { p -> '{"success":true}' }

        when:
        script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://backups.example.com', confirm: true])

        then:
        up.fileName == 'full-backup.tar.gz'
    }

    def "an uploaded database restore whose upload throws is a failure, not an unknown outcome"() {
        given:
        enableWrite()
        script.metaClass._fetchBytesFromUrl = { String url -> '-- H2 0.5/B -- \nDATA'.getBytes('UTF-8') }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes -> throw new RuntimeException('Read timed out') }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://h/x/b.lzf', confirm: true])

        then:
        r.success == false
        r.outcome == null
        r.note.contains('Nothing was restored')
        !hubGet.calls.any { it.path == '/hub2/restoreUploadedBackup' }
    }

    def "a restore the hub answers with an HTTP error is a definite refusal"() {
        given:
        enableWrite()
        hubGet.register('/hub2/localBackups') { p -> '[{"name":"db.lzf","fullBackup":false}]' }
        hubGet.register('/hub2/restoreLocalBackup') { p -> throw new FakeHttpException(500) }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_local', fileName: 'db.lzf', confirm: true])

        then:
        r.success == false
        r.outcome == null
        r.error.contains('refused')
        r.note.contains('Nothing was restored')
    }

    @Unroll
    def "full-restore guards after the fetch: #label"() {
        given:
        enableWrite()
        def uploads = []
        script.metaClass._fetchBytesFromUrl = { String u -> body }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes -> uploads << path; answer }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://h/x/full.tar.gz', confirm: true])

        then:
        r.success == false
        r.error.contains(expected)
        !hubGet.calls.any { it.path == '/hub2/restoreFullLocalBackup' }

        where:
        label               | body                                                              | answer                      | expected
        'over 16 MB'        | bigGzip()                                                         | [success: true]             | '16 MB'
        'upload rejected'   | ([0x1f, 0x8b, 8, 0] as byte[])                                                  | [success: false, message: 'disk full'] | 'disk full'
    }

    def "a Z-Wave backup import over 8 MB after the fetch is refused"() {
        given:
        enableWrite()
        def ups = []
        script.metaClass._fetchBytesFromUrl = { String url -> new byte[8 * 1024 * 1024 + 1] }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes -> ups << path; [success: true] }

        when:
        def r = script.toolCallZwave([action: 'local_backup_import', backup_url: 'https://h/x/net.tar.gz'])

        then:
        r.success == false
        r.error.contains('8 MB')
        ups.isEmpty()
    }

    def "a full local backup with no listed size is not downloaded"() {
        given:
        enableWrite()
        hubGet.register('/hub2/localBackups') { p -> '[{"name":"full_x.tar.gz","fullBackup":true}]' }
        def downloads = []
        script.metaClass.hubInternalBytes = { String m, String path, Map q = null, Map f = null, int t = 300 -> downloads << path; [status: 200, bytes: ([0x1f, 0x8b, 8, 0] as byte[])] }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_local', fileName: 'full_x.tar.gz', confirm: true])

        then:
        r.success == false
        r.error.contains('no size')
        downloads.isEmpty()
    }

    @Unroll
    def "API docs arguments are validated: #label"() {
        when:
        script.toolGetToolGuide(section, null, extra)

        then:
        thrown(IllegalArgumentException)

        where:
        label                 | section  | extra
        'with a section'      | 'rules'  | [platform_api_search: 'x']
        'both lookups'        | null     | [platform_api_search: 'x', platform_api_page: 'y']
        'bad page id'         | null     | [platform_api_page: '../etc']
        'empty search'        | null     | [platform_api_search: '   ']
    }

    @Unroll
    def "platform_api_search reaches a client through hub_get_tool_guide dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/developer-docs/index.json') { p -> DOCS_INDEX }

        when:
        def response = mcpDriver.callTool('hub_get_tool_guide', [platform_api_search: 'eventsBetween'])

        then:
        response.error == null
        response.result.isError != true
        mcpDriver.parseInner(response).matches[0].name == 'eventsBetween'

        where:
        useGateways << [true, false]
    }

    // ---------------- diagnostics helpers ----------------

    def "_typeName and _exceptionWithLine fall back without the 2.5.2 platform helpers"() {
        expect:
        script._typeName([a: 1]) == 'Map'
        script._typeName(null) == 'null'
        script._exceptionWithLine(new IllegalStateException('boom')) == 'java.lang.IllegalStateException: boom'
    }

    def "_exceptionWithLine and _exceptionStack use the 2.5.2 platform helpers when present"() {
        given:
        exceptionLineHook = { Throwable t -> 'IllegalStateException: boom on line 42' }
        stackTraceHook = { Throwable t -> 'at app.method(app:42)\n' + ('x' * 3000) }

        expect:
        script._exceptionWithLine(new IllegalStateException('boom')) == 'IllegalStateException: boom on line 42'
        script._exceptionStack(new IllegalStateException('boom')).length() == 2000
    }

    def "an error record keeps the failing line and the stack frames"() {
        given:
        def records = []
        script.metaClass.mcpLog = { String level, String component, String message, String ruleId = null, Map extra = null -> records << extra }

        when:
        script.mcpLogError('test', 'failed', new IllegalStateException('boom'))

        then:
        records[0].stackTrace.startsWith('java.lang.IllegalStateException: boom')
        records[0].stackTrace.contains('\n')
    }

    @Unroll
    def "hubInternalBytes keeps only a byte body as bytes: #label"() {
        given:
        httpGetHook = { Map params, Closure c -> c([status: 200, data: data]) }

        when:
        def got = script.hubInternalBytes('GET', '/x')

        then:
        (got.bytes != null) == isBytes
        (got.error != null) == !isBytes

        where:
        label         | data                                  | isBytes
        'byte array'  | [1, 2] as byte[]                      | true
        'JSON map'    | [message: 'Invalid password']         | false
        'HTML text'   | '<html>Sign in</html>'                | false
        'empty bytes' | new byte[0]                           | false
    }

    def "hubInternalBytes turns an HTTP error into its status and the hub's message"() {
        given:
        httpGetHook = { Map params, Closure c ->
            def e = new FakeHttpException(403)
            e.response.data = '{"message":"Forbidden"}'
            throw e
        }

        when:
        script.hubInternalBytes('GET', '/x')

        then:
        def ex = thrown(RuntimeException)
        ex.message == 'HTTP 403: Forbidden'
    }

    @Unroll
    def "_parseSizeBytes reads the hub's size strings: #text"() {
        expect:
        script._parseSizeBytes(text) == bytes

        where:
        text      | bytes
        '7 MB'    | 7L * 1024 * 1024
        '512 KB'  | 512L * 1024
        '1.5 GB'  | (long) (1.5 * 1024 * 1024 * 1024)
        '2048'    | 2048L
        '7 MiB'   | null
        '7,340 KB'| null
    }

    def "_typeName uses getObjectClassName when the platform has it"() {
        given:
        script.metaClass.getObjectClassName = { Object o -> 'java.util.LinkedHashMap' }

        expect:
        script._typeName([a: 1]) == 'LinkedHashMap'
    }
}
