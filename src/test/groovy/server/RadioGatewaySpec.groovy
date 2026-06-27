package server

import support.TestLocation
import support.ToolSpecBase
import groovy.json.JsonOutput
import spock.lang.Shared

/**
 * Issue #257 hub_manage_radio gateway — full Z-Wave/Zigbee/Matter radio surface.
 *
 * Structural guards (gateway wiring, tool presence, annotation classification).
 * Per-tool behavior tests (endpoint paths, confirm gating) follow once the build
 * lands. radio dispatch reads location.hub, so wire a non-null TestLocation.
 */
class RadioGatewaySpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    // Write master + a recent backup so requireDestructiveConfirm(confirm) passes.
    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches the harness's fixed now()
    }

    /** Carries an HTTP status the way HttpResponseException does (duck-typed e.response.status). */
    private static class FakeHttpException extends RuntimeException {
        final def response
        FakeHttpException(int status) {
            super("HTTP ${status}")
            this.response = [status: status]
        }
    }

    private Map gw() { script.getGatewayConfig() }
    private List allNames() { script.getAllToolDefinitions()*.name }

    // ---- Gateway wiring ----

    def "hub_manage_radio gateway exists and carries the radio write tools"() {
        expect:
        gw().containsKey('hub_manage_radio')
        gw()['hub_manage_radio'].tools.containsAll([
            'hub_set_zwave', 'hub_call_zwave', 'hub_set_zigbee', 'hub_call_zigbee', 'hub_call_matter'
        ])
    }

    def "executeTool routes the hub_manage_radio gateway NAME to handleGateway (regression: missing dispatch case)"() {
        given:
        settingsMap.useGateways = true   // gateway mode -> the no-tool call returns the sub-tool catalog
        settingsMap.enableRead = true
        settingsMap.enableWrite = true

        when:
        def r = script.executeTool('hub_manage_radio', [:])

        then:
        // Was 'Unknown tool: hub_manage_radio' when the gateway lacked an executeTool dispatch case.
        def text = JsonOutput.toJson(r)
        !text.contains('Unknown tool')
        text.contains('hub_set_zwave')
        text.contains('hub_call_zwave')
    }

    def "hub_get_radio_details is multi-gateway and never stranded (reachable read-only)"() {
        when:
        def g = gw()

        then:
        // present in its write gateways AND in a pure-read gateway (AGENTS.md: no read stranded in manage_)
        g['hub_manage_radio'].tools.contains('hub_get_radio_details')
        g['hub_read_diagnostics'].tools.contains('hub_get_radio_details')
    }

    def "the destructive ops tool lives ONLY in hub_manage_destructive_ops, not hub_manage_radio"() {
        when:
        def g = gw()

        then:
        g['hub_manage_destructive_ops'].tools.contains('hub_call_destructive_ops')
        !g['hub_manage_radio'].tools.contains('hub_call_destructive_ops')
    }

    def "the old hub_call_destructive_radio name is fully gone (hard rename, no alias)"() {
        expect:
        !allNames().contains('hub_call_destructive_radio')
        allNames().contains('hub_call_destructive_ops')
        !gw()['hub_manage_destructive_ops'].tools.contains('hub_call_destructive_radio')
    }

    def "hub_call_zwave_repair is absorbed into hub_call_zwave (old tool gone, new tool present)"() {
        expect:
        !allNames().contains('hub_call_zwave_repair')
        allNames().contains('hub_call_zwave')
    }

    def "every new radio tool is defined exactly once"() {
        given:
        def names = allNames()

        expect:
        ['hub_set_zwave', 'hub_call_zwave', 'hub_set_zigbee', 'hub_call_zigbee',
         'hub_call_matter', 'hub_call_destructive_ops'].each { t ->
            assert names.count { it == t } == 1 : "${t} should be defined exactly once"
        }
    }

    // ---- Annotation classification ----

    def "radio reads are readOnly; set_* are idempotent writes; call_*/destructive are not"() {
        when:
        def readOnly = script.getReadOnlyToolNames()
        def idempotent = script.getIdempotentToolNames()

        then:
        readOnly.contains('hub_get_radio_details')
        !readOnly.contains('hub_set_zwave')
        !readOnly.contains('hub_call_destructive_ops')

        and: 'set_* config writes are idempotent; call_* operations are not'
        idempotent.contains('hub_set_zwave')
        idempotent.contains('hub_set_zigbee')
        !idempotent.contains('hub_call_zwave')
        !idempotent.contains('hub_call_zigbee')
        !idempotent.contains('hub_call_matter')
        !idempotent.contains('hub_call_destructive_ops')
    }

    def "all radio tools are closed-world (radio is the closed system)"() {
        when:
        def openWorld = script.getOpenWorldToolNames()

        then:
        ['hub_set_zwave', 'hub_call_zwave', 'hub_set_zigbee', 'hub_call_zigbee',
         'hub_call_matter', 'hub_call_destructive_ops', 'hub_get_radio_details'].each { t ->
            assert !openWorld.contains(t) : "${t} should be closed-world (openWorld=false)"
        }
    }

    // ---- Display meta (every tool + the new gateway need an entry) ----

    def "every new radio tool + the gateway has a display-meta entry"() {
        when:
        def meta = script.getToolDisplayMeta()

        then:
        ['hub_set_zwave', 'hub_call_zwave', 'hub_set_zigbee', 'hub_call_zigbee',
         'hub_call_matter', 'hub_call_destructive_ops', 'hub_manage_radio'].each { t ->
            assert meta[t]?.title : "${t} needs a display-meta title"
        }
    }

    // ---- Safety gates (throw BEFORE any hub call — no stubs needed) ----

    def "hub_set_zwave requires enabled and/or config"() {
        when: script.toolSetZwave([:])
        then: thrown(IllegalArgumentException)
    }

    def "hub_set_zwave disable is confirm-gated"() {
        when: script.toolSetZwave([enabled: false])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_set_zigbee channel without power_level is rejected"() {
        when: script.toolSetZigbee([channel: 20])
        then: thrown(IllegalArgumentException)
    }

    def "hub_call_zwave requires an action"() {
        when: script.toolCallZwave([:])
        then: thrown(IllegalArgumentException)
    }

    def "hub_call_zwave per-node action '#action' requires node_id"() {
        when: script.toolCallZwave([action: action])
        then: thrown(IllegalArgumentException)
        where: action << ['repair_node', 'node_refresh', 'node_rediscover', 'node_reinitialize', 'node_replace']
    }

    def "hub_call_zwave exclusion_start is confirm-gated"() {
        when: script.toolCallZwave([action: 'exclusion_start'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_call_zwave node_remove is confirm-gated"() {
        when: script.toolCallZwave([action: 'node_remove', node_id: '7'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_call_zwave grant_keys requires security_keys; smartstart_delete requires node_dsk"() {
        when: script.toolCallZwave([action: 'grant_keys'])
        then: thrown(IllegalArgumentException)

        when: script.toolCallZwave([action: 'smartstart_delete'])
        then: thrown(IllegalArgumentException)
    }

    def "hub_call_matter pair requires setup_code; open_pairing_window requires node_id; disable is confirm-gated"() {
        when: script.toolCallMatter([action: 'pair'])
        then: thrown(IllegalArgumentException)

        when: script.toolCallMatter([action: 'open_pairing_window'])
        then: thrown(IllegalArgumentException)

        when: script.toolCallMatter([action: 'disable'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_call_destructive_ops requires confirm before anything else"() {
        when: script.toolCallDestructiveOps([target: 'zwave', action: 'reset'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_call_destructive_ops rejects a firmware action on the wrong radio"() {
        given: enableWrite()   // sets Write master + a recent backup so requireDestructiveConfirm passes
        when: script.toolCallDestructiveOps([target: 'zigbee', action: 'device_firmware_start', confirm: true, node_id: '5', file_name: 'fw.bin'])
        then: thrown(IllegalArgumentException)   // device_firmware_start is Z-Wave only
    }

    // ---- Endpoint smokes (no-query paths, robust to query-match nuances) ----

    def "hub_set_zwave enabled=true hits /hub/zwave/enable/true"() {
        given:
        hubGet.register('/hub/zwave/enable/true') { p -> 'ok' }
        when:
        def r = script.toolSetZwave([enabled: true])
        then:
        r.success == true
        r.radio == 'zwave'
        r.enabled == true
    }

    def "hub_call_zwave repair_cancel hits /hub/zwaveCancelRepair"() {
        given:
        hubGet.register('/hub/zwaveCancelRepair') { p -> 'cancelled' }
        when:
        def r = script.toolCallZwave([action: 'repair_cancel'])
        then:
        r.success == true
        r.action == 'repair_cancel'
    }

    def "hub_call_zigbee rebuild_network hits /hub/rebuildZigbeeNetwork with a disruption warning"() {
        given:
        hubGet.register('/hub/rebuildZigbeeNetwork') { p -> 'rebuilding' }
        when:
        def r = script.toolCallZigbee([action: 'rebuild_network'])
        then:
        r.success == true
        r.warning?.toLowerCase()?.contains('unresponsive')
    }

    def "hub_call_destructive_ops reset zwave hits /hub/zwave/resetJson with an irreversible warning"() {
        given:
        enableWrite()   // recent backup so requireDestructiveConfirm passes
        hubGet.register('/hub/zwave/resetJson') { p -> 'reset done' }
        when:
        def r = script.toolCallDestructiveOps([target: 'zwave', action: 'reset', confirm: true])
        then:
        r.success == true
        r.target == 'zwave'
        r.warning?.toLowerCase()?.contains('irreversible')
    }

    // ---- target=network (disconnect) + target=cloud (disable/enable) ----

    def "hub_call_destructive_ops network disconnect_wifi hits /hub/advanced/disconnectWiFi"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/disconnectWiFi') { p -> 'ok' }
        when:
        def r = script.toolCallDestructiveOps([target: 'network', action: 'disconnect_wifi', confirm: true])
        then:
        r.success == true
        r.target == 'network'
        r.action == 'disconnect_wifi'
        hubGet.calls.any { it.path == '/hub/advanced/disconnectWiFi' }
    }

    def "hub_call_destructive_ops network disconnect_ethernet hits /hub/advanced/disconnectEthernet"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/disconnectEthernet') { p -> 'ok' }
        when:
        def r = script.toolCallDestructiveOps([target: 'network', action: 'disconnect_ethernet', confirm: true])
        then:
        r.success == true
        r.target == 'network'
        hubGet.calls.any { it.path == '/hub/advanced/disconnectEthernet' }
    }

    def "hub_call_destructive_ops cloud disable hits /hub/advanced/disableCloudController and warns about cloud features"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/disableCloudController') { p -> 'ok' }
        when:
        def r = script.toolCallDestructiveOps([target: 'cloud', action: 'disable', confirm: true])
        then:
        r.success == true
        r.target == 'cloud'
        r.action == 'disable'
        hubGet.calls.any { it.path == '/hub/advanced/disableCloudController' }
        r.warning?.toLowerCase()?.contains('alexa')
    }

    def "hub_call_destructive_ops cloud enable hits /hub/advanced/enableCloudController"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/enableCloudController') { p -> 'ok' }
        when:
        def r = script.toolCallDestructiveOps([target: 'cloud', action: 'enable', confirm: true])
        then:
        r.success == true
        r.target == 'cloud'
        r.action == 'enable'
        hubGet.calls.any { it.path == '/hub/advanced/enableCloudController' }
    }

    def "hub_call_destructive_ops network/cloud are confirm-gated (throw before any hub call)"() {
        when: script.toolCallDestructiveOps([target: 'network', action: 'disconnect_wifi'])
        then:
        def e1 = thrown(IllegalArgumentException)
        e1.message.contains('SAFETY CHECK FAILED')

        when: script.toolCallDestructiveOps([target: 'cloud', action: 'disable'])
        then:
        def e2 = thrown(IllegalArgumentException)
        e2.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_call_destructive_ops rejects an unknown action for network/cloud targets"() {
        given: enableWrite()
        when: script.toolCallDestructiveOps([target: 'network', action: 'bogus', confirm: true])
        then: thrown(IllegalArgumentException)

        when: script.toolCallDestructiveOps([target: 'cloud', action: 'bogus', confirm: true])
        then: thrown(IllegalArgumentException)
    }

    def "dispatch: hub_call_destructive_ops cloud disable routes through executeTool"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/disableCloudController') { p -> 'ok' }
        when:
        def r = script.executeTool('hub_call_destructive_ops', [target: 'cloud', action: 'disable', confirm: true])
        then:
        r.success == true
        r.action == 'disable'
    }

    def "dispatch: hub_call_matter pair routes through executeTool and commissions by setup code"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub/matter/pair?setupCode=12345678901') { p -> JsonOutput.toJson([success: true]) }
        when:
        def r = script.executeTool('hub_call_matter', [action: 'pair', setup_code: '12345678901'])
        then:
        r.success == true
        r.action == 'pair'
    }

    // ---- Silent-failure fix: a hub fault on a write surfaces as success:false ----

    def "a hub fault on a write GET returns success:false, not a fabricated success"() {
        given:
        // No handler registered for /hub/zwave/enable/true -> hubInternalGet throws ->
        // throwing _radioGet propagates -> the tool's catch reports success:false.
        when:
        def r = script.toolSetZwave([enabled: true])

        then:
        r.success == false
        r.error
    }

    def "a hub fault on a destructive reset returns success:false (no fabricated 'unpaired')"() {
        given:
        enableWrite()
        // /hub/zwave/resetJson not registered -> throws -> success:false.
        when:
        def r = script.toolCallDestructiveOps([target: 'zwave', action: 'reset', confirm: true])

        then:
        r.success == false
    }

    // ---- POST-path verification (verb + path + body shape) ----

    def "hub_call_zwave node_refresh POSTs form-encoded zwaveNodeId"() {
        given:
        def posted = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body, int t = 420, boolean retry = false ->
            posted.path = path; posted.body = body; [data: 'ok']
        }

        when:
        def res = script.toolCallZwave([action: 'node_refresh', node_id: '7'])

        then:
        posted.path == '/hub/zwave/refreshNodeStatus'
        posted.body == [zwaveNodeId: '7']
        res.success == true
    }

    def "hub_call_zwave grant_keys POSTs JSON to /hub/zwave/securityKeys"() {
        given:
        def posted = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean retry = false ->
            posted.path = path; posted.body = body; [success: true]
        }

        when:
        def res = script.toolCallZwave([action: 'grant_keys', security_keys: [S2Authenticated: true]])

        then:
        posted.path == '/hub/zwave/securityKeys'
        posted.body.contains('S2Authenticated')
        res.success == true
    }

    def "hub_call_zwave node_replace POSTs JSON to /hub2/zwave/nodeReplace"() {
        given:
        def posted = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean retry = false ->
            posted.path = path; posted.body = body; [ok: true]
        }

        when:
        def res = script.toolCallZwave([action: 'node_replace', node_id: '9'])

        then:
        posted.path == '/hub2/zwave/nodeReplace'
        posted.body.contains('zwaveNodeId')
        posted.body.contains('9')
        res.success == true
    }

    def "hub_call_zwave smartstart_delete POSTs nodeDSK to /mobileapi/zwave/smartstart/delete"() {
        given:
        def posted = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean retry = false ->
            posted.path = path; posted.body = body; [ok: true]
        }

        when:
        def res = script.toolCallZwave([action: 'smartstart_delete', node_dsk: 'ABCD-1234'])

        then:
        posted.path == '/mobileapi/zwave/smartstart/delete'
        posted.body.contains('nodeDSK')
        posted.body.contains('ABCD-1234')
    }

    def "hub_call_destructive_ops device_firmware_start POSTs fileName to the firmware endpoint"() {
        given:
        enableWrite()
        def posted = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean retry = false ->
            posted.path = path; posted.body = body; [ok: true]
        }

        when:
        def res = script.toolCallDestructiveOps([target: 'zwave', action: 'device_firmware_start', node_id: '5', file_name: 'fw.gbl', confirm: true])

        then:
        posted.path == '/hub/zwave/deviceFirmware/start'
        posted.body.contains('fileName')
        posted.body.contains('fw.gbl')
        res.success == true
    }

    // ---- repair_start: modern endpoint + legacy fallback ----

    def "repair_start hits zwaveRepair2 and reports the 5-30 min duration"() {
        given:
        hubGet.register('/hub/zwaveRepair2?resetStats=false&maxHealth=10') { p -> 'started' }

        when:
        def res = script.toolCallZwave([action: 'repair_start'])

        then:
        res.success == true
        res.duration?.contains('5-30')
    }

    def "repair_start falls back to legacy /hub/zwaveRepair when zwaveRepair2 errors"() {
        given:
        // zwaveRepair2 unregistered -> _radioGetSafe returns {error} -> legacy fallback fires.
        hubGet.register('/hub/zwaveRepair') { p -> 'legacy started' }

        when:
        def res = script.toolCallZwave([action: 'repair_start'])

        then:
        res.success == true
    }

    // ---- include_* read folds ----

    def "include_status attaches the zwave/zigbee pollers and omits the dropped matter slot"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { p -> JsonOutput.toJson([enabled: true]) }
        hubGet.register('/hub/zwaveRepair2Status') { p -> JsonOutput.toJson([stage: 'IDLE']) }
        hubGet.register('/hub/checkZwaveRepairRunning') { p -> JsonOutput.toJson([isZWaveNetworkHealRunning: 'false']) }
        hubGet.register('/hub/zwaveExclude/status') { p -> JsonOutput.toJson([message: '']) }
        hubGet.register('/hub/zigbeeInfo/status') { p -> JsonOutput.toJson([networkState: 'ONLINE']) }

        when:
        def r = script.toolGetRadioDetails([radio: 'zwave', include_status: true])

        then:
        r.status?.containsKey('zwaveRepair')
        r.status?.containsKey('zigbee')
        !r.status?.containsKey('matter')
    }

    def "a failing include surfaces an {error} under its key without failing the whole read"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { p -> JsonOutput.toJson([enabled: true]) }
        // matterLogs NOT registered -> _radioGetSafe returns {error}; the read still succeeds.

        when:
        def r = script.toolGetRadioDetails([radio: 'zwave', include_logs: true])

        then:
        r instanceof Map          // the base read came back, did not throw
        r.matterLogs?.error       // the failed include is visible, not thrown
    }

    // ---- config-merge: a region change preserves the radio's other settings ----

    def "hub_set_zwave region change preserves the radio's current long-range channel"() {
        given:
        hubGet.register('/hub/zwaveDetails/json') { p ->
            JsonOutput.toJson([enabled: true, region: 'US', secureJoin: 1, longRangeChannel: 2])
        }
        hubGet.register('/hub/zwaveDetails/update?enabled=true&region=EU&secureJoin=1&longRangeChannel=2') { p -> 'ok' }

        when:
        def r = script.toolSetZwave([region: 'EU'])

        then:
        r.success == true
        r.region == 'EU'
        r.longRangeChannel == 2   // merged from current state, not dropped to a default
    }

    // ---- remaining GET smokes + confirm-gate + unknown-action default ----

    def "hub_set_zigbee disable is confirm-gated"() {
        when: script.toolSetZigbee([enabled: false])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_call_matter enable hits /hub/matter/enable/true with a reboot warning"() {
        given:
        hubGet.register('/hub/matter/enable/true') { p -> 'ok' }

        when:
        def r = script.toolCallMatter([action: 'enable'])

        then:
        r.success == true
        r.warning?.toLowerCase()?.contains('reboot')
    }

    def "hub_call_zigbee radio_reboot hits /hub/rebootZigbeeRadio"() {
        given:
        hubGet.register('/hub/rebootZigbeeRadio') { p -> 'ok' }

        when:
        def r = script.toolCallZigbee([action: 'radio_reboot'])

        then:
        r.success == true
    }

    def "unknown radio actions throw IllegalArgumentException"() {
        when: script.toolCallZwave([action: 'bogus'])
        then: thrown(IllegalArgumentException)

        when: script.toolCallZigbee([action: 'bogus'])
        then: thrown(IllegalArgumentException)

        when: script.toolCallMatter([action: 'bogus'])
        then: thrown(IllegalArgumentException)
    }

    // ---- Final-pass additions: pollers, node_replace_stop, zigbee settings/ping, matter per-node, null-fix ----

    def "hub_call_zwave node_replace_stop POSTs to /hub/zwave/nodeReplace/stop"() {
        given:
        def posted = []
        script.metaClass.hubInternalPost = { String path, Map body = null, int t = 30, boolean retry = false ->
            posted << path; 'stopped'
        }

        when:
        def r = script.toolCallZwave([action: 'node_replace_stop'])

        then:
        posted == ['/hub/zwave/nodeReplace/stop']
        r.success == true
        r.action == 'node_replace_stop'
    }

    def "hub_get_radio_details radio='matter' + node_id polls matterPairDeviceStatus (not the zwave node-state)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/matterDetails/json') { p -> JsonOutput.toJson([enabled: true]) }
        hubGet.register('/hub/matterPairDeviceStatus?nodeId=3001') { p -> JsonOutput.toJson([initMap: [:], deviceMap: [:]]) }

        when:
        def r = script.toolGetRadioDetails([radio: 'matter', node_id: '3001'])

        then:
        r.matterPairStatus != null
        !r.containsKey('nodeState')
    }

    def "include_status attaches the join/antenna/nodeReplace pollers"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/zwaveDetails/json') { p -> JsonOutput.toJson([enabled: true]) }
        ['/hub/zwaveRepair2Status', '/hub/checkZwaveRepairRunning', '/hub/zwaveExclude/status',
         '/hub/searchZwaveDevices', '/hub/zwave2/antennaTestProgress', '/hub/zwave/nodeReplace/status',
         '/hub/zwave/nodeReplace/info', '/hub/zigbeeInfo/status'].each { ep -> hubGet.register(ep) { p -> '{}' } }

        when:
        def r = script.toolGetRadioDetails([radio: 'zwave', include_status: true])

        then:
        r.status?.containsKey('zwaveJoinDiscovery')
        r.status?.containsKey('zwaveAntennaTest')
        r.status?.zwaveNodeReplace?.containsKey('status')
        r.status?.zwaveNodeReplace?.containsKey('info')
        r.status?.containsKey('zwaveRepair')    // pre-existing pollers survive the rewrite
        r.status?.containsKey('zwaveExclude')
    }

    def "hub_set_zigbee settings merge preserves the unspecified flag"() {
        given:
        hubGet.register('/hub/zigbeeDetails/json') { p -> JsonOutput.toJson([rebuildNetworkOnReboot: false, inactiveDevicePingEnabled: true]) }
        hubGet.register('/hub/zigbee/updateSettings?rebuildNetworkOnReboot=true&inactiveDevicePingEnabled=true') { p -> 'ok' }

        when:
        def r = script.toolSetZigbee([rebuild_on_reboot: true])

        then:
        r.success == true
        r.rebuildNetworkOnReboot == true
        r.inactiveDevicePingEnabled == true   // preserved from current state
    }

    def "hub_set_zigbee settings 404 (no Zigbee radio) names an absent radio, not a credential problem"() {
        given:
        // Current state reads fine; the updateSettings write 404s -- on the e2e hub that means
        // the Zigbee radio is absent/disabled, NOT a Hub Security credential failure.
        hubGet.register('/hub/zigbeeDetails/json') { p -> JsonOutput.toJson([rebuildNetworkOnReboot: false, inactiveDevicePingEnabled: false]) }
        hubGet.register('/hub/zigbee/updateSettings?rebuildNetworkOnReboot=true&inactiveDevicePingEnabled=false') { p ->
            throw new FakeHttpException(404)
        }

        when:
        def r = script.toolSetZigbee([rebuild_on_reboot: true])

        then:
        r.success == false
        // (a) the requested path is EXACTLY the zigbee updateSettings path with both query flags
        hubGet.calls*.path.contains('/hub/zigbee/updateSettings?rebuildNetworkOnReboot=true&inactiveDevicePingEnabled=false')
        // (b) the note names an absent Zigbee radio and does NOT mislead toward credentials
        r.note?.contains('Zigbee radio')
        r.note?.toLowerCase()?.contains('absent')
        !r.note?.toLowerCase()?.contains('credential')
    }

    def "hub_set_zigbee settings non-404 fault keeps the Hub Security credential hint"() {
        given:
        hubGet.register('/hub/zigbeeDetails/json') { p -> JsonOutput.toJson([rebuildNetworkOnReboot: false, inactiveDevicePingEnabled: false]) }
        hubGet.register('/hub/zigbee/updateSettings?rebuildNetworkOnReboot=true&inactiveDevicePingEnabled=false') { p ->
            throw new FakeHttpException(403)   // auth-class fault -> credential hint stays
        }

        when:
        def r = script.toolSetZigbee([rebuild_on_reboot: true])

        then:
        r.success == false
        r.note?.toLowerCase()?.contains('credential')
        !r.note?.contains('Zigbee radio')
    }

    def "hub_set_zigbee ping_device toggles per-device keep-alive ping"() {
        given:
        hubGet.register('/hub/zigbee/updatePingDevice/0x1234/true') { p -> 'ok' }

        when:
        def r = script.toolSetZigbee([ping_device: [device_id: '0x1234', enabled: true]])

        then:
        r.success == true
        r.pingDevice.deviceId == '0x1234'
        r.pingDevice.enabled == true
    }

    def "hub_set_zwave long-range-only config does not NPE when region is absent (Gemini null-filter)"() {
        given:
        hubGet.register('/hub/zwaveDetails/json') { p -> JsonOutput.toJson([enabled: true, secureJoin: 0]) }   // no region key
        hubGet.register('/hub/zwaveDetails/update?enabled=true&secureJoin=0&longRangeChannel=1') { p -> 'ok' }

        when:
        def r = script.toolSetZwave([long_range_channel: 1])

        then:
        r.success == true   // null region param filtered out, no NullPointerException
    }

    def "hub_set_zigbee with no operation specified is rejected"() {
        when: script.toolSetZigbee([:])
        then: thrown(IllegalArgumentException)
    }

    def "hub_set_zigbee ping_device requires {device_id, enabled}"() {
        when: script.toolSetZigbee([ping_device: [enabled: true]])   // missing device_id
        then: thrown(IllegalArgumentException)

        when: script.toolSetZigbee([ping_device: 'not-a-map'])
        then: thrown(IllegalArgumentException)
    }

    def "hub_set_zigbee settings refuses to fabricate an unspecified flag when current state lacks it"() {
        given:
        // zigbeeDetails comes back MISSING inactiveDevicePingEnabled while ping_inactive is omitted;
        // updateSettings is intentionally NOT registered so the only success path is the refuse guard.
        hubGet.register('/hub/zigbeeDetails/json') { p -> JsonOutput.toJson([rebuildNetworkOnReboot: false]) }

        when:
        def r = script.toolSetZigbee([rebuild_on_reboot: true])

        then:
        r.success == false                       // did NOT silently write inactiveDevicePingEnabled=false
        r.error?.contains('ping-inactive')       // refused for the unreadable flag, not a hub-fault throw
    }

    def "dispatch: hub_call_zwave node_replace_stop routes through executeTool"() {
        given:
        settingsMap.enableWrite = true
        def posted = []
        script.metaClass.hubInternalPost = { String path, Map body = null, int t = 30, boolean retry = false ->
            posted << path; 'stopped'
        }

        when:
        def r = script.executeTool('hub_call_zwave', [action: 'node_replace_stop'])

        then:
        posted == ['/hub/zwave/nodeReplace/stop']
        r.success == true
    }

    def "dispatch: hub_set_zigbee ping_device routes through executeTool"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub/zigbee/updatePingDevice/0xABCD/false') { p -> 'ok' }

        when:
        def r = script.executeTool('hub_set_zigbee', [ping_device: [device_id: '0xABCD', enabled: false]])

        then:
        r.success == true
        r.pingDevice.deviceId == '0xABCD'
    }
}
