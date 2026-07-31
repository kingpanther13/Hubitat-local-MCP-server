package server

import spock.lang.Shared
import support.TestDevice
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * The stateless MCP resources surface (issue #366): resources/list, resources/read,
 * resources/templates/list, and the capability advertisement on initialize +
 * server/discover. Driven through handleMcpRequest (the tier-2 dispatch seam) so the
 * JSON-RPC envelope, era gating, and render path are all exercised.
 *
 * The catalog under test: the tool-guide sections (hubitat://guide/&lt;section&gt;)
 * plus the live context snapshot (hubitat://context-summary and hubitat://context).
 * Each group mirrors the VISIBILITY of the tool whose content it serves
 * (hub_get_tool_guide / hub_list_devices), so the Read master and the #114 per-tool
 * overrides both apply -- the resources surface can never serve content its tool
 * counterpart is gated from serving.
 */
class McpResourcesSpec extends ToolSpecBase {

    /** Origin check + the context resources read location.hub.localIP / location.mode. */
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hub = new TestHub(localIP: '192.168.1.133')
        sharedLocation.hsmStatus = null
    }

    private Map dispatch(Map body, Map headers = null) {
        if (headers != null) mcpDriver.pushHeaders(headers)
        mcpDriver.pushBody(body)
        script.handleMcpRequest()
        return mcpDriver.parseResponseJson() as Map
    }

    // ---- capability advertisement ---------------------------------------

    def "initialize and server/discover advertise the resources capability with both flags false"() {
        // No SSE on this endpoint, so subscribe change-notifications are impossible by
        // design; both flags must be explicitly false, not merely absent.
        when:
        def init = dispatch([jsonrpc: '2.0', id: 1, method: 'initialize', params: [:]])
        def discover = dispatch([jsonrpc: '2.0', id: 2, method: 'server/discover', params: [:]])

        then:
        init.result.capabilities.resources == [subscribe: false, listChanged: false]
        discover.result.capabilities.resources == [subscribe: false, listChanged: false]

        and: 'the tools capability is untouched'
        init.result.capabilities.tools == [:]
        discover.result.capabilities.tools == [:]
    }

    // ---- resources/list --------------------------------------------------

    def "resources/list returns the context resources plus every tool-guide section, with cache hints"() {
        when:
        def response = dispatch([jsonrpc: '2.0', id: 3, method: 'resources/list', params: [:]])

        then:
        response.error == null
        def resources = response.result.resources
        def byUri = resources.collectEntries { [(it.uri): it] }

        and: 'the two context resources'
        byUri['hubitat://context-summary'].mimeType == 'text/plain'
        byUri['hubitat://context'].mimeType == 'application/json'

        and: 'one guide resource per getToolGuideSections() key, none extra'
        def guideUris = resources.findAll { it.uri.startsWith('hubitat://guide/') }
        (guideUris.collect { it.uri - 'hubitat://guide/' } as Set) == (script.getToolGuideSections().keySet() as Set)
        guideUris.every { it.mimeType == 'text/markdown' }
        resources.size() == guideUris.size() + 2

        and: 'every entry satisfies the spec-required uri + name pair'
        resources.every { it.uri && it.name }

        and: 'SEP-2549 cache hints, same policy as tools/list'
        response.result.ttlMs instanceof Number && response.result.ttlMs > 0
        response.result.cacheScope == 'private'
    }

    def "resources/list is empty when the Read master is off -- both mirrored tools are read tools"() {
        given:
        settingsMap.enableRead = false

        when:
        def response = dispatch([jsonrpc: '2.0', id: 4, method: 'resources/list', params: [:]])

        then: 'hub_list_devices AND hub_get_tool_guide are both Read-master-hidden, so nothing is served'
        response.error == null
        response.result.resources == []
    }

    // ---- resources/read: guide sections ---------------------------------

    def "resources/read returns a guide section verbatim as text/markdown"() {
        when:
        def response = dispatch([jsonrpc: '2.0', id: 5, method: 'resources/read',
                                 params: [uri: 'hubitat://guide/performance']])

        then:
        response.error == null
        def content = response.result.contents[0]
        content.uri == 'hubitat://guide/performance'
        content.mimeType == 'text/markdown'
        content.text == script.getToolGuideSections().performance
        response.result.ttlMs instanceof Number && response.result.ttlMs > 0
        response.result.cacheScope == 'private'
    }

    def "resources/read refuses guide sections when the Read master is off, mirroring hub_get_tool_guide"() {
        // hub_get_tool_guide is a read tool: with the Read master off it is hidden from
        // tools/list and rejected at dispatch, so serving the same content here would be
        // a Read-master bypass. (The BEST-PRACTICE gate is the one that exempts the guide
        // tool -- a different, write-only gate that does not apply to resources.)
        given:
        settingsMap.enableRead = false

        when:
        def response = dispatch([jsonrpc: '2.0', id: 6, method: 'resources/read',
                                 params: [uri: 'hubitat://guide/best_practice_reference']])

        then:
        response.error.code == -32002
        response.error.message.contains('Read tools are disabled')
        response.error.data.uri == 'hubitat://guide/best_practice_reference'
    }

    def "the #114 per-tool override on hub_get_tool_guide hides the guide resources, keeping context"() {
        given:
        settingsMap.disabled_tools = ['hub_get_tool_guide']

        when:
        def listed = dispatch([jsonrpc: '2.0', id: 32, method: 'resources/list', params: [:]])

        then:
        def uris = listed.result.resources*.uri
        !uris.any { it.startsWith('hubitat://guide/') }
        uris.contains('hubitat://context-summary')
        uris.contains('hubitat://context')

        when:
        def read = dispatch([jsonrpc: '2.0', id: 33, method: 'resources/read',
                             params: [uri: 'hubitat://guide/performance']])

        then:
        read.error.code == -32002
        read.error.message.contains('Per-tool Overrides')

        cleanup:
        settingsMap.remove('disabled_tools')
    }

    // ---- resources/read: live context -----------------------------------

    def "resources/read hubitat://context-summary returns the full plain-text snapshot"() {
        given:
        settingsMap.selectedDevices = [
            new TestDevice(id: 7, label: 'Kitchen Light', roomName: 'Kitchen',
                capabilities: [[name: 'Switch']], attributeValues: [switch: 'on'])
        ]

        when:
        def response = dispatch([jsonrpc: '2.0', id: 7, method: 'resources/read',
                                 params: [uri: 'hubitat://context-summary']])

        then:
        response.error == null
        def content = response.result.contents[0]
        content.mimeType == 'text/plain'
        def lines = content.text.readLines()
        lines[0] == 'Mode: Home'
        lines.contains('- Kitchen Light (7, Kitchen) - Switch; switch=on')

        and: 'live state is marked immediately stale for caching intermediaries'
        response.result.ttlMs == 0
        response.result.cacheScope == 'private'
    }

    def "resources/read hubitat://context returns the JSON twin with mode, rooms, and devices"() {
        given:
        sharedLocation.modes = [[id: '1', name: 'Home'], [id: '3', name: 'Night']]
        sharedLocation.hsmStatus = 'disarmed'
        settingsMap.selectedDevices = [
            new TestDevice(id: 7, label: 'Kitchen Light', roomName: 'Kitchen',
                capabilities: [[name: 'Switch']],
                // '_1' models the driver-internal tile-text attributes real inventories
                // carry -- the projection must keep them out of the snapshot.
                attributeValues: [switch: 'on', '_1': 'junk row']),
            new TestDevice(id: 8, label: 'Roomless Plug',
                capabilities: [[name: 'Switch']], attributeValues: [switch: 'off'])
        ]

        when:
        def response = dispatch([jsonrpc: '2.0', id: 8, method: 'resources/read',
                                 params: [uri: 'hubitat://context']])

        then:
        response.error == null
        def content = response.result.contents[0]
        content.mimeType == 'application/json'
        def ctx = new groovy.json.JsonSlurper().parseText(content.text)
        ctx.currentMode == 'Home'
        ctx.hsmStatus == 'disarmed'
        ctx.modes == ['Home', 'Night']
        ctx.deviceCount == 2
        ctx.rooms.find { it.name == 'Kitchen' }.deviceIds == ['7']
        ctx.rooms.find { it.name == 'No room' }.deviceIds == ['8']
        def dev = ctx.devices.find { it.id == '7' }
        dev.label == 'Kitchen Light'
        dev.capabilities == ['Switch']
        dev.attributes['switch'] == 'on'

        and: 'attributes are projected through the default context set -- no driver junk'
        !dev.attributes.containsKey('_1')
    }

    @spock.lang.Unroll
    def "resources/read of #uri is refused with -32002 when the Read master is off"() {
        given:
        settingsMap.enableRead = false

        when:
        def response = dispatch([jsonrpc: '2.0', id: 9, method: 'resources/read',
                                 params: [uri: uri]])

        then:
        response.error.code == -32002
        response.error.message.contains('Read tools are disabled')
        response.error.data.uri == uri

        where:
        uri << ['hubitat://context-summary', 'hubitat://context']
    }

    def "the #114 per-tool override on hub_list_devices also hides and refuses the context resources"() {
        // The context resources ARE hub_list_devices data by another route, so a disabled
        // tool must disappear from this surface too ("everywhere it appears").
        given:
        settingsMap.disabled_tools = ['hub_list_devices']

        when:
        def listed = dispatch([jsonrpc: '2.0', id: 30, method: 'resources/list', params: [:]])

        then:
        def uris = listed.result.resources*.uri
        !uris.contains('hubitat://context-summary')
        !uris.contains('hubitat://context')
        uris.any { it.startsWith('hubitat://guide/') }

        when:
        def read = dispatch([jsonrpc: '2.0', id: 31, method: 'resources/read',
                             params: [uri: 'hubitat://context']])

        then:
        read.error.code == -32002
        read.error.message.contains('Per-tool Overrides')
        read.error.data.uri == 'hubitat://context'

        cleanup:
        settingsMap.remove('disabled_tools')
    }

    // ---- resources/read: error contract ---------------------------------

    def "resources/read of an unknown uri returns -32002 with the uri in data"() {
        when:
        def response = dispatch([jsonrpc: '2.0', id: 10, method: 'resources/read',
                                 params: [uri: 'hubitat://no-such-thing']])

        then:
        response.error.code == -32002
        response.error.message.contains('hubitat://no-such-thing')
        response.error.data.uri == 'hubitat://no-such-thing'
    }

    def "resources/read of an unknown guide section returns -32002, not a null-text result"() {
        when:
        def response = dispatch([jsonrpc: '2.0', id: 11, method: 'resources/read',
                                 params: [uri: 'hubitat://guide/no_such_section']])

        then:
        response.error.code == -32002
    }

    @spock.lang.Unroll
    def "resources/read with #label uri returns -32602"() {
        when:
        def response = dispatch([jsonrpc: '2.0', id: 12, method: 'resources/read', params: params])

        then:
        response.error.code == -32602
        response.error.message.contains('uri')

        where:
        label          | params
        'a missing'    | [:]
        'an empty'     | [uri: '']
        'a non-string' | [uri: 123]
    }

    // ---- resource size cap ----------------------------------------------

    def "an oversized inventory truncates the context resources instead of dying behind the -32603 guard"() {
        // resources/read takes only a uri -- an over-cap body would be PERMANENTLY dead on
        // that hub (the outer guard's "request less data" advice is unactionable). Both
        // builders stop at _contextResourceCharBudget() and say so.
        given: 'enough long-labelled devices to overrun the budget'
        settingsMap.selectedDevices = (0..<2500).collect { i ->
            new TestDevice(id: i + 1, label: "Truncation Fixture Device ${String.format('%04d', i)} With A Long Label",
                roomName: "Room ${i % 20}", capabilities: [[name: 'Switch']], attributeValues: [switch: 'on'])
        }

        when:
        def text = dispatch([jsonrpc: '2.0', id: 40, method: 'resources/read',
                             params: [uri: 'hubitat://context-summary']])

        then: 'served (not the -32603 size guard), with an explicit truncation pointer'
        text.error == null
        def body = text.result.contents[0].text
        body.length() <= script._contextResourceCharBudget() + 300
        body.contains('truncated at')
        body.contains("hub_list_devices")

        when:
        def json = dispatch([jsonrpc: '2.0', id: 41, method: 'resources/read',
                             params: [uri: 'hubitat://context']])

        then:
        json.error == null
        def ctx = new groovy.json.JsonSlurper().parseText(json.result.contents[0].text)
        ctx.truncated == true
        ctx.totalDevices == 2500
        ctx.deviceCount < 2500
        ctx.deviceCount == ctx.devices.size()
        ctx.note.contains('hub_list_devices')

        and: 'the rooms index stays complete'
        ctx.rooms.size() == 20
    }

    // ---- resources/templates/list ---------------------------------------

    def "resources/templates/list returns an empty list (not -32601) with cache hints"() {
        when:
        def response = dispatch([jsonrpc: '2.0', id: 13, method: 'resources/templates/list', params: [:]])

        then:
        response.error == null
        response.result.resourceTemplates == []
        response.result.cacheScope == 'private'
    }

    // ---- era gating ------------------------------------------------------

    def "modern-era resources results carry resultType while legacy ones do not"() {
        when: 'legacy (headerless) list'
        def legacy = dispatch([jsonrpc: '2.0', id: 14, method: 'resources/list', params: [:]])

        and: 'modern list with the mirrored method header'
        def modern = dispatch([jsonrpc: '2.0', id: 15, method: 'resources/list', params: [:]],
                              ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'resources/list'])

        then:
        !legacy.result.containsKey('resultType')
        modern.result.resultType == 'complete'

        and: 'both carry the serverInfo _meta stamp'
        legacy.result._meta['io.modelcontextprotocol/serverInfo'].name == 'hubitat-mcp-rule-server'
        modern.result._meta['io.modelcontextprotocol/serverInfo'].name == 'hubitat-mcp-rule-server'
    }
}
