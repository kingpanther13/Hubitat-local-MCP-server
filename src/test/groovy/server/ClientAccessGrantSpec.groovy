package server

import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.ToolSpecBase

/**
 * Per-client token access (issue #457): which token a request came in on, and what that
 * client may do. The main token (callerClient null) keeps every behaviour it had; a client
 * token is narrowed by its own tool list, Read/Write and local/cloud switches, and gets its
 * own tool-name number.
 */
class ClientAccessGrantSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 7L, label: 'MCP')

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
    }

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
        injectedParams().remove('params')
    }

    private Map injectedParams() {
        def f = me.biocomp.hubitat_ci.app.HubitatAppScript.getDeclaredField('injectedMappingHandlerData')
        f.accessible = true
        return f.get(script) as Map
    }

    private void queryTokenIs(Object value) {
        injectedParams()['params'] = [access_token: value]
    }

    private void tokenIs(String token) {
        script.metaClass._requestAccessToken = { -> token }
    }

    private void asClient(String id) {
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

    def "a token the hub accepts that belongs to no client is refused, not treated as the main token"() {
        given: 'a client entry lost to two token-page writes racing: the hub still lists the token'
        stateMap.accessToken = 'main,client-a,client-b,client-orphan'

        when:
        tokenIs('client-orphan')
        def refusal = script._resolveCaller()

        then:
        refusal.status == 401
        refusal.data.contains('belongs to no client')
        script.callerClient == null
        script._untrackedAccessTokens() == ['client-orphan']

        and: 'an OAuth bearer of the app type is not in the app token list, so it keeps main access'
        tokenIs('oauth-bearer')
        script._resolveCaller() == null
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

    def "the request token is read the way the hub reads it: the access_token parameter first, then Bearer"() {
        when: 'query only'
        queryTokenIs('client-a')

        then:
        script._requestAccessToken() == 'client-a'

        when: 'query and Bearer both present: the query wins'
        mcpDriver.pushHeaders(['Authorization': 'Bearer main'])

        then:
        script._requestAccessToken() == 'client-a'

        when: 'a repeated parameter is not a String, so the hub falls through to Bearer'
        queryTokenIs(['client-a', 'client-b'])

        then:
        script._requestAccessToken() == 'main'

        when: 'lowercase scheme, and a bare scheme with no token'
        injectedParams().remove('params')
        mcpDriver.pushHeaders(['Authorization': 'bearer client-b'])
        def lower = script._requestAccessToken()
        mcpDriver.pushHeaders(['Authorization': 'Bearer '])

        then:
        lower == 'client-b'
        script._requestAccessToken() == null
    }

    def "a client token in the query reaches its own access through the real resolver"() {
        given:
        queryTokenIs('client-b')

        when:
        def refusal = script._resolveCaller()

        then:
        refusal == null
        script.callerClient == [id: '2', label: 'Beta']
    }

    @Unroll
    def "a client's refused call is a tool error naming the client, not a protocol error (useGateways=#gateways)"() {
        given:
        settingsMap.useGateways = gateways
        tokenIs('client-b')

        when:
        def response = gateways ? mcpDriver.callTool('hub_read_rooms', [tool: 'hub_list_rooms', args: [:]])
                                : mcpDriver.callTool('hub_list_rooms', [:])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains(expected)

        where:
        gateways | expected
        true     | "hub_read_rooms has no tools enabled for client 'Beta'"
        false    | "hub_list_rooms is not enabled for client 'Beta'"
    }

    def "a gateway the client can see still refuses its ungranted sub-tools"() {
        given:
        settingsMap.useGateways = true
        asClient('1')

        when:
        script.executeTool('hub_manage_rooms', [tool: 'hub_create_room', args: [name: 'x', confirm: true]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("hub_create_room is not enabled for client 'Alpha'")
    }

    def "a gateway hidden by a main-page gate keeps that gate's message, not a client one"() {
        given:
        asClient('1')
        settingsMap.disabled_gateways = ['hub_manage_rooms']

        expect:
        script._clientToolDenial('hub_manage_rooms') == null
    }

    def "a client cannot point an app tool at the MCP Rule Server app itself"() {
        given:
        settingsMap['clientTools_1'] = ['hub_get_app_config', 'hub_set_native_app', 'hub_export_native_app', 'hub_clone_native_app', 'hub_call_rule']
        asClient('1')

        expect: 'reads and writes on this app are refused; other apps are not'
        script._clientToolDenial('hub_get_app_config', [appId: 7, pageName: 'mainPage']).contains('cannot target the MCP Rule Server app itself')
        script._clientToolDenial('hub_get_app_config', [appId: '7']) != null
        script._clientToolDenial('hub_set_native_app', [appId: [12, 7]]) != null
        script._clientToolDenial('hub_get_app_config', [appId: 12]) == null

        and: 'through the aliases too: the cloner sourceAppId and the rule tools ruleId'
        script._clientToolDenial('hub_export_native_app', [sourceAppId: 7]) != null
        script._clientToolDenial('hub_clone_native_app', [sourceAppId: '7']) != null
        script._clientToolDenial('hub_call_rule', [ruleId: [7], action: 'run']) != null
        script._clientToolDenial('hub_export_native_app', [sourceAppId: 12]) == null

        when: 'a generic app mutation reaches the protected-app guard on a client token'
        script._requireUnprotectedAppMutation(7, 'update')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('cannot update the MCP Rule Server app itself')
    }

    def "a client token's tool results are token-redacted even with redaction off"() {
        given:
        settingsMap.redactAccessTokens = false
        def result = [page: 'http://hub/apps/api/7/mcp?access_token=main-secret', state: [accessToken: 'main-secret']]

        when:
        String mainView = groovy.json.JsonOutput.toJson(script._renderToolResult(1, 'hub_get_app_config', 'hub_get_app_config', [:], result))
        asClient('1')
        String clientView = groovy.json.JsonOutput.toJson(script._renderToolResult(2, 'hub_get_app_config', 'hub_get_app_config', [:], result))

        then:
        mainView.contains('main-secret')
        !clientView.contains('main-secret')
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
        given:
        def clients = stateMap.clientTokens
        stateMap.remove('clientTokens')
        def withoutClients = script.getHiddenToolNames()
        stateMap.clientTokens = clients

        expect:
        script._clientToolDenial('hub_update_mcp_settings') == null
        script.getHiddenToolNames() == withoutClients
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

    def "the guide and tool search are always available to a client, even with its Read switch off"() {
        given:
        asClient('2')
        settingsMap['clientRead_2'] = false

        expect:
        script._clientToolDenial('hub_get_tool_guide') == null
        script._clientToolDenial('hub_search_tools') == null
        !script.getHiddenToolNames().contains('hub_get_tool_guide')
        !script.getHiddenToolNames().contains('hub_search_tools')
        script.executeTool('hub_get_tool_guide', [:]) != null
    }

    def "the client's Write switch also drops its writes from the catalog"() {
        given:
        asClient('1')
        settingsMap['clientWrite_1'] = false

        expect:
        script.getHiddenToolNames().contains('hub_manage_mode')
        !script.getHiddenToolNames().contains('hub_list_modes')
    }

    def "a resource whose tool the client lacks names the client"() {
        given:
        asClient('2')

        when:
        def response = script.handleResourcesRead([id: 5, params: [uri: 'hubitat://context-summary']])

        then:
        response.error.code == -32002
        response.error.message.contains("not enabled for client 'Beta'")
    }

    def "inbound tool names use the client's own number"() {
        given:
        settingsMap['clientTools_1'] = ['hub_list_modes']
        settingsMap['clientToolNumber_1'] = '7'
        tokenIs('client-a')

        when:
        def numbered = mcpDriver.callTool('hub7_get_tool_guide', [section: 'tool_access'])

        then:
        numbered.error == null
        numbered.result.isError != true
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
        when:
        script.mcpLog('error', 'server', 'main line 457')
        asClient('1')
        script.mcpLog('error', 'server', 'client line 457')
        def entries = script.initDebugLogs().entries*.entry

        then:
        !entries.find { it.message == 'main line 457' }.containsKey('client')
        entries.find { it.message == 'client line 457' }.client == 'Alpha'
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
