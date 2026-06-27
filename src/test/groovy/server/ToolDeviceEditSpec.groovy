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
 *                              (hubGet.register(path) closures; exact-path match including
 *                              the query string, e.g. '/device/setShowOnHome?deviceId=10&show=true').
 *   - hubInternalPostFormRaw : not on HubitatAppScript, stubbed per-test on script.metaClass
 *                              (captures the wholesale /device/update form the tags path POSTs).
 *
 * The Write master defaults ON; dispatch write-disabled tests seed settingsMap.enableWrite=false
 * and assert the central executeTool gate. hub_create_device gates on confirm==true (not a
 * destructive backup), so no lastBackupTimestamp seeding is needed.
 */
class ToolDeviceEditSpec extends ToolSpecBase {

    // ============================================================
    // hub_update_device : showOnHome
    // ============================================================

    def "toolUpdateDevice showOnHome hits the setShowOnHome endpoint and records the change"() {
        given:
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device
        hubGet.register('/device/setShowOnHome?deviceId=10&show=false') { params -> '' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', showOnHome: false])

        then:
        result.success == true
        result.changes.find { it.property == 'showOnHome' }?.newValue == false
        hubGet.calls.any { it.path == '/device/setShowOnHome?deviceId=10&show=false' }
    }

    @spock.lang.Unroll
    def "via dispatch: hub_update_device showOnHome=true succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device
        hubGet.register('/device/setShowOnHome?deviceId=10&show=true') { params -> '' }

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

    def "toolUpdateDevice showOnHome records a per-property error when the Write master is off"() {
        given: 'Write disabled -- direct call bypasses the central gate, so the per-property guard fires'
        settingsMap.enableWrite = false
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device

        when:
        def result = script.toolUpdateDevice([deviceId: '10', showOnHome: true])

        then: 'no hub call was made; the error names the Write toggle'
        result.success == false
        result.errors.find { it.property == 'showOnHome' }?.error?.contains('Enable Write Tools')
        !hubGet.calls.any { it.path.startsWith('/device/setShowOnHome') }
    }

    def "toolUpdateDevice showOnHome falls back to /device/preference/save when the dedicated GET is absent"() {
        given: 'the dedicated setShowOnHome endpoint 404s (older firmware) -- the GET throws'
        def device = new TestDevice(id: 10, label: 'Porch Light')
        childDevicesList << device
        hubGet.register('/device/setShowOnHome?deviceId=10&show=true') { params -> throw new RuntimeException('Not Found (404)') }
        def posted = null
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            posted = [path: path, body: jsonBody]; return [status: 200]
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

    // ============================================================
    // hub_update_device : defaultCurrentState
    // ============================================================

    def "toolUpdateDevice defaultCurrentState URL-encodes the attribute and records the change"() {
        given:
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=temperature') { params -> 'true' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'temperature'])

        then:
        result.success == true
        result.changes.find { it.property == 'defaultCurrentState' }?.newValue == 'temperature'
        hubGet.calls.any { it.path == '/device/setDefaultCurrentState?id=10&currentState=temperature' }
    }

    def "toolUpdateDevice defaultCurrentState empty string selects None"() {
        given:
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=') { params -> 'true' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: ''])

        then:
        result.success == true
        result.changes.find { it.property == 'defaultCurrentState' }?.newValue == ''
        hubGet.calls.any { it.path == '/device/setDefaultCurrentState?id=10&currentState=' }
    }

    @spock.lang.Unroll
    def "via dispatch: hub_update_device defaultCurrentState succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=switch') { params -> 'true' }

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
        given: 'the endpoint returns a non-true body (e.g. an unknown attribute name)'
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=bogus') { params -> 'false' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'bogus'])

        then: 'no phantom change is recorded; an actionable error is returned instead'
        result.success == false
        result.changes.find { it.property == 'defaultCurrentState' } == null
        result.errors.find { it.property == 'defaultCurrentState' }?.error?.contains('Hub did not accept')
    }

    def "toolUpdateDevice defaultCurrentState records a per-property error when the Write master is off"() {
        given:
        settingsMap.enableWrite = false
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'switch'])

        then: 'no hub call was made; the error names the Write toggle'
        result.success == false
        result.errors.find { it.property == 'defaultCurrentState' }?.error?.contains('Enable Write Tools')
        !hubGet.calls.any { it.path.startsWith('/device/setDefaultCurrentState') }
    }

    def "toolUpdateDevice defaultCurrentState falls back to /device/preference/save when the dedicated GET is absent"() {
        given: 'the dedicated setDefaultCurrentState endpoint 404s (older firmware) -- the GET throws'
        def device = new TestDevice(id: 10, label: 'Thermostat')
        childDevicesList << device
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=temperature') { params -> throw new RuntimeException('Not Found (404)') }
        def posted = null
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            posted = [path: path, body: jsonBody]; return [status: 200]
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
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=bogus') { params -> 'false' }
        def postCalled = false
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            postCalled = true; return [status: 200]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', defaultCurrentState: 'bogus'])

        then: 'an error is recorded for defaultCurrentState and the Preferences-pane fallback was NOT attempted'
        result.success == false
        result.changes.find { it.property == 'defaultCurrentState' } == null
        result.errors.find { it.property == 'defaultCurrentState' }?.error?.contains('Hub did not accept')
        postCalled == false
    }

    // ============================================================
    // hub_update_device : tags (verify/restore path)
    // ============================================================

    def "toolUpdateDevice tags reads the full model, POSTs the wholesale form, and verifies tags applied"() {
        given:
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        childDevicesList << device
        // First fullJson read returns the pre-edit model; the verify read returns the post-edit model.
        def reads = 0
        def preModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}'
        def postModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"kitchen,downstairs","version":4,"controllerType":"LAN"}}'
        hubGet.register('/device/fullJson/10') { params -> (++reads == 1) ? preModel : postModel }
        def postedBody = null
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> postedBody = [path: path, body: body]; '' }

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

    def "toolUpdateDevice tags restores label/name/dni via SDK when the wholesale form blanked them"() {
        given:
        def restored = [:]
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        device.metaClass.setLabel = { String v -> restored.label = v }
        device.metaClass.setName = { String v -> restored.name = v }
        device.metaClass.setDeviceNetworkId = { String v -> restored.dni = v }
        childDevicesList << device
        def reads = 0
        def preModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}'
        // Verify read: tags applied but identity fields BLANKED by the wholesale form.
        def postModel = '{"device":{"id":10,"name":"","label":"","deviceNetworkId":"","tags":"kitchen","version":4,"controllerType":"LAN"}}'
        hubGet.register('/device/fullJson/10') { params -> (++reads == 1) ? preModel : postModel }
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> '' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: ['kitchen']])

        then: 'tags applied AND the blanked identity fields were restored via the SDK setters'
        result.success == true
        restored.label == 'Office Lamp'
        restored.name == 'Generic Switch'
        restored.dni == 'AB'
    }

    def "toolUpdateDevice tags reports an error when the read-back tags do not match"() {
        given:
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        childDevicesList << device
        def reads = 0
        def preModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}'
        // Verify read: tags did NOT take.
        def postModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":4,"controllerType":"LAN"}}'
        hubGet.register('/device/fullJson/10') { params -> (++reads == 1) ? preModel : postModel }
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> '' }

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
        def reads = 0
        def preModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"kitchen","version":3,"controllerType":"LAN"}}'
        def postModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":4,"controllerType":"LAN"}}'
        hubGet.register('/device/fullJson/10') { params -> (++reads == 1) ? preModel : postModel }
        def postedBody = null
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> postedBody = body; '' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: []])

        then: 'the form carried an empty tags value and the change reports newValue == ""'
        result.success == true
        result.changes.find { it.property == 'tags' }?.newValue == ''
        postedBody.contains('tags=&') || postedBody.endsWith('tags=')
    }

    def "toolUpdateDevice tags restores blanked label even when the tags read-back MISMATCHES"() {
        given: 'the wholesale form blanked the label AND tags did not take'
        def restored = [:]
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        device.metaClass.setLabel = { String v -> restored.label = v }
        childDevicesList << device
        def reads = 0
        def preModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}'
        // Verify read: label blanked AND tags wrong (still empty, expected "kitchen").
        def postModel = '{"device":{"id":10,"name":"Generic Switch","label":"","deviceNetworkId":"AB","tags":"","version":4,"controllerType":"LAN"}}'
        hubGet.register('/device/fullJson/10') { params -> (++reads == 1) ? preModel : postModel }
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> '' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: ['kitchen']])

        then: 'identity restore fired on the mismatch path AND the tags error is recorded'
        result.success == false
        restored.label == 'Office Lamp'
        result.errors.find { it.property == 'tags' }?.error?.contains('read back as')
    }

    def "toolUpdateDevice tags records a per-property error when the Write master is off"() {
        given:
        settingsMap.enableWrite = false
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        childDevicesList << device

        when:
        def result = script.toolUpdateDevice([deviceId: '10', tags: ['kitchen']])

        then: 'no hub call was made; the error names the Write toggle'
        result.success == false
        result.errors.find { it.property == 'tags' }?.error?.contains('Enable Write Tools')
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }
    }

    @spock.lang.Unroll
    def "via dispatch: hub_update_device tags succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def device = new TestDevice(id: 10, label: 'Office Lamp', name: 'Generic Switch')
        childDevicesList << device
        def reads = 0
        def preModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"","version":3,"controllerType":"LAN"}}'
        def postModel = '{"device":{"id":10,"name":"Generic Switch","label":"Office Lamp","deviceNetworkId":"AB","tags":"patio","version":4,"controllerType":"LAN"}}'
        hubGet.register('/device/fullJson/10') { params -> (++reads == 1) ? preModel : postModel }
        script.metaClass.hubInternalPostFormRaw = { String path, String body -> '' }

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
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params ->
            '{"device":{"id":777,"label":"My LAN Device","name":"Generic LAN Driver","deviceTypeName":"Generic LAN Driver","virtual":false,"capabilities":["Switch"]}}'
        }
        hubGet.register('/device/updateLabel?deviceId=777&label=Garage+Bridge') { params -> 'true' }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', label: 'Garage Bridge', confirm: true])

        then:
        result.success == true
        result.deviceId == '777'
        result.label == 'Garage Bridge'
        result.deviceTypeId == '500'
        result.warnings == null
        hubGet.calls.any { it.path == '/device/updateLabel?deviceId=777&label=Garage+Bridge' }
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
    }

    def "toolCreateDevice surfaces a non-fatal warning when the label apply fails"() {
        given: 'create + read-back succeed, but updateLabel returns a non-true body'
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params ->
            '{"device":{"id":777,"label":"My LAN Device","name":"Generic LAN Driver","deviceTypeName":"Generic LAN Driver","virtual":false,"capabilities":["Switch"]}}'
        }
        hubGet.register('/device/updateLabel?deviceId=777&label=Garage+Bridge') { params -> 'false' }

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
