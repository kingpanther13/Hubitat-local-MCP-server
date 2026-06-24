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
}
