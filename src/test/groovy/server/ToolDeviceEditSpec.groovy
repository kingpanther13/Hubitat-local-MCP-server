package server

import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for the device edit/create surface added in issue #259:
 *
 *   - hub_update_device  -> toolUpdateDevice  : new showOnHome / defaultCurrentState / tags params
 *   - hub_create_device  -> toolCreateDevice  : instantiate a device from a driver-type id
 *   - hub_get_compatible_devices -> toolGetCompatibleDevices : Hubitat's static compat catalog
 *
 * Every feature has a direct-call (unit) variant AND a dispatch-envelope variant
 * (mcpDriver.callTool, @Unroll'd over useGateways) per the CONTRIBUTING rule.
 *
 * Mocking strategy (see docs/testing.md + ToolAppsDriversSpec):
 *   - hubInternalGet         : routed by HarnessSpec to the @Shared HubInternalGetMock
 *                              (hubGet.register(key) closures). Production passes query
 *                              parameters as a MAP (an embedded '?' in the path 404s exact hub
 *                              routes -- see the _hubRequest guard), and the mock recomposes
 *                              'path?k=v&k=v' from that map, so a registration key still reads
 *                              like '/device/setShowOnHome?deviceId=10&show=true'. Values in the
 *                              key are RAW -- never pre-encoded, since the query map encodes.
 *   - hubInternalPostFormRaw : not on HubitatAppScript, stubbed per-test on script.metaClass
 *                              (captures the wholesale /device/update form the tags path POSTs).
 *
 * The Write master defaults ON; dispatch write-disabled tests seed settingsMap.enableWrite=false
 * and assert the central executeTool gate. hub_create_device gates on confirm==true (not a
 * destructive backup), so no lastBackupTimestamp seeding is needed.
 */
class ToolDeviceEditSpec extends ToolSpecBase {

    private static Map decodeForm(String body) {
        body.split('&').collectEntries { pair ->
            def parts = pair.split('=', 2)
            [(URLDecoder.decode(parts[0], 'UTF-8')): URLDecoder.decode(parts.length > 1 ? parts[1] : '', 'UTF-8')]
        }
    }

    def setup() {
        hubGet.register('/device/drivers') {
            '{"drivers":[{"id":500,"type":"sys"},{"id":12,"type":"sys"},{"id":999,"type":"sys"}]}'
        }
    }

    private static String completeDeviceFormJson(String text) {
        def full = new groovy.json.JsonSlurper().parseText(text)
        def defaults = [id: 10, version: 0, controllerType: 'LAN', name: 'Fixture', label: '',
            zigbeeId: null, maxEvents: 10, maxStates: 10, spammyThreshold: 100,
            deviceNetworkId: 'fixture-10', deviceTypeId: 100, deviceTypeReadableType: 'User',
            roomId: null, meshEnabled: false, retryEnabled: false, meshFullSync: false,
            showOnHome: false, defaultCurrentState: '',
            locationId: 1, hubId: 1, groupId: null, tags: '', defaultIcon: null, notes: null]
        full.device = defaults + full.device
        full.homeKitEnabled = false
        full.dashboards = []
        groovy.json.JsonOutput.toJson(full)
    }


    // ============================================================
    // hub_update_device : showOnHome
    // ============================================================

    def "toolUpdateDevice showOnHome hits the setShowOnHome endpoint and records the change"() {
        given:
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device
        hubGet.register('/device/setShowOnHome?deviceId=10&show=false') { params -> '' }
        // Read-back confirms the flag landed (showOnHome:false as requested).
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"label":"Porch Light","showOnHome":false}}' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', showOnHome: false])

        then:
        result.success == true
        result.changes.find { it.property == 'showOnHome' }?.newValue == false
        hubGet.calls.any { it.key == '/device/setShowOnHome?deviceId=10&show=false' }
    }

    // ============================================================
    // hub_update_device : enabled read-back (listed path)
    // ============================================================

    def "toolUpdateDevice enabled read-back MISMATCH records a per-property error (not a change)"() {
        given: 'the /device/disable POST is a no-op; the FRESH fullJson re-read still shows the device enabled (disabled:false) so the requested disable did not land'
        def device = new TestDevice(id: 10, name: 'Sw', label: 'Switch')
        childDevicesList << device
        script.metaClass.hubInternalPostJson = { String path, String json, int t = 30, boolean r = false ->
            def body = new groovy.json.JsonSlurper().parseText(json); '' }
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"label":"Switch","disabled":false}}' }

        when: 'request disable; the re-fetch reports enabled -> mismatch'
        def result = script.toolUpdateDevice([deviceId: '10', enabled: false])

        then: 'a per-property read-back error, NOT a recorded change'
        result.success == false
        result.errors.find { it.property == 'enabled' }?.error?.contains('read back as')
        !(result.changes.find { it.property == 'enabled' })
    }

    def "toolUpdateDevice enabled read-back HAPPY PATH records the change when the re-fetch shows the flip"() {
        given: 'the FRESH fullJson re-read shows the device now disabled -- the flip landed'
        def device = new TestDevice(id: 10, name: 'Sw', label: 'Switch')
        childDevicesList << device
        script.metaClass.hubInternalPostJson = { String path, String json, int t = 30, boolean r = false ->
            def body = new groovy.json.JsonSlurper().parseText(json); '' }
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"label":"Switch","disabled":true}}' }

        when: 'request disable; the re-fetch confirms disabled'
        def result = script.toolUpdateDevice([deviceId: '10', enabled: false])

        then: 'the change is recorded with no error'
        result.success == true
        result.changes.find { it.property == 'enabled' }?.newValue == false
        !(result.errors?.find { it.property == 'enabled' })
    }

    def "toolUpdateDevice enabled read-back records a could-not-confirm error when the re-fetch fails"() {
        given: 'the /device/disable POST is accepted but the read-back fullJson fetch yields nothing'
        def device = new TestDevice(id: 10, name: 'Sw', label: 'Switch')
        childDevicesList << device
        def accepted = false
        script.metaClass.hubInternalPostJson = { String path, String json, int t = 30, boolean r = false ->
            accepted = true
            def body = new groovy.json.JsonSlurper().parseText(json); '' }
        hubGet.register('/device/fullJson/10') { params -> accepted ? '' : '{"device":{"id":10,"label":"Switch","disabled":false}}' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', enabled: false])

        then: 'a distinct could-not-confirm error, NOT a recorded change'
        result.success == false
        result.errors.find { it.property == 'enabled' }?.error?.contains('could not confirm the change')
        !(result.changes.find { it.property == 'enabled' })
    }

    @spock.lang.Unroll
    def "via dispatch: hub_update_device showOnHome=true succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device
        hubGet.register('/device/setShowOnHome?deviceId=10&show=true') { params -> '' }
        hubGet.register('/device/fullJson/10') { params ->
            '{"device":{"id":10,"label":"Porch Light","showOnHome":true,"retryEnabled":false,"defaultCurrentState":""}}'
        }

        when:
        def response = mcpDriver.callTool('hub_update_device', [deviceId: '10', showOnHome: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.changes.find { it.property == 'showOnHome' }?.newValue == true

        where:
        useGateways << [true, false]
    }

    def "toolUpdateDevice showOnHome rejects the call when the Write master is off"() {
        given: 'the direct entrypoint enforces the Write master before native reads or writes'
        settingsMap.enableWrite = false
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device

        when:
        script.toolUpdateDevice([deviceId: '10', showOnHome: true])

        then: 'no hub call was made; the error names the Write toggle'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Enable Write Tools')
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
        !hubGet.calls.any { it.path.startsWith('/device/setShowOnHome') }
    }

    def "toolUpdateDevice showOnHome does NOT fall back when the ?-in-path guard fires"() {
        // A real hub 404 SHOULD reach the preference/save fallback (the feature below). The guard
        // must NOT: it means a caller regressed to an embedded querystring, and laundering it into
        // the fallback would let the broken dedicated endpoint report success forever.
        given:
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device
        hubGet.register('/device/setShowOnHome') { params ->
            throw new IllegalStateException(
                "hubInternal* path must not contain a querystring: '/device/setShowOnHome?deviceId=10' -- pass the query map instead")
        }
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"label":"Porch Light","showOnHome":true}}' }
        boolean fellBack = false
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            fellBack = true; return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', showOnHome: true])

        then: 'the guard surfaces as a per-property error and the fallback was never attempted'
        !fellBack
        result.changes.find { it.property == 'showOnHome' } == null
        result.errors.find { it.property == 'showOnHome' }?.error?.contains('querystring')
    }

    def "toolUpdateDevice showOnHome falls back to /device/preference/save when the dedicated GET is absent"() {
        given: 'the dedicated setShowOnHome endpoint 404s (older firmware) -- the GET throws'
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device
        def showOnHome = false
        hubGet.register('/device/setShowOnHome?deviceId=10&show=true') { params -> throw new RuntimeException('Not Found (404)') }
        hubGet.register('/device/fullJson/10') { params ->
            groovy.json.JsonOutput.toJson([device: [id: 10, label: 'Porch Light', showOnHome: showOnHome,
                retryEnabled: false, defaultCurrentState: '']])
        }
        def posted = null
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            posted = [path: path, body: jsonBody]
            showOnHome = new groovy.json.JsonSlurper().parseText(jsonBody).showOnHome
            return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', showOnHome: true])

        then: 'the fallback POST carried the deviceId + showOnHome to /device/preference/save, and the change is recorded'
        result.success == true
        result.changes.find { it.property == 'showOnHome' }?.newValue == true
        posted.path == '/device/preference/save'
        def body = new groovy.json.JsonSlurper().parseText(posted.body)
        body.deviceId == 10
        body.showOnHome == true
    }

    def "toolUpdateDevice showOnHome read-back MISMATCH records a per-property error (not a change)"() {
        given: 'the POST is a no-op; the FRESH fullJson re-read still shows showOnHome:true so the requested false did not land'
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device
        hubGet.register('/device/setShowOnHome?deviceId=10&show=false') { params -> '' }
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"label":"Porch Light","showOnHome":true}}' }

        when: 'request showOnHome=false; the re-fetch reports true -> mismatch'
        def result = script.toolUpdateDevice([deviceId: '10', showOnHome: false])

        then: 'a per-property read-back error, NOT a recorded change'
        result.success == false
        result.errors.find { it.property == 'showOnHome' }?.error?.contains('read back as')
        !(result.changes.find { it.property == 'showOnHome' })
    }

    def "toolUpdateDevice showOnHome read-back records a could-not-confirm error when the re-fetch fails"() {
        given: 'the POST is accepted but the read-back fullJson fetch yields nothing'
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device
        def accepted = false
        hubGet.register('/device/setShowOnHome?deviceId=10&show=true') { params -> accepted = true; '' }
        hubGet.register('/device/fullJson/10') { params -> accepted ? '' : '{"device":{"id":10,"label":"Porch Light","showOnHome":false}}' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', showOnHome: true])

        then: 'a distinct could-not-confirm error, NOT a recorded change'
        result.success == false
        result.errors.find { it.property == 'showOnHome' }?.error?.contains('could not confirm the change')
        !(result.changes.find { it.property == 'showOnHome' })
    }

    // ============================================================
    // hub_update_device : defaultCurrentState
    // ============================================================

    def "toolUpdateDevice defaultCurrentState URL-encodes the attribute and records the change"() {
        given:
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        def currentState = 'humidity'
        hubGet.register('/device/fullJson/10') { params ->
            groovy.json.JsonOutput.toJson([device: [id: 10, label: 'Thermostat',
                currentStates: [temperature: [:], humidity: [:], switch: [:]], defaultCurrentState: currentState,
                showOnHome: false, retryEnabled: false]])
        }
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=temperature') { params -> currentState = 'temperature'; 'true' }
        // Read-back confirms the attribute landed.

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'temperature'])

        then:
        result.success == true
        result.changes.find { it.property == 'defaultCurrentState' }?.newValue == 'temperature'
        hubGet.calls.any { it.key == '/device/setDefaultCurrentState?id=10&currentState=temperature' }
    }

    def "toolUpdateDevice defaultCurrentState empty string selects None"() {
        given:
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        def currentState = 'temperature'
        hubGet.register('/device/fullJson/10') { params ->
            groovy.json.JsonOutput.toJson([device: [id: 10, label: 'Thermostat',
                currentStates: [temperature: [:], humidity: [:], switch: [:]], defaultCurrentState: currentState,
                showOnHome: false, retryEnabled: false]])
        }
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=') { params -> currentState = null; 'true' }
        // Read-back: None reads back as null (the empty-string request is a clear).

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: ''])

        then:
        result.success == true
        result.changes.find { it.property == 'defaultCurrentState' }?.newValue == ''
        hubGet.calls.any { it.key == '/device/setDefaultCurrentState?id=10&currentState=' }
    }

    @spock.lang.Unroll
    def "via dispatch: hub_update_device defaultCurrentState succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        def currentState = 'temperature'
        hubGet.register('/device/fullJson/10') { params ->
            groovy.json.JsonOutput.toJson([device: [id: 10, label: 'Thermostat',
                currentStates: [temperature: [:], humidity: [:], switch: [:]], defaultCurrentState: currentState]])
        }
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=switch') { params -> currentState = 'switch'; 'true' }

        when:
        def response = mcpDriver.callTool('hub_update_device', [deviceId: '10', defaultCurrentState: 'switch'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.changes.find { it.property == 'defaultCurrentState' }?.newValue == 'switch'

        where:
        useGateways << [true, false]
    }

    def "toolUpdateDevice defaultCurrentState records an error when the hub body is not true"() {
        given: 'the native setter rejects an attribute that was present in discovery'
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"currentStates":{"temperature":{},"humidity":{},"switch":{}}}}' }
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=temperature') { params -> 'false' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'temperature'])

        then: 'no phantom change is recorded; an actionable error is returned instead'
        result.success == false
        result.changes.find { it.property == 'defaultCurrentState' } == null
        result.errors.find { it.property == 'defaultCurrentState' }?.error?.contains('Hub did not accept')
    }

    def "toolUpdateDevice defaultCurrentState rejects the call when the Write master is off"() {
        given:
        settingsMap.enableWrite = false
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"currentStates":{"temperature":{},"humidity":{},"switch":{}}}}' }

        when:
        script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'switch'])

        then: 'no hub call was made; the error names the Write toggle'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Enable Write Tools')
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
        !hubGet.calls.any { it.path.startsWith('/device/setDefaultCurrentState') }
    }

    def "toolUpdateDevice defaultCurrentState falls back to /device/preference/save when the dedicated GET is absent"() {
        given: 'the dedicated setDefaultCurrentState endpoint 404s (older firmware) -- the GET throws'
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        def currentState = 'switch'
        hubGet.register('/device/fullJson/10') { params ->
            groovy.json.JsonOutput.toJson([device: [id: 10, label: 'Thermostat',
                currentStates: [temperature: [:], humidity: [:], switch: [:]],
                showOnHome: false, retryEnabled: false, defaultCurrentState: currentState]])
        }
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=temperature') { params -> throw new RuntimeException('Not Found (404)') }
        def posted = null
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            posted = [path: path, body: jsonBody]
            currentState = new groovy.json.JsonSlurper().parseText(jsonBody).defaultCurrentState
            return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'temperature'])

        then: 'the fallback POST carried the deviceId + defaultCurrentState to /device/preference/save, and the change is recorded'
        result.success == true
        result.changes.find { it.property == 'defaultCurrentState' }?.newValue == 'temperature'
        posted.path == '/device/preference/save'
        def body = new groovy.json.JsonSlurper().parseText(posted.body)
        body.deviceId == 10
        body.defaultCurrentState == 'temperature'
    }

    def "toolUpdateDevice defaultCurrentState does NOT fall back when the GET returns a 200 that is not true"() {
        given: 'the dedicated endpoint EXISTS (200) but rejects the value (body != "true") -- no fallback'
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        hubGet.register('/device/fullJson/10') { params -> '{"device":{"id":10,"currentStates":{"temperature":{},"humidity":{},"switch":{}}}}' }
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=temperature') { params -> 'false' }
        def postCalled = false
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            postCalled = true; return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'temperature'])

        then: 'an error is recorded for defaultCurrentState and the Preferences-pane fallback was NOT attempted'
        result.success == false
        result.changes.find { it.property == 'defaultCurrentState' } == null
        result.errors.find { it.property == 'defaultCurrentState' }?.error?.contains('Hub did not accept')
        postCalled == false
    }

    def "toolUpdateDevice defaultCurrentState read-back MISMATCH records a per-property error (not a change)"() {
        given: 'the dedicated GET returns true, but the FRESH fullJson re-read shows a DIFFERENT attribute -- the value did not actually land'
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        def currentState = 'switch'
        hubGet.register('/device/fullJson/10') { params ->
            groovy.json.JsonOutput.toJson([device: [id: 10, label: 'Thermostat',
                currentStates: [temperature: [:], humidity: [:], switch: [:]], defaultCurrentState: currentState]])
        }
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=temperature') { params -> currentState = 'humidity'; 'true' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'temperature'])

        then: 'a per-property read-back error naming the mismatch, NOT a recorded change'
        result.success == false
        result.errors.find { it.property == 'defaultCurrentState' }?.error?.contains('read back as')
        !(result.changes.find { it.property == 'defaultCurrentState' })
    }

    def "toolUpdateDevice defaultCurrentState read-back records a could-not-confirm error when the re-fetch fails"() {
        given: 'the dedicated GET returns true but the confirming fullJson fetch yields nothing'
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        def accepted = false
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=switch') { params -> accepted = true; 'true' }
        hubGet.register('/device/fullJson/10') { params -> accepted ? '' : '{"device":{"id":10,"currentStates":{"switch":{}}}}' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'switch'])

        then: 'a distinct could-not-confirm error, NOT a recorded change'
        result.success == false
        result.errors.find { it.property == 'defaultCurrentState' }?.error?.contains('could not confirm the change')
        !(result.changes.find { it.property == 'defaultCurrentState' })
    }

    // ============================================================
    // hub_update_device : preferences (read-back, listed path)
    // ============================================================

    def "toolUpdateDevice preferences read-back HAPPY PATH confirms via fullJson settings and records the change"() {
        given: 'native preference/save applies the pref; the fresh fullJson read shows it in the settings array'
        def device = new TestDevice(id: 10, label: 'Sensor')
        device.metaClass.updateSetting = { String k, v -> throw new AssertionError('SDK preference setter must not execute') }
        childDevicesList << device
        def model = new groovy.json.JsonSlurper().parseText(completeDeviceFormJson('{"device":{"id":10,"label":"Sensor"},"settings":[{"name":"tempOffset","type":"number","value":"0"}],"inputValues":[]}'))
        hubGet.register('/device/fullJson/10') { groovy.json.JsonOutput.toJson(model) }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            def payload = new groovy.json.JsonSlurper().parseText(body)
            posts << [path: path, body: payload]
            payload.preferences.each { row -> model.settings.find { it.name == row.name }.value = row.value.toString() }
            [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [tempOffset: [type: 'number', value: 3]]])

        then: 'the confirmed value is recorded as a change, no error'
        result.success == true
        posts == [[path: '/device/preference/save', body: [deviceId: 10, showOnHome: false,
            commandRetry: false, defaultCurrentState: '', preferences: [[name: 'tempOffset', type: 'number', value: 3]]]]]
        result.changes.find { it.property == 'preference.tempOffset' } != null
        !(result.errors?.find { it.property == 'preference.tempOffset' })
    }

    def "toolUpdateDevice preferences read-back MISMATCH records a 'read back as' error (no false success on a silent no-op)"() {
        given: 'native preference/save is a silent no-op; fullJson retains the previous saved value'
        def device = new TestDevice(id: 10, label: 'Sensor')
        device.metaClass.updateSetting = { String k, v -> throw new AssertionError('SDK preference setter must not execute') }
        childDevicesList << device
        hubGet.register('/device/fullJson/10') { completeDeviceFormJson('{"device":{"id":10,"label":"Sensor"},"settings":[{"name":"tempOffset","type":"number","value":"0"}],"inputValues":[]}') }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> posts << path; [success: true] }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [tempOffset: [type: 'number', value: 3]]])

        then: 'the unconfirmed write is a structured error, NOT a false change'
        result.success == false
        posts == ['/device/preference/save']
        result.errors.find { it.property == 'preference.tempOffset' }?.error?.contains('read back as')
        !(result.changes.find { it.property == 'preference.tempOffset' })
    }

    def "toolUpdateDevice preferences read-back FETCH-NULL records the distinct could-not-confirm error"() {
        given: 'the confirming fullJson re-fetch returns no device'
        def device = new TestDevice(id: 10, label: 'Sensor')
        device.metaClass.updateSetting = { String k, v -> throw new AssertionError('SDK preference setter must not execute') }
        childDevicesList << device
        def accepted = false
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            assert path == '/device/preference/save'
            accepted = true
            [success: true]
        }
        hubGet.register('/device/fullJson/10') { params ->
            accepted ? '{"device":null}' : completeDeviceFormJson('{"device":{"id":10,"label":"Sensor"},"settings":[{"name":"tempOffset","type":"number","value":"0"}],"inputValues":[]}')
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [tempOffset: [type: 'number', value: 3]]])

        then: 'a failed read-back fetch is a DISTINCT error, never a recorded change'
        result.success == false
        accepted
        result.errors.find { it.property == 'preference.tempOffset' }?.error?.contains('could not confirm the preference -- the read-back fetch failed')
        !(result.changes.find { it.property == 'preference.tempOffset' })
    }

    // ============================================================
    // hub_update_device : room newValue canonical name (listed path)
    // ============================================================

    def "toolUpdateDevice room records the CANONICAL room name (hub casing) as newValue, not the caller's raw casing"() {
        given: 'the device is already in room "Foyer"; the caller passes the lowercase "foyer"'
        def device = new TestDevice(id: 10, label: 'Lamp', roomName: 'Foyer')
        childDevicesList << device
        script.metaClass.getRooms = { -> [[id: 7, name: 'Foyer', deviceIds: [10]]] }
        hubGet.register('/device/fullJson/10') { '{"device":{"id":10,"label":"Lamp","roomName":"Foyer"}}' }
        hubGet.register('/device/updateRoom?deviceId=10&room=Foyer') { 'true' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', room: 'foyer'])

        then: 'newValue is the canonical "Foyer" (matched-room casing), not the raw "foyer"'
        result.success == true
        result.changes.find { it.property == 'room' }?.newValue == 'Foyer'
        hubGet.calls.findAll { it.path == '/device/updateRoom' }*.key == ['/device/updateRoom?deviceId=10&room=Foyer']
    }

    // ============================================================
    // hub_update_device : tags (verify/restore path)
    // ============================================================

    def "toolUpdateDevice tags reads the full model, POSTs the wholesale form, and verifies tags applied"() {
        given:
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        childDevicesList << device
        // Only an actual form POST changes the fixture; preflight reads preserve its original state.
        def formApplied = false
        def preModel = completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}')
        def postModel = completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"kitchen,downstairs","version":4,"controllerType":"LAN"}}')
        hubGet.register('/device/fullJson/10') { params -> formApplied ? postModel : preModel }
        def postedBody = null
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> formApplied = true; postedBody = [path: path, body: body]; '' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: ['kitchen', 'downstairs']])

        then: 'the wholesale form went to /device/update and carried the joined tags'
        result.success == true
        result.changes.find { it.property == 'tags' }?.newValue == 'kitchen,downstairs'
        postedBody.path == '/device/update'
        postedBody.body.contains('tags=kitchen%2Cdownstairs')

        and: 'the model preserved identity fields (label/name present in the form)'
        postedBody.body.contains('label=Office+Lamp')
        postedBody.body.contains('name=Generic+Switch')
    }

    @spock.lang.Unroll
    def "tags verification accepts native #nativeTags on the #route path"() {
        given:
        settingsMap.bypassDeviceAllowlist = route == 'bypass'
        if (route != 'bypass') childDevicesList << new TestDevice(id: 10, label: 'Office Lamp')
        def model = new groovy.json.JsonSlurper().parseText(completeDeviceFormJson('{"device":{"id":10,"label":"Office Lamp","tags":"original"}}'))
        hubGet.register('/device/fullJson/10') { groovy.json.JsonOutput.toJson(model) }
        def forms = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body ->
            forms << body
            model.device.tags = nativeTags
            if (route == 'extended') model.device.notes = 'Changed notes'
            ''
        }
        def patch = [deviceId: '10', tags: wanted]
        if (route == 'extended') patch.notes = 'Changed notes'

        when:
        def result = script.toolUpdateDevice(patch)

        then:
        forms.size() == 1
        result.success == true
        result.changes.find { it.property == 'tags' } != null
        !result.errors

        where:
        [route, nativeTags, wanted] << ['listed', 'extended', 'bypass'].collectMany { path ->
            [['kitchen,downstairs', ['kitchen', 'downstairs']],
             [['kitchen', 'downstairs'], ['kitchen', 'downstairs']],
             [[' kitchen ', 'downstairs', ''], ['kitchen', 'downstairs']],
             ['', []], [[], []], [null, []]].collect { row -> [path, row[0], row[1]] }
        }
    }

    @spock.lang.Unroll
    def "a missing tags readback is not a confirmed empty tag set on the #route path"() {
        given:
        settingsMap.bypassDeviceAllowlist = route == 'bypass'
        if (route != 'bypass') childDevicesList << new TestDevice(id: 10, label: 'Office Lamp')
        def model = new groovy.json.JsonSlurper().parseText(completeDeviceFormJson('{"device":{"id":10,"tags":"original"}}'))
        hubGet.register('/device/fullJson/10') { groovy.json.JsonOutput.toJson(model) }
        script.metaClass.hubInternalPostFormRaw = { String path, String body ->
            model.device.remove('tags')
            ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: []])

        then:
        result.success == false
        !result.changes.find { it.property == 'tags' }
        result.errors.find { it.property == 'tags' }

        where:
        route << ['listed', 'bypass']
    }

    def "a listed tags form applies the requested native label and preserves explicit null icon and notes"() {
        given:
        def device = new TestDevice(id: 10, label: 'Office Lamp')
        childDevicesList << device
        def model = new groovy.json.JsonSlurper().parseText(completeDeviceFormJson('{"device":{"id":10,"label":"Office Lamp","tags":"","defaultIcon":null,"icon":"fallback-must-not-be-used","notes":null}}'))
        device.metaClass.setLabel = { String value -> throw new AssertionError('SDK identity setter must not execute') }
        hubGet.register('/device/fullJson/10') { groovy.json.JsonOutput.toJson(model) }
        def posted
        script.metaClass.hubInternalPostFormRaw = { String path, String body ->
            posted = body
            def form = decodeForm(body)
            model.device.tags = form.tags
            model.device.label = form.label
            ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', label: 'New label', tags: ['kitchen']])

        then:
        result.success == true
        posted.contains('label=New+label')
        posted.contains('defaultIcon=&')
        posted.contains('notes=&') || posted.endsWith('notes=')
        !posted.contains('fallback-must-not-be-used')
    }

    def "toolUpdateDevice tags restores label/name/dni via a fresh native form when the wholesale form blanked them"() {
        given:
        def forms = []
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        device.metaClass.setLabel = { String v -> throw new AssertionError('SDK identity setter must not execute') }
        device.metaClass.setName = { String v -> throw new AssertionError('SDK identity setter must not execute') }
        device.metaClass.setDeviceNetworkId = { String v -> throw new AssertionError('SDK identity setter must not execute') }
        childDevicesList << device
        def model = new groovy.json.JsonSlurper().parseText(completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}'))
        hubGet.register('/device/fullJson/10') { groovy.json.JsonOutput.toJson(model) }
        script.metaClass.hubInternalPostFormRaw = { String path, String body ->
            assert path == '/device/update'
            def form = decodeForm(body)
            forms << form
            model.device.tags = form.tags
            model.device.version++
            ['label', 'name', 'deviceNetworkId'].each { property -> model.device.put(property, forms.size() == 1 ? '' : form.get(property)) }
            ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: ['kitchen']])

        then: 'tags applied and a second form restores identity using the fresh version and applied tags'
        result.success == true
        forms.size() == 2
        forms[1].version == '4'
        forms[1].tags == 'kitchen'
        model.device.label == 'Office Lamp'
        model.device.name == 'Generic Switch'
        model.device.deviceNetworkId == 'AB'
    }

    def "toolUpdateDevice tags reports an error when the read-back tags do not match"() {
        given:
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        childDevicesList << device
        def formApplied = false
        def preModel = completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}')
        // Verify read: tags did NOT take.
        def postModel = completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":4,"controllerType":"LAN"}}')
        hubGet.register('/device/fullJson/10') { params -> formApplied ? postModel : preModel }
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> formApplied = true; '' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: ['kitchen']])

        then:
        result.success == false
        result.errors.find { it.property == 'tags' }?.error?.contains('read back as')
    }

    def "toolUpdateDevice tags empty list clears the tags and records an empty newValue"() {
        given: 'the verify read shows tags cleared to ""'
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        childDevicesList << device
        def formApplied = false
        def preModel = completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"kitchen","version":3,"controllerType":"LAN"}}')
        def postModel = completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":4,"controllerType":"LAN"}}')
        hubGet.register('/device/fullJson/10') { params -> formApplied ? postModel : preModel }
        def postedBody = null
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> formApplied = true; postedBody = body; '' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: []])

        then: 'the form carried an empty tags value and the change reports newValue == ""'
        result.success == true
        result.changes.find { it.property == 'tags' }?.newValue == ''
        postedBody.contains('tags=&') || postedBody.endsWith('tags=')
    }

    def "toolUpdateDevice tags restores blanked label even when the tags read-back MISMATCHES"() {
        given: 'the wholesale form blanked the label AND tags did not take'
        def forms = []
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        device.metaClass.setLabel = { String v -> throw new AssertionError('SDK identity setter must not execute') }
        childDevicesList << device
        def model = new groovy.json.JsonSlurper().parseText(completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}'))
        hubGet.register('/device/fullJson/10') { groovy.json.JsonOutput.toJson(model) }
        script.metaClass.hubInternalPostFormRaw = { String path, String body ->
            assert path == '/device/update'
            def form = decodeForm(body)
            forms << form
            model.device.version++
            model.device.label = forms.size() == 1 ? '' : form.label
            ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: ['kitchen']])

        then: 'identity restore fired on the mismatch path AND the tags error is recorded'
        result.success == false
        forms.size() == 2
        forms[1].version == '4'
        forms[1].tags == ''
        model.device.label == 'Office Lamp'
        result.errors.find { it.property == 'tags' }?.error?.contains('read back as')
    }

    def "toolUpdateDevice tags rejects the call when the Write master is off"() {
        given:
        settingsMap.enableWrite = false
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        childDevicesList << device

        when:
        script.toolUpdateDevice([deviceId: '10', tags: ['kitchen']])

        then: 'no hub call was made; the error names the Write toggle'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Enable Write Tools')
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
    }

    @spock.lang.Unroll
    def "via dispatch: hub_update_device tags succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        childDevicesList << device
        def formApplied = false
        def preModel = completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}')
        def postModel = completeDeviceFormJson('{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"patio","version":4,"controllerType":"LAN"}}')
        hubGet.register('/device/fullJson/10') { params -> formApplied ? postModel : preModel }
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> formApplied = true; '' }

        when:
        def response = mcpDriver.callTool('hub_update_device', [deviceId: '10', tags: ['patio']])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.changes.find { it.property == 'tags' }?.newValue == 'patio'

        where:
        useGateways << [true, false]
    }

    // ============================================================
    // hub_create_device
    // ============================================================

    def "toolCreateDevice creates a device from a driver-type id and applies the optional label"() {
        given:
        def label = 'My LAN Device'
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params ->
            groovy.json.JsonOutput.toJson([device: [id: 777, label: label, name: 'Generic LAN Driver',
                deviceTypeName: 'Generic LAN Driver', virtual: false, capabilities: ['Switch']]])
        }
        hubGet.register('/device/updateLabel?deviceId=777&label=Garage Bridge') { params -> label = 'Garage Bridge'; 'true' }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', label: 'Garage Bridge', confirm: true])

        then:
        result.success == true
        result.deviceId == '777'
        result.label == 'Garage Bridge'
        result.deviceTypeId == '500'
        result.warnings == null
        hubGet.calls.any { it.key == '/device/updateLabel?deviceId=777&label=Garage Bridge' }
        hubGet.calls.last().path == '/device/fullJson/777'
    }

    @spock.lang.Unroll
    def "toolCreateDevice verifies a true-but-no-op label setter and reports #fallback native fallback accurately"() {
        given:
        def model = new groovy.json.JsonSlurper().parseText(completeDeviceFormJson('{"device":{"id":777,"label":"My LAN Device","name":"Generic LAN Driver"}}'))
        def events = []
        hubGet.register('/device/sysDriverByIdJson/500') { '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { events << 'read'; groovy.json.JsonOutput.toJson(model) }
        hubGet.register('/device/updateLabel?deviceId=777&label=Garage Bridge') { events << 'label'; 'true' }
        def forms = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body ->
            assert path == '/device/update'
            def form = decodeForm(body)
            forms << form
            events << 'form'
            if (fallback == 'applied') model.device.label = form.label
            ''
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', label: 'Garage Bridge', confirm: true])

        then:
        result.success == true
        result.deviceId == '777'
        forms.size() == 1
        forms[0].label == 'Garage Bridge'
        events.take(3) == ['read', 'label', 'read']
        events.last() == 'read'
        result.label == model.device.label
        if (fallback == 'applied') {
            assert result.label == 'Garage Bridge'
            assert !result.warnings
        } else {
            assert result.label == 'My LAN Device'
            assert result.warnings.any { it.contains('label') }
            assert result.message.contains('WARNING')
            assert !result.message.contains("labeled 'Garage Bridge'")
        }

        where:
        fallback << ['applied', 'no-op']
    }

    @spock.lang.Unroll
    def "toolCreateDevice surfaces #failure identity recovery as warnings while retaining the created ID"() {
        given:
        def model = new groovy.json.JsonSlurper().parseText(completeDeviceFormJson('{"device":{"id":777,"label":"My LAN Device","name":"Generic LAN Driver","version":3}}'))
        hubGet.register('/device/sysDriverByIdJson/500') { '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { groovy.json.JsonOutput.toJson(model) }
        hubGet.register('/device/updateLabel?deviceId=777&label=Garage Bridge') { throw new RuntimeException('Not Found (404)') }
        def forms = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body ->
            assert path == '/device/update'
            def form = decodeForm(body)
            forms << form
            if (forms.size() == 1) {
                model.device.label = form.label
                model.device.name = ''
                model.device.version = 4
            } else if (failure == 'throws') {
                throw new RuntimeException('Native identity restoration failed')
            }
            ''
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', label: 'Garage Bridge', confirm: true])

        then:
        result.success == true
        result.deviceId == '777'
        forms.size() == 2
        forms[1].version == '4'
        forms[1].label == 'Garage Bridge'
        forms[1].name == 'Generic LAN Driver'
        model.device.name == ''
        result.warnings.any { it.contains('name') && it.toLowerCase().contains('restor') }
        result.message.contains('WARNING')

        where:
        failure << ['throws', 'no-op']
    }

    @spock.lang.Unroll
    def "toolCreateDevice label apply: a failed updateLabel (#scenario) falls back to the wholesale /device/update form and applies the label"() {
        given: 'the dedicated updateLabel setter fails (non-true body or a 404 throw, as on fw 2.5.0.157), but the /device/update fallback applies the label'
        def fjLabel = 'My LAN Device'
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params ->
            completeDeviceFormJson(groovy.json.JsonOutput.toJson([device: [id: 777, label: fjLabel, name: 'Generic LAN Driver', deviceTypeName: 'Generic LAN Driver', virtual: false, capabilities: ['Switch']]]))
        }
        hubGet.register('/device/updateLabel?deviceId=777&label=Garage Bridge') { params ->
            if (scenario == 'throws') throw new RuntimeException('status code: 404, reason phrase: Not Found')
            return 'false'
        }
        // The wholesale form applies the label; the read-back then reflects it.
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            if (body.contains('label=Garage+Bridge')) fjLabel = 'Garage Bridge'
            return [status: 200]
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', label: 'Garage Bridge', confirm: true])

        then: 'the fallback applied the label -- no warning, and the response label reflects the applied value'
        result.success == true
        result.deviceId == '777'
        result.label == 'Garage Bridge'
        result.warnings == null

        where:
        scenario << ['non-true', 'throws']
    }

    def "toolCreateDevice warns when the driver looks radio-type"() {
        given:
        hubGet.register('/device/sysDriverByIdJson/12') { params -> '{"success":true,"deviceId":88}' }
        hubGet.register('/device/fullJson/88') { params ->
            '{"device":{"id":88,"label":"Z-Wave Shell","name":"Generic Z-Wave Switch","deviceTypeReadableType":"Z-Wave Plus Switch","controllerType":"ZWV"}}'
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '12', confirm: true])

        then:
        result.success == true
        result.deviceId == '88'
        result.warnings != null
        result.warnings.any { it.contains('radio-type') }
        result.message.contains('WARNING')
    }

    def "toolCreateDevice rejects a missing confirm"() {
        when:
        script.toolCreateDevice([deviceTypeId: '500'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('confirm=true is required')
    }

    def "toolCreateDevice rejects a missing deviceTypeId"() {
        when:
        script.toolCreateDevice([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceTypeId is required')
    }

    def "toolCreateDevice returns a structured error when the hub fails to create"() {
        given:
        hubGet.register('/device/sysDriverByIdJson/999') { params -> '{"success":false,"errorMessage":"No such driver type"}' }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '999', confirm: true])

        then:
        result.success == false
        result.error.contains('No such driver type')
        result.note.contains('hub_list_drivers')
        !hubGet.calls.any { it.path == '/device/createVirtual' }
    }

    def "toolCreateDevice selects the user-driver create route before attempting any creation"() {
        given:
        hubGet.register('/device/drivers') { '{"drivers":[{"id":500,"type":"usr"}]}' }
        hubGet.register('/device/sysDriverByIdJson/500') { params ->
            '{"success":false,"errorMessage":"Driver 500 not found."}'
        }
        hubGet.register('/device/createVirtual?deviceTypeId=500') { params ->
            // Current Vue treats a returned deviceId as success; this response has no success flag.
            '{"deviceId":777}'
        }
        hubGet.register('/device/fullJson/777') { params ->
            '{"device":{"id":777,"label":"Custom Software Device","name":"Custom Software Driver","deviceTypeName":"Custom Software Driver","virtual":true,"capabilities":["Switch"]}}'
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', confirm: true])

        then:
        result.success == true
        result.deviceId == '777'
        result.deviceTypeId == '500'
        hubGet.calls*.key == [
            '/device/drivers',
            '/device/createVirtual?deviceTypeId=500',
            '/device/fullJson/777'
        ]
    }

    @spock.lang.Unroll
    def "an unusable driver catalog #catalog refuses creation before either mutating endpoint"() {
        given:
        hubGet.register('/device/drivers') { catalog }
        hubGet.register('/device/sysDriverByIdJson/500') { '{"success":true,"deviceId":777}' }
        hubGet.register('/device/createVirtual?deviceTypeId=500') { '{"deviceId":778}' }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', confirm: true])

        then:
        result.success == false
        result.error
        hubGet.calls*.key == ['/device/drivers']

        where:
        catalog << ['<html>Login</html>', '{}', '{"drivers":[]}',
            '{"drivers":[{"id":500}]}', '{"drivers":[{"id":500,"type":"dep"}]}',
            '{"drivers":[{"id":500,"type":"unknown"}]}',
            '{"drivers":[{"id":500,"type":"usr"},{"id":500,"type":"sys"}]}']
    }

    @spock.lang.Unroll
    def "a #driverType creation with #failure never tries a second create route"() {
        given:
        hubGet.register('/device/drivers') { groovy.json.JsonOutput.toJson([drivers: [[id: 500, type: driverType]]]) }
        def path = driverType == 'usr' ? '/device/createVirtual?deviceTypeId=500' : '/device/sysDriverByIdJson/500'
        hubGet.register(path) {
            if (failure == 'transport loss') throw new RuntimeException('connection dropped after request')
            if (failure == 'non-JSON response') return '<html>Proxy error</html>'
            if (failure == 'possible created ID') return '{"success":false,"deviceId":777,"errorMessage":"Driver not found"}'
            return '{"success":false,"errorMessage":"Driver not found"}'
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', confirm: true])

        then:
        result.success == false
        hubGet.calls*.key == ['/device/drivers', path]

        where:
        [driverType, failure] << [['sys', 'usr'], ['transport loss', 'non-JSON response', 'possible created ID', 'explicit refusal']].combinations()
    }

    def "toolCreateDevice does not retry after an ambiguous primary create exception"() {
        given:
        hubGet.register('/device/sysDriverByIdJson/500') { params ->
            throw new RuntimeException('connection dropped after request')
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', confirm: true])

        then:
        result.success == false
        result.error.contains('Hub call failed')
        !hubGet.calls.any { it.path == '/device/createVirtual' }
    }

    def "toolCreateDevice does not retry an explicit refusal that carries a possible created device id"() {
        given:
        hubGet.register('/device/sysDriverByIdJson/500') { params ->
            '{"success":false,"errorMessage":"Driver not found","deviceId":777}'
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', confirm: true])

        then:
        result.success == false
        result.error == 'Driver not found'
        !hubGet.calls.any { it.path == '/device/createVirtual' }
    }

    def "toolCreateDevice returns a structured error (not a thrown error) when the create body is not JSON"() {
        given: 'a 200 carrying an HTML/login body instead of JSON'
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '<html><body>Please log in</body></html>' }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', confirm: true])

        then: 'the non-JSON body is caught and returned as the structured runtime-error shape'
        result.success == false
        result.error.contains('Hub call failed')
        result.note.contains('hub_list_drivers')
        !hubGet.calls.any { it.path == '/device/createVirtual' }
    }

    def "toolCreateDevice surfaces a non-fatal warning when BOTH updateLabel AND the wholesale fallback fail"() {
        given: 'updateLabel returns a non-true body AND the /device/update fallback read-back never reflects the label'
        def fjLabel = 'My LAN Device'   // the hub applies the label on neither path
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params ->
            completeDeviceFormJson(groovy.json.JsonOutput.toJson([device: [id: 777, label: fjLabel, name: 'Generic LAN Driver', deviceTypeName: 'Generic LAN Driver', virtual: false, capabilities: ['Switch']]]))
        }
        hubGet.register('/device/updateLabel?deviceId=777&label=Garage Bridge') { params -> 'false' }
        // The form POST is 'accepted' but a no-op, so the read-back still shows the original label.
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false -> [status: 200] }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', label: 'Garage Bridge', confirm: true])

        then: 'success, but a warning fires and the response label reflects reality (not the requested label)'
        result.success == true
        result.deviceId == '777'
        result.warnings != null
        result.warnings.any { it.contains("label 'Garage Bridge' could not be applied") }
        result.label == 'My LAN Device'
    }

    def "toolCreateDevice surfaces a warning when the post-create read-back returns no device"() {
        given: 'create succeeds but the fullJson inspect returns nothing (info == null)'
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params -> '' }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', confirm: true])

        then: 'the radio-orphan-shell warning is not lost'
        result.success == true
        result.deviceId == '777'
        result.warnings != null
        result.warnings.any { it.contains('could not read it back to confirm type') }
    }

    @spock.lang.Unroll
    def "via dispatch: hub_create_device succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params ->
            '{"device":{"id":777,"label":"My LAN Device","name":"Generic LAN Driver","deviceTypeName":"Generic LAN Driver","virtual":false,"capabilities":["Switch"]}}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_device', [deviceTypeId: '500', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.deviceId == '777'
        inner.deviceTypeId == '500'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "via dispatch: hub_create_device rejects missing confirm with -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_create_device', [deviceTypeId: '500'])

        then:
        response.error.code == -32602
        response.error.message.contains('confirm=true is required') || response.error.message.contains('confirm')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "via dispatch: hub_create_device is blocked when the Write master is off (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_create_device', [deviceTypeId: '500', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    // ============================================================
    // hub_get_compatible_devices
    // ============================================================

    private static final String COMPAT_JSON = '''[
        {"brand":"Aeotec","name":"Smart Switch 7","deviceType":"Outlet","productNumber":"ZWA025","protocol":"Z-Wave","driverName":"Generic Z-Wave Outlet","deviceTypeId":"101","id":"1","joinInstructions":"<p>Press the button <b>twice</b></p>","excludeInstructions":"<p>Hold 20s</p>","factoryResetInstructions":"<p>Hold 30s</p>","notes":"none"},
        {"brand":"Sonoff","name":"Zigbee Plug","deviceType":"Outlet","productNumber":"S31","protocol":"Zigbee","driverName":"Generic Zigbee Outlet","deviceTypeId":"102","id":"2","joinInstructions":"<p>Hold 5s</p>"},
        {"brand":"Aeotec","name":"Multisensor 6","deviceType":"Motion Sensor","productNumber":"ZW100","protocol":"Z-Wave","driverName":"Aeotec Multisensor","deviceTypeId":"103","id":"3"}
    ]'''

    def "toolGetCompatibleDevices filters by brand and projects a summary"() {
        given:
        hubGet.register('/hub/compatibleDevices') { params -> COMPAT_JSON }

        when:
        def result = script.toolGetCompatibleDevices([brand: 'aeotec'])

        then:
        result.success == true
        result.total == 2
        result.devices.every { it.brand == 'Aeotec' }
        and: 'summary mode flags hasInstructions but omits the instruction text'
        result.devices[0].containsKey('hasInstructions')
        !result.devices[0].containsKey('joinInstructions')
    }

    def "toolGetCompatibleDevices includeInstructions strips HTML from the instruction fields"() {
        given:
        hubGet.register('/hub/compatibleDevices') { params -> COMPAT_JSON }

        when:
        def result = script.toolGetCompatibleDevices([query: 'smart switch 7', includeInstructions: true])

        then:
        result.success == true
        result.total == 1
        def d = result.devices[0]
        d.joinInstructions == 'Press the button twice'
        d.excludeInstructions == 'Hold 20s'
        d.factoryResetInstructions == 'Hold 30s'
        !d.containsKey('hasInstructions')
    }

    def "toolGetCompatibleDevices paginates summary mode at page size 40"() {
        given: 'a catalog larger than one summary page'
        def big = (1..45).collect { i ->
            "{\"brand\":\"Acme\",\"name\":\"Model ${i}\",\"deviceType\":\"Switch\",\"protocol\":\"LAN\",\"id\":\"${i}\"}"
        }.join(',')
        hubGet.register('/hub/compatibleDevices') { params -> "[${big}]" }

        when: 'first page'
        def page1 = script.toolGetCompatibleDevices([brand: 'acme'])

        then:
        page1.total == 45
        page1.count == 40
        page1.nextCursor != null

        when: 'second page via nextCursor'
        def page2 = script.toolGetCompatibleDevices([brand: 'acme', cursor: page1.nextCursor])

        then:
        page2.count == 5
        page2.nextCursor == null
    }

    def "toolGetCompatibleDevices returns an empty result with guidance when nothing matches"() {
        given:
        hubGet.register('/hub/compatibleDevices') { params -> COMPAT_JSON }

        when:
        def result = script.toolGetCompatibleDevices([brand: 'nonexistent'])

        then:
        result.success == true
        result.total == 0
        result.devices == []
        result.note.contains('No compatible-device records matched')
    }

    def "toolGetCompatibleDevices filters by deviceType"() {
        given:
        hubGet.register('/hub/compatibleDevices') { params -> COMPAT_JSON }

        when:
        def result = script.toolGetCompatibleDevices([deviceType: 'motion'])

        then:
        result.success == true
        result.total == 1
        result.devices[0].name == 'Multisensor 6'
    }

    def "toolGetCompatibleDevices query matches a non-name field (driverName)"() {
        given:
        hubGet.register('/hub/compatibleDevices') { params -> COMPAT_JSON }

        when: 'query matches driverName, not the device name'
        def result = script.toolGetCompatibleDevices([query: 'aeotec multisensor'])

        then:
        result.success == true
        result.total == 1
        result.devices[0].name == 'Multisensor 6'
    }

    def "toolGetCompatibleDevices query does not match the literal null when fields are absent"() {
        given: 'a record with several null fields -- null must not interpolate as the string "null"'
        hubGet.register('/hub/compatibleDevices') { params -> '[{"brand":"Acme","name":"Widget"}]' }

        when:
        def result = script.toolGetCompatibleDevices([query: 'null'])

        then: 'no false match from a stringified null'
        result.success == true
        result.total == 0
    }

    def "toolGetCompatibleDevices returns success:false when the hub is unavailable"() {
        given: 'the catalog fetch throws'
        hubGet.register('/hub/compatibleDevices') { params -> throw new RuntimeException('connection refused') }

        when:
        def result = script.toolGetCompatibleDevices([brand: 'aeotec'])

        then:
        result.success == false
        result.error.contains('Could not read /hub/compatibleDevices')
    }

    def "toolGetCompatibleDevices returns success:false and devices:[] on a non-List shape"() {
        given: 'the endpoint returns an object, not the expected array'
        hubGet.register('/hub/compatibleDevices') { params -> '{"unexpected":"shape"}' }

        when:
        def result = script.toolGetCompatibleDevices([brand: 'aeotec'])

        then:
        result.success == false
        result.devices == []
    }

    @spock.lang.Unroll
    def "via dispatch: hub_get_compatible_devices filters by protocol (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/hub/compatibleDevices') { params -> COMPAT_JSON }

        when:
        def response = mcpDriver.callTool('hub_get_compatible_devices', [protocol: 'zigbee'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.total == 1
        inner.devices[0].brand == 'Sonoff'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "via dispatch: hub_get_compatible_devices is blocked when the Read master is off (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = false

        when:
        def response = mcpDriver.callTool('hub_get_compatible_devices', [brand: 'aeotec'])

        then:
        response.error.code == -32602
        response.error.message.contains('Read tools are disabled')

        where:
        useGateways << [true, false]
    }
}
