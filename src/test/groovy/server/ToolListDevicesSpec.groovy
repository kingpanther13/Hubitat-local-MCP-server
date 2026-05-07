package server

import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for toolListDevices (hubitat-mcp-server.groovy).
 *
 * Covers: labelFilter, capabilityFilter, format=ids, fields projection, and
 * composition with each other and with existing pagination args.
 *
 * The base fixture provides settingsMap.selectedDevices (empty) and
 * childDevicesList (also empty). Tests seed devices via both mechanisms to
 * exercise the deduplication path in the implementation.
 */
class ToolListDevicesSpec extends ToolSpecBase {

    // ---- helpers --------------------------------------------------------

    private TestDevice makeDevice(Map props) {
        new TestDevice(
            id: props.id ?: 1,
            name: props.name ?: 'TestDriver',
            label: props.label ?: props.name ?: 'Test Device',
            roomName: props.room ?: null,
            capabilities: props.capabilities ?: [],
            supportedAttributes: props.attrs ?: [],
            supportedCommands: props.cmds ?: [],
            attributeValues: props.attrValues ?: [:]
        )
    }

    // ---- labelFilter tests ----------------------------------------------

    def "labelFilter: returns only devices whose label contains the substring"() {
        given:
        def kitchen = makeDevice(id: 1, label: 'Kitchen Light')
        def bath    = makeDevice(id: 2, label: 'Bathroom Fan')
        def office  = makeDevice(id: 3, label: 'Office Lamp')
        settingsMap.selectedDevices = [kitchen, bath, office]

        when:
        def result = script.toolListDevices(false, 0, 0, null, 'kitchen', null, null, null)

        then:
        result.devices.size() == 1
        result.devices[0].label == 'Kitchen Light'
        result.total == 1
    }

    def "labelFilter: case-insensitive -- lowercase filter matches mixed-case label"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Kitchen Light')
        def d2 = makeDevice(id: 2, label: 'Bathroom Fan')
        settingsMap.selectedDevices = [d1, d2]

        when:
        def result = script.toolListDevices(false, 0, 0, null, 'KITCHEN', null, null, null)

        then:
        result.devices.size() == 1
        result.devices[0].label == 'Kitchen Light'
    }

    def "labelFilter: null means no label filtering -- all devices returned"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Light A')
        def d2 = makeDevice(id: 2, label: 'Light B')
        settingsMap.selectedDevices = [d1, d2]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null)

        then:
        result.devices.size() == 2
    }

    def "labelFilter: empty string means no label filtering"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Light A')
        def d2 = makeDevice(id: 2, label: 'Light B')
        settingsMap.selectedDevices = [d1, d2]

        when:
        def result = script.toolListDevices(false, 0, 0, null, '', null, null, null)

        then:
        result.devices.size() == 2
    }

    def "labelFilter: no match returns empty device list"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Living Room Light')
        settingsMap.selectedDevices = [d1]

        when:
        def result = script.toolListDevices(false, 0, 0, null, 'basement', null, null, null)

        then:
        result.devices.size() == 0
        result.total == 0
    }

    // ---- capabilityFilter tests -----------------------------------------

    def "capabilityFilter: returns only devices with the matching capability"() {
        given:
        def sw     = makeDevice(id: 1, label: 'Light Switch', capabilities: [[name: 'Switch']])
        def motion = makeDevice(id: 2, label: 'Motion Sensor', capabilities: [[name: 'MotionSensor']])
        def dimmer = makeDevice(id: 3, label: 'Dimmer', capabilities: [[name: 'Switch'], [name: 'SwitchLevel']])
        settingsMap.selectedDevices = [sw, motion, dimmer]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, 'Switch', null, null)

        then:
        result.devices.size() == 2
        result.devices*.label as Set == ['Light Switch', 'Dimmer'] as Set
    }

    def "capabilityFilter: case-insensitive -- 'switch' matches 'Switch'"() {
        given:
        def sw     = makeDevice(id: 1, label: 'Light Switch', capabilities: [[name: 'Switch']])
        def motion = makeDevice(id: 2, label: 'Motion Sensor', capabilities: [[name: 'MotionSensor']])
        settingsMap.selectedDevices = [sw, motion]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, 'switch', null, null)

        then:
        result.devices.size() == 1
        result.devices[0].label == 'Light Switch'
    }

    def "capabilityFilter: null means no capability filtering"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Light', capabilities: [[name: 'Switch']])
        def d2 = makeDevice(id: 2, label: 'Sensor', capabilities: [[name: 'TemperatureMeasurement']])
        settingsMap.selectedDevices = [d1, d2]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, null)

        then:
        result.devices.size() == 2
    }

    // ---- format=ids tests -----------------------------------------------

    def "format=ids: returns flat deviceIds array"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Device A')
        def d2 = makeDevice(id: 2, label: 'Device B')
        def d3 = makeDevice(id: 3, label: 'Device C')
        settingsMap.selectedDevices = [d1, d2, d3]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, 'ids', null)

        then:
        result.deviceIds == [1, 2, 3]
        result.count == 3
        result.total == 3
        // No 'devices' key -- result shape changes for ids
        !result.containsKey('devices')
    }

    def "format=ids combined with labelFilter narrows the ID list"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Kitchen Light')
        def d2 = makeDevice(id: 2, label: 'Bathroom Fan')
        def d3 = makeDevice(id: 3, label: 'Kitchen Outlet')
        settingsMap.selectedDevices = [d1, d2, d3]

        when:
        def result = script.toolListDevices(false, 0, 0, null, 'kitchen', null, 'ids', null)

        then:
        result.deviceIds == [1, 3]
        result.count == 2
        result.total == 2
    }

    // ---- fields projection tests ----------------------------------------

    def "fields projection: only requested fields appear in each device object"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Test Light', name: 'GenericZWaveSwitch')
        settingsMap.selectedDevices = [d1]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, ['id', 'label'])

        then:
        result.devices.size() == 1
        def dev = result.devices[0]
        dev.id == '1'
        dev.label == 'Test Light'
        // Fields not in the projection must be absent
        !dev.containsKey('name')
        !dev.containsKey('room')
        !dev.containsKey('disabled')
        !dev.containsKey('currentStates')
    }

    def "fields projection: fields=['capabilities'] with detailed=true returns capabilities only"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Smart Switch', capabilities: [[name: 'Switch'], [name: 'Refresh']])
        settingsMap.selectedDevices = [d1]

        when:
        // detailed=true with field projection: capabilities present, attributes and commands absent
        def result = script.toolListDevices(true, 0, 0, null, null, null, null, ['id', 'capabilities'])

        then:
        result.devices.size() == 1
        def dev = result.devices[0]
        dev.capabilities != null
        dev.capabilities.contains('Switch')
        !dev.containsKey('attributes')
        !dev.containsKey('commands')
    }

    def "fields projection: unknown field names are silently ignored"() {
        given:
        def d1 = makeDevice(id: 42, label: 'My Device')
        settingsMap.selectedDevices = [d1]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, ['id', 'nonexistent_field', 'label'])

        then:
        result.devices.size() == 1
        def dev = result.devices[0]
        dev.id == '42'
        dev.label == 'My Device'
        !dev.containsKey('nonexistent_field')
    }

    def "fields projection: empty fields list returns all default fields"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Full Device')
        settingsMap.selectedDevices = [d1]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, [])

        then:
        result.devices.size() == 1
        def dev = result.devices[0]
        // All default summary fields should be present
        dev.containsKey('id')
        dev.containsKey('label')
        dev.containsKey('name')
        dev.containsKey('room')
        dev.containsKey('disabled')
        dev.containsKey('currentStates')
    }

    // ---- format validation test -----------------------------------------

    def "format=xml throws IllegalArgumentException with valid values listed"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Device A')
        settingsMap.selectedDevices = [d1]

        when:
        script.toolListDevices(false, 0, 0, null, null, null, 'xml', null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('xml')
        ex.message.contains('summary')
        ex.message.contains('detailed')
        ex.message.contains('ids')
    }

    // ---- composition tests ----------------------------------------------

    def "labelFilter + capabilityFilter: both applied, only intersection returned"() {
        given:
        def kitchenSwitch  = makeDevice(id: 1, label: 'Kitchen Light', capabilities: [[name: 'Switch']])
        def kitchenSensor  = makeDevice(id: 2, label: 'Kitchen Motion', capabilities: [[name: 'MotionSensor']])
        def bedroomSwitch  = makeDevice(id: 3, label: 'Bedroom Light', capabilities: [[name: 'Switch']])
        settingsMap.selectedDevices = [kitchenSwitch, kitchenSensor, bedroomSwitch]

        when:
        def result = script.toolListDevices(false, 0, 0, null, 'kitchen', 'Switch', null, null)

        then:
        result.devices.size() == 1
        result.devices[0].id == '1'
    }

    def "labelFilter + pagination: offset/limit applied to already-filtered set"() {
        given:
        // 4 devices with 'light' in label; request page of 2 starting at offset 1
        def d1 = makeDevice(id: 1, label: 'Light A')
        def d2 = makeDevice(id: 2, label: 'Light B')
        def d3 = makeDevice(id: 3, label: 'Light C')
        def d4 = makeDevice(id: 4, label: 'Light D')
        def d5 = makeDevice(id: 5, label: 'Fan')
        settingsMap.selectedDevices = [d1, d2, d3, d4, d5]

        when:
        def result = script.toolListDevices(false, 1, 2, null, 'light', null, null, null)

        then:
        result.total == 4         // 4 devices match the label filter
        result.count == 2         // page of 2
        result.devices[0].id == '2'
        result.devices[1].id == '3'
        result.hasMore == true
        result.nextOffset == 3
    }

    // ---- backward-compat / default behavior tests -----------------------

    def "no new args: existing args still produce the same response shape"() {
        given:
        def d1 = makeDevice(id: 10, label: 'Compat Light')
        settingsMap.selectedDevices = [d1]

        when:
        // Call with only original args (no new args supplied)
        def result = script.toolListDevices(false, 0, 0, null)

        then:
        result.devices.size() == 1
        result.devices[0].label == 'Compat Light'
        result.devices[0].containsKey('currentStates')
        !result.containsKey('labelFilter')
        !result.containsKey('capabilityFilter')
    }

    def "format=ids with pagination includes hasMore and nextOffset"() {
        given:
        (1..5).each { i ->
            settingsMap.selectedDevices << makeDevice(id: i, label: "Device ${i}")
        }

        when:
        def result = script.toolListDevices(false, 0, 3, null, null, null, 'ids', null)

        then:
        result.deviceIds.size() == 3
        result.hasMore == true
        result.nextOffset == 3
    }

    // ---- BLOCKING-1: fields auto-promote to detailed mode ----------------

    def "fields=['id','capabilities'] without detailed=true still returns capabilities"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Smart Switch', capabilities: [[name: 'Switch'], [name: 'Refresh']])
        settingsMap.selectedDevices = [d1]

        when:
        // detailed=false (default), format omitted -- auto-promote must fire
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, ['id', 'capabilities'])

        then:
        result.devices.size() == 1
        def dev = result.devices[0]
        dev.capabilities != null
        dev.capabilities.contains('Switch')
        // id requested and present
        dev.id == '1'
        // attributes and commands were not requested -- absent
        !dev.containsKey('attributes')
        !dev.containsKey('commands')
        // currentStates was not requested -- absent
        !dev.containsKey('currentStates')
    }

    // ---- WARN-2: offset overshoot with format=ids returns ids shape ------

    def "format=ids with offset beyond total returns deviceIds shape not devices shape"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Device A')
        settingsMap.selectedDevices = [d1]

        when:
        def result = script.toolListDevices(false, 999, 0, null, null, null, 'ids', null)

        then:
        result.containsKey('deviceIds')
        result.deviceIds == []
        result.count == 0
        result.total == 1
        !result.containsKey('devices')
    }

    // ---- WARN-3: format validation fires before offset early-return ------

    def "invalid format with offset overshoot throws IllegalArgumentException not silent early-return"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Device A')
        settingsMap.selectedDevices = [d1]

        when:
        // offset=999 would trigger early-return before format validation in the old ordering
        script.toolListDevices(false, 999, 0, null, null, null, 'xml', null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('xml')
        ex.message.contains('summary')
    }

    // ---- WARN-5: unfilteredTotal present with labelFilter only -----------

    def "labelFilter only: response includes unfilteredTotal"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Kitchen Light')
        def d2 = makeDevice(id: 2, label: 'Kitchen Outlet')
        def d3 = makeDevice(id: 3, label: 'Bedroom Light')
        settingsMap.selectedDevices = [d1, d2, d3]

        when:
        def result = script.toolListDevices(false, 0, 0, null, 'kitchen', null, null, null)

        then:
        result.total == 2
        result.unfilteredTotal == 3
    }

    // ---- WARN-6: all-unknown fields projection returns empty device objects

    def "fields projection with all-unknown names produces empty device objects"() {
        given:
        def d1 = makeDevice(id: 1, label: 'My Device')
        settingsMap.selectedDevices = [d1]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, ['nope'])

        then:
        result.devices.size() == 1
        // All-unknown projection: no recognized field was requested, so the object is empty
        result.devices[0].isEmpty()
    }
}
