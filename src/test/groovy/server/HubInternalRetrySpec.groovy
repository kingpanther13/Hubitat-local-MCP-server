package server

import spock.lang.Shared

import support.ToolSpecBase

/**
 * Spec for the consolidated hub HTTP client core (_hubRequest) that backs the
 * six hubInternal* variants. PR2b folded six near-duplicate request bodies into
 * one shared core; these features pin the behaviours that were either newly
 * added or easy to regress in that consolidation:
 *
 *  - the Hub Security cookie is cached in atomicState (thread-safe) and a live
 *    cache entry skips the /login round-trip while still attaching the cookie;
 *  - an auth failure (HTTP 401/403, read duck-typed off the exception) clears
 *    the cached cookie and retries the request exactly once with a fresh cookie;
 *  - a non-auth failure (e.g. 500) is NOT retried;
 *  - a persistent auth failure retries once and then propagates (no loop);
 *  - a body-read failure mid-stream is re-thrown, never swallowed into a
 *    Reader.toString() junk string that downstream code would treat as a body.
 *
 * Interception point: hubInternalPost is pure dynamic Groovy with no metaClass
 * shadow in the harness (unlike hubInternalGet), so calling it runs the real
 * _hubRequest down to appExecutor.httpPost — intercepted here via the same
 * setupSpec-dispatcher pattern ToolRoomsSpec documents. The dispatcher routes
 * /login (cookie auth) and the app path (the request under test) by params.path.
 */
class HubInternalRetrySpec extends ToolSpecBase {

    @Shared Closure httpPostHandler = null
    @Shared Closure httpGetHandler = null

    def setupSpec() {
        appExecutor.httpPost(_, _) >> { args ->
            if (httpPostHandler) {
                httpPostHandler.call(args[0], args[1])
            }
        }
        appExecutor.httpGet(_, _) >> { args ->
            if (httpGetHandler) {
                httpGetHandler.call(args[0], args[1])
            }
        }
    }

    def cleanup() {
        httpPostHandler = null
        httpGetHandler = null
    }

    /** Enable Hub Security with credentials so the cookie path is live. */
    private void enableHubSecurity() {
        settingsMap.hubSecurityEnabled = true
        settingsMap.hubSecurityUser = 'admin'
        settingsMap.hubSecurityPassword = 'secret'
    }

    /** Seed a live (unexpired) cached cookie so getHubSecurityCookie skips /login. */
    private void seedCachedCookie(String value = 'JSESSIONID=cached') {
        atomicStateMap.hubSecurityCookie = value
        atomicStateMap.hubSecurityCookieExpiry = 1234567890000L + 60_000  // now() is fixed at 1234567890000L
    }

    /** An exception that carries an HTTP status the way HttpResponseException does (duck-typed). */
    private static class FakeHttpException extends RuntimeException {
        final def response
        FakeHttpException(int status) {
            super("HTTP ${status}")
            this.response = [status: status]
        }
        // For the 3xx redirect path: also expose a Location header, which
        // _hubRequest's handle3xx branch reads via resp.headers?."Location".
        FakeHttpException(int status, String location) {
            super("HTTP ${status}")
            this.response = [status: status, headers: ['Location': location]]
        }
    }

    /** An exception with NO parseable .response.status -- only a message carrying the auth signal. */
    private static class FakeBareException extends RuntimeException {
        FakeBareException(String msg) { super(msg) }
    }

    /** A Reader whose read() always fails, emulating a socket reset mid-body. */
    private Reader failingReader(String msg) {
        return new Reader() {
            int read(char[] cbuf, int off, int len) throws IOException { throw new IOException(msg) }
            void close() throws IOException {}
        }
    }

    def "a live cached cookie is reused without a /login round-trip and is attached to the request"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and: 'record every POST by path so we can prove /login was never called'
        def loginCalls = 0
        def appCallHeaders = null
        httpPostHandler = { Map params, Closure cb ->
            if (params.path == '/login') {
                loginCalls++
                cb.call([headers: ['Set-Cookie': 'JSESSIONID=should-not-be-used; Path=/']])
            } else {
                appCallHeaders = params.headers
                cb.call([status: 200, data: 'OK'])
            }
        }

        when:
        def result = script.hubInternalPost('/foo', [a: 1])

        then: 'cached cookie short-circuits auth and is sent on the request'
        result == 'OK'
        loginCalls == 0
        appCallHeaders?.Cookie == 'JSESSIONID=cached'
    }

    def "a 401 clears the cached cookie, re-authenticates, and retries once successfully"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=stale')

        and: 'first app POST 401s; /login mints a fresh cookie; the retry succeeds'
        def appCalls = 0
        def loginCalls = 0
        def retryHeaders = null
        httpPostHandler = { Map params, Closure cb ->
            if (params.path == '/login') {
                loginCalls++
                cb.call([headers: ['Set-Cookie': 'JSESSIONID=fresh; Path=/']])
            } else {
                appCalls++
                if (appCalls == 1) {
                    throw new FakeHttpException(401)
                }
                retryHeaders = params.headers
                cb.call([status: 200, data: 'RETRIED-OK'])
            }
        }

        when:
        def result = script.hubInternalPost('/foo', [a: 1])

        then: 'the request was retried once with a freshly-minted cookie'
        result == 'RETRIED-OK'
        appCalls == 2
        loginCalls == 1
        retryHeaders?.Cookie == 'JSESSIONID=fresh'

        and: 'the refreshed cookie is what is now cached'
        atomicStateMap.hubSecurityCookie == 'JSESSIONID=fresh'
    }

    def "a non-auth failure (500) is not retried"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and:
        def appCalls = 0
        httpPostHandler = { Map params, Closure cb ->
            if (params.path == '/login') {
                cb.call([headers: ['Set-Cookie': 'JSESSIONID=fresh; Path=/']])
            } else {
                appCalls++
                throw new FakeHttpException(500)
            }
        }

        when:
        script.hubInternalPost('/foo', [a: 1])

        then: 'the 500 propagates after a single attempt; the cached cookie is left intact'
        def ex = thrown(Exception)
        ex.message.contains('500')
        appCalls == 1
        atomicStateMap.hubSecurityCookie == 'JSESSIONID=cached'
    }

    def "a persistent 401 retries exactly once and then propagates (no infinite loop)"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=stale')

        and: 'every app POST 401s, even after re-auth'
        def appCalls = 0
        def loginCalls = 0
        httpPostHandler = { Map params, Closure cb ->
            if (params.path == '/login') {
                loginCalls++
                cb.call([headers: ['Set-Cookie': 'JSESSIONID=fresh; Path=/']])
            } else {
                appCalls++
                throw new FakeHttpException(401)
            }
        }

        when:
        script.hubInternalPost('/foo', [a: 1])

        then: 'original attempt + exactly one retry, then the 401 surfaces'
        thrown(Exception)
        appCalls == 2
        loginCalls == 1
    }

    def "a mid-stream body read failure is re-thrown, not swallowed into a junk string"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and: 'the POST itself succeeds but reading the response body fails'
        httpPostHandler = { Map params, Closure cb ->
            cb.call([status: 200, data: failingReader('simulated socket reset')])
        }

        when:
        script.hubInternalPost('/foo', [a: 1])

        then: 'the read error propagates rather than returning a Reader.toString() junk body'
        def ex = thrown(IOException)
        ex.message == 'simulated socket reset'
    }

    // ---- struct returnShape (form/raw/getRaw) + handle3xx + GET path: real _hubRequest ----

    def "hubInternalPostForm returns a {status,location,data} struct on a 2xx success"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and: 'the hub returns a normal 2xx with a body'
        def sentHeaders = null
        httpPostHandler = { Map params, Closure cb ->
            sentHeaders = params.headers
            cb.call([status: 200, headers: ['Location': null], data: 'BODY'])
        }

        when:
        def resp = script.hubInternalPostForm('/foo', [a: 1])

        then: 'the struct shape carries status + body, with the cached cookie attached'
        resp instanceof Map
        resp.status == 200
        resp.data == 'BODY'
        resp.containsKey('location')
        sentHeaders?.Cookie == 'JSESSIONID=cached'
    }

    def "hubInternalGetRaw on a 302 extracts the Location header into the struct (handle3xx)"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and: 'followRedirects:false makes HTTPBuilder throw on the 302; the exception carries the Location'
        def sawFollowRedirects = null
        httpGetHandler = { Map params, Closure cb ->
            sawFollowRedirects = params.followRedirects
            throw new FakeHttpException(302, '/installedapp/configure/9999/mainPage')
        }

        when:
        def resp = script.hubInternalGetRaw('/installedapp/create/310')

        then: 'the 302 is treated as success and the new-child Location is surfaced'
        sawFollowRedirects == false
        resp.status == 302
        resp.location == '/installedapp/configure/9999/mainPage'
    }

    def "hubInternalPostForm on a /save (create) path does NOT follow the 302, surfacing the editor Location (handle3xx)"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and: '/app/save returns a 302 -> /app/editor/<newId>; the id is ONLY in the Location header'
        def sawFollowRedirects = null
        httpPostHandler = { Map params, Closure cb ->
            sawFollowRedirects = params.followRedirects
            throw new FakeHttpException(302, 'http://127.0.0.1:8080/app/editor/777')
        }

        when:
        def resp = script.hubInternalPostForm('/app/save', [id: '', version: '', create: '', source: 'definition(name: "X")'])

        then: 'the create POST does not follow the redirect, so the new-app Location survives (fixes hub_create_app)'
        sawFollowRedirects == false
        resp.status == 302
        resp.location == 'http://127.0.0.1:8080/app/editor/777'
    }

    def "hubInternalPostForm on a non-/save path does not override followRedirects (path-sniff is scoped to create)"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and:
        def sawFollow = 'unset'
        httpPostHandler = { Map params, Closure cb ->
            sawFollow = params.containsKey('followRedirects') ? params.followRedirects : 'unset'
            cb.call([status: 200, headers: ['Location': null], data: 'OK'])
        }

        when:
        def resp = script.hubInternalPostForm('/app/ajax/update', [id: '1', source: 'x'])

        then: 'non-create POSTs keep the default redirect behavior'
        sawFollow == 'unset'
        resp.status == 200
    }

    def "hubInternalGetRaw on a 404 (outside the 3xx window) propagates rather than returning a struct"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and:
        httpGetHandler = { Map params, Closure cb -> throw new FakeHttpException(404) }

        when:
        script.hubInternalGetRaw('/installedapp/create/310')

        then: 'a 404 is not mistaken for a redirect success -- it propagates'
        thrown(Exception)
    }

    def "the GET branch of _hubRequest attaches the cached cookie and returns the body text"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and: 'capture the request headers on a GET (hubInternalGet is metaClass-shadowed by HarnessSpec, so drive _hubRequest directly)'
        def sentHeaders = null
        httpGetHandler = { Map params, Closure cb ->
            sentHeaders = params.headers
            cb.call([status: 200, data: 'GETBODY'])
        }

        when:
        def body = script._hubRequest('GET', '/bar', [returnShape: 'text'])

        then:
        body == 'GETBODY'
        sentHeaders?.Cookie == 'JSESSIONID=cached'
    }

    def "a struct (form) caller treats a committed 2xx with an unreadable body as success (status + null data), not a failure"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and: 'the POST commits (2xx) but the response body cannot be read'
        httpPostHandler = { Map params, Closure cb ->
            cb.call([status: 200, data: failingReader('body unreadable')])
        }

        when:
        def resp = script.hubInternalPostForm('/foo', [a: 1])

        then: 'no throw: status-only callers (e.g. _rmClickAppButton) see the committed 2xx with null data'
        noExceptionThrown()
        resp.status == 200
        resp.data == null
    }

    // ---- status-less message-substring auth detection (shouldRetryWithFreshCookie fallback) ----

    def "a status-less 401 detected only via the exception message clears the cookie and retries once"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=stale')

        and: 'first app POST throws an exception with NO .response.status; message contains "Unauthorized"'
        def appCalls = 0
        def loginCalls = 0
        def retryHeaders = null
        httpPostHandler = { Map params, Closure cb ->
            if (params.path == '/login') {
                loginCalls++
                cb.call([headers: ['Set-Cookie': 'JSESSIONID=fresh; Path=/']])
            } else {
                appCalls++
                if (appCalls == 1) throw new FakeBareException('server returned: Unauthorized')
                retryHeaders = params.headers
                cb.call([status: 200, data: 'RETRIED-OK'])
            }
        }

        when:
        def result = script.hubInternalPost('/foo', [a: 1])

        then: 'the message-substring fallback drove a single cookie-refresh retry'
        result == 'RETRIED-OK'
        appCalls == 2
        loginCalls == 1
        retryHeaders?.Cookie == 'JSESSIONID=fresh'
    }

    def "a status-less non-auth error (message lacks 401/403/Unauthorized) does NOT retry"() {
        given:
        enableHubSecurity()
        seedCachedCookie('JSESSIONID=cached')

        and:
        def appCalls = 0
        def loginCalls = 0
        httpPostHandler = { Map params, Closure cb ->
            if (params.path == '/login') { loginCalls++; cb.call([headers: ['Set-Cookie': 'JSESSIONID=fresh; Path=/']]) }
            else { appCalls++; throw new FakeBareException('Connection reset by peer') }
        }

        when:
        script.hubInternalPost('/foo', [a: 1])

        then: 'a non-auth status-less error propagates after one attempt, cookie untouched'
        thrown(Exception)
        appCalls == 1
        loginCalls == 0
        atomicStateMap.hubSecurityCookie == 'JSESSIONID=cached'
    }
}
