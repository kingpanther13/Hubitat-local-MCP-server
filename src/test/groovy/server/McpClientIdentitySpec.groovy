package server

import spock.lang.Shared
import spock.lang.Unroll
import support.McpRequestDriver
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Covers the client-identity capture wired into {@code processJsonRpcMessage()}:
 * {@code _recordMcpClient(msg)} writes {@code atomicState.mcpClientLastSeen} /
 * {@code atomicState.mcpClientsRecent}, and {@code mcpClientIdentity()} reads them back for
 * {@code hub_get_info}.
 *
 * Driven through {@link McpRequestDriver} rather than by calling the recorder directly,
 * because the two facts that decide a record -- the {@code MCP-Protocol-Version} header value
 * and {@code request.requestSource} -- live on the hub request object, so a direct call would
 * prove the storage shape while skipping every input that selects it.
 */
class McpClientIdentitySpec extends ToolSpecBase {

    private static final long FIXED_NOW = 1234567890000L

    /** hub_get_info reads location.hub -- a class-2 seam, so stubbed on the Mock. */
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hub = new TestHub(localIP: '192.168.1.133')
    }

    def cleanup() {
        NOW_OVERRIDE.set(null)
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
        script.metaClass.mcpLog = { String level, String component, String message ->
            entries << [level: level, component: component, message: message]
        }
        return entries
    }

    // ---- legacy initialize ----

    def "a legacy initialize with clientInfo records the client, both versions, and the legacy era"() {
        when:
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0', title: 'Claude'])

        then:
        def last = script.mcpClientIdentity().lastSeen
        last.name == 'claude-ai'
        last.version == '1.4.0'
        last.title == 'Claude'
        last.requestedProtocolVersion == '2025-06-18'
        last.protocolVersion == '2025-06-18'
        last.era == 'legacy'
        last.source == 'local'
        last.seenAt == FIXED_NOW
    }

    def "an unsupported requested version is stored alongside the version initialize negotiated down to"() {
        when:
        driveLegacyInitialize([name: 'oldclient', version: '0.1'], '1999-01-01')

        then: 'requested is what the client asked for; protocolVersion is what it was answered with'
        def last = script.mcpClientIdentity().lastSeen
        last.requestedProtocolVersion == '1999-01-01'
        last.protocolVersion == '2025-11-25'
        script.mcpClientIdentity().recent.size() == 1
    }

    def "an initialize without clientInfo records the versions and leaves the client unnamed"() {
        when:
        driveLegacyInitialize(null)

        then:
        def last = script.mcpClientIdentity().lastSeen
        last.name == null
        last.version == null
        last.protocolVersion == '2025-06-18'
        last.era == 'legacy'
    }

    // ---- modern era ----

    def "a modern tools call records the modern era and the client name from _meta"() {
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

        and:
        def last = script.mcpClientIdentity().lastSeen
        last.name == 'mcp-python-sdk'
        last.version == '2.0.0'
        last.era == 'modern'
        last.protocolVersion == '2026-07-28'
        last.requestedProtocolVersion == null
    }

    // ---- per-request identity, protocol carry-over ----

    def "a later legacy message without clientInfo is not attributed to the handshake client"() {
        given:
        script.metaClass.getRooms = { -> [] }
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])

        when: 'a plain tools/call, which carries no clientInfo at all'
        mcpDriver.callTool('hub_list_rooms', [:])

        then: 'one install serves several clients at once, so a nameless request names nobody'
        def last = script.mcpClientIdentity().lastSeen
        last.name == null
        last.version == null
        last.era == 'legacy'

        and: 'the protocol fields still carry over, and the handshake client stays in the history'
        last.protocolVersion == '2025-06-18'
        script.mcpClientIdentity().recent*.name == ['claude-ai']
    }

    def "a nameless request is not pushed onto recent"() {
        given:
        script.metaClass.getRooms = { -> [] }

        when: 'a headerless tools/call with no handshake before it'
        mcpDriver.callTool('hub_list_rooms', [:])

        then: 'the request was recorded, but it identified no client to remember'
        script.mcpClientIdentity().lastSeen != null
        script.mcpClientIdentity().recent == []
    }

    def "a nameless request does not log an identity line"() {
        given:
        script.metaClass.getRooms = { -> [] }
        def logs = captureMcpLogs()

        when:
        mcpDriver.callTool('hub_list_rooms', [:])

        then:
        logs.findAll { it.message.startsWith('MCP client ') }.isEmpty()
    }

    def "a headerless follow-up keeps the negotiated protocol version rather than blanking it"() {
        given:
        script.metaClass.getRooms = { -> [] }
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])

        when:
        mcpDriver.callTool('hub_list_rooms', [:])

        then: 'no version signal on this message, so the handshake value carries forward'
        script.mcpClientIdentity().lastSeen.protocolVersion == '2025-06-18'
    }

    def "a legacy follow-up header replaces the negotiated version while the requested one stays"() {
        given:
        script.metaClass.getRooms = { -> [] }
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])
        def logs = captureMcpLogs()

        when: 'the mandatory header rides a plain tools/call, naming a different supported revision'
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2025-03-26'])
        mcpDriver.callTool('hub_list_rooms', [:])

        then: 'the header is what the client negotiated, so it replaces the stored value'
        def last = script.mcpClientIdentity().lastSeen
        last.protocolVersion == '2025-03-26'

        and: 'what the client asked for at the handshake is untouched, and a nameless request logs nothing'
        last.requestedProtocolVersion == '2025-06-18'
        logs.findAll { it.message.startsWith('MCP client ') }.isEmpty()
    }

    // ---- write gating ----

    def "an identical repeat inside the ten-minute window does not rewrite seenAt"() {
        given:
        script.metaClass.getRooms = { -> [] }
        def clock = new java.util.concurrent.atomic.AtomicLong(FIXED_NOW)
        NOW_OVERRIDE.set({ -> clock.get() })
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])
        mcpDriver.callTool('hub_list_rooms', [:])
        long firstSeenAt = script.mcpClientIdentity().lastSeen.seenAt as long

        when: 'the same client repeats the same call a minute later'
        clock.set(FIXED_NOW + 60000L)
        mcpDriver.callTool('hub_list_rooms', [:])

        then: 'the tuple is unchanged, so the record was not rewritten'
        script.mcpClientIdentity().lastSeen.seenAt == firstSeenAt
    }

    def "two clients alternating request-by-request rewrite the record every time"() {
        given:
        def clock = new java.util.concurrent.atomic.AtomicLong(FIXED_NOW)
        NOW_OVERRIDE.set({ -> clock.get() })

        when:
        driveLegacyInitialize([name: 'client-a', version: '1.0'])

        then:
        script.mcpClientIdentity().lastSeen.seenAt == FIXED_NOW

        when: 'the other client takes a turn a second later'
        clock.set(FIXED_NOW + 1000L)
        driveLegacyInitialize([name: 'client-b', version: '2.0'])

        then:
        script.mcpClientIdentity().lastSeen.seenAt == FIXED_NOW + 1000L

        when: 'and the first one comes back a second after that'
        clock.set(FIXED_NOW + 2000L)
        driveLegacyInitialize([name: 'client-a', version: '1.0'])

        then: 'the tuple differs on every step, so the write gate suppresses nothing -- the accepted cost'
        script.mcpClientIdentity().lastSeen.seenAt == FIXED_NOW + 2000L
        script.mcpClientIdentity().lastSeen.name == 'client-a'
    }

    def "a repeat past the ten-minute window refreshes seenAt"() {
        given:
        script.metaClass.getRooms = { -> [] }
        def clock = new java.util.concurrent.atomic.AtomicLong(FIXED_NOW)
        NOW_OVERRIDE.set({ -> clock.get() })
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])
        mcpDriver.callTool('hub_list_rooms', [:])

        when:
        clock.set(FIXED_NOW + 700000L)
        mcpDriver.callTool('hub_list_rooms', [:])

        then:
        script.mcpClientIdentity().lastSeen.seenAt == FIXED_NOW + 700000L
    }

    // ---- recent history ----

    def "a different client is pushed onto recent, newest first"() {
        when:
        driveLegacyInitialize([name: 'client-a', version: '1.0'])
        driveLegacyInitialize([name: 'client-b', version: '2.0'])

        then:
        script.mcpClientIdentity().recent*.name == ['client-b', 'client-a']
        script.mcpClientIdentity().lastSeen.name == 'client-b'
    }

    def "recent is capped at five clients, dropping the oldest"() {
        when:
        (1..7).each { n -> driveLegacyInitialize([name: "client-${n}".toString(), version: '1.0']) }

        then:
        def recent = script.mcpClientIdentity().recent
        recent.size() == 5
        recent*.name == ['client-7', 'client-6', 'client-5', 'client-4', 'client-3']
    }

    def "a returning client is deduped rather than appended a second time"() {
        when:
        driveLegacyInitialize([name: 'client-a', version: '1.0'])
        driveLegacyInitialize([name: 'client-b', version: '2.0'])
        driveLegacyInitialize([name: 'client-a', version: '1.0'])

        then:
        script.mcpClientIdentity().recent*.name == ['client-a', 'client-b']
    }

    def "mcpClientIdentity hands back copies, so a caller cannot edit stored state"() {
        given:
        driveLegacyInitialize([name: 'client-a', version: '1.0'])

        when:
        def snapshot = script.mcpClientIdentity()
        snapshot.lastSeen.name = 'tampered'
        snapshot.recent[0].name = 'tampered'

        then:
        script.mcpClientIdentity().lastSeen.name == 'client-a'
        script.mcpClientIdentity().recent[0].name == 'client-a'
    }

    // ---- malformed input ----

    def "clientInfo sent as a String is tolerated and the response is normal"() {
        when:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 9, method: 'initialize',
                            params: [protocolVersion: '2025-06-18', clientInfo: 'claude-ai']])
        script.handleMcpRequest()

        then: 'the handshake answered normally'
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.protocolVersion == '2025-06-18'

        and: 'the unusable clientInfo is ignored, not stored and not rethrown'
        def last = script.mcpClientIdentity().lastSeen
        last.name == null
        last.protocolVersion == '2025-06-18'
    }

    def "a notification is not recorded as a client"() {
        when: 'an id-less message -- a notification, which identifies nothing worth keeping'
        mcpDriver.pushBody([jsonrpc: '2.0', method: 'notifications/initialized', params: [:]])
        script.handleMcpRequest()

        then:
        script.mcpClientIdentity().lastSeen == null
        script.mcpClientIdentity().recent == []
    }

    def "an identity read before any request answers empty rather than null-dereferencing"() {
        expect:
        script.mcpClientIdentity() == [lastSeen: null, recent: []]
    }

    // ---- recorder failure ----

    def "a recorder failure is reported and the request is still served"() {
        given: 'a stored record that throws the moment the recorder copies it'
        atomicStateMap.mcpClientLastSeen = new ExplodingRecord()
        def logs = captureMcpLogs()

        when:
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])

        then: 'losing an identity record is a diagnostic gap, never a reason to fail the handshake'
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.protocolVersion == '2025-06-18'

        and:
        logs.any {
            it.level == 'warn' && it.component == 'server' &&
            it.message.startsWith('MCP client identity capture failed: IllegalStateException')
        }
    }

    def "a recorder failure whose own recovery log throws still serves the request"() {
        given: 'only warn throws -- handleInitialize logs its own info line through the same method'
        atomicStateMap.mcpClientLastSeen = new ExplodingRecord()
        script.metaClass.mcpLog = { String level, String component, String message ->
            if (level == 'warn') throw new RuntimeException('log down')
        }

        when:
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])

        then:
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.protocolVersion == '2025-06-18'
    }

    def "an identity read of an unreadable record answers empty rather than throwing at the caller"() {
        given:
        atomicStateMap.mcpClientLastSeen = new ExplodingRecord()

        expect:
        script.mcpClientIdentity() == [lastSeen: null, recent: []]
    }

    // ---- transport source ----

    def "a request over the cloud relay records source cloud"() {
        given:
        mcpDriver.requestSource = 'cloud'

        when:
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])

        then:
        script.mcpClientIdentity().lastSeen.source == 'cloud'
    }

    // ---- logging ----

    def "initialize logs the client and both protocol versions"() {
        given:
        def logs = captureMcpLogs()

        when:
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])

        then:
        logs.any {
            it.level == 'info' && it.component == 'server' &&
            it.message == 'initialize from claude-ai 1.4.0: requested protocolVersion 2025-06-18, negotiated 2025-06-18 (local)'
        }
    }

    def "initialize without clientInfo logs an unknown client"() {
        given:
        def logs = captureMcpLogs()

        when:
        driveLegacyInitialize(null)

        then:
        logs.any { it.level == 'info' && it.message.startsWith('initialize from unknown client:') }
    }

    def "an identity change from a non-initialize message logs one line, and an unchanged repeat logs none"() {
        given:
        script.metaClass.getRooms = { -> [] }
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])
        def logs = captureMcpLogs()

        when: 'the era and protocol version change under the same connection'
        driveModernToolsCall('hub_list_rooms', [
            'io.modelcontextprotocol/clientInfo': [name: 'claude-ai', version: '1.4.0'],
            'io.modelcontextprotocol/protocolVersion': '2026-07-28',
        ])

        then:
        def identityLines = logs.findAll { it.message.startsWith('MCP client ') }
        identityLines.size() == 1
        identityLines[0].level == 'info'
        identityLines[0].component == 'server'
        identityLines[0].message == 'MCP client claude-ai 1.4.0 on protocol 2026-07-28 (modern, local)'

        when: 'the same client repeats the same call'
        driveModernToolsCall('hub_list_rooms', [
            'io.modelcontextprotocol/clientInfo': [name: 'claude-ai', version: '1.4.0'],
            'io.modelcontextprotocol/protocolVersion': '2026-07-28',
        ])

        then: 'no second line -- an unchanged repeat must not log on every request'
        logs.findAll { it.message.startsWith('MCP client ') }.size() == 1
    }

    // ---- hub_get_info ----

    @Unroll
    def "hub_get_info exposes the client identity through the dispatch envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        driveLegacyInitialize([name: 'claude-ai', version: '1.4.0'])

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        response.result.isError != true
        def info = mcpDriver.parseInner(response)

        and: 'this tools/call declared no clientInfo of its own, so lastSeen names nobody'
        info.mcpClient.lastSeen.name == null
        info.mcpClient.lastSeen.era == 'legacy'
        info.mcpClient.lastSeen.protocolVersion == '2025-06-18'

        and: 'the handshake client is still named in the history'
        info.mcpClient.recent*.name == ['claude-ai']

        where:
        useGateways << [true, false]
    }

    /**
     * A stored record that throws the moment production copies it out of atomicState. It holds
     * one real entry: an EMPTY map is copied without ever consulting entrySet() (HashMap.putAll
     * short-circuits on size 0), so an empty subclass would never throw.
     */
    static class ExplodingRecord extends LinkedHashMap {
        ExplodingRecord() { super.put('name', 'exploding') }
        @Override
        Set entrySet() { throw new IllegalStateException('boom') }
    }
}
