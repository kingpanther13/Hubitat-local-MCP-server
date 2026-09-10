package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolCommandParameterTypesSpec extends ToolSpecBase {
    @Unroll
    def 'declared #type preserves argument #input on #path dispatch'() {
        given:
        def received = []
        if (path == 'bypass') {
            settingsMap.bypassDeviceAllowlist = true
            hubGet.register('/device/fullJson/10') { JsonOutput.toJson([
                device: [id: 10, name: 'Fixture'],
                commands: [[name: 'probe', parameters: [[name: 'value', type: type]]]]
            ]) }
            script.metaClass.hubInternalPostJson = { String url, String body, int timeout = 420, boolean retry = false ->
                assert url == '/device/runmethod'
                def payload = new JsonSlurper().parseText(body)
                assert payload.args[0].type == type
                received << payload.args[0].value
                [success: true]
            }
        } else {
            def device = new TestDevice(id: 10, name: 'Fixture', supportedCommands: [[name: 'probe', arguments: [type]]])
            device.metaClass.probe = { value -> received << value }
            if (path == 'selected') settingsMap.selectedDevices = [device]
            else childDevicesList << device
        }

        when:
        def result = script.toolSendCommand('10', 'probe', [input], null, null, null, false)

        then:
        result.success == true
        result.parameters == [expected]
        received == [expected]
        received[0].getClass() == expected.getClass() || (expected instanceof Map && received[0] instanceof Map)

        where:
        [path, type, input, expected] << ['selected', 'child', 'bypass'].collectMany { path ->
            [
                ['STRING', '6858', '6858'],
                ['STRING', '0007', '0007'],
                ['STRING', '3.14', '3.14'],
                ['STRING', '{"a":1}', '{"a":1}'],
                ['STRING', '[1,2]', '[1,2]'],
                ['ENUM', '01', '01'],
                ['NUMBER', '75', 75],
                ['JSON_OBJECT', '{"a":1}', [a: 1]],
            ].collect { [path] + it }
        }
    }

    def 'serialized argument array respects declared text before legacy embedded JSON repair'() {
        expect:
        script.normalizeCommandParams('["0007","{\\"a\\":1}"]', ['STRING', 'STRING']) == ['0007', '{"a":1}']
    }
}
