package server

import groovy.json.JsonSlurper
import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Issue #453: enableLocalAccess / enableCloudAccess gate every mapped endpoint on the transport the
 * request arrived on (request.requestSource). Both default on; only an explicit false blocks, and a
 * blocked request is answered before any MCP work runs.
 */
class TransportAccessSpec extends ToolSpecBase {

    private static final Map INITIALIZE = [jsonrpc: '2.0', id: 1, method: 'initialize',
                                           params: [protocolVersion: '2025-06-18']]

    @Unroll
    def "/mcp over #source with local=#local cloud=#cloud is #outcome"() {
        given:
        mcpDriver.requestSource = source
        if (local != null) settingsMap.enableLocalAccess = local
        if (cloud != null) settingsMap.enableCloudAccess = cloud
        mcpDriver.pushBody(INITIALIZE)

        when:
        script.handleMcpRequest()

        then:
        def body = mcpDriver.parseResponseJson()
        if (outcome == 'blocked') {
            assert mcpDriver.lastRenderArgs.status == 403
            assert body.id == null
            assert body.error.code == -32600
            assert body.error.message.contains("${source == 'cloud' ? 'cloud' : 'local'} access")
            assert body.result == null
        } else {
            assert mcpDriver.lastRenderArgs.status == null
            assert body.result.protocolVersion == '2025-06-18'
        }

        where:
        source  | local | cloud || outcome
        null    | null  | null  || 'served'
        'local' | true  | false || 'served'
        'cloud' | false | true  || 'served'
        'local' | false | true  || 'blocked'
        null    | false | true  || 'blocked'
        'cloud' | true  | false || 'blocked'
        'cloud' | false | false || 'blocked'
        'local' | false | false || 'blocked'
    }

    def "a blocked request never reaches tool dispatch"() {
        given:
        mcpDriver.requestSource = 'cloud'
        settingsMap.enableCloudAccess = false
        boolean dispatched = false
        script.metaClass.handleToolsCall = { Map msg -> dispatched = true; [:] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        mcpDriver.lastRenderArgs.status == 403
        response.error.code == -32600
        !dispatched
    }

    @Unroll
    def "/health over #source answers #status"() {
        given:
        mcpDriver.requestSource = source
        settingsMap.enableCloudAccess = false

        when:
        script.handleHealth()

        then:
        mcpDriver.lastRenderArgs.status == status
        new JsonSlurper().parseText(mcpDriver.lastRenderArgs.data as String).status == body

        where:
        source  || status | body
        'local' || null   | 'ok'
        'cloud' || 403    | 'forbidden'
    }

    @Unroll
    def "GET /mcp over a disabled transport answers 403 before the 405"() {
        given:
        mcpDriver.requestSource = 'local'
        settingsMap.enableLocalAccess = local

        when:
        script.handleMcpGet()

        then:
        mcpDriver.lastRenderArgs.status == status

        where:
        local || status
        false || 403
        true  || 405
    }
}
