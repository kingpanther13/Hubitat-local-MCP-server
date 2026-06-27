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
