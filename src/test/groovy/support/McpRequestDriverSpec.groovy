package support

import spock.lang.Shared
import spock.lang.Specification

/** Self-tests for {@link McpRequestDriver} so harness regressions surface here, not in every dependent spec. */
class McpRequestDriverSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    McpRequestDriver driver

    def setupSpec() {
        // hub_get_info reads location.hub; wire a stub so the callTool test
        // exercises a real success path rather than the tool's outer error wrap.
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        driver = new McpRequestDriver()
        sharedLocation.hub = new TestHub()
    }

    def "pushBody stores a map under request.JSON for the proxy to return"() {
        given:
        driver.pushBody([jsonrpc: '2.0', id: 1, method: 'initialize'])

        expect: 'the proxy reads what pushBody staged'
        driver.scriptRequest.getJSON() == [jsonrpc: '2.0', id: 1, method: 'initialize']
    }

    def "pushBody(null) stages null — the requestBody == null branch"() {
        given:
        driver.pushBody(null)

        expect: 'getJSON returns null rather than throwing'
        driver.scriptRequest.getJSON() == null
    }

    def "pushBody clears any prior throwing state"() {
        given: 'first stage a throw'
        driver.pushBodyThrowing(new RuntimeException('earlier test'))

        when: 'then push a normal body in a new test — should reset the throw'
        driver.pushBody([id: 2])

        then:
        driver.scriptRequest.getJSON() == [id: 2]
    }

    def "pushBodyThrowing makes scriptRequest.getJSON throw the given Throwable"() {
        given:
        def oops = new RuntimeException('simulated parse failure')
        driver.pushBodyThrowing(oops)

        when:
        driver.scriptRequest.getJSON()

        then:
        def e = thrown(RuntimeException)
        e.is(oops)
    }

    def "reset clears request, headers, throwing state, and lastRenderArgs"() {
        given:
        driver.pushBody([some: 'body'])
        driver.pushHeaders(['Mcp-Method': 'tools/list'])
        driver.captureRender([status: 200, data: '{}'])

        when:
        driver.reset()

        then:
        driver.scriptRequest.getJSON() == null
        driver.scriptRequest.getHeaders() == [:]
        driver.lastRenderArgs == null
        driver.throwingRequest == null
        driver.throwingHeaders == null
        !driver.nullHeaders
    }

    def "pushHeaders reproduces the hub's wire shape — names case-normalized, values List-wrapped"() {
        // The live probe showed the hub normalizes header names to
        // first-character-upper/rest-lower and List-wraps every value. Staging that
        // shape is what makes the production case-insensitive lookup + unwrap the
        // thing under test.
        given:
        driver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'X-Custom-Probe': 'hello123',
        ])

        expect:
        driver.scriptRequest.getHeaders() == [
            'Mcp-protocol-version': ['2026-07-28'],
            'Mcp-method': ['tools/call'],
            'X-custom-probe': ['hello123'],
        ]
    }

    def "pushHeaders keeps an already-List value verbatim so a spec can stage an empty or multi-valued header"() {
        given:
        driver.pushHeaders(['Mcp-Method': [], 'Mcp-Name': ['a', 'b']])

        expect:
        driver.scriptRequest.getHeaders() == ['Mcp-method': [], 'Mcp-name': ['a', 'b']]
    }

    @spock.lang.Unroll
    def "hubHeaderName normalizes #raw to #expected"() {
        expect:
        McpRequestDriver.hubHeaderName(raw) == expected

        where:
        raw                     || expected
        'MCP-Protocol-Version'  || 'Mcp-protocol-version'
        'mcp-method'            || 'Mcp-method'
        'User-Agent'            || 'User-agent'
        'Host'                  || 'Host'
        ''                      || ''
        null                    || null
    }

    def "nullHeaders makes request.headers read back null (older firmware)"() {
        given:
        driver.pushHeaders(['Mcp-Method': 'tools/list'])

        when:
        driver.nullHeaders = true

        then:
        driver.scriptRequest.getHeaders() == null
    }

    def "throwingHeaders makes request.headers throw (firmware without the property)"() {
        given:
        def oops = new MissingPropertyException('headers', McpRequestDriver)
        driver.throwingHeaders = oops

        when:
        driver.scriptRequest.getHeaders()

        then:
        def e = thrown(MissingPropertyException)
        e.is(oops)
    }

    def "pushHeaders clears any prior null/throwing header state"() {
        given:
        driver.nullHeaders = true
        driver.throwingHeaders = new RuntimeException('earlier test')

        when:
        driver.pushHeaders(['Mcp-Method': 'ping'])

        then:
        driver.scriptRequest.getHeaders() == ['Mcp-method': ['ping']]
        !driver.nullHeaders
        driver.throwingHeaders == null
    }

    def "captureRender stores the Map and returns the same Map for caller assignment"() {
        given:
        def args = [status: 200, contentType: 'application/json', data: '{}']

        when:
        def ret = driver.captureRender(args)

        then:
        ret.is(args)
        driver.lastRenderArgs.is(args)
    }

    def "parseResponseJson throws IllegalStateException when no render was captured"() {
        when:
        driver.parseResponseJson()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('No render() call captured')
    }

    def "parseResponseJson returns null for empty data — the 202 all-notifications case"() {
        given:
        driver.captureRender([status: 202, data: ''])

        expect:
        driver.parseResponseJson() == null
    }

    def "parseResponseJson returns null when data is null"() {
        given:
        driver.captureRender([status: 202, data: null])

        expect:
        driver.parseResponseJson() == null
    }

    def "parseResponseJson parses valid JSON"() {
        given:
        driver.captureRender([status: 200, data: '{"jsonrpc":"2.0","id":1,"result":{"ok":true}}'])

        expect:
        def r = driver.parseResponseJson()
        r.jsonrpc == '2.0'
        r.result.ok == true
    }

    def "parseResponseJson throws IllegalStateException with render args echoed when body is unparseable"() {
        given:
        driver.captureRender([status: 200, contentType: 'application/json', data: '<<not-json>>'])

        when:
        driver.parseResponseJson()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('did not parse as JSON')
        e.message.contains('<<not-json>>') || e.message.contains('Captured render args')
    }

    def "callTool builds a JSON-RPC tools/call envelope and returns the parsed response"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/hub/advanced/freeOSMemory') { params -> 'TestHub-987654' }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        response.jsonrpc == '2.0'
        response.id == mcpDriver.lastSentId
        response.result != null
        response.result.content instanceof List
        response.result.content[0].type == 'text'
        response.result.content[0].text.contains('TestHub-987654')
    }

    def "decodeToolCallResponse unwraps a direct serialize-once response"() {
        given:
        def sentinel = [__preserialized:
            '{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"{\\"ok\\":true}"}]}}']

        when:
        def decoded = driver.decodeToolCallResponse(sentinel)

        then:
        decoded.jsonrpc == '2.0'
        decoded.id == 7
        driver.parseInner(sentinel) == [ok: true]
    }

    def "decodeToolCallResponse leaves an ordinary decoded response unchanged"() {
        given:
        def response = [jsonrpc: '2.0', id: 8, result: [ok: true]]

        expect:
        driver.decodeToolCallResponse(response).is(response)
    }

    def "decodeToolCallResponse rejects a preserialized payload that is not a JSON object"() {
        when:
        driver.decodeToolCallResponse([__preserialized: '[1,2,3]'])

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('did not decode to a JSON object')
    }
}
