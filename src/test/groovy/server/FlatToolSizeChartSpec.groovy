package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Issue #296 step 1 diagnostic: break the flat-mode tools/list catalog down
 * per tool and emit a ranked chart (largest -> smallest) plus a per-component
 * byte breakdown, so the audit/refactor can target the real offenders.
 *
 * Reuses the exact measurement the size tripwires use (UpdateNativeAppSchemaTrimSpec /
 * HandleMcpRequestDispatchSpec): script.getToolDefinitions() with useGateways=false,
 * then JsonOutput.toJson(tool) UTF-8 byte length. Widest catalog = every feature
 * toggle on (custom rule engine + developer mode) so every flat tool appears.
 *
 * Pure diagnostic: writes build/flat-tool-size-chart.md and prints a summary.
 * The only assertion is that the catalog is non-empty.
 */
class FlatToolSizeChartSpec extends ToolSpecBase {

    private static int b(Object o) { o == null ? 0 : JsonOutput.toJson(o).getBytes('UTF-8').length }
    private static String pct(int part, int whole) { whole == 0 ? '0.0%' : String.format('%.1f%%', (part * 100.0d) / whole) }

    def "emit flat-mode per-tool size chart (issue #296 step 1)"() {
        given: 'the widest flat catalog: gateways off, every feature toggle on'
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = true
        settingsMap.enableDeveloperMode = true

        when: 'widest flat catalog (dev mode ON -> includes hub_update_package)'
        def tools = script.getToolDefinitions()
        int catalogBytes = JsonOutput.toJson(tools).getBytes('UTF-8').length
        int wrappedBytes = JsonOutput.toJson([tools: tools]).getBytes('UTF-8').length

        and: 'the budget-comparable catalog (dev mode OFF -> what the 122k tripwire measures)'
        settingsMap.enableDeveloperMode = false
        def toolsDevOff = script.getToolDefinitions()
        int catalogBytesDevOff = JsonOutput.toJson(toolsDevOff).getBytes('UTF-8').length

        and: 'per-tool rows with a component breakdown'
        def rows = tools.collect { t ->
            def schema = (t.inputSchema instanceof Map) ? t.inputSchema : null
            Map props = (schema?.properties instanceof Map) ? (Map) schema.properties : [:]
            [
                name   : (t.name ?: '(unnamed)') as String,
                total  : b(t),
                nameB  : b(t.name),
                descB  : b(t.description),
                schemaB: b(schema),
                annoB  : b(t.annotations),
                nProps : props.size(),
                props  : props.collect { k, v -> [k: (k as String), bytes: b(v)] }.sort { -it.bytes },
            ]
        }.sort { -it.total }

        int sumTotal = (int) rows.sum { it.total }

        and: 'render the report'
        StringBuilder sb = new StringBuilder()
        sb << "# Flat-mode tool size chart (issue #296 step 1)\n\n"
        sb << "Measurement: getToolDefinitions() with useGateways=false; JsonOutput.toJson(tool) UTF-8 bytes.\n\n"
        sb << "- Flat catalog (widest: custom engine + developer mode ON): **${catalogBytes} B** across **${tools.size()} tools**\n"
        sb << "- Wrapped as {tools:[...]} (what the hub cap measures): **${wrappedBytes} B**\n"
        sb << "- Budget-comparable catalog (developer mode OFF, the 122k tripwire config): **${catalogBytesDevOff} B** across **${toolsDevOff.size()} tools**\n"
        sb << "- Hub hard cap: 124,000 B (wrapped). Budget tripwire: 122,000 B. Floor: 100,000 B.\n"
        sb << "- Sum of per-tool bytes (widest): ${sumTotal} B (JSON array brackets/commas are the small remainder vs catalog bytes)\n\n"

        sb << "## Ranked: every flat tool, largest -> smallest\n\n"
        sb << String.format('| %-4s | %-34s | %10s | %8s | %9s |%n', 'rank', 'tool', 'bytes', '% cat', 'cum %')
        sb << "|------|------------------------------------|------------|----------|-----------|\n"
        int cum = 0
        rows.eachWithIndex { r, i ->
            cum += r.total
            sb << String.format('| %-4d | %-34s | %10d | %8s | %9s |%n',
                i + 1, r.name, r.total, pct((int) r.total, catalogBytes), pct(cum, catalogBytes))
        }

        sb << "\n## Component breakdown — top 30 tools\n\n"
        sb << "desc = description bytes, schema = inputSchema bytes, anno = annotations bytes.\n\n"
        sb << String.format('| %-34s | %8s | %8s | %7s | %8s | %6s | %7s | %5s |%n',
            'tool', 'total', 'desc', 'desc%', 'schema', 'sch%', 'anno', 'props')
        sb << "|------------------------------------|----------|----------|---------|----------|--------|---------|-------|\n"
        rows.take(30).each { r ->
            sb << String.format('| %-34s | %8d | %8d | %7s | %8d | %6s | %7d | %5d |%n',
                r.name, r.total, r.descB, pct((int) r.descB, (int) r.total),
                r.schemaB, pct((int) r.schemaB, (int) r.total), r.annoB, r.nProps)
        }

        sb << "\n## Largest inputSchema properties — top 20 tools (top 6 props each)\n\n"
        sb << "Shows whether schema bytes are param descriptions/enums vs structure.\n\n"
        rows.take(20).each { r ->
            if (r.schemaB <= 0) return
            sb << "- **${r.name}** (schema ${r.schemaB} B, ${r.nProps} props): "
            sb << r.props.take(6).collect { "${it.k}=${it.bytes}B" }.join(', ')
            sb << "\n"
        }

        def report = sb.toString()
        new File('build').mkdirs()
        new File('build/flat-tool-size-chart.md').setText(report, 'UTF-8')
        println "\n===FLAT_TOOL_SIZE_CHART_BEGIN===\n${report}\n===FLAT_TOOL_SIZE_CHART_END===\n"

        and: 'full machine-readable sidecar for downstream library + duplication analysis'
        def full = tools.collect { t ->
            def schema = (t.inputSchema instanceof Map) ? t.inputSchema : null
            Map props = (schema?.properties instanceof Map) ? (Map) schema.properties : [:]
            [
                name   : t.name,
                total  : b(t),
                descB  : b(t.description),
                schemaB: b(schema),
                annoB  : b(t.annotations),
                desc   : (t.description ?: '') as String,
                props  : props.collect { k, v ->
                    [name: (k as String), bytes: b(v),
                     descText: ((v instanceof Map && v.description != null) ? (v.description as String) : '')]
                },
            ]
        }
        new File('build/flat-tool-size.json').setText(JsonOutput.toJson(full), 'UTF-8')

        and: 'raw inputSchema per tool, for recursive description-vs-structure decomposition'
        def schemas = tools.collectEntries { [(it.name as String): it.inputSchema] }
        new File('build/flat-tool-schemas.json').setText(JsonOutput.toJson(schemas), 'UTF-8')

        and: 'RAW (un-stripped) defs with [[FLAT_TRIM]] markers intact -- lets us measure gateway-mode size (markers removed, content KEPT) vs flat (content stripped), so we can prove compaction in EVERY mode'
        def raw = script.getAllToolDefinitions().collectEntries { [(it.name as String): [description: it.description, inputSchema: it.inputSchema]] }
        new File('build/raw-tool-defs.json').setText(JsonOutput.toJson(raw), 'UTF-8')

        then:
        tools.size() > 0
    }
}
