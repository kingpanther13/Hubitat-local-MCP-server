package server

import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Per-client token access (issue #457): which token a request came in on, and what that
 * client may do. The main token (callerClient null) keeps every behaviour it had; a client
 * token is narrowed by its own tool list, Read/Write and local/cloud switches, and gets its
 * own tool-name number.
 */
class ClientAccessGrantSpec extends ToolSpecBase {

    def setup() {
        script.callerClient = null
        stateMap.accessToken = 'main,client-a,client-b'
        stateMap.clientTokens = ['1': [label: 'Alpha', token: 'client-a'], '2': [label: 'Beta', token: 'client-b']]
        stateMap.remove('retiredTokenHashes')
        settingsMap['clientTools_1'] = ['hub_list_modes', 'hub_list_rooms', 'hub_manage_mode', 'hub_update_mcp_settings']
        settingsMap['clientTools_2'] = ['hub_list_modes']
    }

    def cleanup() {
        script.callerClient = null
    }

    private void tokenIs(String token) {
        script.metaClass._requestAccessToken = { -> token }
    }

    private Map asClient(String id) {
        script.callerClient = [id: id, label: stateMap.clientTokens[id].label]
    }

    // ---- which token is calling ----

    def "the main token and a token the app does not know keep full access"() {
        expect:
        tokenIs(token)
        script._resolveCaller() == null
        script.callerClient == null

        where:
        token << ['main', 'oauth-bearer-of-the-app-type']
    }

    def "a client token sets the calling client"() {
        when:
        tokenIs('client-b')
        def refusal = script._resolveCaller()

        then:
        refusal == null
        script.callerClient == [id: '2', label: 'Beta']
    }

    def "a client token the hub no longer lists is refused with 401"() {
        given:
        stateMap.accessToken = 'main,client-a'

        when:
        tokenIs('client-b')
        def refusal = script._resolveCaller()

        then:
        refusal.status == 401
        refusal.data.contains('revoked')
        script.callerClient == null
    }

    def "a retired token is refused even though the hub's 60 s cache still accepts it"() {
        given: 'a revoked client token is gone from clientTokens, so it would otherwise read as the main token'
        script._retireAccessToken('client-gone')

        when:
        tokenIs('client-gone')
        def refusal = script._resolveCaller()

        then:
        refusal.status == 401
        script.callerClient == null
    }

    def "a request whose token the app cannot see is refused only once client tokens exist"() {
        when:
        tokenIs(null)
        def withClients = script._resolveCaller()
        stateMap.remove('clientTokens')
        def withoutClients = script._resolveCaller()

        then:
        withClients.status == 403
        withClients.data.contains('access_token')
        withoutClients == null
    }

    @Unroll
    def "a client's #source access switch refuses that connection only"() {
        given:
        settingsMap["${setting}_1".toString()] = false
        script.metaClass._isCloudRequest = { -> source == 'cloud' }

        when:
        tokenIs('client-a')
        def refusal = script._resolveCaller()
        script.metaClass._isCloudRequest = { -> source != 'cloud' }
        def otherConnection = script._resolveCaller()

        then:
        refusal.status == 403
        refusal.data.contains("${source} access is disabled for client 'Alpha'")
        otherConnection == null

        where:
        source  | setting
        'local' | 'clientLocal'
        'cloud' | 'clientCloud'
    }

    def "the request token is read the way the hub reads it: Bearer when no access_token parameter"() {
        given:
        mcpDriver.pushHeaders(['Authorization': 'Bearer client-a'])

        expect:
        script._requestAccessToken() == 'client-a'
    }

    def "a refused client request never reaches dispatch"() {
        given:
        stateMap.accessToken = 'main'
        tokenIs('client-a')

        when:
        def response = mcpDriver.callTool('hub_list_modes', [:])

        then:
        mcpDriver.lastRenderArgs.status == 401
        response.error.code == -32600
    }

    // ---- what the client may call ----

    def "the main token is not narrowed by any client's access"() {
        expect:
        script._clientToolDenial('hub_update_mcp_settings') == null
        !script.getHiddenToolNames().contains('hub_list_variables')
    }

    def "a tool the client was not given is refused with the client's name"() {
        given:
        asClient('2')

        when:
        script.executeTool('hub_list_rooms', [:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("hub_list_rooms is not enabled for client 'Beta'")
    }

    def "main-token-only tools cannot be granted even when picked"() {
        given:
        asClient('1')

        when:
        script.executeTool('hub_update_mcp_settings', [confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('works only with the main access token')

        and: 'code management and the Developer Mode deploy are main-token only too'
        script._ungrantableToolNames().containsAll(['hub_update_package', 'hub_update_app', 'hub_install_bundle'])
    }

    def "the client's Read and Write switches narrow a granted tool"() {
        given:
        asClient('1')
        settingsMap['clientWrite_1'] = false

        expect:
        script._clientToolDenial('hub_manage_mode').contains("Write tools are disabled for client 'Alpha'")
        script._clientToolDenial('hub_list_modes') == null

        when:
        settingsMap['clientWrite_1'] = true
        settingsMap['clientRead_1'] = false

        then:
        script._clientToolDenial('hub_list_modes').contains("Read tools are disabled for client 'Alpha'")
        script._clientToolDenial('hub_manage_mode') == null
    }

    def "a write-off client may still run hub_set_rule's schema-only probe, like the Write master allows"() {
        given:
        settingsMap['clientTools_1'] = ['hub_set_rule']
        settingsMap['clientWrite_1'] = false
        asClient('1')

        expect:
        script._clientToolDenial('hub_set_rule', [operation: 'guide']) == null
        script._clientToolDenial('hub_set_rule', [operation: 'update', confirm: true]) != null
    }

    def "the guide and tool search are always available to a client"() {
        given:
        asClient('2')

        expect:
        script._clientToolDenial('hub_get_tool_guide') == null
        script._clientToolDenial('hub_search_tools') == null
        script.executeTool('hub_get_tool_guide', [:]) != null
    }

    def "a client's catalog lists only what it can call"() {
        given:
        asClient('1')
        settingsMap.useGateways = true

        when:
        def names = script.handleToolsList([id: 1]).result.tools*.name as Set

        then: 'its granted base tools, the gateways that carry a granted tool, and the two always-on tools'
        names == ['hub_get_tool_guide', 'hub_search_tools', 'hub_list_modes', 'hub_manage_mode',
                  'hub_read_rooms', 'hub_manage_rooms'] as Set

        and: 'a gateway catalog shows only the granted sub-tool'
        script.handleGateway('hub_manage_rooms', null, null).tools*.name == ['hub_list_rooms']
    }

    def "a gateway with no granted tools is refused"() {
        given:
        asClient('2')

        when:
        script.executeTool('hub_manage_rooms', [:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("hub_manage_rooms has no tools enabled for client 'Beta'")
    }

    def "a client sees its own access in hub_get_info's clientAccess, and the main token sees none"() {
        expect:
        script._clientAccessInfo() == null

        when:
        asClient('2')
        settingsMap['clientCloud_2'] = false

        then:
        script._clientAccessInfo() == [client: 'Beta', readEnabled: true, writeEnabled: true,
                                       localAccessEnabled: true, cloudAccessEnabled: false,
                                       tools: ['hub_get_tool_guide', 'hub_list_modes', 'hub_search_tools']]
    }

    // ---- naming, logs, continuation, cursors ----

    @Unroll
    def "a client's tool-name number '#choice' gives #expected (main token number 4)"() {
        given:
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '4'
        settingsMap['clientToolNumber_1'] = choice
        asClient('1')

        expect:
        script._externalToolName('hub_list_modes') == expected

        where:
        choice | expected
        null   | 'hub4_list_modes'
        'main' | 'hub4_list_modes'
        'none' | 'hub_list_modes'
        '7'    | 'hub7_list_modes'
    }

    def "log records made during a client's request carry the client's name"() {
        expect:
        script._debugLogRecord([level: 'info', component: 'server', message: 'm', client: 'Alpha'], 'x').entry.client == 'Alpha'
        !script._debugLogRecord([level: 'info', component: 'server', message: 'm'], 'x').entry.containsKey('client')
    }

    def "one client's requestState cannot continue another client's or the main token's operation"() {
        given:
        def mainBinding = script._mrtrBinding('hub_call_rule', 'hub_call_rule', [ruleId: 1])
        asClient('1')
        def alphaBinding = script._mrtrBinding('hub_call_rule', 'hub_call_rule', [ruleId: 1])
        asClient('2')
        def betaBinding = script._mrtrBinding('hub_call_rule', 'hub_call_rule', [ruleId: 1])

        expect:
        script._mrtrBindingMatches(alphaBinding, alphaBinding)
        !script._mrtrBindingMatches(alphaBinding, betaBinding)
        !script._mrtrBindingMatches(mainBinding, alphaBinding)
    }

    def "hub_get_device cursors do not carry across clients"() {
        when:
        def mainSel = script._deviceReadSelection('5', 'full', null, null)
        asClient('1')
        def alphaSel = script._deviceReadSelection('5', 'full', null, null)

        then:
        mainSel != alphaSel
    }
}
