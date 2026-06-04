package server

import support.ToolSpecBase

/**
 * Guards that every leaf tool is classified read or write, so the central
 * Read/Write master gate (#113) can never leave a tool ungated. The read set is
 * getReadOnlyToolNames(); the write set is its complement over getAllToolDefinitions().
 */
class ToolClassificationCompletenessSpec extends ToolSpecBase {

    def "every leaf tool is classified read or write, and the partition is total + disjoint"() {
        given:
        def all = script.getAllToolDefinitions()*.name as Set
        def readOnly = script.getReadOnlyToolNames()
        def writes = all - readOnly

        expect:
        all.size() > 0
        // no stale names in the read set
        (readOnly - all).isEmpty()
        // disjoint
        readOnly.intersect(writes).isEmpty()
        // total
        (readOnly + writes) == all
    }

    def "no gateway NAME is in the read-only leaf set (gateways are gated per sub-tool)"() {
        given:
        def gatewayNames = script.getGatewayConfig().keySet()
        def readOnly = script.getReadOnlyToolNames()

        expect:
        gatewayNames.every { !readOnly.contains(it) }
    }
}
