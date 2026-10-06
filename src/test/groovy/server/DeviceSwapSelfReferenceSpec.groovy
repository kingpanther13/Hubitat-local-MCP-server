package server

import support.TestChildApp
import support.ToolSpecBase

/**
 * Swap's dependent counts leave out this server's own device allowlist entry: the MCP app lists a
 * selected device in appsUsing, but it is not an automation that uses the device.
 */
class DeviceSwapSelfReferenceSpec extends ToolSpecBase {

    def setupSpec() {
        appExecutor.getApp() >> new TestChildApp(id: 99999L, label: 'MCP')
    }

    def "the server's own allowlist entry is not counted as a dependent"() {
        given:
        hubGet.register('/device/fullJson/55') { params -> body }

        expect:
        script._deviceSwapSnapshot('55').dependents == expected

        where:
        body                                                               || expected
        '{"appsUsing":[{"id":1},{"id":99999}],"appsUsingCount":2}'         || 1
        '{"appsUsing":[{"id":99999}],"appsUsingCount":1}'                  || 0
        '{"appsUsing":[{"id":1},{"id":2}],"appsUsingCount":2}'             || 2
    }
}
