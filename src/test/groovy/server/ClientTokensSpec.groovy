package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Per-client MCP access tokens (issue #457, firmware 2.5.2 createAnotherAccessToken). Live probe on
 * 2.5.2.129: every token in the list authenticates (query and Bearer); state.accessToken becomes the
 * comma-joined list once a second token exists; resetSpecificAccessToken rotates one token in place;
 * createAccessToken() wipes every token. The token APIs are declared on AppExecutor, so they are
 * stubbed on the mock in setupSpec and route to per-test closures.
 */
class ClientTokensSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')
    @Shared List<String> calls = []
    @Shared int nextToken = 0
    @Shared boolean revokeSticks = true
    @Shared boolean createThrows = false

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
        appExecutor.createAccessToken() >> { calls << 'create'; stateMap.accessToken = 'main-new'; 'main-new' }
        appExecutor.createAnotherAccessToken() >> {
            if (createThrows) throw new MissingMethodException('createAnotherAccessToken', Object, [] as Object[])
            def t = "client-${++nextToken}".toString()
            calls << "another:${t}"
            stateMap.accessToken = "${stateMap.accessToken},${t}".toString()
            t
        }
        appExecutor.resetSpecificAccessToken(_) >> { args ->
            calls << "reset:${args[0]}"
            stateMap.accessToken = stateMap.accessToken.toString().replace(args[0].toString(), "${args[0]}-rotated")
        }
        appExecutor.revokeSpecificAccessToken(_) >> { args ->
            calls << "revoke:${args[0]}"
            if (revokeSticks) {
                stateMap.accessToken = stateMap.accessToken.toString().split(',').findAll { it != args[0] }.join(',')
            }
        }
        appExecutor.getAccessTokens() >> { stateMap.accessToken.toString().split(',').toList() }
    }

    def setup() {
        calls.clear()
        nextToken = 0
        revokeSticks = true
        createThrows = false
        stateMap.accessToken = 'main'
        ['clientTokens', 'nextClientTokenId', 'retiredTokenHashes'].each { stateMap.remove(it) }
        script.metaClass.mcpLog = { String level, String component, String msg -> }
        sharedAppStub.settingsStore.clear()
    }

    def "creating a client token stores it under a new id and keeps the main token first"() {
        given:
        settingsMap.newClientTokenLabel = 'Claude Desktop'

        when:
        script.appButtonHandler('createClientTokenBtn')

        then:
        calls == ['another:client-1']
        stateMap.clientTokens == ['1': [label: 'Claude Desktop', token: 'client-1']]
        stateMap.nextClientTokenId == 2
        script._primaryAccessToken() == 'main'

        and: 'the new client starts with every grantable tool on, and no main-token-only tool'
        def written = sharedAppStub.settingsStore['clientTools_1'] as Map
        written.type == 'enum'
        def granted = written.value as List
        granted as Set == script._grantableToolOptions().keySet() as Set
        granted.contains('hub_list_modes')
        !granted.contains('hub_update_mcp_settings')
        !granted.contains('hub_update_package')
    }

    def "Select all and Deselect all set or clear only that client's tools"() {
        given:
        stateMap.clientTokens = ['1': [label: 'Alpha', token: 'client-a'], '2': [label: 'Beta', token: 'client-b']]
        sharedAppStub.settingsStore['clientTools_1'] = ['hub_list_modes']
        sharedAppStub.settingsStore['clientTools_2'] = ['hub_list_rooms']

        when:
        script.appButtonHandler('clientToolsAll_1')

        then:
        ((sharedAppStub.settingsStore['clientTools_1'] as Map).value as Set) == script._grantableToolOptions().keySet() as Set
        sharedAppStub.settingsStore['clientTools_2'] == ['hub_list_rooms']

        when:
        script.appButtonHandler('clientToolsNone_1')

        then:
        !sharedAppStub.settingsStore.containsKey('clientTools_1')
        sharedAppStub.settingsStore['clientTools_2'] == ['hub_list_rooms']

        when: 'a button for a client that no longer exists changes nothing'
        script.appButtonHandler('clientToolsAll_9')

        then:
        !sharedAppStub.settingsStore.containsKey('clientTools_9')
    }

    def "a hub that cannot issue a token creates no client and says so"() {
        given:
        createThrows = true
        settingsMap.newClientTokenLabel = 'Old Firmware'
        script.metaClass.mcpLogError = { String component, String msg, Exception e = null, String ruleId = null -> }

        when:
        script.appButtonHandler('createClientTokenBtn')

        then:
        !stateMap.clientTokens
        stateMap.nextClientTokenId == null
        stateMap.clientTokenNotice.contains('firmware 2.5.2')
    }

    def "a failed revoke or rotate is shown on the page"() {
        given:
        revokeSticks = false
        stateMap.accessToken = 'main,client-a'
        stateMap.clientTokens = ['1': [label: 'Alpha', token: 'client-a']]

        when:
        script.appButtonHandler('revokeClientToken_1')

        then:
        stateMap.clientTokenNotice.contains("'Alpha' was not revoked")
    }

    def "ids are never reused after a revoke"() {
        given:
        stateMap.accessToken = 'main,client-a'
        stateMap.clientTokens = ['1': [label: 'Alpha', token: 'client-a']]
        stateMap.nextClientTokenId = 2
        settingsMap.newClientTokenLabel = 'Beta'

        when:
        script.appButtonHandler('revokeClientToken_1')
        script.appButtonHandler('createClientTokenBtn')

        then:
        stateMap.clientTokens == ['2': [label: 'Beta', token: 'client-1']]
    }

    @spock.lang.Unroll
    def "an unusable client name creates nothing: '#label'"() {
        given:
        stateMap.clientTokens = ['1': [label: 'Phone', token: 'client-0']]
        settingsMap.newClientTokenLabel = label

        when:
        script.appButtonHandler('createClientTokenBtn')

        then: 'nothing is created and the page says why'
        calls.isEmpty()
        stateMap.clientTokens == ['1': [label: 'Phone', token: 'client-0']]
        stateMap.clientTokenNotice.startsWith('No token created')

        where:
        label << ['', 'a,b', 'x' * 41, 'Phone']
    }

    def "revoking a client token removes only that client, its access settings, and retires the token"() {
        given:
        stateMap.accessToken = 'main,client-a,client-b'
        stateMap.clientTokens = ['1': [label: 'Alpha', token: 'client-a'], '2': [label: 'Beta', token: 'client-b']]
        def accessKeys = ['clientTools', 'clientRead', 'clientWrite', 'clientLocal', 'clientCloud', 'clientToolNumber']
        accessKeys.each { sharedAppStub.settingsStore["${it}_2".toString()] = 'x' }
        sharedAppStub.settingsStore['clientTools_1'] = ['hub_list_rooms']

        when:
        script.appButtonHandler('revokeClientToken_2')

        then:
        calls == ['revoke:client-b']
        stateMap.clientTokens == ['1': [label: 'Alpha', token: 'client-a']]
        stateMap.accessToken == 'main,client-a'
        accessKeys.every { !sharedAppStub.settingsStore.containsKey("${it}_2".toString()) }
        sharedAppStub.settingsStore['clientTools_1'] == ['hub_list_rooms']
        script._isRetiredAccessToken('client-b')
        !script._isRetiredAccessToken('client-a')
    }

    def "a revoke the hub does not apply keeps the client listed (fails closed)"() {
        given:
        revokeSticks = false
        stateMap.accessToken = 'main,client-a'
        stateMap.clientTokens = ['1': [label: 'Alpha', token: 'client-a']]

        when:
        script.appButtonHandler('revokeClientToken_1')

        then: 'the token is still in the list, so it must stay visible and revocable'
        calls == ['revoke:client-a']
        stateMap.clientTokens == ['1': [label: 'Alpha', token: 'client-a']]
        !script._isRetiredAccessToken('client-a')
    }

    def "rotating a client token swaps in the new token and retires the old one"() {
        given:
        stateMap.accessToken = 'main,client-a'
        stateMap.clientTokens = ['1': [label: 'Alpha', token: 'client-a']]

        when:
        script.appButtonHandler('rotateClientToken_1')

        then:
        calls == ['reset:client-a']
        stateMap.clientTokens == ['1': [label: 'Alpha', token: 'client-a-rotated']]
        script._isRetiredAccessToken('client-a')
        script._primaryAccessToken() == 'main'
    }

    def "response redaction masks every per-client token and keeps the client names"() {
        given:
        settingsMap.redactAccessTokens = true

        expect:
        script._redactAccessTokens([state: [clientTokens: ['1': [label: 'Alpha', token: 'client-a']], accessToken: 'main,client-a']]) ==
            [state: [clientTokens: ['1': [label: 'Alpha', token: '***redacted (access token)***']],
                     accessToken: '***redacted (access token)***']]

        and: 'the same map serialized into a string'
        !script._redactAccessTokens('{"clientTokens":{"1":{"label":"Alpha","token":"client-a"}}}').contains('client-a')
    }

    def "regenerating the main token rotates only it while client tokens exist, and retires the old one"() {
        given:
        stateMap.accessToken = 'main,client-a'
        stateMap.clientTokens = ['1': [label: 'Alpha', token: 'client-a']]

        when:
        script.appButtonHandler('regenerateTokenBtn')

        then: 'createAccessToken (which wipes every token) is not used'
        calls == ['reset:main']
        stateMap.accessToken == 'main-rotated,client-a'
        script._primaryAccessToken() == 'main-rotated'
        script._isRetiredAccessToken('main')
    }

    def "regenerating without client tokens keeps the original full re-issue"() {
        when:
        script.appButtonHandler('regenerateTokenBtn')

        then:
        calls == ['create']
        stateMap.accessToken == 'main-new'
        script._isRetiredAccessToken('main')
    }

    def "a retired token is forgotten after ten minutes"() {
        given:
        script._retireAccessToken('old')
        def at = stateMap.retiredTokenHashes.values().first() as Long
        stateMap.retiredTokenHashes = stateMap.retiredTokenHashes.collectEntries { h, t -> [(h): at - 600001L] }

        expect:
        !script._isRetiredAccessToken('old')
    }

    def "_primaryAccessToken reads the first token of the comma-joined list"() {
        expect:
        script._primaryAccessToken() == 'main'

        when:
        stateMap.accessToken = 'p1, c1,c2'

        then:
        script._primaryAccessToken() == 'p1'
    }
}
