package server

import groovy.json.JsonSlurper
import support.ToolSpecBase

/**
 * ChatGPT (clientInfo name {@code openai-mcp}) renders any tool result whose JSON text carries a
 * top-level {@code content} key as {@code {}} (issue #565), so that client alone gets the key as
 * {@code contentText}. Every other client keeps {@code content}.
 */
class OpenAiContentKeySpec extends ToolSpecBase {

    private Map guideResult(Map clientInfo) {
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': 'hub_get_tool_guide',
        ])
        def meta = ['io.modelcontextprotocol/protocolVersion': '2026-07-28']
        if (clientInfo != null) meta['io.modelcontextprotocol/clientInfo'] = clientInfo
        mcpDriver.pushBody([jsonrpc: '2.0', id: 7, method: 'tools/call',
                            params: [name: 'hub_get_tool_guide', arguments: [section: 'rooms'], _meta: meta]])
        script.handleMcpRequest()
        def response = mcpDriver.parseResponseJson()
        return new JsonSlurper().parseText(response.result.content[0].text) as Map
    }

    def "ChatGPT gets the guide text under contentText"() {
        when:
        def result = guideResult([name: 'openai-mcp', version: '1.0.0'])

        then:
        !result.containsKey('content')
        result.contentText.startsWith('## Rooms')
        result.success == true
    }

    def "other clients keep the content key"() {
        when:
        def result = guideResult(clientInfo)

        then:
        result.content.startsWith('## Rooms')
        !result.containsKey('contentText')

        where:
        clientInfo << [[name: 'claude-code', version: '2.1.302'], null]
    }
}
