package server

import support.TestChildApp
import support.ToolSpecBase

/**
 * Swap counts every app using a device, this server included when the device is in its device
 * selection. When this server is the only one, the snapshot says so, so the note can point it out.
 */
class DeviceSwapSelfReferenceSpec extends ToolSpecBase {

    def setupSpec() {
        appExecutor.getApp() >> new TestChildApp(id: 99999L, label: 'MCP')
    }

    def "the snapshot marks a device only this server uses"() {
        given:
        hubGet.register('/device/fullJson/55') { params -> body }

        when:
        def snap = script._deviceSwapSnapshot('55')

        then:
        snap.dependents == count
        snap.onlySelf == onlySelf

        where:
        body                                                               || count | onlySelf
        '{"appsUsing":[{"id":99999}],"appsUsingCount":1}'                  || 1     | true
        '{"appsUsing":[{"id":1},{"id":99999}],"appsUsingCount":2}'         || 2     | false
        '{"appsUsing":[{"id":1}],"appsUsingCount":1}'                      || 1     | false
    }
}
