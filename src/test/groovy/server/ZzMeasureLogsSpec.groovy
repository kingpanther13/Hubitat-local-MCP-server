package server

import groovy.json.JsonOutput
import support.ToolSpecBase

// Scratch measurement for the logs-tools rebuild; not committed.
class ZzMeasureLogsSpec extends ToolSpecBase {

    private static int b(Object o) { o == null ? 0 : JsonOutput.toJson(o).getBytes('UTF-8').length }

    def "measure"() {
        given:
        settingsMap.enableCustomRuleEngine = true
        settingsMap.enableDeveloperMode = false
        def names = ['hub_get_logs', 'hub_get_performance_stats', 'hub_get_jobs', 'hub_delete_debug_logs',
                     'hub_delete_logs', 'hub_list_device_events', 'hub_set_log_level']
        def out = [:]

        when:
        settingsMap.useGateways = false
        def flat = script.getToolDefinitions()
        out.flatCatalog = b([tools: flat])
        out.flatTools = names.collectEntries { n -> [(n): b(flat.find { it.name == n })] }
        settingsMap.useGateways = true
        def gw = script.getToolDefinitions()
        out.gatewayCatalog = b([tools: gw])
        def gws = ['hub_manage_logs', 'hub_read_diagnostics', 'hub_read_devices', 'hub_manage_devices']
        out.gatewayEntries = gws.collectEntries { g -> [(g): b(gw.find { it.name == g })] }
        out.gatewayCatalogCalls = gws.collectEntries { g -> [(g): b(script.handleGateway(g, null, null))] }
        out.gatewaySubTools = gws.collectEntries { g ->
            def cat = script.handleGateway(g, null, null)
            [(g): cat.tools.findAll { it.name in names }.collectEntries { [(it.name): b(it)] }]
        }
        def guide = script.getToolGuideSections()
        out.guideTotal = b(guide)
        out.guideSections = guide.collectEntries { k, v -> [(k): b(v)] }
        new File('build').mkdirs()
        new File('build/measure-logs.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(out))
        println('MEASURE-LOGS ' + JsonOutput.toJson(out))

        then:
        flat
    }
}
