package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Per-client MCP access tokens (issue #490, firmware 2.5.2 createAnotherAccessToken). Live probe on
 * 2.5.2.129: every token in the list authenticates (query and Bearer); state.accessToken becomes the
 * comma-joined list once a second token exists; resetSpecificAccessToken rotates one token in place;
 * createAccessToken() wipes every token. The token APIs are declared on AppExecutor, so they are
 * stubbed on the mock in setupSpec and route to per-test closures.
 */
class ClientTokensSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')
    @Shared List<String> calls = []
    @Shared int nextToken = 0

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
            stateMap.accessToken = stateMap.accessToken.toString().replace(args[0].toString(), 'main-rotated')
        }
        appExecutor.revokeSpecificAccessToken(_) >> { args -> calls << "revoke:${args[0]}" }
        appExecutor.getAccessTokens() >> { stateMap.accessToken.toString().split(',').toList() }
    }

    def setup() {
        calls.clear()
        nextToken = 0
        stateMap.accessToken = 'main'
        stateMap.remove('clientTokens')
        script.metaClass.mcpLog = { String level, String component, String msg -> }
    }

    def "creating a client token adds it under its name and keeps the main token first"() {
        given:
        settingsMap.newClientTokenLabel = 'Claude Desktop'

        when:
        script.appButtonHandler('createClientTokenBtn')

        then:
        calls == ['another:client-1']
        stateMap.clientTokens == ['Claude Desktop': 'client-1']
        script._primaryAccessToken() == 'main'
    }

    @spock.lang.Unroll
    def "an unusable client name creates nothing: '#label'"() {
        given:
        stateMap.clientTokens = ['Phone': 'client-0']
        settingsMap.newClientTokenLabel = label

        when:
        script.appButtonHandler('createClientTokenBtn')

        then:
        calls.isEmpty()
        stateMap.clientTokens == ['Phone': 'client-0']

        where:
        label << ['', 'a,b', 'x' * 41, 'Phone']
    }

    def "revoking a client token removes only that client"() {
        given:
        stateMap.accessToken = 'main,client-a,client-b'
        stateMap.clientTokens = ['Alpha': 'client-a', 'Beta': 'client-b']

        when: 'revokeClientToken_1 is the second name in sorted order'
        script.appButtonHandler('revokeClientToken_1')

        then:
        calls == ['revoke:client-b']
        stateMap.clientTokens == ['Alpha': 'client-a']
    }

    def "regenerating the main token rotates only it while client tokens exist"() {
        given:
        stateMap.accessToken = 'main,client-a'
        stateMap.clientTokens = ['Alpha': 'client-a']

        when:
        script.appButtonHandler('regenerateTokenBtn')

        then: 'createAccessToken (which wipes every token) is not used'
        calls == ['reset:main']
        stateMap.accessToken == 'main-rotated,client-a'
        script._primaryAccessToken() == 'main-rotated'
    }

    def "regenerating without client tokens keeps the original full re-issue"() {
        when:
        script.appButtonHandler('regenerateTokenBtn')

        then:
        calls == ['create']
        stateMap.accessToken == 'main-new'
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
