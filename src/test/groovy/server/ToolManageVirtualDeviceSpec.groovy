package server

import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppScript
import support.ToolSpecBase

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
 *   whose .id / .name / .label / .deviceNetworkId / .capabilities / .supportedCommands /
 *   .supportedAttributes / .currentValue(attr) are readable (TestDevice satisfies this).
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

    def setup() {
        wireChildDeviceFactory()
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
            self.childDeviceFactoryStub.call(*callArgs)
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
        def capturedDataValues = [:]
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '77'
            getIdAsLong() >> 77L
            getName() >> 'Levoit Classic 200S Humidifier'
            getLabel() >> 'Kitchen Humidifier Test'
            getDeviceNetworkId() >> 'mcp-virtual-TEST'
            getCapabilities() >> []
            getSupportedCommands() >> []
            getSupportedAttributes() >> []
            currentValue(_) >> null
            updateDataValue(_, _) >> { String key, String val -> capturedDataValues[key] = val }
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
        def capturedDataValues = [:]
        // ChildDeviceWrapper mock satisfies the HubitatAppScript castToType check in addChildDevice.
        // updateDataValue is declared on DeviceWrapper (parent interface) so Spock can stub it.
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '77'
            getIdAsLong() >> 77L
            getName() >> 'Levoit Classic 200S Humidifier'
            getLabel() >> 'Kitchen Humidifier Test'
            getDeviceNetworkId() >> 'mcp-virtual-TEST'
            getCapabilities() >> []
            getSupportedCommands() >> []
            getSupportedAttributes() >> []
            currentValue(_) >> null
            // Capture updateDataValue calls so create-path data-value persistence is verifiable
            updateDataValue(_, _) >> { String key, String val -> capturedDataValues[key] = val }
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
    def "via dispatch: create succeeds even when updateDataValue throws -- warn fires and driverNamespace is still correct (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
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
            getCapabilities() >> []
            getSupportedCommands() >> []
            getSupportedAttributes() >> []
            currentValue(_) >> null
            updateDataValue(_, _) >> { String key, String val ->
                throw new RuntimeException("simulated data-value persistence failure")
            }
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
        mcpLogCalls.any { it.level == 'warn' && it.msg.contains('mcpDriverNamespace') }

        where:
        useGateways << [true, false]
    }

    def "create succeeds even when updateDataValue throws -- warn fires and driverNamespace is still correct"() {
        // Defensive contract: create must NOT fail just because the data-value persistence step fails.
        // The namespace in the response is sourced from the validated arg, not from the data value,
        // so it is unaffected by the persistence failure.
        // A mcpLog("warn", ...) must fire so operators can diagnose the fallback condition.
        given:
        enableWrite()
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
            getCapabilities() >> []
            getSupportedCommands() >> []
            getSupportedAttributes() >> []
            currentValue(_) >> null
            // Simulate persistence failure
            updateDataValue(_, _) >> { String key, String val ->
                throw new RuntimeException("simulated data-value persistence failure")
            }
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
        def capturedDataValues = [:]
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '42'
            getIdAsLong() >> 42L
            getName() >> 'Virtual Switch'
            getLabel() >> 'BAT Test Switch'
            getDeviceNetworkId() >> 'mcp-virtual-TEST'
            getCapabilities() >> []
            getSupportedCommands() >> []
            getSupportedAttributes() >> []
            currentValue(_) >> null
            updateDataValue(_, _) >> { String key, String val -> capturedDataValues[key] = val }
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
        def capturedDataValues = [:]
        def fakeDevice = Mock(ChildDeviceWrapper) {
            getId() >> '42'
            getIdAsLong() >> 42L
            getName() >> 'Virtual Switch'
            getLabel() >> 'BAT Test Switch'
            getDeviceNetworkId() >> 'mcp-virtual-TEST'
            getCapabilities() >> []
            getSupportedCommands() >> []
            getSupportedAttributes() >> []
            currentValue(_) >> null
            updateDataValue(_, _) >> { String key, String val -> capturedDataValues[key] = val }
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

    def "list_virtual_devices: fallback -- getDriverType() returns namespace-bearing object when no data value"() {
        // Backward-compat fallback: when mcpDriverNamespace data value is absent (device created before
        // this fix or by other means), the list path falls back to getDriverType()?.namespace.
        // This path is still exercised even though the primary path (data value) is preferred.
        given:
        def fakeDriverType = [namespace: 'level99-vesync']
        def fakeDevice = new support.TestDevice(
            id: 99,
            name: 'Levoit Classic 200S Humidifier',
            label: 'Office Humidifier',
            deviceNetworkId: 'mcp-virtual-test-99',
            typeName: 'Levoit Classic 200S Humidifier'
            // dataValues empty -- no mcpDriverNamespace persisted -- exercises fallback path
        )
        fakeDevice.metaClass.getDriverType = { -> fakeDriverType }
        childDevicesList << fakeDevice

        when:
        def result = script.toolListVirtualDevices([:])

        then:
        result.success != false
        result.devices.size() >= 1
        def d = result.devices.find { it.id == '99' }
        d != null
        d.driverNamespace == 'level99-vesync'  // fallback to getDriverType().namespace when data value absent
        d.driverType      == 'Levoit Classic 200S Humidifier'
        d.typeName        == 'Levoit Classic 200S Humidifier'  // deprecated alias present in list response
        d.deviceNetworkId == 'mcp-virtual-test-99'

        cleanup:
        childDevicesList.removeAll { it.id == 99 }
    }

    @spock.lang.Unroll
    def "via dispatch: list_virtual_devices fallback getDriverType returns namespace-bearing object when no data value (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def fakeDriverType = [namespace: 'level99-vesync']
        def fakeDevice = new support.TestDevice(
            id: 99,
            name: 'Levoit Classic 200S Humidifier',
            label: 'Office Humidifier',
            deviceNetworkId: 'mcp-virtual-test-99',
            typeName: 'Levoit Classic 200S Humidifier'
        )
        fakeDevice.metaClass.getDriverType = { -> fakeDriverType }
        childDevicesList << fakeDevice

        when:
        def response = mcpDriver.callTool('hub_list_devices', [filter: 'virtual'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success != false
        inner.devices.size() >= 1
        def d = inner.devices.find { it.id == '99' }
        d != null
        d.driverNamespace == 'level99-vesync'
        d.driverType      == 'Levoit Classic 200S Humidifier'
        d.typeName        == 'Levoit Classic 200S Humidifier'
        d.deviceNetworkId == 'mcp-virtual-test-99'

        cleanup:
        childDevicesList.removeAll { it.id == 99 }

        where:
        useGateways << [true, false]
    }

    def "list_virtual_devices: getDriverType() exception falls back to hubitat namespace without crashing"() {
        // Invariant: on firmware where getDriverType() is unavailable or throws, the fallback
        // namespace is 'hubitat' and the tool continues to return a valid response.
        given:
        def fakeDevice = new support.TestDevice(
            id: 100,
            name: 'Virtual Switch',
            label: 'Fallback Test Switch',
            deviceNetworkId: 'mcp-virtual-test-100'
            // typeName defaults to null in TestDevice fixture -- this exercises the
            // Elvis chain: typeName ?: name. This is a fixture default, not a production
            // guarantee that built-in devices always have null typeName.
        )
        fakeDevice.metaClass.getDriverType = { -> throw new MissingMethodException('getDriverType', Object, [] as Object[]) }
        childDevicesList << fakeDevice

        when:
        def result = script.toolListVirtualDevices([:])

        then:
        result.success != false
        def d = result.devices.find { it.id == '100' }
        d != null
        d.driverNamespace == 'hubitat'   // fallback when getDriverType() throws
        d.driverType == 'Virtual Switch' // fixture typeName is null so name wins via Elvis
        d.typeName == 'Virtual Switch'   // deprecated alias derives from same Elvis result

        cleanup:
        childDevicesList.removeAll { it.id == 100 }
    }

    @spock.lang.Unroll
    def "via dispatch: list_virtual_devices getDriverType exception falls back to hubitat namespace (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def fakeDevice = new support.TestDevice(
            id: 100,
            name: 'Virtual Switch',
            label: 'Fallback Test Switch',
            deviceNetworkId: 'mcp-virtual-test-100'
        )
        fakeDevice.metaClass.getDriverType = { -> throw new MissingMethodException('getDriverType', Object, [] as Object[]) }
        childDevicesList << fakeDevice

        when:
        def response = mcpDriver.callTool('hub_list_devices', [filter: 'virtual'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success != false
        def d = inner.devices.find { it.id == '100' }
        d != null
        d.driverNamespace == 'hubitat'
        d.driverType == 'Virtual Switch'
        d.typeName == 'Virtual Switch'

        cleanup:
        childDevicesList.removeAll { it.id == 100 }

        where:
        useGateways << [true, false]
    }

    // N1: getDriverType() returns object with null namespace (common for built-ins on modern firmware)
    def "list_virtual_devices: getDriverType() returns object with null namespace falls back to hubitat"() {
        // Covers the non-exception Elvis path: device.getDriverType()?.namespace returns null
        // (returns an object whose .namespace property is null) -- distinct from the throw path.
        // This is the common case for built-in virtual drivers on modern firmware.
        given:
        def fakeDriverType = [namespace: null]  // object present, namespace null
        def fakeDevice = new support.TestDevice(
            id: 101,
            name: 'Virtual Switch',
            label: 'Built-in Modern Firmware Switch',
            deviceNetworkId: 'mcp-virtual-test-101',
            typeName: 'Virtual Switch'
        )
        fakeDevice.metaClass.getDriverType = { -> fakeDriverType }
        childDevicesList << fakeDevice

        when:
        def result = script.toolListVirtualDevices([:])

        then:
        result.success != false
        def d = result.devices.find { it.id == '101' }
        d != null
        d.driverNamespace == 'hubitat'   // null namespace from getDriverType() triggers ?: "hubitat" fallback
        d.driverType == 'Virtual Switch'

        cleanup:
        childDevicesList.removeAll { it.id == 101 }
    }

    @spock.lang.Unroll
    def "via dispatch: list_virtual_devices getDriverType returns object with null namespace falls back to hubitat (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def fakeDriverType = [namespace: null]
        def fakeDevice = new support.TestDevice(
            id: 101,
            name: 'Virtual Switch',
            label: 'Built-in Modern Firmware Switch',
            deviceNetworkId: 'mcp-virtual-test-101',
            typeName: 'Virtual Switch'
        )
        fakeDevice.metaClass.getDriverType = { -> fakeDriverType }
        childDevicesList << fakeDevice

        when:
        def response = mcpDriver.callTool('hub_list_devices', [filter: 'virtual'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success != false
        def d = inner.devices.find { it.id == '101' }
        d != null
        d.driverNamespace == 'hubitat'
        d.driverType == 'Virtual Switch'

        cleanup:
        childDevicesList.removeAll { it.id == 101 }

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "via dispatch: list_virtual_devices primary path reads mcpDriverNamespace data value when present (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def fakeDevice = new support.TestDevice(
            id: 103,
            name: 'Levoit Classic 200S Humidifier',
            label: 'Namespace From Data Value Test',
            deviceNetworkId: 'mcp-virtual-test-103',
            typeName: 'Levoit Classic 200S Humidifier'
        )
        fakeDevice.dataValues['mcpDriverNamespace'] = 'NiklasGustafsson'
        fakeDevice.metaClass.getDriverType = { -> null }
        childDevicesList << fakeDevice

        when:
        def response = mcpDriver.callTool('hub_list_devices', [filter: 'virtual'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success != false
        def d = inner.devices.find { it.id == '103' }
        d != null
        d.driverNamespace == 'NiklasGustafsson'
        d.driverType == 'Levoit Classic 200S Humidifier'
        d.typeName   == 'Levoit Classic 200S Humidifier'

        cleanup:
        childDevicesList.removeAll { it.id == 103 }

        where:
        useGateways << [true, false]
    }

    // Primary data-value path: customDriver create persists namespace; list reads it back
    // getDriverType() returns null to mirror real hub behavior (confirmed on Hubitat 2.5.0.126)
    def "list_virtual_devices: primary path -- reads mcpDriverNamespace data value when present (getDriverType returns null)"() {
        // The core bug fix: getDriverType()?.namespace returns null on real hubs for custom-driver
        // virtual devices. The primary path reads mcpDriverNamespace from device data values instead.
        // This spec mirrors the real hub: getDriverType() returns null, data value carries the truth.
        given:
        def fakeDevice = new support.TestDevice(
            id: 103,
            name: 'Levoit Classic 200S Humidifier',
            label: 'Namespace From Data Value Test',
            deviceNetworkId: 'mcp-virtual-test-103',
            typeName: 'Levoit Classic 200S Humidifier'
        )
        // Persist the namespace as the create path would
        fakeDevice.dataValues['mcpDriverNamespace'] = 'NiklasGustafsson'
        // getDriverType() returns null -- mirrors real hub behavior for custom-driver virtual devices
        fakeDevice.metaClass.getDriverType = { -> null }
        childDevicesList << fakeDevice

        when:
        def result = script.toolListVirtualDevices([:])

        then:
        result.success != false
        def d = result.devices.find { it.id == '103' }
        d != null
        d.driverNamespace == 'NiklasGustafsson'  // sourced from data value, NOT from getDriverType()
        d.driverType == 'Levoit Classic 200S Humidifier'
        d.typeName   == 'Levoit Classic 200S Humidifier'  // deprecated alias reflects same value

        cleanup:
        childDevicesList.removeAll { it.id == 103 }
    }

    @spock.lang.Unroll
    def "via dispatch: list_virtual_devices primary path built-in device data value hubitat returned as driverNamespace (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def fakeDevice = new support.TestDevice(
            id: 104,
            name: 'Virtual Switch',
            label: 'Built-in Data Value Test',
            deviceNetworkId: 'mcp-virtual-test-104',
            typeName: 'Virtual Switch'
        )
        fakeDevice.dataValues['mcpDriverNamespace'] = 'hubitat'
        fakeDevice.metaClass.getDriverType = { -> [namespace: 'wrong-namespace-should-not-be-used'] }
        childDevicesList << fakeDevice

        when:
        def response = mcpDriver.callTool('hub_list_devices', [filter: 'virtual'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success != false
        def d = inner.devices.find { it.id == '104' }
        d != null
        d.driverNamespace == 'hubitat'

        cleanup:
        childDevicesList.removeAll { it.id == 104 }

        where:
        useGateways << [true, false]
    }

    // Primary data-value path: built-in create persists "hubitat"; list reads it back
    def "list_virtual_devices: primary path -- built-in device data value 'hubitat' returned as driverNamespace"() {
        // Built-in devices created by this version have mcpDriverNamespace = "hubitat" persisted.
        // List reads this data value directly -- no getDriverType() call needed.
        given:
        def fakeDevice = new support.TestDevice(
            id: 104,
            name: 'Virtual Switch',
            label: 'Built-in Data Value Test',
            deviceNetworkId: 'mcp-virtual-test-104',
            typeName: 'Virtual Switch'
        )
        fakeDevice.dataValues['mcpDriverNamespace'] = 'hubitat'
        // getDriverType() should NOT be reached when data value is present; we verify by having
        // it return a wrong namespace -- if the test passes, the data value was preferred.
        fakeDevice.metaClass.getDriverType = { -> [namespace: 'wrong-namespace-should-not-be-used'] }
        childDevicesList << fakeDevice

        when:
        def result = script.toolListVirtualDevices([:])

        then:
        result.success != false
        def d = result.devices.find { it.id == '104' }
        d != null
        d.driverNamespace == 'hubitat'  // from data value, not from getDriverType() (which would return wrong-namespace)

        cleanup:
        childDevicesList.removeAll { it.id == 104 }
    }

    @spock.lang.Unroll
    def "via dispatch: list_virtual_devices backward-compat no data value and getDriverType null falls back to hubitat (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def fakeDevice = new support.TestDevice(
            id: 105,
            name: 'Virtual Switch',
            label: 'Backward Compat Fallback Test',
            deviceNetworkId: 'mcp-virtual-test-105',
            typeName: 'Virtual Switch'
        )
        fakeDevice.metaClass.getDriverType = { -> null }
        childDevicesList << fakeDevice

        when:
        def response = mcpDriver.callTool('hub_list_devices', [filter: 'virtual'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success != false
        def d = inner.devices.find { it.id == '105' }
        d != null
        d.driverNamespace == 'hubitat'

        cleanup:
        childDevicesList.removeAll { it.id == 105 }

        where:
        useGateways << [true, false]
    }

    // Backward-compat: no data value AND getDriverType returns null -> falls back to "hubitat"
    def "list_virtual_devices: backward-compat -- no data value and getDriverType returns null falls back to hubitat"() {
        // Devices created before this fix have no mcpDriverNamespace data value.
        // getDriverType() returning null (real hub behavior) means the final fallback is "hubitat".
        given:
        def fakeDevice = new support.TestDevice(
            id: 105,
            name: 'Virtual Switch',
            label: 'Backward Compat Fallback Test',
            deviceNetworkId: 'mcp-virtual-test-105',
            typeName: 'Virtual Switch'
            // dataValues empty, no mcpDriverNamespace
        )
        fakeDevice.metaClass.getDriverType = { -> null }  // mirrors real hub: getDriverType returns null
        childDevicesList << fakeDevice

        when:
        def result = script.toolListVirtualDevices([:])

        then:
        result.success != false
        def d = result.devices.find { it.id == '105' }
        d != null
        d.driverNamespace == 'hubitat'  // final fallback: no data value, getDriverType null, ?: "hubitat" fires

        cleanup:
        childDevicesList.removeAll { it.id == 105 }
    }

    @spock.lang.Unroll
    def "via dispatch: list_virtual_devices device with typeName distinct from name returns typeName as driverType (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def fakeDriverType = [namespace: 'my-ns']
        def fakeDevice = new support.TestDevice(
            id: 102,
            name: 'Generic Component',
            label: 'Elvis Discriminator Test',
            deviceNetworkId: 'mcp-virtual-test-102',
            typeName: 'My Custom Driver'
        )
        fakeDevice.metaClass.getDriverType = { -> fakeDriverType }
        childDevicesList << fakeDevice

        when:
        def response = mcpDriver.callTool('hub_list_devices', [filter: 'virtual'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success != false
        def d = inner.devices.find { it.id == '102' }
        d != null
        d.driverType == 'My Custom Driver'
        d.typeName   == 'My Custom Driver'

        cleanup:
        childDevicesList.removeAll { it.id == 102 }

        where:
        useGateways << [true, false]
    }

    // N.35 Elvis discriminator: typeName != name proves device.typeName ?: device.name returns typeName when non-null
    def "list_virtual_devices: device with typeName distinct from name returns typeName as driverType"() {
        // Invariant: the Elvis chain (device.typeName ?: device.name) returns typeName when it is
        // non-null AND differs from name. Every prior fixture had typeName == name or typeName == null,
        // leaving the discriminating branch unproven.
        given:
        def fakeDriverType = [namespace: 'my-ns']
        def fakeDevice = new support.TestDevice(
            id: 102,
            name: 'Generic Component',
            label: 'Elvis Discriminator Test',
            deviceNetworkId: 'mcp-virtual-test-102',
            typeName: 'My Custom Driver'
        )
        fakeDevice.metaClass.getDriverType = { -> fakeDriverType }
        childDevicesList << fakeDevice

        when:
        def result = script.toolListVirtualDevices([:])

        then:
        result.success != false
        def d = result.devices.find { it.id == '102' }
        d != null
        d.driverType == 'My Custom Driver'   // typeName ('My Custom Driver') wins over name ('Generic Component')
        d.typeName   == 'My Custom Driver'   // deprecated alias reflects same Elvis result

        cleanup:
        childDevicesList.removeAll { it.id == 102 }
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
            getCapabilities() >> []
            getSupportedCommands() >> []
            getSupportedAttributes() >> []
            currentValue(_) >> null
            updateDataValue(_, _) >> {}  // stub; not under test in this spec
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
            getCapabilities() >> []
            getSupportedCommands() >> []
            getSupportedAttributes() >> []
            currentValue(_) >> null
            updateDataValue(_, _) >> {}
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
