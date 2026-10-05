package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * redactAccessTokens (Advanced page): _renderToolResult replaces OAuth access tokens in every
 * tool result. Off by default, so app pages come back exactly as the hub renders them.
 * Driven through hub_get_app_config, the read that returns app pages with their endpoint URLs.
 */
class AccessTokenRedactionSpec extends ToolSpecBase {

    private static final String TOKEN = '0f8e2c1a-1111-2222-3333-444455556666'
    private static final String MARKER = '***redacted (access token)***'

    private static String appPageJson() {
        JsonOutput.toJson([
            app: [id: 35, trueLabel: 'MCP Rule Server', label: 'MCP Rule Server', name: 'MCP Rule Server',
                  installed: true, appType: [name: 'MCP Rule Server', namespace: 'mcp']],
            configPage: [name: 'mainPage', title: 'MCP Rule Server', install: true, sections: [[
                title: 'Endpoints',
                input: [],
                body: [
                    [description: "<code>http://192.168.1.2/apps/api/35/mcp?access_token=${TOKEN}&debug=1</code>"],
                    [description: 'Version: 4.5.2']
                ]
            ]]],
            settings: [accessToken: TOKEN, pinToken: '1234',
                       exported: JsonOutput.toJson([access_token: TOKEN, name: 'kept'])],
            childApps: []
        ])
    }

    @spock.lang.Unroll
    def "tokens come back unchanged when redactAccessTokens is off (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/installedapp/configure/json/35') { params -> appPageJson() }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, includeSettings: true])
        def inner = mcpDriver.parseInner(response)

        then:
        !response.result.isError
        inner.page.sections[0].paragraphs[0].contains("access_token=${TOKEN}&debug=1")
        inner.settings.accessToken == TOKEN

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "every token shape is redacted when redactAccessTokens is on (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.redactAccessTokens = true
        hubGet.register('/installedapp/configure/json/35') { params -> appPageJson() }

        when:
        def response = mcpDriver.callTool('hub_get_app_config', [appId: 35, includeSettings: true])
        def inner = mcpDriver.parseInner(response)

        then:
        !response.result.isError
        !response.result.content[0].text.contains(TOKEN)
        inner.page.sections[0].paragraphs[0].contains("access_token=${MARKER}&debug=1")
        inner.page.sections[0].paragraphs[1] == 'Version: 4.5.2'
        inner.settings.accessToken == MARKER
        inner.settings.pinToken == '1234'
        inner.settings.exported.contains("\"access_token\":\"${MARKER}\"")
        inner.settings.exported.contains('"name":"kept"')

        where:
        useGateways << [true, false]
    }

    def "a token key holding a structure is walked, not replaced"() {
        when:
        def out = script._redactAccessTokens([accessToken: [value: "x?access_token=${TOKEN}"], list: ["a", 1, null]])

        then:
        out == [accessToken: [value: "x?access_token=${MARKER}".toString()], list: ["a", 1, null]]
    }
}
