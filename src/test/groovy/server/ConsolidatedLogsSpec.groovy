package server

import spock.lang.Unroll
import support.ToolSpecBase

class ConsolidatedLogsSpec extends ToolSpecBase {
    def "log discovery exposes one read tool with every Logs page tab plus the MCP log and status"() {
        when:
        def definitions = script.getAllToolDefinitions()
        def readLogs = definitions.findAll { it.name in ['hub_get_logs', 'hub_get_debug_logs', 'hub_get_jobs', 'hub_get_performance_stats'] }

        then:
        readLogs*.name == ['hub_get_logs']
        def tabs = ['past', 'devices', 'apps', 'cloudCalls', 'locationEvents', 'hubEvents', 'scheduledJobs', 'runningJobs', 'hubActions', 'mcp', 'status']
        readLogs[0].inputSchema.properties.mode.anyOf == [[type: 'string', enum: tabs], [type: 'array', items: [type: 'string', enum: tabs]]]
        tabs == script._logsModeArgs().keySet() as List
        readLogs[0].inputSchema.properties.component != null
        readLogs[0].inputSchema.properties.ruleId != null
        script.getGatewayConfig().hub_read_diagnostics.tools.contains('hub_get_logs')
        !script.getGatewayConfig().values().any { gw -> ['hub_get_debug_logs', 'hub_get_jobs', 'hub_get_performance_stats', 'hub_delete_debug_logs'].any { gw.tools.contains(it) } }
        script.getGatewayConfig().hub_manage_logs.tools == ['hub_get_logs', 'hub_delete_logs', 'hub_set_log_level']
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
        mode << ['past', 'devices', 'scheduledJobs', 'locationEvents', 'mcp', 'status']
    }

    def "unknown log mode fails explicitly"() {
        when:
        script.toolGetHubLogs([mode: 'misspelled'])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('mode')
    }
}
