package server

import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppScript
import support.ToolSpecBase
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Spec for hub_manage_virtual_device (create path) in hubitat-mcp-server.groovy.
 *
 * Covers:
 *   - Dispatch validation: mutually exclusive deviceType / customDriver, both missing
 *   - customDriver shape validation: non-Map, missing namespace, missing name
 *   - Built-in deviceType path: success + unsupported type rejection
 *   - customDriver path: success (addChildDevice gets correct namespace + name)
 *   - customDriver not-found error translates to hub_list_drivers hint
 *   - Built-in path regression: still works after the dual-path refactor
 *
 * Mocking strategy for addChildDevice (5-arg form):
 *   addChildDevice(namespace, name, dni, null, props) routes through HubitatAppScript's
 *   private `childDeviceFactory` closure -- same pattern as addChildApp / childAppFactory.
 *   We reflect into childDeviceFactory and replace it with a test closure per feature.
 *   The closure receives (namespace, name, dni, hubId, props) and must return an object
 *   whose .id is readable. Device metadata and namespace persistence use native
 *   HTTP fixtures installed by the factory and the per-test POST recorder.
 *   Throw to simulate addChildDevice errors (UnknownDeviceTypeException shape, etc.).
 *
 * requireDestructiveConfirm seeding (confirm + 24h backup; the Write master gates
 * centrally at executeTool and defaults ON, so it needs no seed here):
 *   stateMap.lastBackupTimestamp    = 1234567890000L  (matches HarnessSpec fixed now())
 *   args.confirm                    = true
 */
class ToolManageVirtualDeviceSpec extends ToolSpecBase {

    // Per-test factory closure. wireChildDeviceFactory() installs it into the script.
    // Null means "not configured; addChildDevice should not be called in this test."
    Closure childDeviceFactoryStub = null
    Map nativeDataValues = [:]
    boolean nativeWriteFailure = false

    def setup() {
        wireChildDeviceFactory()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            assert path == '/device/runmethod'
            def payload = new JsonSlurper().parseText(body)
            assert payload.method == 'updateDataValue'
            if (nativeWriteFailure) throw new RuntimeException('simulated native data write failure')
            nativeDataValues[payload.args[0].value] = payload.args[1].value
            [success: true]
        }
    }

    /**
     * Reflect into HubitatAppScript's private childDeviceFactory field.
     *
     * HubitatAppScript uses childDeviceFactory as a combined accessor + factory closure:
     *   - getChildDevices()    calls childDeviceFactory("list")
     *   - addChildDevice(5-arg) calls childDeviceFactory(ns, name, dni, hubId, props)
     *
     * This dispatcher handles both ops: "list" returns childDevicesList (the shared
     * HarnessSpec fixture); any other first arg (a namespace String) routes to the
     * per-test childDeviceFactoryStub for create-time interception.
     */
    private void wireChildDeviceFactory() {
        def self = this
        def devListRef = childDevicesList
        def factoryField = HubitatAppScript.getDeclaredField('childDeviceFactory')
        factoryField.accessible = true
        factoryField.set(script, { Object... callArgs ->
            if (callArgs.length == 1 && callArgs[0] == 'list') {
                return devListRef
            }
            // 5-arg form: (namespace, typeName, dni, hubId, props)
            if (self.childDeviceFactoryStub == null) {
                throw new IllegalStateException(
                    "addChildDevice(${callArgs[0]}, ${callArgs[1]}, ${callArgs[2]}, ...) was called but " +
                    "childDeviceFactoryStub is null. Set it in given: before calling " +
                    "toolCreateVirtualDevice.")
            }
            def child = self.childDeviceFactoryStub.call(*callArgs)
            if (child) {
                def id = child.id.toString()
                self.hubGet.register("/device/fullJson/${id}") {
                    JsonOutput.toJson([device: [id: id.toInteger(), name: callArgs[4].name,
                        label: callArgs[4].label, deviceNetworkId: callArgs[2],
                        deviceTypeName: callArgs[1], deviceTypeNamespace: callArgs[0],
                        data: self.nativeDataValues, capabilities: [], currentStates: [:]], commands: []])
                }
            }
            child
        } as Closure)
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec fixed now()
    }

    // -------- Safety gate --------

    def "create fails when the Write master is disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: 'Virtual Switch',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "via dispatch: create fails when the Write master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: 'Virtual Switch',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    // -------- Dispatch validation: mutually exclusive / both missing / missing deviceLabel --------

    def "create rejects when both deviceType and customDriver are provided"() {
        given:
        enableWrite()

        when:
        script.toolManageVirtualDevice([
            action: 'create',
            deviceType: 'Virtual Switch',
            customDriver: [namespace: 'x', name: 'y'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('mutually exclusive')
    }

    @spock.lang.Unroll
    def "via dispatch: create rejects when both deviceType and customDriver are provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: 'Virtual Switch',
            customDriver: [namespace: 'x', name: 'y'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('mutually exclusive')

        where:
        useGateways << [true, false]
    }

    def "create rejects when neither deviceType nor customDriver is provided"() {
        given:
        enableWrite()

        when:
        script.toolManageVirtualDevice([
            action: 'create',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Either deviceType or customDriver is required')
    }

    @spock.lang.Unroll
    def "via dispatch: create rejects when neither deviceType nor customDriver is provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('Either deviceType or customDriver is required')

        where:
        useGateways << [true, false]
    }

    def "dispatch rejects create with missing deviceLabel"() {
        given:
        enableWrite()

        when:
        script.toolManageVirtualDevice([
            action: 'create',
            deviceType: 'Virtual Switch',
            confirm: true
            // deviceLabel intentionally omitted
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceLabel is required')
    }

    @spock.lang.Unroll
    def "via dispatch: rejects create with missing deviceLabel (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: 'Virtual Switch',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('deviceLabel is required')

        where:
        useGateways << [true, false]
    }

    // -------- customDriver shape validation --------

    def "create with customDriver not a Map throws"() {
        given:
        enableWrite()

        when:
        script.toolCreateVirtualDevice([
            customDriver: 'not-a-map',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("customDriver must be an object")
    }

    @spock.lang.Unroll
    def "via dispatch: create with customDriver not a Map returns -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: 'not-a-map',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains("customDriver must be an object")

        where:
        useGateways << [true, false]
    }

    def "create with customDriver missing namespace throws"() {
        given:
        enableWrite()

        when:
        script.toolCreateVirtualDevice([
            customDriver: [name: 'My Driver'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'namespace'")
        ex.message.contains("'name'")
    }

    @spock.lang.Unroll
    def "via dispatch: create with customDriver missing namespace returns -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [name: 'My Driver'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains("'namespace'")
        response.error.message.contains("'name'")

        where:
        useGateways << [true, false]
    }

    def "create with customDriver missing name throws"() {
        given:
        enableWrite()

        when:
        script.toolCreateVirtualDevice([
            customDriver: [namespace: 'my-ns'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'namespace'")
        ex.message.contains("'name'")
    }

    @spock.lang.Unroll
    def "via dispatch: create with customDriver missing name returns -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: 'my-ns'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains("'namespace'")
        response.error.message.contains("'name'")

        where:
        useGateways << [true, false]
    }

    // -------- customDriver success path --------

    @spock.lang.Unroll
    def "via dispatch: create with customDriver delegates to addChildDevice with correct namespace and name (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def capturedArgs = [:]
        def capturedDataValues = nativeDataValues
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '77'
            getIdAsLong() >> 77L
            getName() >> 'Levoit Classic 200S Humidifier'
            getLabel() >> 'Kitchen Humidifier Test'
            getDeviceNetworkId() >> 'mcp-virtual-TEST'
            getCapabilities() >> { throw new AssertionError('SDK capabilities read') }
            getSupportedCommands() >> { throw new AssertionError('SDK commands read') }
            getSupportedAttributes() >> { throw new AssertionError('SDK attributes read') }
            currentValue(_) >> { throw new AssertionError('SDK state read') }
            updateDataValue(_, _) >> { throw new AssertionError('SDK data write') }
        }
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            capturedArgs.namespace = ns
            capturedArgs.name      = name
            capturedArgs.dni       = dni
            capturedArgs.hubId     = hubId
            capturedArgs.props     = props
            fakeDevice
        }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: 'level99-vesync', name: 'Levoit Classic 200S Humidifier'],
            deviceLabel: 'Kitchen Humidifier Test',
            confirm: true
        ])

        then:
        capturedArgs.namespace == 'level99-vesync'
        capturedArgs.name      == 'Levoit Classic 200S Humidifier'
        capturedArgs.hubId     == null
        capturedArgs.props?.label == 'Kitchen Humidifier Test'
        capturedArgs.props?.name  == 'Levoit Classic 200S Humidifier'
        capturedDataValues['mcpDriverNamespace'] == 'level99-vesync'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.device.id == '77'
        inner.device.driverNamespace == 'level99-vesync'
        inner.device.driverType      == 'Levoit Classic 200S Humidifier'
        inner.device.typeName == inner.device.driverType

        where:
        useGateways << [true, false]
    }

    def "create with customDriver delegates to addChildDevice with correct namespace and name"() {
        given:
        enableWrite()
        def capturedArgs = [:]
        def capturedDataValues = nativeDataValues
        // ChildDeviceWrapper mock satisfies the HubitatAppScript castToType check in addChildDevice.
        // The factory installs native information using the lifecycle arguments.
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '77'
            getIdAsLong() >> 77L
            getName() >> 'Levoit Classic 200S Humidifier'
            getLabel() >> 'Kitchen Humidifier Test'
            getDeviceNetworkId() >> 'mcp-virtual-TEST'
            getCapabilities() >> { throw new AssertionError('SDK capabilities read') }
            getSupportedCommands() >> { throw new AssertionError('SDK commands read') }
            getSupportedAttributes() >> { throw new AssertionError('SDK attributes read') }
            currentValue(_) >> { throw new AssertionError('SDK state read') }
            // Native writes are recorded separately; SDK calls remain forbidden.
            updateDataValue(_, _) >> { throw new AssertionError('SDK data write') }
        }
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            capturedArgs.namespace = ns
            capturedArgs.name      = name
            capturedArgs.dni       = dni
            capturedArgs.hubId     = hubId
            capturedArgs.props     = props
            fakeDevice
        }

        when:
        def result = script.toolCreateVirtualDevice([
            customDriver: [namespace: 'level99-vesync', name: 'Levoit Classic 200S Humidifier'],
            deviceLabel: 'Kitchen Humidifier Test',
            confirm: true
        ])

        then:
        capturedArgs.namespace == 'level99-vesync'
        capturedArgs.name      == 'Levoit Classic 200S Humidifier'
        capturedArgs.hubId     == null  // always null per 5-arg form convention
        capturedArgs.props?.label == 'Kitchen Humidifier Test'  // label propagates to addChildDevice props map
        capturedArgs.props?.name  == 'Levoit Classic 200S Humidifier'  // driver type name propagates to props
        // Verify namespace was persisted as a data value so hub_list_devices(filter:'virtual') can read it back
        capturedDataValues['mcpDriverNamespace'] == 'level99-vesync'
        result.success == true
        result.device.id == '77'
        result.device.driverNamespace == 'level99-vesync'
        result.device.driverType      == 'Levoit Classic 200S Humidifier'
        // typeName is a deprecated alias for driverType; both must be present and equal in create response
        result.device.typeName == result.device.driverType
    }

    // -------- customDriver updateDataValue failure (defensive contract) --------

    @spock.lang.Unroll
    def "via dispatch: create succeeds with partial warning when native namespace persistence fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        nativeWriteFailure = true
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '88'
            getIdAsLong() >> 88L
            getName() >> 'Levoit Classic 200S Humidifier'
            getLabel() >> 'Persistence Failure Test'
            getDeviceNetworkId() >> 'mcp-virtual-TEST-88'
            getCapabilities() >> { throw new AssertionError('SDK capabilities read') }
            getSupportedCommands() >> { throw new AssertionError('SDK commands read') }
            getSupportedAttributes() >> { throw new AssertionError('SDK attributes read') }
            currentValue(_) >> { throw new AssertionError('SDK state read') }
            updateDataValue(_, _) >> { throw new AssertionError('SDK data write') }
        }
        childDeviceFactoryStub = { ns, name, dni, hubId, props -> fakeDevice }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: 'NiklasGustafsson', name: 'Levoit Classic 200S Humidifier'],
            deviceLabel: 'Persistence Failure Test',
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.device.driverNamespace == 'NiklasGustafsson'
        inner.partialSuccess == true
        inner.warnings
        mcpLogCalls.any { it.level == 'warn' && it.msg.contains('mcpDriverNamespace') }

        where:
        useGateways << [true, false]
    }

    def "create succeeds with partial warning when native namespace persistence fails"() {
        // Defensive contract: create must NOT fail just because the data-value persistence step fails.
        // The namespace in the response is sourced from the validated arg, not from the data value,
        // so it is unaffected by the persistence failure.
        // A mcpLog("warn", ...) must fire so operators can diagnose the fallback condition.
        given:
        enableWrite()
        nativeWriteFailure = true
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '88'
            getIdAsLong() >> 88L
            getName() >> 'Levoit Classic 200S Humidifier'
            getLabel() >> 'Persistence Failure Test'
            getDeviceNetworkId() >> 'mcp-virtual-TEST-88'
            getCapabilities() >> { throw new AssertionError('SDK capabilities read') }
            getSupportedCommands() >> { throw new AssertionError('SDK commands read') }
            getSupportedAttributes() >> { throw new AssertionError('SDK attributes read') }
            currentValue(_) >> { throw new AssertionError('SDK state read') }
            // Simulate persistence failure
            updateDataValue(_, _) >> { throw new AssertionError('SDK data write') }
        }
        childDeviceFactoryStub = { ns, name, dni, hubId, props -> fakeDevice }

        when:
        def result = script.toolCreateVirtualDevice([
            customDriver: [namespace: 'NiklasGustafsson', name: 'Levoit Classic 200S Humidifier'],
            deviceLabel: 'Persistence Failure Test',
            confirm: true
        ])

        then:
        // (a) create still succeeds despite the persistence failure
        result.success == true
        // (b) driverNamespace in the response comes from the resolved arg, not the data value
        result.device.driverNamespace == 'NiklasGustafsson'
        result.partialSuccess == true
        result.warnings
        // (c) a warn-level mcpLog fired mentioning mcpDriverNamespace
        mcpLogCalls.any { it.level == 'warn' && it.msg.contains('mcpDriverNamespace') }
    }

    // -------- customDriver not-found error path --------

    def "create with customDriver: UnknownDeviceTypeException translates to hub_list_drivers hint"() {
        given:
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            throw new Exception("UnknownDeviceTypeException: Driver not found")
        }

        when:
        script.toolCreateVirtualDevice([
            customDriver: [namespace: 'fake-namespace', name: 'fake-driver'],
            deviceLabel: 'Test Device',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('fake-namespace:fake-driver')
        ex.message.contains('hub_list_drivers')
    }

    @spock.lang.Unroll
    def "via dispatch: create with customDriver UnknownDeviceTypeException returns -32602 with hub_list_drivers hint (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            throw new Exception("UnknownDeviceTypeException: Driver not found")
        }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: 'fake-namespace', name: 'fake-driver'],
            deviceLabel: 'Test Device',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('fake-namespace:fake-driver')
        response.error.message.contains('hub_list_drivers')

        where:
        useGateways << [true, false]
    }

    def "create with customDriver: not-found error (message 'not found') also translates"() {
        given:
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            throw new Exception("driver not found")
        }

        when:
        script.toolCreateVirtualDevice([
            customDriver: [namespace: 'my-ns', name: 'My Driver'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('hub_list_drivers')
        ex.message.contains('my-ns:My Driver')  // namespace:name echo present (parity with sibling specs)
    }

    @spock.lang.Unroll
    def "via dispatch: create with customDriver not-found error also translates (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            throw new Exception("driver not found")
        }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: 'my-ns', name: 'My Driver'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('hub_list_drivers')
        response.error.message.contains('my-ns:My Driver')

        where:
        useGateways << [true, false]
    }

    def "create with customDriver: any hub exception always surfaces hub_list_drivers hint (fail-closed)"() {
        // Invariant: any addChildDevice exception on the customDriver path surfaces as IllegalArgumentException
        // with the hub_list_drivers hint regardless of the exception class or message text.
        given:
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            throw new Exception("some unexpected hub error that does not mention not-found")
        }

        when:
        script.toolCreateVirtualDevice([
            customDriver: [namespace: 'my-ns', name: 'My Driver'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('hub_list_drivers')
        ex.message.contains('my-ns:My Driver')
    }

    @spock.lang.Unroll
    def "via dispatch: create with customDriver any hub exception surfaces hub_list_drivers hint fail-closed (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            throw new Exception("some unexpected hub error that does not mention not-found")
        }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: 'my-ns', name: 'My Driver'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('hub_list_drivers')
        response.error.message.contains('my-ns:My Driver')

        where:
        useGateways << [true, false]
    }

    // -------- Built-in deviceType path (regression pin) --------

    @spock.lang.Unroll
    def "via dispatch: create with built-in deviceType still works after dual-path refactor (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def capturedArgs = [:]
        def capturedDataValues = nativeDataValues
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '42'
            getIdAsLong() >> 42L
            getName() >> 'Virtual Switch'
            getLabel() >> 'BAT Test Switch'
            getDeviceNetworkId() >> 'mcp-virtual-TEST'
            getCapabilities() >> { throw new AssertionError('SDK capabilities read') }
            getSupportedCommands() >> { throw new AssertionError('SDK commands read') }
            getSupportedAttributes() >> { throw new AssertionError('SDK attributes read') }
            currentValue(_) >> { throw new AssertionError('SDK state read') }
            updateDataValue(_, _) >> { throw new AssertionError('SDK data write') }
        }
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            capturedArgs.namespace = ns
            capturedArgs.name      = name
            capturedArgs.props     = props
            fakeDevice
        }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: 'Virtual Switch',
            deviceLabel: 'BAT Test Switch',
            confirm: true
        ])

        then:
        capturedArgs.namespace == 'hubitat'
        capturedArgs.name      == 'Virtual Switch'
        capturedArgs.props?.label == 'BAT Test Switch'
        capturedArgs.props?.name  == 'Virtual Switch'
        capturedDataValues['mcpDriverNamespace'] == 'hubitat'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.device.id == '42'
        inner.device.driverNamespace == 'hubitat'
        inner.device.driverType      == 'Virtual Switch'
        inner.device.typeName == inner.device.driverType

        where:
        useGateways << [true, false]
    }

    def "create with built-in deviceType still works after dual-path refactor"() {
        given:
        enableWrite()
        def capturedArgs = [:]
        def capturedDataValues = nativeDataValues
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '42'
            getIdAsLong() >> 42L
            getName() >> 'Virtual Switch'
            getLabel() >> 'BAT Test Switch'
            getDeviceNetworkId() >> 'mcp-virtual-TEST'
            getCapabilities() >> { throw new AssertionError('SDK capabilities read') }
            getSupportedCommands() >> { throw new AssertionError('SDK commands read') }
            getSupportedAttributes() >> { throw new AssertionError('SDK attributes read') }
            currentValue(_) >> { throw new AssertionError('SDK state read') }
            updateDataValue(_, _) >> { throw new AssertionError('SDK data write') }
        }
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            capturedArgs.namespace = ns
            capturedArgs.name      = name
            capturedArgs.props     = props
            fakeDevice
        }

        when:
        def result = script.toolCreateVirtualDevice([
            deviceType: 'Virtual Switch',
            deviceLabel: 'BAT Test Switch',
            confirm: true
        ])

        then:
        capturedArgs.namespace == 'hubitat'
        capturedArgs.name      == 'Virtual Switch'
        capturedArgs.props?.label == 'BAT Test Switch'  // label propagates to addChildDevice props map
        capturedArgs.props?.name  == 'Virtual Switch'   // driver type name propagates to props
        // Verify namespace was persisted as a data value so hub_list_devices(filter:'virtual') can read it back
        capturedDataValues['mcpDriverNamespace'] == 'hubitat'
        result.success == true
        result.device.id == '42'
        result.device.driverNamespace == 'hubitat'
        result.device.driverType      == 'Virtual Switch'
        // typeName is a deprecated alias for driverType; both must be present and equal in create response
        result.device.typeName == result.device.driverType
    }

    def "create with unsupported built-in deviceType throws descriptive error"() {
        given:
        enableWrite()

        when:
        script.toolCreateVirtualDevice([
            deviceType: 'Virtual Toaster',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Virtual Toaster')
        ex.message.contains('Unsupported device type')
    }

    @spock.lang.Unroll
    def "via dispatch: create with unsupported built-in deviceType returns -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: 'Virtual Toaster',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('Virtual Toaster')
        response.error.message.contains('Unsupported device type')

        where:
        useGateways << [true, false]
    }

    def "create with built-in deviceType: UnknownDeviceTypeException stays RuntimeException with firmware hint"() {
        // Invariant: built-in driver-not-found is a platform condition that throws RuntimeException,
        // NOT IllegalArgumentException. The distinction preserves the semantic that caller-fixable
        // errors (wrong args) become IAE, while platform/firmware gaps become RuntimeException.
        given:
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            throw new Exception("UnknownDeviceTypeException")
        }

        when:
        script.toolCreateVirtualDevice([
            deviceType: 'Virtual Switch',
            deviceLabel: 'BAT Built-in Not-Found Test',
            confirm: true
        ])

        then:
        def ex = thrown(RuntimeException)
        !(ex instanceof IllegalArgumentException)  // pins the class distinction from customDriver path
        ex.message.contains('may not include this built-in driver')
    }

    @spock.lang.Unroll
    def "via dispatch: create with built-in deviceType UnknownDeviceTypeException becomes isError with firmware hint (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            throw new Exception("UnknownDeviceTypeException")
        }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: 'Virtual Switch',
            deviceLabel: 'BAT Built-in Not-Found Test',
            confirm: true
        ])

        then: 'platform error: RuntimeException wraps to isError=true (not -32602)'
        response.error == null
        response.result.isError == true
        response.result.content[0].text.contains('may not include this built-in driver')

        where:
        useGateways << [true, false]
    }

    // -------- toolListVirtualDevices --------

    def "list_virtual_devices honors limit offset and cursor and rejects conflicting starts"() {
        given:
        (0..<6).each { int index ->
            def device = new support.TestDevice(
                id: 200 + index,
                name: 'Virtual Switch',
                label: "Paged Virtual ${index}",
                deviceNetworkId: "mcp-virtual-page-${index}",
                typeName: 'Virtual Switch'
            )
            device.dataValues['mcpDriverNamespace'] = 'hubitat'
            childDevicesList << device
            hubGet.register("/device/fullJson/${200 + index}") {
                JsonOutput.toJson([device: [id: 200 + index, name: 'Virtual Switch', label: "Paged Virtual ${index}",
                    deviceNetworkId: "mcp-virtual-page-${index}", deviceTypeName: 'Virtual Switch',
                    deviceTypeNamespace: 'hubitat', data: [mcpDriverNamespace: 'hubitat'],
                    capabilities: [], currentStates: [:]], commands: []])
            }
        }

        when: 'the declared limit is smaller than the virtual inventory'
        def first = script.toolListVirtualDevices([limit: 3])

        then:
        first.devices*.id == ['200', '201', '202']
        first.count == 3
        first.total == 6
        first.offset == 0
        first.limit == 3
        first.hasMore == true
        first.nextOffset == 3

        when: 'offset and limit select the requested classic page'
        def offsetPage = script.toolListVirtualDevices([offset: 2, limit: 2])

        then:
        offsetPage.devices*.id == ['202', '203']
        offsetPage.nextOffset == 4

        when: 'the maximum declared integer limit would overflow startIndex plus limit'
        def maximumLimitPage = script.toolListVirtualDevices([offset: 1, limit: Integer.MAX_VALUE])

        then: 'the end is clamped to the inventory without wrapping negative'
        maximumLimitPage.devices*.id == ['201', '202', '203', '204', '205']
        maximumLimitPage.count == 5
        maximumLimitPage.offset == 1
        maximumLimitPage.limit == Integer.MAX_VALUE
        maximumLimitPage.hasMore == false
        !maximumLimitPage.containsKey('nextOffset')

        when: 'cursor selects the same page and emits the next cursor'
        def cursorPage = script.toolListVirtualDevices([cursor: '2', limit: 2])

        then:
        cursorPage.devices*.id == ['202', '203']
        cursorPage.nextCursor == '4'

        when: 'two different starting controls are supplied together'
        script.toolListVirtualDevices([cursor: '2', offset: 1, limit: 2])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('cursor and offset are mutually exclusive')
    }

    @spock.lang.Unroll
    def "virtual list preserves native namespace precedence and distinct driver type (data=#stored, native=#namespace, gateway=#gateway)"() {
        given:
        settingsMap.useGateways = gateway
        def child = new support.TestDevice(id: 99, name: 'SDK stale', deviceNetworkId: 'mcp-99')
        childDevicesList << child
        hubGet.register('/device/fullJson/99') {
            JsonOutput.toJson([device: [id: 99, name: 'Generic Component', label: 'Native label',
                deviceNetworkId: 'mcp-99', deviceTypeName: 'My Custom Driver', deviceTypeNamespace: namespace,
                data: stored ? [mcpDriverNamespace: stored] : [:], capabilities: [], currentStates: [:]], commands: []])
        }

        when:
        def response = mcpDriver.callTool('hub_list_devices', [filter: 'virtual'])

        then:
        response.error == null
        !response.result.isError
        def result = mcpDriver.parseInner(response)
        result.devices.size() == 1
        result.devices[0].id == '99'
        result.devices[0].name == 'Generic Component'
        result.devices[0].driverNamespace == expected
        result.devices[0].driverType == 'My Custom Driver'
        result.devices[0].typeName == 'My Custom Driver'
        result.devices[0].deviceNetworkId == 'mcp-99'

        where:
        [stored, namespace, expected, gateway] << [true, false].collectMany { gw ->
            [['persisted-ns', 'native-ns', 'persisted-ns'], [null, 'native-ns', 'native-ns'],
             ['hubitat', 'wrong-ns', 'hubitat'], [null, 'hubitat', 'hubitat']].collect { it + [gw] }
        }
    }

    // -------- M3: cause-chain probe (two-arg IllegalArgumentException/RuntimeException ctors) --------

    def "create with customDriver: thrown IllegalArgumentException preserves cause chain"() {
        // Verifies that the two-arg constructor IllegalArgumentException(msg, e) preserves the
        // original exception as .cause. If the Hubitat sandbox strips cause chains, .cause would be
        // null; this spec detects that regression.
        given:
        enableWrite()
        def originalEx = new Exception("root cause message")
        childDeviceFactoryStub = { ns, name, dni, hubId, props -> throw originalEx }

        when:
        script.toolCreateVirtualDevice([
            customDriver: [namespace: 'x-ns', name: 'X Driver'],
            deviceLabel: 'Cause Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.cause != null           // two-arg ctor preserved the cause
        ex.cause.is(originalEx)    // exact same instance
    }

    @spock.lang.Unroll
    def "via dispatch: create with customDriver IllegalArgumentException root cause echoed in -32602 message (useGateways=#useGateways)"() {
        // Dispatch envelope flattens exception to message string -- can't inspect .cause through render.
        // Parallel assertion: root-cause text is woven into the -32602 message via the
        // "(Hub reported: ...)" suffix at toolCreateVirtualDevice line 11302.
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def originalEx = new Exception("root cause message")
        childDeviceFactoryStub = { ns, name, dni, hubId, props -> throw originalEx }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: 'x-ns', name: 'X Driver'],
            deviceLabel: 'Cause Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('root cause message')
        response.error.message.contains('hub_list_drivers')

        where:
        useGateways << [true, false]
    }

    def "create with built-in: RuntimeException preserves cause chain"() {
        // Mirrors the customDriver cause-chain probe for the built-in path RuntimeException.
        given:
        enableWrite()
        def originalEx = new Exception("UnknownDeviceTypeException: not found")
        childDeviceFactoryStub = { ns, name, dni, hubId, props -> throw originalEx }

        when:
        script.toolCreateVirtualDevice([
            deviceType: 'Virtual Switch',
            deviceLabel: 'Cause Test Built-in',
            confirm: true
        ])

        then:
        def ex = thrown(RuntimeException)
        ex.cause != null
        ex.cause.is(originalEx)
    }

    @spock.lang.Unroll
    def "via dispatch: create with built-in RuntimeException root cause echoed in isError message (useGateways=#useGateways)"() {
        // Dispatch envelope wraps RuntimeException as isError=true; .cause is not observable through render.
        // Parallel assertion: root-cause text is woven into the isError message via "(Hub reported: ...)".
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def originalEx = new Exception("UnknownDeviceTypeException: not found")
        childDeviceFactoryStub = { ns, name, dni, hubId, props -> throw originalEx }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: 'Virtual Switch',
            deviceLabel: 'Cause Test Built-in',
            confirm: true
        ])

        then:
        response.error == null
        response.result.isError == true
        response.result.content[0].text.contains('UnknownDeviceTypeException: not found')

        where:
        useGateways << [true, false]
    }

    // -------- M6: blank deviceType + customDriver mutex, whitespace namespace/name, non-String --------

    def "create: blank-after-trim deviceType with customDriver present triggers mutex error"() {
        // Groovy truthiness: '  ' (whitespace) is truthy, so without explicit trimming
        // the dispatch would silently route to customDriver, hiding a caller ambiguity.
        // After trim '  ' becomes '' which is falsy -- but that alone would drop to the
        // "both provided" false branch and silently take customDriver. The guard must reject explicitly.
        given:
        enableWrite()

        when:
        script.toolManageVirtualDevice([
            action: 'create',
            deviceType: '   ',
            customDriver: [namespace: 'x', name: 'y'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('mutually exclusive')
    }

    @spock.lang.Unroll
    def "via dispatch: create blank-after-trim deviceType with customDriver present triggers mutex error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: '   ',
            customDriver: [namespace: 'x', name: 'y'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('mutually exclusive')

        where:
        useGateways << [true, false]
    }

    def "create: blank-after-trim deviceType without customDriver triggers missing-arg error"() {
        // A blank-after-trim deviceType with no customDriver should surface the "either required" error,
        // not a cryptic null/empty failure from deeper in the chain.
        given:
        enableWrite()

        when:
        script.toolManageVirtualDevice([
            action: 'create',
            deviceType: '   ',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Either deviceType or customDriver is required')
    }

    @spock.lang.Unroll
    def "via dispatch: create blank-after-trim deviceType without customDriver triggers missing-arg error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: '   ',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('Either deviceType or customDriver is required')

        where:
        useGateways << [true, false]
    }

    def "create: whitespace-only customDriver namespace throws descriptive error"() {
        given:
        enableWrite()

        when:
        script.toolCreateVirtualDevice([
            customDriver: [namespace: '   ', name: 'My Driver'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('non-empty strings')
        ex.message.contains("'namespace'")
    }

    @spock.lang.Unroll
    def "via dispatch: create whitespace-only customDriver namespace returns -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: '   ', name: 'My Driver'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('non-empty strings')
        response.error.message.contains("'namespace'")

        where:
        useGateways << [true, false]
    }

    def "create: whitespace-only customDriver name throws descriptive error"() {
        given:
        enableWrite()

        when:
        script.toolCreateVirtualDevice([
            customDriver: [namespace: 'my-ns', name: '   '],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('non-empty strings')
        ex.message.contains("'name'")
    }

    @spock.lang.Unroll
    def "via dispatch: create whitespace-only customDriver name returns -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: 'my-ns', name: '   '],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('non-empty strings')
        response.error.message.contains("'name'")

        where:
        useGateways << [true, false]
    }

    def "create: non-String (numeric) customDriver namespace coerces then rejects if blank"() {
        // Non-String values (e.g. 123) coerce via toString() then trim() -- a numeric 123 is
        // non-blank so it gets passed through to the hub (which will reject it with a clear error);
        // a whitespace-only String gets caught here before reaching the hub.
        given:
        enableWrite()
        def capturedNs = null
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '55'
            getIdAsLong() >> 55L
            getName() >> 'test-driver'
            getLabel() >> 'Coerce Test'
            getDeviceNetworkId() >> 'mcp-virtual-coerce'
            getCapabilities() >> { throw new AssertionError('SDK capabilities read') }
            getSupportedCommands() >> { throw new AssertionError('SDK commands read') }
            getSupportedAttributes() >> { throw new AssertionError('SDK attributes read') }
            currentValue(_) >> { throw new AssertionError('SDK state read') }
            updateDataValue(_, _) >> { throw new AssertionError('SDK data write') }
        }
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            capturedNs = ns
            fakeDevice
        }

        when:
        def result = script.toolCreateVirtualDevice([
            customDriver: [namespace: 123, name: 'test-driver'],
            deviceLabel: 'Coerce Test',
            confirm: true
        ])

        then:
        // Numeric 123 coerces to String "123" -- non-blank, passes validation, reaches hub
        capturedNs == '123'
        result.success == true
    }

    @spock.lang.Unroll
    def "via dispatch: create non-String numeric customDriver namespace coerces then succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def capturedNs = null
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '55'
            getIdAsLong() >> 55L
            getName() >> 'test-driver'
            getLabel() >> 'Coerce Test'
            getDeviceNetworkId() >> 'mcp-virtual-coerce'
            getCapabilities() >> { throw new AssertionError('SDK capabilities read') }
            getSupportedCommands() >> { throw new AssertionError('SDK commands read') }
            getSupportedAttributes() >> { throw new AssertionError('SDK attributes read') }
            currentValue(_) >> { throw new AssertionError('SDK state read') }
            updateDataValue(_, _) >> { throw new AssertionError('SDK data write') }
        }
        childDeviceFactoryStub = { ns, name, dni, hubId, props ->
            capturedNs = ns
            fakeDevice
        }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            customDriver: [namespace: 123, name: 'test-driver'],
            deviceLabel: 'Coerce Test',
            confirm: true
        ])

        then:
        capturedNs == '123'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true

        where:
        useGateways << [true, false]
    }

    // -------- M8: addChildDevice returns null --------

    def "create: addChildDevice returning null throws RuntimeException with partial-registration guidance"() {
        given:
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props -> null }

        when:
        script.toolCreateVirtualDevice([
            deviceType: 'Virtual Switch',
            deviceLabel: 'Null Return Test',
            confirm: true
        ])

        then:
        def ex = thrown(RuntimeException)
        ex.message.contains('addChildDevice returned null')
        ex.message.contains('partially registered')
    }

    @spock.lang.Unroll
    def "via dispatch: create addChildDevice returning null becomes isError with partial-registration guidance (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        childDeviceFactoryStub = { ns, name, dni, hubId, props -> null }

        when:
        def response = mcpDriver.callTool('hub_manage_virtual_device', [
            action: 'create',
            deviceType: 'Virtual Switch',
            deviceLabel: 'Null Return Test',
            confirm: true
        ])

        then: 'platform error: RuntimeException wraps to isError=true (not -32602)'
        response.error == null
        response.result.isError == true
        response.result.content[0].text.contains('addChildDevice returned null')
        response.result.content[0].text.contains('partially registered')

        where:
        useGateways << [true, false]
    }
}
