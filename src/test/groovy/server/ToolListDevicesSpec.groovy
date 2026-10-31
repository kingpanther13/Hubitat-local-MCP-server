package server

import groovy.json.JsonOutput
import java.util.concurrent.atomic.AtomicLong
import spock.lang.Shared
import spock.lang.Unroll
import support.NativeInventoryFixture
import support.TestDevice
import support.TestLocation
import support.ToolSpecBase

/**
 * hub_list_devices (toolListDevices in McpDevicesLib): every listing is served from ONE read of the
 * hub's Devices page (/hub2/devicesList), plus the capability feed (/hub2/vrb/devices) when
 * capabilities are needed. Only detailed records read /device/fullJson, for the devices on the page.
 *
 * The page fixture lists the selected devices, then the MCP children, then `hubOnly` (devices on the
 * hub that MCP is not authorized for), in that order.
 */
class ToolListDevicesSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    private List hubOnly = []

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hsmStatus = null
        hubOnly = []
        NativeInventoryFixture.registerDevicesPage(hubGet) { -> (settingsMap.selectedDevices ?: []) + childDevicesList + hubOnly }
    }

    private TestDevice makeDevice(Map props) {
        def device = new TestDevice(
            id: props.id ?: 1,
            name: props.name ?: 'TestDriver',
            label: props.containsKey('label') ? props.label : 'Test Device',
            roomName: props.room ?: null,
            deviceNetworkId: props.dni ?: null,
            capabilities: props.capabilities ?: [],
            supportedCommands: props.cmds ?: [],
            attributeValues: props.attrValues ?: [:],
            lastActivity: props.lastActivity ?: null,
            disabled: props.disabled
        )
        NativeInventoryFixture.register(hubGet, device.id.toString()) { device }
        return device
    }

    private List fullJsonReads() { hubGet.calls.findAll { it.path.startsWith('/device/fullJson/') }*.path }

    // ---- summary record ---------------------------------------------------

    def "summary lists the selection and the app's children from one page read, with no per-device reads"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, name: 'Generic Zigbee Bulb', label: 'Kitchen Light', room: 'Kitchen',
            dni: 'ABCD', attrValues: [switch: 'on', level: 40, '_1': 'tile'])]
        childDevicesList << makeDevice(id: 2, name: 'Virtual Switch', label: null)

        when:
        def result = script.toolListDevices([:])

        then:
        result.total == 2
        result.count == 2
        !result.containsKey('unfilteredTotal')
        !result.containsKey('nextCursor')
        result.devices[0] == [id: '1', label: 'Kitchen Light', name: 'Generic Zigbee Bulb', driver: 'Generic Zigbee Bulb', room: 'Kitchen',
            disabled: false, deviceNetworkId: 'ABCD', lastActivity: null, protocol: 'virtual',
            currentStates: [switch: 'on', level: '40']]
        result.devices[1].label == 'Virtual Switch'
        result.devices[1].name == 'Virtual Switch'
        result.devices[1].mcpManaged == true
        result.devices[1].currentStates == [:]

        and: 'one page read and nothing else'
        hubGet.calls*.path == ['/hub2/devicesList']
    }

    def "page fields map onto the record: children carry parentDeviceId, Hub Mesh links and tags ride along"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubGet.register('/hub2/devicesList') {
            JsonOutput.toJson([devices: [
                [key: 'DEV-10', data: [id: 10, name: 'Hub Bridge', secondaryName: 'Bridge Driver', type: 'Bridge', isNetwork: true,
                    isLinked: true, tags: 'lights, upstairs', roomName: '', lastActivity: '2026-10-20T18:05:27+0000',
                    currentStates: [[key: 'switch', value: 'on'], [key: 'switch', value: 'off']]],
                 children: [[key: 'DEV-11', data: [id: 11, name: 'Bridge Child', type: 'Child', isZwave: true, disabled: true], children: []]]]
            ]])
        }

        when:
        def result = script.toolListDevices([:])

        then: 'depth-first page order'
        result.devices*.id == ['10', '11']

        and:
        with(result.devices[0]) {
            label == 'Hub Bridge'
            name == 'Bridge Driver'
            driver == 'Bridge'
            room == null
            protocol == 'network'
            linked == true
            tags == ['lights', 'upstairs']
            lastActivity == '2026-10-20T18:05:27+0000'
            currentStates == [switch: 'off']
            !containsKey('parentDeviceId')
        }
        with(result.devices[1]) {
            parentDeviceId == '10'
            protocol == 'zwave'
            disabled == true
            !containsKey('linked')
            !containsKey('tags')
        }
    }

    def "the allowlist bypass lists every page device under the default scope"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubOnly = [makeDevice(id: 7, label: 'Garage'), makeDevice(id: 8, label: 'Porch')]

        when:
        def result = script.toolListDevices([:])

        then:
        result.devices*.id == ['7', '8']
    }

    def "a selected device the page no longer lists is not invented"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, label: 'Kept')]
        def gone = makeDevice(id: 2, label: 'Deleted')
        hubGet.register('/hub2/devicesList') { JsonOutput.toJson([devices: [NativeInventoryFixture.pageNode(settingsMap.selectedDevices[0])]]) }
        settingsMap.selectedDevices << gone

        when:
        def result = script.toolListDevices([:])

        then:
        result.devices*.id == ['1']
    }

    // ---- filters --------------------------------------------------------

    def "filters apply in order before paging and each echoes, with unfilteredTotal over the scope"() {
        given:
        settingsMap.selectedDevices = [
            makeDevice(id: 1, label: 'Kitchen Light', room: 'Kitchen', capabilities: [[name: 'Switch']], attrValues: [switch: 'on']),
            makeDevice(id: 2, label: 'Kitchen Fan', room: 'Kitchen', capabilities: [[name: 'Switch']], attrValues: [switch: 'off']),
            makeDevice(id: 3, label: 'kitchen sensor', room: 'kitchen', capabilities: [[name: 'MotionSensor']]),
            makeDevice(id: 4, label: 'Porch Light', room: 'Porch', capabilities: [[name: 'Switch']], attrValues: [switch: 'on'])
        ]

        when:
        def result = script.toolListDevices([labelFilter: 'KITCHEN', capabilityFilter: 'switch', roomFilter: 'KITCHEN', stateFilter: [switch: 'on']])

        then:
        result.devices*.id == ['1']
        result.total == 1
        result.unfilteredTotal == 4
        result.labelFilter == 'KITCHEN'
        result.capabilityFilter == 'switch'
        result.roomFilter == 'KITCHEN'
        result.stateFilter == [switch: 'on']
        !fullJsonReads()
    }

    @Unroll
    def "#label"() {
        given:
        settingsMap.selectedDevices = [
            makeDevice(id: 1, label: 'A', room: 'Den', disabled: true),
            makeDevice(id: 2, label: 'B', room: 'Den', disabled: false),
            makeDevice(id: 3, label: 'C')
        ]

        when:
        def result = script.toolListDevices(args)

        then:
        result.devices*.id == ids
        result.containsKey('unfilteredTotal') == filtered

        where:
        label                                   | args                  || ids               | filtered
        'disabled=true keeps only disabled'     | [disabled: true]      || ['1']             | true
        'disabled=false keeps only enabled'     | [disabled: false]     || ['2', '3']        | true
        'an empty roomFilter is a no-op'        | [roomFilter: '']      || ['1', '2', '3']   | false
        'an empty labelFilter is a no-op'       | [labelFilter: '']     || ['1', '2', '3']   | false
        'an empty stateFilter is a no-op'       | [stateFilter: [:]]    || ['1', '2', '3']   | false
    }

    def "stateFilter needs every named attribute to read its value, compared as the page string"() {
        given:
        settingsMap.selectedDevices = [
            makeDevice(id: 1, attrValues: [switch: 'on', level: 50]),
            makeDevice(id: 2, attrValues: [switch: 'on', level: 20]),
            makeDevice(id: 3, attrValues: [level: 50])
        ]

        expect:
        script.toolListDevices([stateFilter: [switch: 'on', level: 50]]).devices*.id == ['1']
    }

    def "staleHours keeps devices quiet that long and never-active ones; changedSince keeps the recently active"() {
        given:
        long nowMs = script.now()
        settingsMap.selectedDevices = [
            makeDevice(id: 1, lastActivity: new Date(nowMs - 48 * 3600000L)),
            makeDevice(id: 2, lastActivity: new Date(nowMs - 3600000L)),
            makeDevice(id: 3)
        ]

        when:
        def stale = script.toolListDevices([staleHours: 24])
        def changed = script.toolListDevices([changedSince: nowMs - 2 * 3600000L])

        then:
        stale.devices*.id == ['1', '3']
        stale.staleHours == 24
        changed.devices*.id == ['2']

        and: 'epoch input echoes as canonical ISO'
        changed.changedSince ==~ /\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}[+-]\d{4}/
    }

    def "a returned lastActivity round-trips into changedSince"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, lastActivity: new Date(script.now() - 60000L)), makeDevice(id: 2)]
        def stamp = script.toolListDevices([:]).devices[0].lastActivity

        expect:
        script.toolListDevices([changedSince: stamp]).devices*.id == ['1']
        script.toolListDevices([changedSince: stamp]).changedSince == stamp
    }

    def "zero matches tell a typo from an empty result, over the unfiltered scope"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, label: 'Lamp', room: 'Den', capabilities: [[name: 'Switch']])]

        expect:
        script.toolListDevices([capabilityFilter: 'Swich']).capabilityFilterMatchedKnownCapability == false
        script.toolListDevices([labelFilter: 'none', capabilityFilter: 'Switch']).capabilityFilterMatchedKnownCapability == true
        script.toolListDevices([roomFilter: 'Dne']).roomFilterMatchedKnownRoom == false
        script.toolListDevices([labelFilter: 'none', roomFilter: 'den']).roomFilterMatchedKnownRoom == true
        !script.toolListDevices([roomFilter: 'Den']).containsKey('roomFilterMatchedKnownRoom')
    }

    def "an unreadable capability feed is reported and capabilityFilter matches nothing"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, capabilities: [[name: 'Switch']])]
        hubGet.register('/hub2/vrb/devices') { throw new IOException('down') }

        when:
        def result = script.toolListDevices([capabilityFilter: 'Switch'])

        then:
        result.devices == []
        result.capabilitiesUnavailable == true
        result.note.contains('/hub2/vrb/devices')
        !result.containsKey('capabilityFilterMatchedKnownCapability')
    }

    // ---- projection -------------------------------------------------------

    def "fields projects the record, id is always kept, and page-only fields need no device read"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, label: 'Lamp', room: 'Den', capabilities: [[name: 'Switch']])]

        when:
        def result = script.toolListDevices([fields: ['label', 'roomId', 'notes', 'source', 'capabilities']])

        then:
        result.devices == [[id: '1', label: 'Lamp', roomId: null, notes: null, source: null, capabilities: ['Switch']]]
        !fullJsonReads()
    }

    def "an empty fields list is the default record"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1)]

        expect:
        script.toolListDevices([fields: []]).devices == script.toolListDevices([:]).devices
    }

    def "unknown fields are refused with the valid names"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1)]

        when:
        script.toolListDevices([fields: ['id', 'lable']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('lable')
        ex.message.contains('deviceNetworkId')
    }

    def "attributeNames picks currentStates attributes in caller order; [] is the default set"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, attrValues: [switch: 'on', level: 5, colorName: 'Red'])]

        expect:
        script.toolListDevices([attributeNames: ['colorName', 'switch']]).devices[0].currentStates == [colorName: 'Red', switch: 'on']
        script.toolListDevices([attributeNames: []]).devices[0].currentStates == [switch: 'on', level: '5']
    }

    def "credential-named attributes are masked when credential redaction is on"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, attrValues: [apiToken: 'abc123', switch: 'on'])]

        when:
        def masked = script.toolListDevices([attributeNames: ['apiToken', 'switch']])
        settingsMap.redactCredentials = false
        def plain = script.toolListDevices([attributeNames: ['apiToken']])

        then:
        masked.devices[0].currentStates == [apiToken: script._appConfigRedactedMarker(), switch: 'on']
        plain.devices[0].currentStates == [apiToken: 'abc123']
    }

    // ---- detailed -------------------------------------------------------

    def "detailed reads each listed device for typed attributes, commands and driverNamespace"() {
        given:
        def lamp = makeDevice(id: 1, label: 'Lamp', capabilities: [[name: 'Switch'], [name: 'SwitchLevel']], attrValues: [switch: 'on', level: 40])
        settingsMap.selectedDevices = [lamp]
        hubGet.register('/device/fullJson/1') {
            JsonOutput.toJson([device: [id: 1, name: 'Dimmer', label: 'Lamp', deviceTypeNamespace: 'hubitat', capabilities: ['Switch'],
                currentStates: [switch: [value: 'on'], level: [value: '40', dataType: 'NUMBER', numberValue: 40, unit: '%']]],
                commands: [[name: 'setLevel', parameters: [[name: 'level', type: 'NUMBER']]], [name: 'on']]])
        }

        when:
        def result = script.toolListDevices([format: 'detailed'])

        then:
        with(result.devices[0]) {
            capabilities == ['Switch', 'SwitchLevel']
            attributes.find { it.name == 'level' }.value == 40
            attributes.find { it.name == 'level' }.unit == '%'
            !attributes.find { it.name == 'switch' }.containsKey('unit')
            commands == [[name: 'setLevel', arguments: [[name: 'level', type: 'NUMBER']]], [name: 'on', arguments: null]]
            driverNamespace == 'hubitat'
            !containsKey('currentStates')
        }
        fullJsonReads() == ['/device/fullJson/1']
    }

    def "fields naming attributes reads only the devices on the page"() {
        given:
        settingsMap.selectedDevices = (1..5).collect { makeDevice(id: it, attrValues: [switch: 'on']) }

        when:
        def result = script.toolListDevices([fields: ['attributes'], limit: 2])

        then:
        result.devices*.id == ['1', '2']
        result.devices[0].attributes == [[name: 'switch', dataType: null, value: 'on']]
        fullJsonReads() == ['/device/fullJson/1', '/device/fullJson/2']
    }

    def "a device whose detail read fails is still listed, marked, and the response is partial"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1), makeDevice(id: 2)]
        hubGet.register('/device/fullJson/2') { throw new IOException('timeout') }

        when:
        def result = script.toolListDevices([format: 'detailed'])

        then:
        result.devices*.id == ['1', '2']
        !result.devices[0].containsKey('metadataUnavailable')
        result.devices[1].metadataUnavailable == true
        result.devices[1].label == 'Test Device'
        result.partial == true
    }

    // ---- ids + context ----------------------------------------------------

    def "format=ids returns integer ids, filtered and paged"() {
        given:
        settingsMap.selectedDevices = (1..4).collect { makeDevice(id: it, label: it % 2 ? 'Odd' : 'Even') }

        when:
        def result = script.toolListDevices([format: 'ids', labelFilter: 'odd', limit: 1])

        then:
        result.deviceIds == [1]
        result.count == 1
        result.total == 2
        result.unfilteredTotal == 4
        result.hasMore == true
        result.nextCursor == '1'
        !result.containsKey('devices')
    }

    def "format=context is a self-contained text snapshot with page values and feed capabilities"() {
        given:
        sharedLocation.hsmStatus = 'armedAway'
        settingsMap.selectedDevices = [
            makeDevice(id: 1, label: 'Kitchen Light', room: 'Kitchen', capabilities: [[name: 'Switch'], [name: 'SwitchLevel']],
                attrValues: [switch: 'on', level: 80, temperature: 72.5]),
            makeDevice(id: 2, label: 'Roomless Plug', capabilities: [[name: 'Switch']], attrValues: [switch: 'off'])
        ]

        when:
        def result = script.toolListDevices([format: 'context'])

        then:
        result.mode == 'Home'
        result.hsmStatus == 'armedAway'
        result.count == 2
        result.total == 2
        def lines = result.summary.readLines()
        lines[0] == 'Mode: Home'
        lines[1] == 'HSM: armedAway'
        lines[2] == 'Devices: 2 of 2'
        lines[3] == '- Kitchen Light (1, Kitchen) - Switch, SwitchLevel; switch=on, level=80, temperature=72.5'
        lines[4] == '- Roomless Plug (2, No room) - Switch; switch=off'
        !fullJsonReads()
    }

    def "format=context pages at 50 by default and names the next cursor in its header"() {
        given:
        settingsMap.selectedDevices = (1..60).collect { makeDevice(id: it, label: "Device-${it}") }

        when:
        def first = script.toolListDevices([format: 'context'])
        def second = script.toolListDevices([format: 'context', cursor: first.nextCursor])

        then:
        first.count == 50
        first.total == 60
        first.nextCursor == '50'
        first.summary.contains("Devices: 50 of 60 (nextCursor=50 -- pass cursor:'50' for the next page)")
        second.count == 10
        !second.containsKey('nextCursor')
    }

    def "an explicit attribute projection that matches nothing in context says so"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, attrValues: [switch: 'on'])]

        expect:
        script.toolListDevices([format: 'context', attributeNames: ['temp']]).attributeNamesMatchedNoAttributes == true
        !script.toolListDevices([format: 'context', attributeNames: ['switch']]).containsKey('attributeNamesMatchedNoAttributes')
    }

    @Unroll
    def "an empty authorized scope keeps the #format shape without reading the hub"() {
        when:
        def result = script.toolListDevices([format: format])

        then:
        result.total == 0
        result.message.contains('No devices selected')
        result.containsKey(key)
        hubGet.calls.isEmpty()

        where:
        format    | key
        'summary' | 'devices'
        'ids'     | 'deviceIds'
        'context' | 'summary'
    }

    // ---- paging -----------------------------------------------------------

    def "cursor pages through the list once, 50 at a time unless limit is set"() {
        given:
        settingsMap.selectedDevices = (1..120).collect { makeDevice(id: it, label: "D${it}") }

        when:
        def ids = []
        def pages = []
        def cursor = ''
        while (cursor != null) {
            def page = script.toolListDevices([cursor: cursor, fields: ['id']])
            pages << page
            ids.addAll(page.devices*.id)
            cursor = page.nextCursor
            assert pages.size() < 5
        }

        then:
        ids == (1..120)*.toString()
        pages*.count == [50, 50, 20]
        pages[1].offset == 50
        pages[1].limit == 50
        pages[2].hasMore == false
    }

    def "limit alone pages from the start and hands back a cursor"() {
        given:
        settingsMap.selectedDevices = (1..3).collect { makeDevice(id: it) }

        when:
        def result = script.toolListDevices([limit: 2])

        then:
        result.devices*.id == ['1', '2']
        result.offset == 0
        result.limit == 2
        result.nextCursor == '2'
    }

    @Unroll
    def "cursor #cursor is refused"() {
        given:
        settingsMap.selectedDevices = (1..3).collect { makeDevice(id: it) }

        when:
        script.toolListDevices([cursor: cursor])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expected)

        where:
        cursor | expected
        'abc'  | 'hub_list_devices nextCursor'
        '3'    | 'out of range'
        '-1'   | 'out of range'
    }

    // ---- validation -------------------------------------------------------

    @Unroll
    def "validation: #label is refused before any hub read, even with nothing authorized"() {
        when:
        script.toolListDevices(args)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(expected)
        hubGet.calls.isEmpty()

        where:
        label                              | args                                       || expected
        'the removed detailed argument'    | [detailed: true]                           || 'Unknown argument: detailed'
        'the removed offset argument'      | [offset: 5]                                || 'Unknown argument: offset'
        'the removed filter argument'      | [filter: 'virtual']                        || 'Unknown argument: filter'
        'the removed onlyOn argument'      | [onlyOn: true]                             || 'Unknown argument: onlyOn'
        'an unknown scope'                 | [scope: 'bogus']                           || 'scope must be one of'
        'an unknown format'                | [format: 'xml']                            || 'summary, detailed, ids, context'
        'a non-string labelFilter'         | [labelFilter: ['a']]                       || 'labelFilter must be a string'
        'a non-string capabilityFilter'    | [capabilityFilter: 7]                      || 'capabilityFilter must be a string'
        'a non-string roomFilter'          | [roomFilter: 42]                           || 'roomFilter must be a string'
        'a non-boolean disabled'           | [disabled: 'yes']                          || 'disabled must be a boolean'
        'a zero staleHours'                | [staleHours: 0]                            || 'staleHours'
        'a string staleHours'              | [staleHours: '24']                         || 'staleHours'
        'an offset-less changedSince'      | [changedSince: '2026-06-23T10:00:00']      || 'Unparseable changedSince'
        'a list stateFilter'               | [stateFilter: ['switch']]                  || 'stateFilter must be an object'
        'a nested stateFilter value'       | [stateFilter: [switch: [on: 1]]]           || 'stateFilter must be an object'
        'a string attributeNames'          | [attributeNames: 'switch']                 || 'attributeNames must be an array'
        'a non-string attribute name'      | [attributeNames: ['switch', 7]]            || 'attributeNames must be an array'
        'a string fields'                  | [fields: 'id']                             || 'fields must be an array'
        'a negative limit'                 | [limit: -1]                                || 'limit must be a non-negative integer'
        'a fractional limit'               | [limit: 2.5]                               || 'limit must be a non-negative integer'
        'attributeNames on detailed'       | [format: 'detailed', attributeNames: []]   || 'attributeNames applies'
        'fields on ids'                    | [format: 'ids', fields: ['id']]            || 'fields applies'
        'scope all with context'           | [scope: 'all', format: 'context']          || "scope='all' supports"
        'scope all with stateFilter'       | [scope: 'all', stateFilter: [switch: 'on']]|| "scope='all' lists identity only"
        'scope all with staleHours'        | [scope: 'all', staleHours: 1]              || "scope='all' lists identity only"
        'scope all with changedSince'      | [scope: 'all', changedSince: 0]            || "scope='all' lists identity only"
        'scope all with attributeNames'    | [scope: 'all', attributeNames: []]         || "scope='all' lists identity only"
        'scope all with currentStates'     | [scope: 'all', fields: ['currentStates']]  || "scope='all' lists identity only"
    }

    def "server-injected keys are not caller arguments, and a non-numeric clock is ignored"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1)]

        expect:
        script.toolListDevices([__reqT0: 'banana']).devices*.id == ['1']
    }

    // ---- scope all / owned --------------------------------------------------

    def "scope='all' lists every page device with mcpAuthorized and counts over the filtered set"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1, label: 'Lamp A', capabilities: [[name: 'Switch']])]
        childDevicesList << makeDevice(id: 2, label: 'Lamp B', capabilities: [[name: 'Switch']])
        hubOnly = [makeDevice(id: 3, label: 'Lamp C', capabilities: [[name: 'Switch']]), makeDevice(id: 4, label: 'Lock', capabilities: [[name: 'Lock']])]

        when:
        def result = script.toolListDevices([scope: 'all', capabilityFilter: 'switch'])

        then:
        result.scope == 'all'
        result.devices*.id == ['1', '2', '3']
        result.devices*.mcpAuthorized == [true, true, false]
        result.devices[2] == [id: '3', label: 'Lamp C', name: 'TestDriver', driver: 'TestDriver', room: null, protocol: 'virtual',
            disabled: false, capabilities: ['Switch'], mcpAuthorized: false]
        result.mcpAuthorizedCount == 2
        result.unauthorizedCount == 1
        result.unfilteredTotal == 4
        !fullJsonReads()
    }

    def "scope='all' leaves capabilities out unless asked, and the bypass authorizes every device"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        hubOnly = [makeDevice(id: 3, label: 'C')]

        when:
        def result = script.toolListDevices([scope: 'all'])
        def ids = script.toolListDevices([scope: 'all', format: 'ids'])

        then:
        !result.devices[0].containsKey('capabilities')
        result.devices[0].mcpAuthorized == true
        result.unauthorizedCount == 0
        !hubGet.calls.any { it.path == '/hub2/vrb/devices' }
        ids.deviceIds == [3]
        ids.mcpAuthorizedCount == 1
    }

    def "scope='owned' lists only this app's children, with their saved driver namespace when detailed"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1)]
        childDevicesList << makeDevice(id: 2, label: 'My Virtual')
        hubGet.register('/device/fullJson/2') {
            JsonOutput.toJson([device: [id: 2, name: 'Virtual Switch', label: 'My Virtual', deviceTypeNamespace: 'hubitat',
                data: [mcpDriverNamespace: 'custom.ns'], capabilities: [], currentStates: [:]], commands: []])
        }

        when:
        def summary = script.toolListDevices([scope: 'owned'])
        def detailed = script.toolListDevices([scope: 'owned', format: 'detailed'])

        then:
        summary.devices*.id == ['2']
        summary.devices[0].mcpManaged == true
        detailed.devices[0].driverNamespace == 'custom.ns'
    }

    def "scope='owned' with no children says how to create one, without a hub read"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1)]

        when:
        def result = script.toolListDevices([scope: 'owned'])

        then:
        result == [devices: [], count: 0, total: 0,
                   message: 'No MCP-managed virtual devices found. Use hub_manage_virtual_device(action="create") to create one.']
        hubGet.calls.isEmpty()
    }

    // ---- page failures ----------------------------------------------------

    @Unroll
    def "an unreadable Devices page (#label) is a runtime error, not an empty list"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1)]
        hubGet.register('/hub2/devicesList', responder)

        when:
        def result = script.toolListDevices([:])

        then:
        result.success == false
        result.isError == true
        result.error.startsWith("The hub's device list (/hub2/devicesList) could not be read: ")
        result.note.contains("Devices page")

        where:
        label            | responder
        'transport'      | { throw new IOException('read timed out') }
        'empty body'     | { '' }
        'wrong shape'    | { JsonOutput.toJson([devices: 'nope']) }
        'a node with no id' | { JsonOutput.toJson([devices: [[key: 'DEV-1', data: [name: 'x']]]]) }
    }

    // ---- request budget on detailed pages -------------------------------------

    // Each detail read advances the request clock by 2500 ms, so a 6000 ms cloud budget is spent
    // once the third device of a page has been read.
    private AtomicLong budgetedDevices(int count) {
        def clock = new AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> clock.get() })
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        settingsMap.selectedDevices = (1..count).collect { i ->
            def d = makeDevice(id: i, label: "Device-${i}")
            NativeInventoryFixture.register(hubGet, i.toString()) { clock.addAndGet(2500L); d }
            d
        }
        return clock
    }

    def "a detailed page whose reads reach the request budget ends early with a cursor"() {
        given:
        def clock = budgetedDevices(10)

        when:
        def result = script.toolListDevices([format: 'detailed', __reqT0: clock.get()])

        then:
        result.devices*.id == ['1', '2', '3']
        result.total == 10
        result.budgetLimited == true
        result.hasMore == true
        result.offset == 0
        result.nextCursor == '3'
        result.note.contains('devices 1-3 of 10')
        result.note.contains("cursor '3'")
    }

    def "following the budget cursor reads every device exactly once, in order"() {
        given:
        def clock = budgetedDevices(10)

        when:
        def ids = []
        def pages = 0
        def cursor = ''
        while (cursor != null) {
            def page = script.toolListDevices([format: 'detailed', cursor: cursor, __reqT0: clock.get()])
            ids.addAll(page.devices*.id)
            cursor = page.nextCursor
            assert ++pages < 10
        }

        then:
        ids == (1..10)*.toString()
        pages == 4
    }

    def "a spent budget still returns one device, and summary pages are never cut"() {
        given:
        def clock = budgetedDevices(10)

        when:
        def detailed = script.toolListDevices([format: 'detailed', __reqT0: clock.get() - 7000L])
        def summary = script.toolListDevices([__reqT0: clock.get() - 7000L])

        then:
        detailed.devices*.id == ['1']
        detailed.nextCursor == '1'
        summary.devices.size() == 10
        !summary.containsKey('budgetLimited')
    }

    def "no detailed page is cut without a request clock or on a LAN with no budget"() {
        given:
        def clock = budgetedDevices(10)
        if (lan) script.metaClass._isCloudRequest = { -> false }

        when:
        def result = script.toolListDevices(withClock ? [format: 'detailed', __reqT0: clock.get()] : [format: 'detailed'])

        then:
        result.devices.size() == 10
        !result.containsKey('budgetLimited')

        where:
        withClock | lan
        false     | false
        true      | true
    }

    // ---- dispatch (one per shape) ----------------------------------------------

    @Unroll
    def "via dispatch (useGateways=#useGateways): #format round-trips the tools/call envelope"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.selectedDevices = [makeDevice(id: 1, label: 'Kitchen Light', capabilities: [[name: 'Switch']], attrValues: [switch: 'on']),
                                       makeDevice(id: 2, label: 'Fan')]

        when:
        def response = mcpDriver.callTool('hub_list_devices', [format: format, labelFilter: 'kitchen'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.total == 1
        check(inner)

        where:
        useGateways | format    | check
        true        | 'summary' | { Map r -> r.devices*.label == ['Kitchen Light'] }
        false       | 'summary' | { Map r -> r.devices*.label == ['Kitchen Light'] }
        false       | 'ids'     | { Map r -> r.deviceIds == [1] }
        false       | 'context' | { Map r -> r.summary.contains('- Kitchen Light (1, No room) - Switch; switch=on') }
    }

    def "via dispatch: a validation refusal is an isError result naming the argument"() {
        given:
        settingsMap.selectedDevices = [makeDevice(id: 1)]

        when:
        def response = mcpDriver.callTool('hub_list_devices', [onlyOn: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Unknown argument: onlyOn')
    }
}
