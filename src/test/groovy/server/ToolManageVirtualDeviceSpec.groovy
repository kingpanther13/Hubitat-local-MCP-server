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
        // typeName is a deprecated alias for driverType; both must be present and equal in create response
        result.device.typeName == result.device.driverType
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
        ex.message.contains('my-ns:My Driver')  // namespace:name echo present (parity with sibling specs)
    }

    def "create with customDriver: any hub exception always surfaces list_hub_drivers hint (fail-closed)"() {
        // Invariant: any addChildDevice exception on the customDriver path surfaces as IllegalArgumentException
        // with the list_hub_drivers hint regardless of the exception class or message text.
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
        // Invariant: built-in driver-not-found is a platform condition that throws RuntimeException,
        // NOT IllegalArgumentException. The distinction preserves the semantic that caller-fixable
        // errors (wrong args) become IAE, while platform/firmware gaps become RuntimeException.
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
        ex.message.contains('may not include this built-in driver')
    }

    // -------- toolListVirtualDevices --------

    def "list_virtual_devices: success path with custom-driver device returns driverNamespace and driverType"() {
        // Invariant: when getDriverType() returns a namespace-bearing object, driverNamespace is
        // populated from it and driverType/typeName both reflect the driver type name.
        given:
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
        def result = script.toolListVirtualDevices([:])

        then:
        result.success != false
        result.devices.size() >= 1
        def d = result.devices.find { it.id == '99' }
        d != null
        d.driverNamespace == 'level99-vesync'
        d.driverType      == 'Levoit Classic 200S Humidifier'
        d.typeName        == 'Levoit Classic 200S Humidifier'  // deprecated alias present in list response
        d.deviceNetworkId == 'mcp-virtual-test-99'

        cleanup:
        childDevicesList.removeAll { it.id == 99 }
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

    // -------- M3: cause-chain probe (two-arg IllegalArgumentException/RuntimeException ctors) --------

    def "create with customDriver: thrown IllegalArgumentException preserves cause chain"() {
        // Verifies that the two-arg constructor IllegalArgumentException(msg, e) preserves the
        // original exception as .cause. If the Hubitat sandbox strips cause chains, .cause would be
        // null; this spec detects that regression.
        given:
        enableHubAdminWrite()
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

    def "create with built-in: RuntimeException preserves cause chain"() {
        // Mirrors the customDriver cause-chain probe for the built-in path RuntimeException.
        given:
        enableHubAdminWrite()
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

    // -------- M6: blank deviceType + customDriver mutex, whitespace namespace/name, non-String --------

    def "create: blank-after-trim deviceType with customDriver present triggers mutex error"() {
        // Groovy truthiness: '  ' (whitespace) is truthy, so without explicit trimming
        // the dispatch would silently route to customDriver, hiding a caller ambiguity.
        // After trim '  ' becomes '' which is falsy -- but that alone would drop to the
        // "both provided" false branch and silently take customDriver. The guard must reject explicitly.
        given:
        enableHubAdminWrite()

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

    def "create: blank-after-trim deviceType without customDriver triggers missing-arg error"() {
        // A blank-after-trim deviceType with no customDriver should surface the "either required" error,
        // not a cryptic null/empty failure from deeper in the chain.
        given:
        enableHubAdminWrite()

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

    def "create: whitespace-only customDriver namespace throws descriptive error"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolCreateVirtualDevice([
            customDriver: [namespace: '   ', name: 'My Driver'],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('non-empty strings')
    }

    def "create: whitespace-only customDriver name throws descriptive error"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolCreateVirtualDevice([
            customDriver: [namespace: 'my-ns', name: '   '],
            deviceLabel: 'Test',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('non-empty strings')
    }

    def "create: non-String (numeric) customDriver namespace coerces then rejects if blank"() {
        // Non-String values (e.g. 123) coerce via toString() then trim() -- a numeric 123 is
        // non-blank so it gets passed through to the hub (which will reject it with a clear error);
        // a whitespace-only String gets caught here before reaching the hub.
        given:
        enableHubAdminWrite()
        def capturedNs = null
        def fakeDevice = Mock(me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper) {
            getId() >> '55'
            getIdAsLong() >> 55L
            getName() >> 'test-driver'
            getLabel() >> 'Coerce Test'
            getDeviceNetworkId() >> 'mcp-virtual-coerce'
            getCapabilities() >> []
            getSupportedCommands() >> []
            getSupportedAttributes() >> []
            currentValue(_) >> null
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

    // -------- M8: addChildDevice returns null --------

    def "create: addChildDevice returning null throws RuntimeException with partial-registration guidance"() {
        given:
        enableHubAdminWrite()
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
}
