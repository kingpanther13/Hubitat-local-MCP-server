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
 * The catalog under test: the tool-guide sections (hubitat://guide/&lt;section&gt;,
 * never gated -- mirroring hub_get_tool_guide) plus the live context snapshot
 * (hubitat://context-summary and hubitat://context, gated by the Read master).
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

    def "resources/list omits the context resources when the Read master is off, keeping the guide"() {
        given:
        settingsMap.enableRead = false

        when:
        def response = dispatch([jsonrpc: '2.0', id: 4, method: 'resources/list', params: [:]])

        then:
        def uris = response.result.resources*.uri
        !uris.contains('hubitat://context-summary')
        !uris.contains('hubitat://context')
        uris.any { it.startsWith('hubitat://guide/') }
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

    def "resources/read serves guide sections even when the Read master is off"() {
        // Mirrors hub_get_tool_guide, which is deliberately never gated (it documents
        // how to recover from gating).
        given:
        settingsMap.enableRead = false

        when:
        def response = dispatch([jsonrpc: '2.0', id: 6, method: 'resources/read',
                                 params: [uri: 'hubitat://guide/best_practice_reference']])

        then:
        response.error == null
        response.result.contents[0].text.contains('bps-ack-299')
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
                capabilities: [[name: 'Switch']], attributeValues: [switch: 'on']),
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
    }

    def "resources/read of a context resource is refused with -32002 when the Read master is off"() {
        given:
        settingsMap.enableRead = false

        when:
        def response = dispatch([jsonrpc: '2.0', id: 9, method: 'resources/read',
                                 params: [uri: 'hubitat://context-summary']])

        then:
        response.error.code == -32002
        response.error.message.contains('Read tools are disabled')
        response.error.data.uri == 'hubitat://context-summary'
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

    def "resources/read without a uri returns -32602"() {
        when:
        def response = dispatch([jsonrpc: '2.0', id: 12, method: 'resources/read', params: [:]])

        then:
        response.error.code == -32602
        response.error.message.contains('uri')
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
