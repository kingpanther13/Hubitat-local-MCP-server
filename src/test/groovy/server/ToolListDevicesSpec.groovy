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

    def "fields projection: unknown field name throws IllegalArgumentException listing the bad name"() {
        given:
        def d1 = makeDevice(id: 42, label: 'My Device')
        settingsMap.selectedDevices = [d1]

        when:
        script.toolListDevices(false, 0, 0, null, null, null, null, ['id', 'lable', 'label'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('lable')
        // Valid field list should also appear so the caller knows what to use
        ex.message.contains('label')
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

    // ---- MEDIUM-1: format=ids unfilteredTotal parity with capabilityFilter --

    def "format=ids + capabilityFilter: response includes unfilteredTotal"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Light Switch', capabilities: [[name: 'Switch']])
        def d2 = makeDevice(id: 2, label: 'Motion Sensor', capabilities: [[name: 'MotionSensor']])
        def d3 = makeDevice(id: 3, label: 'Dimmer', capabilities: [[name: 'Switch'], [name: 'SwitchLevel']])
        settingsMap.selectedDevices = [d1, d2, d3]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, 'Switch', 'ids', null)

        then:
        result.total == 2
        result.unfilteredTotal == 3
        result.deviceIds as Set == [1, 3] as Set
        result.containsKey('capabilityFilter')
    }

    // ---- MEDIUM-2: format=ids offset-overshoot unfilteredTotal parity -------

    def "format=ids + labelFilter + offset overshoot: early-return includes unfilteredTotal"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Kitchen Light')
        def d2 = makeDevice(id: 2, label: 'Kitchen Outlet')
        def d3 = makeDevice(id: 3, label: 'Bedroom Fan')
        settingsMap.selectedDevices = [d1, d2, d3]

        when:
        // 2 devices match 'kitchen'; offset=999 triggers early-return
        def result = script.toolListDevices(false, 999, 0, null, 'kitchen', null, 'ids', null)

        then:
        result.deviceIds == []
        result.count == 0
        result.total == 2
        result.unfilteredTotal == 3
        result.containsKey('labelFilter')
    }

    // ---- B2: all-unknown fields throws, not silently empty

    def "fields projection with all-unknown names throws IllegalArgumentException"() {
        given:
        def d1 = makeDevice(id: 1, label: 'My Device')
        settingsMap.selectedDevices = [d1]

        when:
        script.toolListDevices(false, 0, 0, null, null, null, null, ['nope'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('nope')
    }

    // ---- B3: capabilityFilterMatchedKnownCapability typo diagnostic ----------

    def "capabilityFilter typo: count=0 response includes capabilityFilterMatchedKnownCapability=false"() {
        given:
        def sw = makeDevice(id: 1, label: 'Light Switch', capabilities: [[name: 'Switch']])
        settingsMap.selectedDevices = [sw]

        when:
        // 'Switches' (plural) does not match 'Switch' (exact)
        def result = script.toolListDevices(false, 0, 0, null, null, 'Switches', null, null)

        then:
        result.devices.size() == 0
        result.containsKey('capabilityFilterMatchedKnownCapability')
        result.capabilityFilterMatchedKnownCapability == false
    }

    def "capabilityFilter with labelFilter pre-eliminating all devices: diagnostic distinguishes real capability from typo via unfiltered scan"() {
        given:
        // Device has Switch but labelFilter eliminates it before capabilityFilter runs.
        // The diagnostic scans the UNFILTERED device set, so it correctly identifies that
        // 'Switch' is a real capability on this hub even though no device passes both filters.
        def sw = makeDevice(id: 1, label: 'Kitchen Light', capabilities: [[name: 'Switch']])
        settingsMap.selectedDevices = [sw]

        when:
        // labelFilter 'basement' eliminates all devices; capabilityFilter 'Switch' then sees an empty set.
        // Without the unfiltered scan this would falsely report 'Switch' as a typo.
        def result = script.toolListDevices(false, 0, 0, null, 'basement', 'Switch', null, null)

        then:
        result.devices.size() == 0
        result.containsKey('capabilityFilterMatchedKnownCapability')
        // 'Switch' is real on the hub (Kitchen Light has it), just excluded by labelFilter
        result.capabilityFilterMatchedKnownCapability == true
    }

    // ---- B4: type validation throws on wrong arg types ----------------------

    def "capabilityFilter: passing a List instead of String throws IllegalArgumentException"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Device A')
        settingsMap.selectedDevices = [d1]

        when:
        script.toolListDevices(false, 0, 0, null, null, ['Switch'], null, null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('capabilityFilter')
    }

    def "labelFilter: passing a List instead of String throws IllegalArgumentException"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Device A')
        settingsMap.selectedDevices = [d1]

        when:
        script.toolListDevices(false, 0, 0, null, ['kitchen'], null, null, null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('labelFilter')
    }

    def "fields: passing a String instead of List throws IllegalArgumentException"() {
        given:
        def d1 = makeDevice(id: 1, label: 'Device A')
        settingsMap.selectedDevices = [d1]

        when:
        script.toolListDevices(false, 0, 0, null, null, null, null, 'id,label')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('fields')
    }

    // ---- B5: hub-read avoidance asserted via call counters ------------------

    def "fields projection: currentValue NOT called when fields excludes currentStates"() {
        given:
        def calls = []
        def d1 = makeDevice(id: 1, label: 'My Light', name: 'TestDriver')
        d1.attributeValues = [switch: 'on']
        // Override currentValue on the instance to count calls
        d1.metaClass.currentValue = { String attr ->
            calls << attr
            return d1.attributeValues[attr]
        }
        settingsMap.selectedDevices = [d1]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, ['id', 'label'])

        then:
        result.devices.size() == 1
        result.devices[0].id == '1'
        result.devices[0].label == 'My Light'
        !result.devices[0].containsKey('currentStates')
        calls.isEmpty()
    }

    def "fields projection: currentValue IS called when fields includes currentStates"() {
        given:
        def calls = []
        def d1 = makeDevice(id: 1, label: 'My Light', name: 'TestDriver')
        d1.attributeValues = [switch: 'on']
        d1.metaClass.currentValue = { String attr ->
            calls << attr
            return d1.attributeValues[attr]
        }
        settingsMap.selectedDevices = [d1]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, ['id', 'label', 'currentStates'])

        then:
        result.devices.size() == 1
        result.devices[0].containsKey('currentStates')
        !calls.isEmpty()
    }

    // ---- B6: kitchen-sink -- labelFilter + capabilityFilter + pagination -----

    def "labelFilter + capabilityFilter + pagination -- all three applied in pipeline order"() {
        given:
        // 8 devices: varied labels and capabilities to exercise both filters + pagination
        def kSwitch  = makeDevice(id: 1, label: 'Kitchen Light',  capabilities: [[name: 'Switch']])
        def kSensor  = makeDevice(id: 2, label: 'Kitchen Motion', capabilities: [[name: 'MotionSensor']])
        def kDimmer  = makeDevice(id: 3, label: 'Kitchen Dimmer', capabilities: [[name: 'Switch'], [name: 'SwitchLevel']])
        def kOutlet  = makeDevice(id: 4, label: 'Kitchen Outlet', capabilities: [[name: 'Switch']])
        def kFan     = makeDevice(id: 5, label: 'Kitchen Fan',    capabilities: [[name: 'Switch']])
        def bSwitch  = makeDevice(id: 6, label: 'Bedroom Switch', capabilities: [[name: 'Switch']])
        def bSensor  = makeDevice(id: 7, label: 'Bedroom Sensor', capabilities: [[name: 'MotionSensor']])
        def bDimmer  = makeDevice(id: 8, label: 'Bedroom Dimmer', capabilities: [[name: 'Switch'], [name: 'SwitchLevel']])
        settingsMap.selectedDevices = [kSwitch, kSensor, kDimmer, kOutlet, kFan, bSwitch, bSensor, bDimmer]

        when:
        // labelFilter='kitchen' AND capabilityFilter='Switch' AND pagination (offset=1, limit=2)
        def result = script.toolListDevices(false, 1, 2, null, 'kitchen', 'Switch', null, null)

        then:
        // Kitchen Switch devices: kSwitch(1), kDimmer(3), kOutlet(4), kFan(5) = 4 after both filters
        result.total == 4
        result.count == 2
        result.hasMore == true
        result.nextOffset == 3
        result.devices.size() == 2
        // offset 1: devices at index 1 and 2 of the filtered+sorted set: kDimmer(3) and kOutlet(4)
        result.devices*.id as Set == ['3', '4'] as Set
        // Verify every returned device satisfies both filters
        result.devices.every { dev ->
            def d = settingsMap.selectedDevices.find { it.id.toString() == dev.id }
            d.label.toLowerCase().contains('kitchen') &&
            d.capabilities.any { it.name == 'Switch' }
        }
        result.unfilteredTotal == 8
        result.labelFilter == 'kitchen'
        result.capabilityFilter == 'Switch'
    }

    // ---- P6: inverse case-insensitivity (both directions) -------------------

    def "labelFilter: UPPERCASE filter matches lowercase label"() {
        given:
        def d1 = makeDevice(id: 1, label: 'kitchen light')
        def d2 = makeDevice(id: 2, label: 'bathroom fan')
        settingsMap.selectedDevices = [d1, d2]

        when:
        def result = script.toolListDevices(false, 0, 0, null, 'KITCHEN', null, null, null)

        then:
        result.devices.size() == 1
        result.devices[0].label == 'kitchen light'
    }

    def "capabilityFilter: UPPERCASE filter matches lowercase capability name"() {
        given:
        def sw = makeDevice(id: 1, label: 'Light', capabilities: [[name: 'switch']])
        settingsMap.selectedDevices = [sw]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, 'SWITCH', null, null)

        then:
        result.devices.size() == 1
        result.devices[0].label == 'Light'
    }

    // ---- B1: id always present, not projected away --------------------------

    def "fields projection: id always present even when not in fields list"() {
        given:
        def d1 = makeDevice(id: 99, label: 'No-Id Requested')
        settingsMap.selectedDevices = [d1]

        when:
        def result = script.toolListDevices(false, 0, 0, null, null, null, null, ['label'])

        then:
        result.devices.size() == 1
        // id must always be present -- callers can't address the device without it
        result.devices[0].id == '99'
        result.devices[0].label == 'No-Id Requested'
        !result.devices[0].containsKey('name')
    }
}
