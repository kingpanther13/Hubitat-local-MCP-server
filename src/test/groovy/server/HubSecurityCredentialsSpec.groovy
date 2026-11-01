package server

import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * The optional Hub Login Security credentials. They are never needed for the server to work: the
 * hub's login filter exempts only requests from 127.0.0.1, so without them hub calls stay on
 * loopback. With them, the server signs in over loopback and its session cookie (accepted from any
 * address) lets hub calls take the hub's LAN address. Routing without credentials is in HubBaseUriSpec.
 */
class HubSecurityCredentialsSpec extends ToolSpecBase {

    static final String PROBE_PATH = '/hub/advanced/freeOSMemory'
    static final String LOOP = 'http://127.0.0.1:8080'
    static final String LAN = 'http://192.168.1.13:8080'
    // The hub's login page markup, trimmed: a "Login" title and an action-less POST form with
    // username and password inputs.
    static final String LOGIN_PAGE = '<html><head><title>Login - Test Hub</title></head><body><form method="POST">' +
        '<input type="text" name="username" class="mdl-textfield__input" size="4" id="username" required="required" maxlength="64"/>' +
        '<input type="password" name="password" class="mdl-textfield__input" size="4" id="password" required="required" maxlength="120"/>' +
        '<input type="submit" name="submit" value="Login" class="mdl-button mdl-js-button mdl-button--raised"/>' +
        '</form></body></html>'

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')
    @Shared private TestLocation sharedLocation = new TestLocation()
    @Shared Closure httpGetHandler = null
    @Shared Closure httpPostHandler = null
    @Shared List logins = []
    @Shared List probes = []
    // When true, POST /login fails the way a refused sign-in does.
    @Shared boolean refuseSignIn = false
    // Overrides the /login answer for one feature.
    @Shared Closure loginHandler = null

    /** A redirect the way HTTPBuilder throws it (duck-typed .response). */
    private static class RedirectException extends RuntimeException {
        final def response
        RedirectException(String location, String setCookie = null) {
            super('HTTP 302')
            this.response = [status: 302, headers: setCookie ? ['Location': location, 'Set-Cookie': setCookie] : ['Location': location]]
        }
    }

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
        appExecutor.getLocation() >> sharedLocation
        appExecutor.httpGet(_, _) >> { args ->
            Map params = args[0]
            if (params.path == PROBE_PATH) {
                probes << [uri: params.uri, cookie: params.headers?.Cookie]
                return args[1].call([status: 200, data: '123456'])
            }
            if (httpGetHandler) httpGetHandler.call(params, args[1])
        }
        appExecutor.httpPost(_, _) >> { args ->
            Map params = args[0]
            if (params.path == '/login') {
                logins << [uri: params.uri, body: params.body, followRedirects: params.followRedirects]
                if (loginHandler) return loginHandler.call(params, args[1])
                // As the hub answers: wrong credentials re-render the login page (200) and still open an
                // unauthenticated session; good ones redirect away from /login with the session cookie.
                if (refuseSignIn) {
                    return args[1].call([status: 200, data: LOGIN_PAGE,
                                         headers: ['Set-Cookie': 'HUBSESSION=anonymous; Path=/; HttpOnly']])
                }
                throw new RedirectException('/', "HUBSESSION=s${logins.size()}; Path=/; HttpOnly".toString())
            }
            if (httpPostHandler) httpPostHandler.call(params, args[1])
        }
    }

    def setup() {
        sharedAppStub.settingsStore.clear()
        sharedLocation.hub = new TestHub(localIP: '192.168.1.13', firmwareVersionString: '2.5.2.135')
        logins.clear()
        probes.clear()
        mcpDriver.reset()
        // Hub Login Security is on for these features: only the credentials open the LAN route.
        script.metaClass.isHubitatUISecurityEnabled = { -> true }
    }

    def cleanup() {
        httpGetHandler = null
        httpPostHandler = null
        refuseSignIn = false
        loginHandler = null
    }

    private void configureCredentials() {
        settingsMap.hubSecurityEnabled = true
        settingsMap.hubSecurityUser = 'hubadmin'
        settingsMap.hubSecurityPassword = 'hunter2'
        sharedAppStub.settingsStore.hubSecurityEnabled = true
        sharedAppStub.settingsStore.hubSecurityUser = 'hubadmin'
        sharedAppStub.settingsStore.hubSecurityPassword = 'hunter2'
    }

    @Unroll
    def 'stored credentials are never wiped by an MCP request on firmware #fw'() {
        given:
        sharedLocation.hub = new TestHub(localIP: '192.168.1.13', firmwareVersionString: fw)
        configureCredentials()
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'initialize', params: [protocolVersion: '2026-07-28']])

        when:
        script.handleMcpRequest()

        then:
        sharedAppStub.settingsStore.hubSecurityEnabled == true
        sharedAppStub.settingsStore.hubSecurityUser == 'hubadmin'
        sharedAppStub.settingsStore.hubSecurityPassword == 'hunter2'
        !stateMap.containsKey('hubSecurityRetired')

        where:
        fw << ['2.5.2.135', '2.5.0.123', '2.4.9.999', null]
    }

    def 'the sign-in goes to loopback and the session cookie rides the LAN route'() {
        given:
        configureCredentials()
        def sent = []
        httpGetHandler = { Map params, Closure cb ->
            sent << [uri: params.uri, cookie: params.headers?.Cookie]
            cb.call([status: 200, data: 'BODY'])
        }

        when:
        def body = script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])

        then: 'one sign-in, over loopback, with the configured credentials and without following its redirect'
        logins == [[uri: LOOP, body: [username: 'hubadmin', password: 'hunter2'], followRedirects: false]]

        and: 'the LAN route was probed with the new cookie, then the request took it with the cookie attached'
        probes == [[uri: LAN, cookie: 'HUBSESSION=s1']]
        body == 'BODY'
        sent == [[uri: LAN, cookie: 'HUBSESSION=s1']]
        script.toolGetHubInfo().hubRequestRoute == 'lan'
    }

    def 'Hub Login Security on without credentials stays on loopback, signs in nowhere and logs nothing'() {
        given:
        def logs = []
        script.metaClass.mcpLog = { String level, String component, String message, String ruleId = null, Map extra = null ->
            logs << [level: level, message: message]
        }
        def sent = []
        httpGetHandler = { Map params, Closure cb ->
            sent << [uri: params.uri, cookie: params.headers?.Cookie]
            cb.call([status: 200, data: 'BODY'])
        }

        when:
        script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])

        then:
        sent == [[uri: LOOP, cookie: null]]
        logins.isEmpty()
        probes.isEmpty()
        !logs.any { it.level == 'warn' }
    }

    def 'an expired session on the LAN route answers with the login page: sign in again, re-probe and repeat once'() {
        given: 'a cached session the hub no longer accepts'
        configureCredentials()
        atomicStateMap.hubSecurityCookie = 'HUBSESSION=old'
        atomicStateMap.hubSecurityCookieExpiry = 1234567890000L + 60_000
        def sent = []
        httpGetHandler = { Map params, Closure cb ->
            sent << [uri: params.uri, cookie: params.headers?.Cookie]
            cb.call([status: 200, data: params.headers?.Cookie == 'HUBSESSION=old' ? LOGIN_PAGE : 'BODY'])
        }

        when:
        def body = script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])

        then: 'the cached LAN verdict was dropped, so the route was probed again with the new session'
        body == 'BODY'
        logins.size() == 1
        probes == [[uri: LAN, cookie: 'HUBSESSION=old'], [uri: LAN, cookie: 'HUBSESSION=s1']]
        sent == [[uri: LAN, cookie: 'HUBSESSION=old'], [uri: LAN, cookie: 'HUBSESSION=s1']]
        atomicStateMap.hubSecurityCookie == 'HUBSESSION=s1'
    }

    def 'a login page on the repeat is returned as is, not retried again'() {
        given:
        configureCredentials()
        atomicStateMap.hubSecurityCookie = 'HUBSESSION=old'
        atomicStateMap.hubSecurityCookieExpiry = 1234567890000L + 60_000
        def sent = 0
        httpGetHandler = { Map params, Closure cb -> sent++; cb.call([status: 200, data: LOGIN_PAGE]) }

        when:
        def body = script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])

        then:
        body == LOGIN_PAGE
        sent == 2
        logins.size() == 1
    }

    def 'a LAN POST redirected to /login signs in again once and repeats'() {
        given:
        configureCredentials()
        atomicStateMap.hubSecurityCookie = 'HUBSESSION=old'
        atomicStateMap.hubSecurityCookieExpiry = 1234567890000L + 60_000
        def sent = []
        httpPostHandler = { Map params, Closure cb ->
            sent << params.headers?.Cookie
            if (params.headers?.Cookie == 'HUBSESSION=old') throw new RedirectException('http://192.168.1.13:8080/login')
            cb.call([status: 200, data: 'OK'])
        }

        when:
        def body = script._hubRequest('POST', '/installedapp/update/json', [returnShape: 'text', body: 'x=1'])

        then:
        body == 'OK'
        sent == ['HUBSESSION=old', 'HUBSESSION=s1']
        logins.size() == 1
    }

    @Unroll
    def 'hub_get_info always lists hubSecurityConfigured (#configured) and never hubSecurityRetired'() {
        given: 'a hub upgraded from the retirement era may still carry the old marker'
        stateMap.hubSecurityRetired = true
        if (configured) configureCredentials()

        when:
        def info = script.toolGetHubInfo([:])

        then:
        info.hubSecurityConfigured == configured
        !info.containsKey('hubSecurityRetired')

        where:
        configured << [true, false]
    }

    def 'the retirement-era markers are removed from state on upgraded hubs'() {
        given:
        stateMap.hubSecurityRetired = true
        stateMap.hubSecurityFwUnreadable = true

        when:
        script._cleanupRetiredToolState()

        then:
        !stateMap.containsKey('hubSecurityRetired')
        !stateMap.containsKey('hubSecurityFwUnreadable')
    }

    def 'a login page with a session cookie is a refused sign-in: hub calls go over loopback without a cookie, one sign-in per window'() {
        given:
        configureCredentials()
        refuseSignIn = true
        def logs = []
        script.metaClass.mcpLog = { String level, String component, String message, String ruleId = null, Map extra = null ->
            logs << [level: level, message: message]
        }
        def sent = []
        httpGetHandler = { Map params, Closure cb ->
            sent << [uri: params.uri, cookie: params.headers?.Cookie]
            cb.call([status: 200, data: 'BODY'])
        }

        when: 'three hub calls and a hub_get_info inside the backoff window'
        def bodies = (1..3).collect { script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text']) }
        def info = script.toolGetHubInfo([:])

        then: 'every call succeeds on loopback, cookie-less: the unauthenticated session is never cached'
        bodies == ['BODY', 'BODY', 'BODY']
        sent == [[uri: LOOP, cookie: null]] * 3
        !atomicStateMap.hubSecurityCookie

        and: 'one /login POST for the whole window, and no LAN probe'
        logins.size() == 1
        probes.isEmpty()

        and: 'the route says why, and the refusal is logged once'
        info.hubRequestRoute == 'loopback'
        info.hubRequestRouteNote == 'Hub Login Security sign-in failed'
        logs.count { it.level == 'warn' && it.message.contains('credentials were refused') } == 1
    }

    def 'after the sign-in backoff a new sign-in is attempted, and a success opens the LAN route'() {
        given:
        configureCredentials()
        refuseSignIn = true
        httpGetHandler = { Map params, Closure cb -> cb.call([status: 200, data: 'BODY']) }
        script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])
        long failedAt = script.HUB_SIGNIN_FAILED_AT.get('at') as long

        when: 'the window has passed and the credentials now work'
        script.HUB_SIGNIN_FAILED_AT.put('at', failedAt - script.HUB_LAN_ROUTE_RETRY_MS)
        refuseSignIn = false
        def uri = script.hubBaseUri()

        then:
        logins.size() == 2
        uri == LAN
        script.HUB_SIGNIN_FAILED_AT.isEmpty()
    }

    @Unroll
    def 'the sign-in counts only a redirect away from /login: #answer'() {
        given:
        configureCredentials()
        loginHandler = handler

        when:
        def cookie = script._hubSignIn()

        then:
        cookie == expected
        logins*.followRedirects == [false]

        where:
        answer                       | expected            | handler
        '302 to / with a cookie'     | 'HUBSESSION=good'   | { Map p, Closure c -> throw new RedirectException('/', 'HUBSESSION=good; Path=/') }
        '302 handed to the closure'  | 'HUBSESSION=good'   | { Map p, Closure c -> c([status: 302, headers: ['Location': 'http://127.0.0.1:8080/', 'Set-Cookie': 'HUBSESSION=good; Path=/']]) }
        '302 back to /login'         | null                | { Map p, Closure c -> throw new RedirectException('/login?error=1', 'HUBSESSION=anon; Path=/') }
        '200 login page with cookie' | null                | { Map p, Closure c -> c([status: 200, data: LOGIN_PAGE, headers: ['Set-Cookie': 'HUBSESSION=anon; Path=/']]) }
        '302 without a cookie'       | null                | { Map p, Closure c -> throw new RedirectException('/') }
        'a connection failure'       | null                | { Map p, Closure c -> throw new IllegalStateException('no route') }
    }
}
