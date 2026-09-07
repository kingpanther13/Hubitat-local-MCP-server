package server

import spock.lang.Unroll
import support.ToolSpecBase

class ConsolidatedLogsSpec extends ToolSpecBase {
    def "log discovery exposes one read tool with hub MCP and status modes"() {
        when:
        def definitions = script.getAllToolDefinitions()
        def readLogs = definitions.findAll { it.name in ['hub_get_logs', 'hub_get_debug_logs'] }

        then:
        readLogs*.name == ['hub_get_logs']
        readLogs[0].inputSchema.properties.mode.enum == ['hub', 'mcp', 'status']
        readLogs[0].inputSchema.properties.component != null
        readLogs[0].inputSchema.properties.ruleId != null
        script.getGatewayConfig().hub_read_diagnostics.tools.contains('hub_get_logs')
        !script.getGatewayConfig().values().any { it.tools.contains('hub_get_debug_logs') }
    }

    @Unroll
    def "consolidated #mode logs remain behind the Read master"() {
        given:
        settingsMap.enableRead = false

        when:
        script.executeTool('hub_get_logs', [mode: mode])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('Read tools are disabled')

        where:
        mode << ['hub', 'mcp', 'status']
    }

    def "unknown log mode fails explicitly"() {
        when:
        script.toolGetHubLogs([mode: 'misspelled'])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('mode')
    }
}
