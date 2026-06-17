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

    def "hub_get_radio_details is multi-gateway and never stranded (reachable read-only)"() {
        when:
        def g = gw()

        then:
        // present in its write gateways AND in a pure-read gateway (AGENTS.md: no read stranded in manage_)
        g['hub_manage_radio'].tools.contains('hub_get_radio_details')
        g['hub_read_diagnostics'].tools.contains('hub_get_radio_details')
    }

    def "the destructive radio tool lives ONLY in hub_manage_destructive_ops, not hub_manage_radio"() {
        when:
        def g = gw()

        then:
        g['hub_manage_destructive_ops'].tools.contains('hub_call_destructive_radio')
        !g['hub_manage_radio'].tools.contains('hub_call_destructive_radio')
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
         'hub_call_matter', 'hub_call_destructive_radio'].each { t ->
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
        !readOnly.contains('hub_call_destructive_radio')

        and: 'set_* config writes are idempotent; call_* operations are not'
        idempotent.contains('hub_set_zwave')
        idempotent.contains('hub_set_zigbee')
        !idempotent.contains('hub_call_zwave')
        !idempotent.contains('hub_call_zigbee')
        !idempotent.contains('hub_call_matter')
        !idempotent.contains('hub_call_destructive_radio')
    }

    def "all radio tools are closed-world (radio is the closed system)"() {
        when:
        def openWorld = script.getOpenWorldToolNames()

        then:
        ['hub_set_zwave', 'hub_call_zwave', 'hub_set_zigbee', 'hub_call_zigbee',
         'hub_call_matter', 'hub_call_destructive_radio', 'hub_get_radio_details'].each { t ->
            assert !openWorld.contains(t) : "${t} should be closed-world (openWorld=false)"
        }
    }

    // ---- Display meta (every tool + the new gateway need an entry) ----

    def "every new radio tool + the gateway has a display-meta entry"() {
        when:
        def meta = script.getToolDisplayMeta()

        then:
        ['hub_set_zwave', 'hub_call_zwave', 'hub_set_zigbee', 'hub_call_zigbee',
         'hub_call_matter', 'hub_call_destructive_radio', 'hub_manage_radio'].each { t ->
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

    def "hub_call_destructive_radio requires confirm before anything else"() {
        when: script.toolCallDestructiveRadio([radio: 'zwave', action: 'reset'])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_call_destructive_radio rejects a firmware action on the wrong radio"() {
        given: enableWrite()   // sets Write master + a recent backup so requireDestructiveConfirm passes
        when: script.toolCallDestructiveRadio([radio: 'zigbee', action: 'device_firmware_start', confirm: true, node_id: '5', file_name: 'fw.bin'])
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

    def "hub_call_destructive_radio reset zwave hits /hub/zwave/resetJson with an irreversible warning"() {
        given:
        enableWrite()   // recent backup so requireDestructiveConfirm passes
        hubGet.register('/hub/zwave/resetJson') { p -> 'reset done' }
        when:
        def r = script.toolCallDestructiveRadio([radio: 'zwave', action: 'reset', confirm: true])
        then:
        r.success == true
        r.radio == 'zwave'
        r.warning?.toLowerCase()?.contains('irreversible')
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
}
