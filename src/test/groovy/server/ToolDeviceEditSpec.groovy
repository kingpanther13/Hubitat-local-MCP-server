package server

import spock.lang.Shared
import support.TestChildApp
import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for the device edit/create surface added in issue #259:
 *
 *   - hub_update_device  -> toolUpdateDevice  : enabled, tags and dispatch coverage (the full write
 *                                               contract lives in ToolDeviceConfigurationWriteSpec)
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
 *                              like '/device/createVirtual?deviceTypeId=500'. Values in the
 *                              key are RAW -- never pre-encoded, since the query map encodes.
 *   - hubInternalPostFormRaw : not on HubitatAppScript, stubbed per-test on script.metaClass
 *                              (captures the Device Info /device/update form).
 *
 * The Write master defaults ON; dispatch write-disabled tests seed settingsMap.enableWrite=false
 * and assert the central executeTool gate. hub_create_device gates on confirm==true (not a
 * destructive backup), so no lastBackupTimestamp seeding is needed.
 */
class ToolDeviceEditSpec extends ToolSpecBase {

    @Shared private TestChildApp appStub = new TestChildApp(id: 1L, label: 'MCP')

    def setupSpec() {
        appExecutor.getApp() >> appStub
    }

    def cleanup() {
        appStub.settingsStore.clear()
        appStub.updateSettingFailure = null
    }

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
    // hub_update_device
    // ============================================================

    private Map deviceModel(Map device = [:]) {
        new groovy.json.JsonSlurper().parseText(completeDeviceFormJson(groovy.json.JsonOutput.toJson(
            [device: [id: 10, name: 'Generic Switch', label: 'Office Lamp', deviceNetworkId: 'AB', version: 3] + device])))
    }

    private void registerModel(Map model) {
        childDevicesList << new TestDevice(id: 10, name: 'Generic Switch', label: 'Office Lamp')
        hubGet.register('/device/fullJson/10') { params -> groovy.json.JsonOutput.toJson(model) }
    }

    private Map callUpdate(boolean useGateways, Map args) {
        useGateways ? mcpDriver.callTool('hub_manage_devices', [tool: 'hub_update_device', args: args]) :
            mcpDriver.callTool('hub_update_device', args)
    }

    @spock.lang.Unroll
    def "enabled=false is #outcome from the single read-back"() {
        given:
        def model = [device: [id: 10, label: 'Switch', disabled: false]]
        childDevicesList << new TestDevice(id: 10, name: 'Sw', label: 'Switch')
        def reads = 0
        hubGet.register('/device/fullJson/10') { params ->
            if (++reads > 1 && after == null) return ''
            if (reads > 1) model.device.disabled = after
            groovy.json.JsonOutput.toJson(model)
        }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String json, int t = 420, boolean r = false ->
            posts << [path: path, body: new groovy.json.JsonSlurper().parseText(json)]; ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [enabled: false]])

        then:
        posts == [[path: '/device/disable', body: [id: 10, disable: true]]]
        reads == 2
        result.success == (outcome == 'confirmed')
        if (outcome == 'confirmed') {
            assert result.changes == [[property: 'enabled', oldValue: true, newValue: false]]
            assert !result.errors
        } else {
            assert result.changes.empty
            assert result.errors*.property == ['enabled']
            assert result.errors[0].error.contains(message)
        }

        where:
        outcome       | after | message
        'confirmed'   | true  | null
        'a mismatch'  | false | 'enabled reads back as true'
        'unconfirmed' | null  | 'could not be read back'
    }

    def "tags are joined into the Device Info form and confirmed against the normalized read-back"() {
        given:
        def model = deviceModel(tags: 'old')
        registerModel(model)
        def forms = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            forms << [path: path, form: decodeForm(body)]
            model.device.tags = [' kitchen ', 'downstairs', '']
            [status: 200, data: '']
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [tags: ['kitchen', 'downstairs']]])

        then: 'one form, the joined tags, and the identity fields at their current values'
        forms*.path == ['/device/update']
        forms[0].form.tags == 'kitchen,downstairs'
        forms[0].form.label == 'Office Lamp'
        forms[0].form.name == 'Generic Switch'
        forms[0].form.deviceNetworkId == 'AB'
        forms[0].form.version == '3'
        result.success == true
        result.changes == [[property: 'tags', oldValue: ['old'], newValue: ['kitchen', 'downstairs']]]
    }

    def "a field that already had the requested value is saved but reported unchanged"() {
        given:
        def model = deviceModel(tags: 'old')
        registerModel(model)
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            model.device.tags = ['new']
            [status: 200, data: '']
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [label: 'Office Lamp', tags: ['new']]])

        then:
        result.success == true
        result.changes*.property == ['tags']
        result.unchanged == ['label']
    }

    @spock.lang.Unroll
    def "a tags read-back of #nativeTags does not confirm #wanted"() {
        given:
        def model = deviceModel(tags: 'original')
        registerModel(model)
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            model.device.tags = nativeTags
            [status: 200, data: '']
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [tags: wanted]])

        then:
        result.success == false
        result.changes.empty
        result.errors.find { it.property == 'tags' }?.error?.contains(message)

        where:
        nativeTags    | wanted      | message
        'original'    | ['kitchen'] | 'tags reads back as'
        [1, 2]        | []          | 'could not be read back'
        'kitchen,den' | ['kitchen'] | 'tags reads back as'
    }

    @spock.lang.Unroll
    def "via dispatch: hub_update_device saves fields and preferences (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def model = deviceModel()
        model.settings = [[name: 'logEnable', type: 'bool', value: 'true']]
        model.inputValues = []
        registerModel(model)
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String json, int t = 420, boolean r = false ->
            def body = new groovy.json.JsonSlurper().parseText(json)
            posts << path
            model.device.showOnHome = body.showOnHome
            body.preferences.each { pref -> model.settings.find { it.name == pref.name }.value = pref.value.toString() }
            [success: true]
        }

        when:
        def response = callUpdate(useGateways, [deviceId: '10', fields: [showOnHome: true], preferences: [logEnable: false]])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.changes*.property == ['showOnHome', 'preference.logEnable']
        posts == ['/device/preference/save']

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "via dispatch: #fragment is an isError validation result before any read (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        childDevicesList << new TestDevice(id: 10, label: 'Office Lamp')

        when:
        def response = callUpdate(useGateways, [deviceId: '10'] + args)

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains(fragment)
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }

        where:
        [useGateways, row] << [[true, false], [
            [[label: 'Old top-level shape'], 'Unknown argument label'],
            [[:], 'Nothing to update'],
            [[fields: [deviceTypeId: 12]], 'SAFETY CHECK FAILED']
        ]].combinations()
        args = row[0]
        fragment = row[1]
    }

    @spock.lang.Unroll
    def "via dispatch: hub_update_device is blocked when the Write master is off (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false
        childDevicesList << new TestDevice(id: 10, label: 'Office Lamp')

        when:
        def response = callUpdate(useGateways, [deviceId: '10', fields: [tags: ['kitchen']]])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')
        !hubGet.calls.any { it.path.startsWith('/device/fullJson') }

        where:
        useGateways << [true, false]
    }

    // ============================================================
    // hub_create_device
    // ============================================================

    @spock.lang.Unroll
    def "toolCreateDevice labels a built-in-driver device with one Device Info save when the save #outcome"() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 1)]
        def model = new groovy.json.JsonSlurper().parseText(completeDeviceFormJson('{"device":{"id":777,"label":"","name":"Generic LAN Driver","deviceTypeName":"Generic LAN Driver","virtual":false,"capabilities":["Switch"]}}'))
        if (outcome == 'cannot be built') model.device.remove('version')
        def events = []
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params -> events << 'read'; groovy.json.JsonOutput.toJson(model) }
        def forms = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            assert path == '/device/update'
            def form = decodeForm(body)
            forms << form
            events << 'form'
            if (outcome == 'throws') throw new RuntimeException('connection reset')
            if (outcome == 'applies the label') model.device.label = form.label
            [status: 200, data: '']
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', label: 'Garage Bridge', confirm: true])

        then: 'the created device is kept and selected whatever the label save does'
        result.success == true
        result.deviceId == '777'
        appStub.settingsStore.selectedDevices == [type: 'capability.*', value: ['1', '777']]
        events == expectedEvents
        forms.every { it.label == 'Garage Bridge' && it.name == 'Generic LAN Driver' && it.version == '0' }

        and:
        if (outcome == 'applies the label') {
            assert result.label == 'Garage Bridge'
            assert result.warnings == null
            assert result.message.contains("labeled 'Garage Bridge'")
        } else {
            assert result.label == ''
            assert result.warnings.any { it.contains("label 'Garage Bridge' could not be applied") && it.contains(reason) }
            assert result.message.contains('WARNING')
        }

        where:
        outcome             | expectedEvents                   | reason
        'applies the label' | ['read', 'read', 'form', 'read'] | null
        'is a no-op'        | ['read', 'read', 'form', 'read'] | 'the label did not read back'
        'throws'            | ['read', 'read', 'form']         | 'Device Info save failed'
        'cannot be built'   | ['read', 'read']                 | 'missing version'
    }

    def "toolCreateDevice passes the label to a user-driver create and needs no follow-up save"() {
        given:
        hubGet.register('/device/drivers') { '{"drivers":[{"id":500,"type":"usr"}]}' }
        hubGet.register('/device/createVirtual?deviceTypeId=500&deviceLabel=Garage Bridge') { params -> '{"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params ->
            '{"device":{"id":777,"label":"Garage Bridge","name":"Custom Software Driver","virtual":true,"capabilities":["Switch"]}}'
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            throw new AssertionError('a user-driver create is labelled by the create itself')
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', label: 'Garage Bridge', confirm: true])

        then:
        result.success == true
        result.label == 'Garage Bridge'
        result.warnings == null
        hubGet.calls*.key == [
            '/device/drivers',
            '/device/createVirtual?deviceTypeId=500&deviceLabel=Garage Bridge',
            '/device/fullJson/777'
        ]
    }

    def "toolCreateDevice with present-but-null mesh keys still does a normal driver create (not diverted to link)"() {
        given: 'a driver create with mesh_source_* explicitly null -- must NOT be pushed onto the link form'
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params ->
            groovy.json.JsonOutput.toJson([device: [id: 777, label: 'X', name: 'Generic LAN Driver',
                deviceTypeName: 'Generic LAN Driver', virtual: false, capabilities: ['Switch']]])
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', mesh_source_hub_id: null, mesh_source_device_id: null, confirm: true])

        then: 'the driver-create path runs; no "not both" throw, no divert to the mesh link GET'
        result.success == true
        result.deviceId == '777'
        result.alreadyLinked != true
        !hubGet.calls.any { it.key?.toString()?.startsWith('/device/createLinked/') }
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
    def "via dispatch: hub_create_device rejects missing confirm with isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_create_device', [deviceTypeId: '500'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('confirm=true is required') || mcpDriver.parseInner(response).error.contains('confirm')

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
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    // ============================================================
    // hub_create_device -- Hub Mesh link
    // ============================================================

    // Outcome 1 (linked): the source appears in localLinkedDevices with sourceHubId AND its
    // availableLinkedDevices row flips linkedLocally -> success with the resolved local id.
    def "toolCreateDevice links a Hub Mesh device and resolves the new local id (outcome: linked)"() {
        given: 'before: source offered unlinked; after createLinked it is linked locally'
        def done = false
        hubGet.register('/device/createLinked/HUB-A/42') { params -> done = true; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: done ? [[id: 900, name: 'Kitchen Bridge', sourceHubId: 'HUB-A']] : [],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Kitchen Bridge', linkedLocally: done]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        hubGet.calls.any { it.key == '/device/createLinked/HUB-A/42' }
        result.success == true
        result.deviceId == '900'
        result.name == 'Kitchen Bridge'
        result.sourceHubId == 'HUB-A'
        result.sourceDeviceId == '42'
        result.linkedDevice == true
        result.warnings == null

        and: 'the linked device joins the selected devices'
        appStub.settingsStore.selectedDevices == [type: 'capability.*', value: ['900']]
    }

    def "a linked device the read-back cannot tie to the requested hub is not added to the selection"() {
        given: 'the only new local link names a different source hub'
        def done = false
        hubGet.register('/device/createLinked/HUB-A/42') { params -> done = true; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: done ? [[id: 901, name: 'Other Bridge', sourceHubId: 'HUB-B']] : [],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Kitchen Bridge', linkedLocally: done]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == true
        result.deviceId == '901'
        result.warnings.any { it.contains('could not tie it to hub HUB-A') }
        appStub.settingsStore.selectedDevices == null
    }

    def "a created device already selected is not written again, and an empty selection gets just the new id"() {
        when:
        settingsMap.selectedDevices = selected
        def warning = script._selectCreatedDevice('777')

        then:
        warning == null
        appStub.settingsStore.selectedDevices == written

        where:
        selected                                           || written
        [new TestDevice(id: 777)]                          || null
        null                                               || [type: 'capability.*', value: ['777']]
    }

    def "a selection write that fails still reports the created device, with a warning"() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 1)]
        appStub.updateSettingFailure = new IllegalStateException('settings locked')
        hubGet.register('/device/sysDriverByIdJson/500') { params -> '{"success":true,"deviceId":777}' }
        hubGet.register('/device/fullJson/777') { params ->
            groovy.json.JsonOutput.toJson([device: [id: 777, name: 'Generic LAN Driver', deviceTypeName: 'Generic LAN Driver', virtual: false, capabilities: ['Switch']]])
        }

        when:
        def result = script.toolCreateDevice([deviceTypeId: '500', confirm: true])

        then:
        result.success == true
        result.deviceId == '777'
        result.warnings.any { it.contains('could not be added') && it.contains('settings locked') }
    }

    // Outcome 2 (silent no-op): the GET returns 200 but the source stays in availableLinkedDevices
    // with linkedLocally:false and localLinkedDevices never changes -> FAILURE, not a warning.
    def "toolCreateDevice mesh link FAILS on a silent no-op (source stays unlinked)"() {
        given: 'the createLinked GET is accepted but no link ever appears'
        hubGet.register('/device/createLinked/HUB-A/42') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: [],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Kitchen Bridge', linkedLocally: false]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error.contains('did not take')
        and: 'the note leads with the transient re-establish/retry guidance, with the peer-token store as the persistent-cause fallback'
        result.note.toLowerCase().contains('retry')
        result.note.toLowerCase().contains('re-establish')
        result.note.contains('mesh token')
        result.note.contains('hub_update_hub_mesh(peer_hub_id, peer_token)')
    }

    // Outcome 3 (unresolvable): the link GET is accepted but the mesh list cannot be read back at all
    // -- we can prove neither linked nor not-linked -> warn, don't fail.
    def "toolCreateDevice mesh link warns (not fails) when the mesh list is unreadable"() {
        given: 'the link is accepted but hubMeshJson cannot be read back'
        hubGet.register('/device/createLinked/HUB-A/42') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params -> throw new RuntimeException('mesh JSON unreachable') }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == true
        result.deviceId == null
        result.warnings != null
        result.warnings.any { it.contains('could not confirm') }
    }

    // A throw with an UNREADABLE read-back is an UNKNOWN outcome, not a definite failure: the note must
    // say the link MAY have applied and steer to verification, not that it did not happen.
    def "toolCreateDevice mesh link reports an unknown (may-have-applied) outcome when createLinked throws and the mesh list is unreadable"() {
        given:
        hubGet.register('/device/createLinked/HUB-A/42') { params -> throw new RuntimeException('Hub API 500') }
        hubGet.register('/hub2/hubMeshJson') { params -> throw new RuntimeException('mesh JSON unreachable') }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error.contains('linking the shared device')
        result.note.contains('MAY have applied')
        result.note.contains('hub_get_hub_mesh')
    }

    // A throw whose read-back PROVES the source is still unlinked is a definite no-op failure.
    def "toolCreateDevice mesh link FAILS definitively when createLinked throws but the read-back proves it did not link"() {
        given:
        hubGet.register('/device/createLinked/HUB-A/42') { params -> throw new RuntimeException('Hub API 500') }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: [],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Kitchen Bridge', linkedLocally: false]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error.contains('did not take')
    }

    def "toolCreateDevice rejects mixing deviceTypeId with the mesh_source pair"() {
        when:
        script.toolCreateDevice([deviceTypeId: '500', mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not both')
    }

    // Item: the mesh-link form rejects create-only fields (a linked proxy mirrors the peer's source, so
    // it cannot carry a local label).
    def "toolCreateDevice mesh link rejects a create-only field (label)"() {
        when:
        script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', label: 'Nope', confirm: true])

        then: 'rejected before any hub call'
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('label')
        !hubGet.calls.any { it.key == '/device/createLinked/HUB-A/42' }
    }

    def "toolCreateDevice mesh link requires BOTH hub id and device id"() {
        when:
        script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BOTH mesh_source_hub_id and mesh_source_device_id')
    }

    // An available list that is READABLE but does NOT offer the pair (typo/stale/peer stopped sharing)
    // must throw BEFORE the createLinked GET -- validation-before-side-effect -- rather than let
    // srcRow==null read as a false success.
    def "toolCreateDevice mesh link throws (no GET) when the pair is not offered in a readable available list"() {
        given: 'the available list is readable but offers a DIFFERENT device, not HUB-A/42'
        hubGet.register('/device/createLinked/HUB-A/42') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: [],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 99, deviceDisplayName: 'Other', linkedLocally: false]]
            ])
        }

        when:
        script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then: 'rejected as a validation error and the createLinked GET was never called'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('availableLinkedDevices')
        !hubGet.calls.any { it.key == '/device/createLinked/HUB-A/42' }
    }

    // Item: linking an already-linked source short-circuits -- no fresh GET, alreadyLinked:true with the
    // existing local id resolved from localLinkedDevices.
    def "toolCreateDevice mesh link short-circuits with alreadyLinked when the source is already linked (no GET)"() {
        given: 'the available row is already flagged linkedLocally:true and a local mirror exists'
        hubGet.register('/device/createLinked/HUB-A/42') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: [[id: 900, name: 'Kitchen Bridge', sourceHubId: 'HUB-A']],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Kitchen Bridge', linkedLocally: true]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == true
        result.alreadyLinked == true
        result.deviceId == '900'
        result.linkedDevice == true
        !hubGet.calls.any { it.key == '/device/createLinked/HUB-A/42' }
    }

    def "toolCreateDevice mesh link does NOT mark an unoffered device alreadyLinked from an unrelated same-hub proxy"() {
        given: 'HUB-A has a DIFFERENT device linked (local proxy 900); the requested deviceId 42 is not offered'
        hubGet.register('/device/createLinked/HUB-A/42') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: [[id: 900, name: 'Other Bridge', sourceHubId: 'HUB-A']],
                availableLinkedDevices: []
            ])
        }

        when: 'local device rows carry no source-device-id, so a same-hub proxy must NOT prove 42 is linked'
        script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then: 'rejected as not-offered, not a false alreadyLinked; no GET'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('availableLinkedDevices')
        !hubGet.calls.any { it.key == '/device/createLinked/HUB-A/42' }
    }

    def "toolCreateDevice mesh link links a SECOND device from a peer that already has one linked"() {
        given: 'HUB-A already has device 900 linked; deviceId 42 is offered and not yet linked'
        def done = false
        hubGet.register('/device/createLinked/HUB-A/42') { params -> done = true; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: done
                    ? [[id: 900, name: 'Other Bridge', sourceHubId: 'HUB-A'], [id: 901, name: 'New Bridge', sourceHubId: 'HUB-A']]
                    : [[id: 900, name: 'Other Bridge', sourceHubId: 'HUB-A']],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'New Bridge', linkedLocally: done]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then: 'the link GET IS sent (not short-circuited by the pre-existing same-hub proxy) and it links'
        hubGet.calls.any { it.key == '/device/createLinked/HUB-A/42' }
        result.success == true
        result.alreadyLinked != true
    }

    def "toolCreateDevice mesh link alreadyLinked warns when the existing local id cannot be resolved"() {
        given: 'linkedLocally:true but no localLinkedDevices row for this source'
        hubGet.register('/device/createLinked/HUB-A/42') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: [],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Kitchen Bridge', linkedLocally: true]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == true
        result.alreadyLinked == true
        result.deviceId == null
        result.warnings != null
        !hubGet.calls.any { it.key == '/device/createLinked/HUB-A/42' }
    }

    // Item: the source row DISAPPEARS from availableLinkedDevices between pre-check (offered, unlinked)
    // and read-back (gone) -> proves linked, and the new id resolves from the localLinkedDevices diff.
    def "toolCreateDevice mesh link proves linked when the offered source row disappears after the GET"() {
        given: 'source offered pre-call, then absent from availableLinkedDevices after createLinked'
        def done = false
        hubGet.register('/device/createLinked/HUB-A/42') { params -> done = true; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: done ? [[id: 901, name: 'Patio Bridge', sourceHubId: 'HUB-A']] : [],
                availableLinkedDevices: done ? [] : [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Patio Bridge', linkedLocally: false]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == true
        result.deviceId == '901'
        result.warnings == null
    }

    // Item: new-id resolution prefers the added row whose sourceHubId matches, over an unrelated add.
    def "toolCreateDevice mesh link picks the added local row matching sourceHubId when several appear"() {
        given: 'two rows are added, only one from this peer'
        def done = false
        hubGet.register('/device/createLinked/HUB-A/42') { params -> done = true; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: done
                    ? [[id: 700, name: 'From Other', sourceHubId: 'HUB-Z'], [id: 900, name: 'Kitchen Bridge', sourceHubId: 'HUB-A']]
                    : [],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Kitchen Bridge', linkedLocally: done]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == true
        result.deviceId == '900'
    }

    // Item: proven linked, but the new local id never resolves (no matching added row) -> success + warn.
    def "toolCreateDevice mesh link warns when linked but the new local id cannot be resolved"() {
        given: 'the availableLinkedDevices row flips linked, but no matching localLinkedDevices row appears'
        def done = false
        hubGet.register('/device/createLinked/HUB-A/42') { params -> done = true; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: [],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Kitchen Bridge', linkedLocally: done]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == true
        result.deviceId == null
        result.warnings != null
        result.warnings.any { it.contains('could not resolve its new local id') }
    }

    // Item: new-id resolution POLLS -- the row is absent on the first read-back and appears on a later one.
    def "toolCreateDevice mesh link polls until the new local id resolves"() {
        given: 'localLinkedDevices is empty on the first mesh read and populated on the next'
        int meshReads = 0
        hubGet.register('/device/createLinked/HUB-A/42') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            meshReads++
            // read 1 = before-snapshot (offered, unlinked, no local row);
            // read 2 = first read-back (still no local row); read 3+ = local row present + linked.
            boolean settled = meshReads >= 3
            groovy.json.JsonOutput.toJson([
                localLinkedDevices: settled ? [[id: 902, name: 'Hall Bridge', sourceHubId: 'HUB-A']] : [],
                availableLinkedDevices: [[hubId: 'HUB-A', deviceId: 42, deviceDisplayName: 'Hall Bridge', linkedLocally: settled]]
            ])
        }

        when:
        def result = script.toolCreateDevice([mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        result.success == true
        result.deviceId == '902'
        result.warnings == null
    }

    @spock.lang.Unroll
    def "via dispatch: hub_create_device mesh link passes through (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def linked = []
        hubGet.register('/device/createLinked/HUB-A/42') { params -> linked = [[id: 900, name: 'Kitchen Bridge']]; '' }
        hubGet.register('/hub2/hubMeshJson') { params -> groovy.json.JsonOutput.toJson([localLinkedDevices: linked]) }

        when:
        def response = mcpDriver.callTool('hub_create_device', [mesh_source_hub_id: 'HUB-A', mesh_source_device_id: '42', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.deviceId == '900'
        inner.linkedDevice == true

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
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Read tools are disabled')

        where:
        useGateways << [true, false]
    }
}
