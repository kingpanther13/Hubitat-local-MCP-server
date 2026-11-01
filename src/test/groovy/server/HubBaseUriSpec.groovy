package server

import spock.lang.Shared
import spock.lang.Unroll
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Covers {@code hubBaseUri()}, the base URI of every hubInternal* call. The hub does not charge
 * HTTP wait to the calling app when the host is not loopback, so the server routes through the
 * hub's own LAN address once a probe of that address has answered with data; loopback stays the
 * route whenever the login is on without credentials, an input is unreadable, or the probe fails.
 * The credential path itself (sign-in, cookie, expired session) is in HubSecurityCredentialsSpec.
 */
class HubBaseUriSpec extends ToolSpecBase {

    static final String PROBE_PATH = '/hub/advanced/freeOSMemory'
    static final String LOOP = 'http://127.0.0.1:8080'
    static final String LAN = 'http://192.168.1.13:8080'

    @Shared private TestLocation sharedLocation = new TestLocation()
    @Shared Closure httpGetHandler = null
    @Shared Closure httpPostHandler = null
    // Answers the LAN probe GET; the default proves the LAN route.
    @Shared Closure probeHandler = null

    /** A redirect the way HTTPBuilder throws it under followRedirects:false (duck-typed .response). */
    private static class RedirectException extends RuntimeException {
        final def response
        RedirectException(String location) {
            super('HTTP 302')
            this.response = [status: 302, headers: ['Location': location]]
        }
    }

    /** An HTTP error the way HTTPBuilder throws it: the exception carries the response. */
    private static class StatusException extends RuntimeException {
        final def response
        StatusException(int status) {
            super("HTTP ${status}")
            this.response = [status: status]
        }
    }

    /** location.hub whose localIP getter throws. */
    static class ThrowingIpHub extends TestHub {
        @Override String getLocalIP() { throw new IllegalStateException('localIP unreadable') }
    }

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
        appExecutor.httpGet(_, _) >> { args ->
            Map params = args[0]
            if (params.path == PROBE_PATH && probeHandler) return probeHandler.call(params, args[1])
            if (httpGetHandler) httpGetHandler.call(params, args[1])
        }
        appExecutor.httpPost(_, _) >> { args -> if (httpPostHandler) httpPostHandler.call(args[0], args[1]) }
    }

    def cleanup() {
        httpGetHandler = null
        httpPostHandler = null
        probeHandler = null
    }

    def setup() {
        sharedLocation.hub = new TestHub(localIP: '192.168.1.13')
        probeHandler = { Map params, Closure cb -> cb.call([status: 200, data: '123456']) }
    }

    private void loginSecurity(boolean enabled) {
        script.metaClass.isHubitatUISecurityEnabled = { -> enabled }
    }

    private List captureLogs() {
        def entries = []
        script.metaClass.mcpLog = { String level, String component, String message,
                                    String ruleId = null, Map extra = null ->
            entries << [level: level, component: component, message: message]
        }
        return entries
    }

    def 'login security off routes hub calls through the hub LAN address once the probe answers'() {
        given:
        loginSecurity(false)
        def probes = []
        probeHandler = { Map params, Closure cb ->
            probes << [uri: params.uri, followRedirects: params.followRedirects]
            cb.call([status: 200, data: '123456'])
        }

        when:
        def uris = (1..3).collect { script.hubBaseUri() }

        then: 'one probe per code load, sent to the LAN address without following redirects'
        uris == [LAN, LAN, LAN]
        probes == [[uri: LAN, followRedirects: false]]
    }

    def 'login security on without credentials keeps hub calls on loopback, with no probe and no log'() {
        given:
        loginSecurity(true)
        def logs = captureLogs()
        def probes = 0
        probeHandler = { Map params, Closure cb -> probes++; cb.call([status: 200, data: '1']) }

        expect:
        script.hubBaseUri() == LOOP
        probes == 0
        logs.isEmpty()
    }

    def 'an unreadable login security flag keeps hub calls on loopback'() {
        // No override: the harness has no isHubitatUISecurityEnabled, so the call throws
        // MissingMethodException -- the same shape as a firmware without the method.
        expect:
        script.hubBaseUri() == LOOP
    }

    def 'a non-boolean login security flag keeps hub calls on loopback'() {
        given:
        script.metaClass.isHubitatUISecurityEnabled = { -> null }

        expect:
        script.hubBaseUri() == LOOP
    }

    @Unroll
    def 'login security off with localIP #ip keeps hub calls on loopback'() {
        given:
        loginSecurity(false)
        sharedLocation.hub = new TestHub(localIP: ip)

        expect:
        script.hubBaseUri() == LOOP

        where:
        ip << [null, '', 'fe80::1', 'not an ip', '192.168.1', '192.168.1.13:8080', '0.0.0.0', '127.0.0.1', '127.0.1.1']
    }

    def 'login security off with no hub object keeps hub calls on loopback'() {
        given:
        loginSecurity(false)
        sharedLocation.hub = null

        expect:
        script.hubBaseUri() == LOOP
    }

    def 'login security off with a throwing localIP read keeps hub calls on loopback'() {
        given:
        loginSecurity(false)
        sharedLocation.hub = new ThrowingIpHub()

        expect:
        script.hubBaseUri() == LOOP
    }

    @Unroll
    def 'the base URI is a plain http String on port 8080 when login security is #state'() {
        given:
        loginSecurity(enabled)

        when:
        def uri = script.hubBaseUri()

        then: 'a String (not a GString) of scheme, host and port, with no path, as every caller expects'
        uri instanceof String
        uri ==~ /http:\/\/\d{1,3}(\.\d{1,3}){3}:8080/

        where:
        state | enabled
        'off' | false
        'on'  | true
    }

    @Unroll
    def 'a LAN probe answered by #answer keeps hub calls on loopback, says why, and logs once'() {
        given:
        loginSecurity(false)
        def logs = captureLogs()
        probeHandler = handler

        when:
        def uris = (1..3).collect { script.hubBaseUri() }
        def info = script.toolGetHubInfo()

        then:
        uris == [LOOP, LOOP, LOOP]
        info.hubRequestRoute == 'loopback'
        info.hubRequestRouteNote.contains(note)
        logs.count { it.level == 'warn' && it.message.contains(note) } == 1

        where:
        answer                 | note                          | handler
        'a 302'                | 'redirects LAN requests'      | { Map p, Closure cb -> throw new RedirectException('https://192.168.1.13/hub/advanced/freeOSMemory') }
        'the login page'       | 'redirects LAN requests'      | { Map p, Closure cb -> cb.call([status: 200, data: '<html><form action="/login" method="post"></form></html>']) }
        'a non-numeric body'   | 'unexpected answer'           | { Map p, Closure cb -> cb.call([status: 200, data: 'oops']) }
        'an HTTP 500'          | 'unexpected answer (HTTP 500)' | { Map p, Closure cb -> throw new StatusException(500) }
    }

    def 'a LAN probe that cannot connect opens the loopback window instead of caching a verdict'() {
        given:
        loginSecurity(false)
        probeHandler = { Map p, Closure cb -> throw new java.net.ConnectException('Connection refused') }

        when:
        def first = script.hubBaseUri()

        then:
        first == LOOP
        script.HUB_LAN_ROUTE_FAILED_AT.get('at') != null
        script.HUB_LAN_PROBE.isEmpty()
        script.toolGetHubInfo().hubRequestRouteNote.contains('did not answer on its LAN address')
    }

    def 'a request that reads a 302 Location stays on loopback while a plain read takes the LAN address'() {
        given:
        loginSecurity(false)
        def sent = []
        httpGetHandler = { Map params, Closure cb ->
            sent << [uri: params.uri, path: params.path, followRedirects: params.followRedirects]
            if (params.followRedirects == false) throw new RedirectException('http://127.0.0.1:8080/installedapp/configure/42')
            cb.call([status: 200, data: 'BODY'])
        }

        when: 'hubInternalGet is shadowed by the harness mock, so the plain read drives its _hubRequest call directly'
        def raw = script.hubInternalGetRaw('/installedapp/createchild/x/y/parent/1')
        def body = script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])

        then:
        raw.status == 302
        raw.location == 'http://127.0.0.1:8080/installedapp/configure/42'
        body == 'BODY'
        sent == [
            [uri: LOOP, path: '/installedapp/createchild/x/y/parent/1', followRedirects: false],
            [uri: LAN, path: '/hub2/hubData', followRedirects: null],
        ]
    }

    def 'a missing LAN address with login security off is logged once, not on every call'() {
        given:
        loginSecurity(false)
        sharedLocation.hub = new TestHub(localIP: null)
        def logs = captureLogs()

        when:
        3.times { script.hubBaseUri() }

        then:
        logs.size() == 1
        logs[0].level == 'warn'
        logs[0].component == 'server'
        logs[0].message.contains('stay on loopback')
        logs[0].message.contains('localIP')
    }

    def 'an unreadable login security flag is logged once, not on every call'() {
        given: 'no override, so the flag read throws'
        def logs = captureLogs()

        when:
        3.times { script.hubBaseUri() }

        then:
        logs.size() == 1
        logs[0].level == 'warn'
        logs[0].message.contains('Hub Login Security switch is unreadable')
    }

    def 'the LAN route and the secured route log nothing'() {
        given:
        loginSecurity(enabled)
        def logs = captureLogs()

        when:
        script.hubBaseUri()

        then:
        logs.isEmpty()

        where:
        enabled << [false, true]
    }

    @Unroll
    def 'hub_get_info reports hubRequestRoute #route when login security is #state and localIP is #ip'() {
        given:
        if (enabled != null) loginSecurity(enabled)
        sharedLocation.hub = new TestHub(localIP: ip)

        when:
        def info = script.toolGetHubInfo()

        then:
        info.hubRequestRoute == route
        note == null ? !info.containsKey('hubRequestRouteNote') : info.hubRequestRouteNote.contains(note)

        where:
        state        | enabled | ip             || route      | note
        'off'        | false   | '192.168.1.13' || 'lan'      | null
        'on'         | true    | '192.168.1.13' || 'loopback' | 'no Hub Login Security credentials'
        'unreadable' | null    | '192.168.1.13' || 'loopback' | 'switch is unreadable'
        'off'        | false   | null           || 'loopback' | 'localIP'
    }

    def 'hub_get_info reports hubRequestRoute with the Read master off'() {
        given: 'the route names no address, so it is not withheld with the PII fields'
        loginSecurity(false)
        settingsMap.enableRead = false

        when:
        def info = script.toolGetHubInfo()

        then:
        !info.containsKey('localIP')
        info.hubRequestRoute == 'lan'
    }

    def 'a request sent in an existing hub session stays on loopback'() {
        given:
        loginSecurity(false)
        def sent = []
        httpGetHandler = { Map params, Closure cb ->
            sent << [uri: params.uri, cookie: params.headers?.Cookie]
            cb.call([status: 200, data: 'BODY'])
        }

        when:
        def body = script.hubInternalGetInSession('/dashboard/all', [:], 'HUBSESSION=abc')

        then:
        body == 'BODY'
        sent == [[uri: LOOP, cookie: 'HUBSESSION=abc']]
    }

    def 'a LAN connection failure retries the request once on loopback and logs the fallback once'() {
        given:
        loginSecurity(false)
        def logs = captureLogs()
        def sent = []
        httpGetHandler = { Map params, Closure cb ->
            sent << params.uri
            if (params.uri != LOOP) throw new java.net.ConnectException('Connection refused')
            cb.call([status: 200, data: 'BODY'])
        }

        when:
        def body = script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])

        then:
        body == 'BODY'
        sent == [LAN, LOOP]
        logs.count { it.level == 'warn' && it.message.contains('did not answer on its LAN address') } == 1
    }

    def 'after a LAN connection failure hub calls stay on loopback for the retry window, then probe the LAN again'() {
        given:
        loginSecurity(false)
        def probes = 0
        probeHandler = { Map p, Closure cb -> probes++; cb.call([status: 200, data: '123456']) }
        httpGetHandler = { Map params, Closure cb ->
            if (params.uri != LOOP) throw new java.net.ConnectException('Connection refused')
            cb.call([status: 200, data: 'BODY'])
        }
        script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])
        long failedAt = script.HUB_LAN_ROUTE_FAILED_AT.get('at') as long

        expect: 'inside the window: loopback'
        script.hubBaseUri() == LOOP
        script.toolGetHubInfo().hubRequestRoute == 'loopback'

        when: 'the window has passed'
        script.HUB_LAN_ROUTE_FAILED_AT.put('at', failedAt - script.HUB_LAN_ROUTE_RETRY_MS)

        then: 'the failure cleared the probe verdict, so the LAN route is probed again'
        script.hubBaseUri() == LAN
        probes == 2
    }

    def 'an HTTP error status on the LAN route is not retried on loopback and leaves the route unchanged'() {
        given:
        loginSecurity(false)
        def sent = []
        httpGetHandler = { Map params, Closure cb ->
            sent << params.uri
            throw new StatusException(500)
        }

        when:
        script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])

        then:
        thrown(StatusException)
        sent == [LAN]
        script.HUB_LAN_ROUTE_FAILED_AT.isEmpty()
        script.hubBaseUri() == LAN
    }

    def 'a read timeout on the LAN route is not retried, because the request may have reached the hub'() {
        given:
        loginSecurity(false)
        def sent = []
        httpPostHandler = { Map params, Closure cb ->
            sent << params.uri
            throw new java.net.SocketTimeoutException('Read timed out')
        }

        when:
        script._hubRequest('POST', '/installedapp/update/json', [returnShape: 'text', body: 'x=1'])

        then: 'the harness may wrap the checked exception, so look one level down too'
        def err = thrown(Exception)
        err instanceof java.net.SocketTimeoutException || err.cause instanceof java.net.SocketTimeoutException
        sent == [LAN]
        script.HUB_LAN_ROUTE_FAILED_AT.isEmpty()
    }

    @Unroll
    def 'a login refusal on a LAN route probed OK earlier drops the verdict and repeats once on loopback: #shape'() {
        given: 'the LAN route was probed OK, then Hub Login Security was turned on (the flag still reads off here)'
        loginSecurity(false)
        assert script.hubBaseUri() == LAN
        def loginPage = '<html><form action="/login" method="post"></form></html>'
        probeHandler = { Map p, Closure cb -> cb.call([status: 200, data: loginPage]) }
        def logs = captureLogs()
        def sent = []
        Closure answer = { Map params, Closure cb ->
            sent << params.uri
            if (params.uri == LOOP) return cb.call([status: 200, data: 'BODY'])
            if (shape == 'login page') return cb.call([status: 200, data: loginPage])
            throw new RedirectException('http://192.168.1.13:8080/login')
        }
        if (method == 'GET') httpGetHandler = answer else httpPostHandler = answer

        when:
        def body = script._hubRequest(method, '/hub2/hubData', [returnShape: 'text', body: method == 'POST' ? 'x=1' : null])
        def info = script.toolGetHubInfo()

        then:
        body == 'BODY'
        sent == [LAN, LOOP]
        info.hubRequestRoute == 'loopback'
        info.hubRequestRouteNote.contains('redirects LAN requests')
        logs.count { it.level == 'warn' && it.message.contains('redirects LAN requests') } == 1

        where:
        shape                 | method
        'login page'          | 'GET'
        '302 to /login'       | 'POST'
    }

    def 'a login page on the LAN repeat is returned as is, not retried again'() {
        given:
        loginSecurity(false)
        assert script.hubBaseUri() == LAN
        def loginPage = '<html><form action="/login" method="post"></form></html>'
        def sent = []
        httpGetHandler = { Map params, Closure cb -> sent << params.uri; cb.call([status: 200, data: loginPage]) }

        when: 'the re-probe still passes, so the repeat goes to the LAN again and gets the login page again'
        def body = script._hubRequest('GET', '/hub2/hubData', [returnShape: 'text'])

        then:
        body == loginPage
        sent == [LAN, LAN]
    }
}
