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
    @Shared List<String> removedSettings = []

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
        appExecutor.createAccessToken() >> { calls << 'create'; stateMap.accessToken = 'main-new'; 'main-new' }
        appExecutor.createAnotherAccessToken() >> {
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
        removedSettings.clear()
        nextToken = 0
        revokeSticks = true
        stateMap.accessToken = 'main'
        ['clientTokens', 'nextClientTokenId', 'retiredTokenHashes'].each { stateMap.remove(it) }
        script.metaClass.mcpLog = { String level, String component, String msg -> }
        sharedAppStub.metaClass.removeSetting = { String name -> removedSettings << name; settingsMap.remove(name) }
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

        then:
        calls.isEmpty()
        stateMap.clientTokens == ['1': [label: 'Phone', token: 'client-0']]

        where:
        label << ['', 'a,b', 'x' * 41, 'Phone']
    }

    def "revoking a client token removes only that client, its access settings, and retires the token"() {
        given:
        stateMap.accessToken = 'main,client-a,client-b'
        stateMap.clientTokens = ['1': [label: 'Alpha', token: 'client-a'], '2': [label: 'Beta', token: 'client-b']]
        settingsMap['clientTools_2'] = ['hub_list_modes']
        settingsMap['clientTools_1'] = ['hub_list_rooms']

        when:
        script.appButtonHandler('revokeClientToken_2')

        then:
        calls == ['revoke:client-b']
        stateMap.clientTokens == ['1': [label: 'Alpha', token: 'client-a']]
        stateMap.accessToken == 'main,client-a'
        removedSettings.containsAll(['clientTools_2', 'clientRead_2', 'clientWrite_2', 'clientLocal_2', 'clientCloud_2', 'clientToolNumber_2'])
        settingsMap['clientTools_1'] == ['hub_list_rooms']
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
