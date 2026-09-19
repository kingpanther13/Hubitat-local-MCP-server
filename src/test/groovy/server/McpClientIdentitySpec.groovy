package server

import spock.lang.Shared
import spock.lang.Unroll
import support.McpRequestDriver
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Covers {@code mcpClientIdentity()}: the client behind the request currently in hand,
 * derived from {@code request.JSON} plus the request's own header and source, and read back
 * by {@code hub_get_info} and the bug-report tool. Nothing is persisted.
 *
 * Driven through {@link McpRequestDriver} rather than by calling the derivation directly,
 * because the three facts that decide an identity -- the body, the
 * {@code MCP-Protocol-Version} header value and {@code request.requestSource} -- all live on
 * the hub request object, so a direct call would prove the shape while skipping every input
 * that selects it.
 */
class McpClientIdentitySpec extends ToolSpecBase {

    /** hub_get_info reads location.hub -- a class-2 seam, so stubbed on the Mock. */
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hub = new TestHub(localIP: '192.168.1.133')
    }

    // ---- helpers ----

    private void driveLegacyInitialize(Map clientInfo, String requested = '2025-06-18') {
        def params = [protocolVersion: requested]
        if (clientInfo != null) params.clientInfo = clientInfo
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'initialize', params: params])
        script.handleMcpRequest()
    }

    private void driveModernToolsCall(String toolName, Map meta) {
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': toolName,
        ])
        def params = [name: toolName, arguments: [:]]
        if (meta != null) params['_meta'] = meta
        mcpDriver.pushBody([jsonrpc: '2.0', id: 2, method: 'tools/call', params: params])
        script.handleMcpRequest()
    }

    private List captureMcpLogs() {
        def entries = []
        script.metaClass.mcpLog = { String level, String component, String message,
                                    String ruleId = null, Map extra = null ->
            entries << [level: level, component: component, message: message]
        }
        return entries
    }

    // ---- legacy initialize ----

    def "a legacy initialize names the client, both versions, and the legacy era"() {
        when:
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0', title: 'Claude'])

        then:
        def client = script.mcpClientIdentity().client
        client.name == 'claude-ai'
        client.version == '1.4.0'
        client.title == 'Claude'
        client.requestedProtocolVersion == '2025-06-18'
        client.protocolVersion == '2025-06-18'
        client.era == 'legacy'
        client.source == 'local'
        client.wrapper == false
    }

    def "an unsupported requested version is reported alongside the version initialize negotiated down to"() {
        when:
        driveLegacyInitialize([name: 'oldclient', version: '0.1'], '1999-01-01')

        then: 'requested is what the client asked for; protocolVersion is what it was answered with'
        def client = script.mcpClientIdentity().client
        client.requestedProtocolVersion == '1999-01-01'
        client.protocolVersion == '2025-11-25'
    }

    def "an initialize without clientInfo reports the versions and leaves the client unnamed"() {
        when:
        driveLegacyInitialize(null)

        then:
        def client = script.mcpClientIdentity().client
        client.name == null
        client.version == null
        client.protocolVersion == '2025-06-18'
        client.era == 'legacy'
    }

    def "clientInfo sent as a String is tolerated and the response is normal"() {
        when:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 9, method: 'initialize',
                            params: [protocolVersion: '2025-06-18', clientInfo: 'claude-ai']])
        script.handleMcpRequest()

        then: 'the handshake answered normally'
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.protocolVersion == '2025-06-18'

        and: 'the unusable clientInfo is ignored, not read and not rethrown'
        def client = script.mcpClientIdentity().client
        client.name == null
        client.protocolVersion == '2025-06-18'
    }

    def "a request over the cloud relay reports source cloud"() {
        given:
        mcpDriver.requestSource = 'cloud'

        when:
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])

        then:
        script.mcpClientIdentity().client.source == 'cloud'
    }

    // ---- modern era ----

    def "a modern tools call reports the modern era and the client name from _meta"() {
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }

        when:
        driveModernToolsCall('hub_list_rooms', [
            'io.modelcontextprotocol/clientInfo': [name: 'mcp-python-sdk', version: '2.0.0'],
            'io.modelcontextprotocol/protocolVersion': '2026-07-28',
        ])

        then: 'the tool still answered normally'
        mcpDriver.lastRenderArgs.status == null
        mcpDriver.parseResponseJson().error == null

        and: 'the header carries the version outside the handshake, so nothing was requested here'
        def client = script.mcpClientIdentity().client
        client.name == 'mcp-python-sdk'
        client.version == '2.0.0'
        client.era == 'modern'
        client.protocolVersion == '2026-07-28'
        client.requestedProtocolVersion == null
    }

    def "an initialize under a modern header reads as legacy era with what it negotiated"() {
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'initialize'])

        when:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 4, method: 'initialize',
                            params: [protocolVersion: '2026-07-28', capabilities: [:],
                                     clientInfo: [name: 'dual-era-client', version: '1.0']]])
        script.handleMcpRequest()

        then: 'reaching initialize proves a legacy-era client, so it is capped like any other'
        def client = script.mcpClientIdentity().client
        client.era == 'legacy'
        client.requestedProtocolVersion == '2026-07-28'
        client.protocolVersion == '2025-11-25'
    }

    // ---- per-request derivation ----

    def "a headerless tools call names nobody and states no protocol version"() {
        given:
        script.metaClass.getRooms = { -> [] }

        when: 'a plain tools/call, which carries no clientInfo and no header of its own'
        mcpDriver.callTool('hub_list_rooms', [:])

        then: 'a legacy client names itself at the handshake only, so this request names nobody'
        def client = script.mcpClientIdentity().client
        client.name == null
        client.version == null
        client.era == 'legacy'
        client.protocolVersion == null
        client.requestedProtocolVersion == null
        client.wrapper == false
    }

    def "an earlier handshake does not name a later request"() {
        given:
        script.metaClass.getRooms = { -> [] }
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])

        when: 'the same install serves the next call, which declares nothing'
        mcpDriver.callTool('hub_list_rooms', [:])

        then: 'one install serves several clients at once, and nothing is carried between requests'
        script.mcpClientIdentity().client.name == null
    }

    def "no identity is stored anywhere in app state"() {
        given:
        script.metaClass.getRooms = { -> [] }

        when:
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])
        mcpDriver.callTool('hub_list_rooms', [:])

        then: 'a per-request atomicState write is the load this identity read exists to avoid'
        !atomicStateMap.containsKey('mcpClientLastSeen')
        !atomicStateMap.containsKey('mcpClientsRecent')
        atomicStateMap.keySet().every { !(it as String).startsWith('mcpClient') }
    }

    // ---- which message in the POST speaks ----

    @Unroll
    def "a batch POST is one client: the member that declares clientInfo names it (named first: #namedFirst)"() {
        given:
        script.metaClass.getRooms = { -> [] }
        def named = [jsonrpc: '2.0', id: 1, method: 'tools/call',
                     params: [name: 'hub_list_rooms', arguments: [:],
                              _meta: ['io.modelcontextprotocol/clientInfo': [name: 'batch-probe', version: '1.0']]]]
        def nameless = [jsonrpc: '2.0', id: 2, method: 'tools/call',
                        params: [name: 'hub_list_rooms', arguments: [:]]]

        when:
        mcpDriver.pushBody(namedFirst ? [named, nameless] : [nameless, named])
        script.handleMcpRequest()

        then: 'order cannot change who sent the POST'
        script.mcpClientIdentity().client.name == 'batch-probe'

        where:
        namedFirst << [true, false]
    }

    def "a malformed member never names the client"() {
        when: 'the envelope is rejected at dispatch, so its clientInfo speaks for nothing'
        mcpDriver.pushBody([jsonrpc: '1.0', id: 3, method: 'initialize',
                            params: [protocolVersion: '2025-06-18',
                                     clientInfo: [name: 'bogus-client', version: '1.0']]])
        script.handleMcpRequest()

        then:
        script.mcpClientIdentity().client == null
    }

    def "a notification names no client"() {
        when: 'an id-less message -- a notification, which identifies nothing worth reporting'
        mcpDriver.pushBody([jsonrpc: '2.0', method: 'notifications/initialized', params: [:]])
        script.handleMcpRequest()

        then:
        script.mcpClientIdentity().client == null
    }

    def "an identity read outside any request answers null rather than throwing"() {
        expect:
        script.mcpClientIdentity() == [client: null]
    }

    // ---- read failure ----

    def "an unreadable body names the failure, and the request is still served"() {
        given:
        mcpDriver.pushBodyThrowing(new IllegalStateException('boom'))
        def logs = captureMcpLogs()

        when:
        script.handleMcpRequest()

        then: 'an unreadable body is answered as a parse error, never as a crash'
        mcpDriver.parseResponseJson().error.code == -32700

        when:
        def identity = script.mcpClientIdentity()

        then: 'a null client would read as a request nobody sent'
        identity.client == null
        identity.error == 'IllegalStateException: boom'

        and:
        logs.any {
            it.level == 'warn' && it.component == 'server' &&
            it.message.startsWith('MCP client identity read failed: IllegalStateException')
        }
    }

    def "a credential quoted by the read error is scrubbed from the error and the log line"() {
        given:
        def logs = captureMcpLogs()
        mcpDriver.throwingRequest = new IllegalStateException('parse failed near access_token=SECRETY9 in body')

        when:
        def identity = script.mcpClientIdentity()

        then:
        identity.client == null
        identity.error.startsWith('IllegalStateException: ')
        !identity.error.contains('SECRETY9')
        identity.error.contains('access_token=<redacted>')
        logs.every { !it.message.contains('SECRETY9') }
    }

    def "a read failure whose own recovery log throws still answers"() {
        given:
        mcpDriver.pushBodyThrowing(new IllegalStateException('boom'))
        script.metaClass.mcpLog = { String level, String component, String message ->
            throw new RuntimeException('log down')
        }

        expect:
        script.mcpClientIdentity() == [client: null, error: 'IllegalStateException: boom']
    }

    // ---- transport-wrapper flag ----

    @Unroll
    def "the wrapper flag is #expected for a client named '#clientName' version #clientVersion"() {
        given:
        def info = [name: clientName]
        if (clientVersion != null) info.version = clientVersion

        when:
        driveLegacyInitialize(info)

        then: 'an unanchored contains-match would flag unrelated names'
        script.mcpClientIdentity().client.wrapper == expected

        where:
        clientName           | clientVersion || expected
        'mcp-remote'         | '0.1.29'      || true
        'MCP-Remote'         | '0.1.29'      || true
        'mcp-proxy'          | '0.9.0'       || true
        'fastmcp-remote'     | '2.0'         || true
        'supergateway'       | '3.4.0'       || true
        'mcp'                | '0.1.0'       || true
        'mcp'                | null          || true
        'claude-code'        | '2.1'         || false
        'mcp'                | '1.2.0'       || false
        'mcp-remote-control' | '1.0'         || false
    }

    // ---- client-supplied text ----

    def "a client name carrying markdown and backticks is flattened, stripped and capped"() {
        given:
        def hostile = 'evil`name`\n## Injected heading\t' + ('x' * 200)

        when:
        driveLegacyInitialize([name: hostile, version: '1.0'])

        then: 'the name is echoed into logs and into markdown headed for a public tracker'
        def name = script.mcpClientIdentity().client.name
        name.length() == 120
        !name.contains('\n')
        !name.contains('\t')
        !name.contains('`')
        name.startsWith('evil name ## Injected heading x')
    }

    def "the initialize log line is sanitized, requested version included"() {
        given:
        def logs = []
        script.metaClass.mcpLog = { String level, String component, String msg, String ruleId = null, Map extra = null ->
            logs << [level: level, component: component, msg: msg]
        }

        when: 'both the name and the requested version are client-supplied text'
        driveLegacyInitialize([name: 'evil\n## Injected heading ' + ('x' * 200), version: '1.0'],
                              '2025-06-18\n## Injected version')

        then:
        def line = logs.find { it.msg?.startsWith('initialize from ') }
        line != null
        !line.msg.contains('\n')
        line.msg.startsWith('initialize from evil ## Injected heading x')
        line.msg.contains('requested protocolVersion 2025-06-18 ## Injected version, negotiated 2025-11-25')
    }

    def "initialize without clientInfo logs an unknown client"() {
        given:
        def logs = captureMcpLogs()

        when:
        driveLegacyInitialize(null)

        then:
        logs.any { it.level == 'info' && it.message.startsWith('initialize from unknown client:') }
    }

    // ---- hub_get_info ----

    @Unroll
    def "hub_get_info exposes the caller's identity through the dispatch envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        response.result.isError != true
        def info = mcpDriver.parseInner(response)

        and: 'this tools/call declared no clientInfo of its own, so it names nobody'
        info.mcpClient.client.name == null
        info.mcpClient.client.era == 'legacy'
        info.mcpClient.client.wrapper == false

        and: 'nothing is remembered, so there is no history alongside it'
        !info.mcpClient.containsKey('recent')
        !info.mcpClient.containsKey('lastSeen')

        where:
        useGateways << [true, false]
    }

    def "hub_get_info names a modern client that declared itself on the same call"() {
        when:
        driveModernToolsCall('hub_get_info', [
            'io.modelcontextprotocol/clientInfo': [name: 'claude-code', version: '2.1', title: 'Claude Code'],
        ])

        then:
        def info = mcpDriver.parseInner(mcpDriver.parseResponseJson() as Map)
        info.mcpClient.client.name == 'claude-code'
        info.mcpClient.client.title == 'Claude Code'
        info.mcpClient.client.era == 'modern'
    }
}
