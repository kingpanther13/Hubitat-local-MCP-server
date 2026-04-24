package support

import spock.lang.Specification

/**
 * Self-tests for {@link McpRequestDriver}. Keeps harness-level behaviour
 * (pushBody/pushBodyThrowing/captureRender/parseResponseJson lifecycle)
 * pinned independently from the specs that use it — a harness bug that
 * silently ate a render or let throwing state leak across tests would
 * otherwise degrade every dependent spec together and be diagnostically
 * confusing.
 *
 * Does not load the sandbox / production script — this is pure driver
 * logic under test.
 */
class McpRequestDriverSpec extends Specification {

    McpRequestDriver driver

    def setup() {
        driver = new McpRequestDriver()
    }

    def "pushBody stores a map under request.JSON for the proxy to return"() {
        given:
        driver.pushBody([jsonrpc: '2.0', id: 1, method: 'initialize'])

        expect: 'the proxy reads what pushBody staged'
        driver.scriptRequest.getJSON() == [jsonrpc: '2.0', id: 1, method: 'initialize']
    }

    def "pushBody(null) stages null — the requestBody == null branch"() {
        given:
        driver.pushBody(null)

        expect: 'getJSON returns null rather than throwing'
        driver.scriptRequest.getJSON() == null
    }

    def "pushBody clears any prior throwing state"() {
        given: 'first stage a throw'
        driver.pushBodyThrowing(new RuntimeException('earlier test'))

        when: 'then push a normal body in a new test — should reset the throw'
        driver.pushBody([id: 2])

        then:
        driver.scriptRequest.getJSON() == [id: 2]
    }

    def "pushBodyThrowing makes scriptRequest.getJSON throw the given Throwable"() {
        given:
        def oops = new RuntimeException('simulated parse failure')
        driver.pushBodyThrowing(oops)

        when:
        driver.scriptRequest.getJSON()

        then:
        def e = thrown(RuntimeException)
        e.is(oops)
    }

    def "reset clears request, throwing state, and lastRenderArgs"() {
        given:
        driver.pushBody([some: 'body'])
        driver.captureRender([status: 200, data: '{}'])

        when:
        driver.reset()

        then:
        driver.scriptRequest.getJSON() == null
        driver.lastRenderArgs == null
        driver.throwingRequest == null
    }

    def "captureRender stores the Map and returns the same Map for caller assignment"() {
        given:
        def args = [status: 200, contentType: 'application/json', data: '{}']

        when:
        def ret = driver.captureRender(args)

        then:
        ret.is(args)
        driver.lastRenderArgs.is(args)
    }

    def "parseResponseJson throws IllegalStateException when no render was captured"() {
        when:
        driver.parseResponseJson()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('No render() call captured')
    }

    def "parseResponseJson returns null for empty data — the 204 case"() {
        given:
        driver.captureRender([status: 204, data: ''])

        expect:
        driver.parseResponseJson() == null
    }

    def "parseResponseJson returns null when data is null"() {
        given:
        driver.captureRender([status: 204, data: null])

        expect:
        driver.parseResponseJson() == null
    }

    def "parseResponseJson parses valid JSON"() {
        given:
        driver.captureRender([status: 200, data: '{"jsonrpc":"2.0","id":1,"result":{"ok":true}}'])

        expect:
        def r = driver.parseResponseJson()
        r.jsonrpc == '2.0'
        r.result.ok == true
    }

    def "parseResponseJson throws IllegalStateException with render args echoed when body is unparseable"() {
        given:
        driver.captureRender([status: 200, contentType: 'application/json', data: '<<not-json>>'])

        when:
        driver.parseResponseJson()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('did not parse as JSON')
        e.message.contains('<<not-json>>') || e.message.contains('Captured render args')
    }
}
