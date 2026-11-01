package server

import spock.lang.Shared
import spock.lang.Unroll
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Covers {@code hubBaseUri()}, the base URI of every hubInternal* call. The hub does not charge
 * HTTP wait to the calling app when the host is not loopback, so with Hub Login Security off the
 * server routes through the hub's own LAN address; loopback stays the route whenever the login is
 * on or either input is unreadable, because only 127.0.0.1 is exempt from that login.
 */
class HubBaseUriSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()
    @Shared Closure httpGetHandler = null

    /** A redirect the way HTTPBuilder throws it under followRedirects:false (duck-typed .response). */
    private static class RedirectException extends RuntimeException {
        final def response
        RedirectException(String location) {
            super('HTTP 302')
            this.response = [status: 302, headers: ['Location': location]]
        }
    }

    /** location.hub whose localIP getter throws. */
    static class ThrowingIpHub extends TestHub {
        @Override String getLocalIP() { throw new IllegalStateException('localIP unreadable') }
    }

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
        appExecutor.httpGet(_, _) >> { args -> if (httpGetHandler) httpGetHandler.call(args[0], args[1]) }
    }

    def cleanup() {
        httpGetHandler = null
    }

    def setup() {
        sharedLocation.hub = new TestHub(localIP: '192.168.1.13')
        // The once-only fallback latch is class memory, shared by every spec in the JVM.
        script.HUB_LOOPBACK_FALLBACK_NOTED.clear()
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

    def 'login security off routes hub calls through the hub LAN address'() {
        given:
        loginSecurity(false)

        expect:
        script.hubBaseUri() == 'http://192.168.1.13:8080'
    }

    def 'login security on keeps hub calls on loopback'() {
        given:
        loginSecurity(true)

        expect:
        script.hubBaseUri() == 'http://127.0.0.1:8080'
    }

    def 'an unreadable login security flag keeps hub calls on loopback'() {
        // No override: the harness has no isHubitatUISecurityEnabled, so the call throws
        // MissingMethodException -- the same shape as a firmware without the method.
        expect:
        script.hubBaseUri() == 'http://127.0.0.1:8080'
    }

    def 'a non-boolean login security flag keeps hub calls on loopback'() {
        given:
        script.metaClass.isHubitatUISecurityEnabled = { -> null }

        expect:
        script.hubBaseUri() == 'http://127.0.0.1:8080'
    }

    @Unroll
    def 'login security off with localIP #ip keeps hub calls on loopback'() {
        given:
        loginSecurity(false)
        sharedLocation.hub = new TestHub(localIP: ip)

        expect:
        script.hubBaseUri() == 'http://127.0.0.1:8080'

        where:
        ip << [null, '', 'fe80::1', 'not an ip', '192.168.1', '192.168.1.13:8080', '0.0.0.0', '127.0.0.1', '127.0.1.1']
    }

    def 'login security off with no hub object keeps hub calls on loopback'() {
        given:
        loginSecurity(false)
        sharedLocation.hub = null

        expect:
        script.hubBaseUri() == 'http://127.0.0.1:8080'
    }

    def 'login security off with a throwing localIP read keeps hub calls on loopback'() {
        given:
        loginSecurity(false)
        sharedLocation.hub = new ThrowingIpHub()

        expect:
        script.hubBaseUri() == 'http://127.0.0.1:8080'
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
            [uri: 'http://127.0.0.1:8080', path: '/installedapp/createchild/x/y/parent/1', followRedirects: false],
            [uri: 'http://192.168.1.13:8080', path: '/hub2/hubData', followRedirects: null],
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

        where:
        state        | enabled | ip             || route
        'off'        | false   | '192.168.1.13' || 'lan'
        'on'         | true    | '192.168.1.13' || 'loopback'
        'unreadable' | null    | '192.168.1.13' || 'loopback'
        'off'        | false   | null           || 'loopback'
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

    @Unroll
    def 'the Hub Security credential path keeps hub calls on loopback on firmware #fw'() {
        given: 'login security reads off and localIP is usable, but the credential settings are on'
        loginSecurity(false)
        sharedLocation.hub = new TestHub(localIP: '192.168.1.13', firmwareVersionString: fw)
        settingsMap.hubSecurityEnabled = true
        settingsMap.hubSecurityUser = 'admin'
        settingsMap.hubSecurityPassword = 'secret'
        def logs = captureLogs()

        expect: 'below 2.5.0 the credentials are live, so loopback, with no fallback warning; from 2.5.0 they are retired'
        script.hubBaseUri() == route
        !logs.any { it.message.contains('stay on loopback') }

        where:
        fw          || route
        '2.4.3.158' || 'http://127.0.0.1:8080'
        '2.5.0.123' || 'http://192.168.1.13:8080'
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
        sent == [[uri: 'http://127.0.0.1:8080', cookie: 'HUBSESSION=abc']]
    }
}
