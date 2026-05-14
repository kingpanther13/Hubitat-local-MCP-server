package server

import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppScript
import support.ToolSpecBase

/**
 * Spec for manage_virtual_device (create path) in hubitat-mcp-server.groovy.
 *
 * Covers:
 *   - Dispatch validation: mutually exclusive deviceType / customDriver, both missing
 *   - customDriver shape validation: non-Map, missing namespace, missing name
 *   - Built-in deviceType path: success + unsupported type rejection
 *   - customDriver path: success (addChildDevice gets correct namespace + name)
 *   - customDriver not-found error translates to list_hub_drivers hint
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
 * requireHubAdminWrite seeding:
 *   settingsMap.enableHubAdminWrite = true
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

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec fixed now()
    }

    // -------- Safety gate --------

    def "create fails when Hub Admin Write is disabled"() {
        // Default settingsMap seeds only [selectedDevices:[]] -- gate is off without enableHubAdminWrite()
        when:
        script.toolCreateVirtualDevice([
            deviceType: 'Virtual Switch',
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    // -------- Dispatch validation: mutually exclusive / both missing / missing deviceLabel --------

    def "create rejects when both deviceType and customDriver are provided"() {
        given:
        enableHubAdminWrite()

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

    def "create rejects when neither deviceType nor customDriver is provided"() {
        given:
        enableHubAdminWrite()

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

    def "dispatch rejects create with missing deviceLabel"() {
        given:
        enableHubAdminWrite()

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

    // -------- customDriver shape validation --------

    def "create with customDriver not a Map throws"() {
        given:
        enableHubAdminWrite()

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

    def "create with customDriver missing namespace throws"() {
        given:
        enableHubAdminWrite()

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

    def "create with customDriver missing name throws"() {
        given:
        enableHubAdminWrite()

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

    // -------- customDriver success path --------

    def "create with customDriver delegates to addChildDevice with correct namespace and name"() {
        given:
        enableHubAdminWrite()
        def capturedArgs = [:]
        // ChildDeviceWrapper mock satisfies the HubitatAppScript castToType check in addChildDevice
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
        result.success == true
        result.device.id == '77'
        result.device.driverNamespace == 'level99-vesync'
        result.device.driverType      == 'Levoit Classic 200S Humidifier'
        !result.device.containsKey('typeName')  // typeName removed from create response; use driverType
    }

    // -------- customDriver not-found error path --------

    def "create with customDriver: UnknownDeviceTypeException translates to list_hub_drivers hint"() {
        given:
        enableHubAdminWrite()
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
        ex.message.contains('list_hub_drivers')
    }

    def "create with customDriver: not-found error (message 'not found') also translates"() {
        given:
        enableHubAdminWrite()
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
        ex.message.contains('list_hub_drivers')
    }

    def "create with customDriver: any hub exception always surfaces list_hub_drivers hint (fail-closed)"() {
        // Pins B4: fail-closed routing -- any exception on the customDriver path becomes an
        // IllegalArgumentException with a list_hub_drivers hint, regardless of the hub's error text.
        given:
        enableHubAdminWrite()
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
        ex.message.contains('list_hub_drivers')
        ex.message.contains('my-ns:My Driver')
    }

    // -------- Built-in deviceType path (regression pin) --------

    def "create with built-in deviceType still works after dual-path refactor"() {
        given:
        enableHubAdminWrite()
        def capturedArgs = [:]
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
        result.success == true
        result.device.id == '42'
        result.device.driverNamespace == 'hubitat'
        result.device.driverType      == 'Virtual Switch'
    }

    def "create with unsupported built-in deviceType throws descriptive error"() {
        given:
        enableHubAdminWrite()

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

    def "create with built-in deviceType: UnknownDeviceTypeException stays RuntimeException with firmware hint"() {
        // Pins B2: built-in not-found throws RuntimeException (platform condition), NOT IllegalArgumentException
        // (which would misclassify the hub firmware gap as a caller error).
        given:
        enableHubAdminWrite()
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
        ex.message.contains('hub firmware')
    }
}
