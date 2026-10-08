package server

import support.ToolSpecBase

/**
 * Size-budget guard for the issue #299 best-practice gate. The bestPracticeKey argument the
 * enableMandatoryBPS gate reads is INTENTIONALLY undeclared in every tool inputSchema: declaring
 * a property on every write tool would inflate the flat tools/list toward the 120KB cap. The LLM
 * learns the param from the block-message contract + the best_practice_reference guide instead.
 * This fails if a future edit declares bestPracticeKey on any schema (re-introducing the bloat).
 */
class BpsSchemaSizeGuardSpec extends ToolSpecBase {

    def "no tool inputSchema declares the bestPracticeKey property (undeclared by design)"() {
        when:
        def offenders = script.getAllToolDefinitions().findAll { tool ->
            tool?.inputSchema?.properties instanceof Map &&
                tool.inputSchema.properties.containsKey('bestPracticeKey')
        }*.name

        then:
        offenders == []
    }

    // Issue #518: a client that drops undeclared top-level arguments loses the key on a direct
    // (non-gateway) write tool, so each one declares an opaque `args` object to carry it -- the
    // gateways' own `args` already does this for their sub-tools. Kept property-less so no schema
    // names the key.
    def "every direct write tool declares an opaque args object"() {
        given:
        def proxied = script.getGatewayConfig().values().collectMany { it.tools } as Set
        def reads = script.getReadOnlyToolNames() as Set
        def direct = script.getAllToolDefinitions().findAll { !proxied.contains(it.name) && !reads.contains(it.name) }

        expect:
        direct*.name.sort() == ['hub_create_backup', 'hub_manage_mode', 'hub_manage_virtual_device', 'hub_set_hsm',
                                'hub_set_mode_manager', 'hub_set_system_settings', 'hub_update_firmware', 'hub_update_package']
        direct.findAll { it.inputSchema?.properties?.args != [type: 'object'] }*.name == []
    }
}
