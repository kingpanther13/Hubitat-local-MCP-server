package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Unroll

import support.TestDevice
import support.ToolSpecBase

/** Room tools (libraries/mcp-rooms-lib.groovy): httpPost via the setupSpec dispatcher, GETs via hubGet; see docs/testing.md. */
class ToolRoomsSpec extends ToolSpecBase {

    int getRoomsCalls = 0

    private void installGetRoomsStub(List<Map> roomsList) {
        getRoomsCalls = 0
        script.metaClass.getRooms = { -> getRoomsCalls++; roomsList }
    }

    /** Calls a room tool through its gateway in gateway mode, and by its own name in flat mode. */
    private Map dispatchRoomTool(String tool, Map args, boolean viaGateway) {
        if (!viaGateway) return mcpDriver.callTool(tool, args)
        String gateway = tool in ['hub_list_rooms', 'hub_get_room'] ? 'hub_read_rooms' : 'hub_manage_rooms'
        return mcpDriver.callTool(gateway, [tool: tool, args: args])
    }

    /** Also turns the allowlist bypass on, so arbitrary device ids pass; the selection features turn it off. */
    private void enableWrite() {
        settingsMap.enableWrite = true
        settingsMap.bypassDeviceAllowlist = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
        script.metaClass.getHubSecurityCookie = { -> null }
    }

    /** Answers GET /room/updateName with {@code reply} as JSON, as the hub does. */
    private void registerUpdateName(Map reply) {
        hubGet.register('/room/updateName') { JsonOutput.toJson(reply) }
    }

    /** Bypass off, with {@code ids} selected for MCP. */
    private void selectOnly(List ids) {
        settingsMap.bypassDeviceAllowlist = false
        settingsMap.selectedDevices = ids.collect { new TestDevice(id: it, name: "dev${it}", label: "Dev ${it}") }
    }

    /** The hub's /hub2/devicesList tree: {devices: [{key, data, children}]}. */
    private void registerDevicesPage(List<Map> nodes) {
        hubGet.register('/hub2/devicesList') { JsonOutput.toJson([devices: nodes.collect { [key: "DEV-${it.id}".toString(), data: it, children: []] }]) }
    }

    /** A Devices page listing just these ids. */
    private void devicesOnHub(List ids) {
        registerDevicesPage(ids.collect { [id: it as Integer, name: "Dev ${it}".toString(), currentStates: []] })
    }

    /** Records every POST (path + parsed body) and answers each with {@code reply} as JSON. */
    private List<Map> postJson(Map reply) {
        List<Map> posts = []
        httpPostHandler = { Map params, Closure cb ->
            posts << [path: params.path, body: new JsonSlurper().parseText(params.body)]
            cb.call([status: 200, data: JsonOutput.toJson(reply)])
        }
        return posts
    }

    @Shared Closure httpPostHandler = null

    def setupSpec() {
        appExecutor.httpPost(_, _) >> { args ->
            if (httpPostHandler) {
                httpPostHandler.call(args[0], args[1])
            }
        }
    }

    def cleanup() {
        httpPostHandler = null
    }

    // -------- hub_list_rooms: the list --------

    def "hub_list_rooms returns an empty result when no rooms are configured"() {
        given:
        script.metaClass.getRooms = { -> null }

        when:
        def result = script.toolListRooms()

        then:
        result.rooms == []
        result.count == 0
        result.message.contains('No rooms')
    }

    @Unroll
    def "hub_list_rooms via dispatch returns sorted rooms with device counts (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([
            [id: 2, name: 'Kitchen', deviceIds: [100, 101, 102]],
            [id: 1, name: 'Bedroom', deviceIds: [200]],
            [id: 4, name: 'attic', deviceIds: []],
            [id: 3, name: 'Living Room', deviceIds: []]
        ])

        when:
        def response = dispatchRoomTool('hub_list_rooms', [:], useGateways)

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 4
        inner.rooms*.name == ['attic', 'Bedroom', 'Kitchen', 'Living Room']
        inner.rooms*.deviceCount == [0, 1, 3, 0]
        inner.rooms[1] == [id: '1', name: 'Bedroom', deviceCount: 1, deviceIds: ['200']]
        hubGet.calls.empty

        where:
        useGateways << [true, false]
    }

    def "hub_list_rooms handles rooms that omit deviceIds entirely"() {
        given: 'a room with no deviceIds field — the defensive ?: [] branches must not NPE'
        installGetRoomsStub([[id: 9, name: 'Garage']])

        when:
        def result = script.toolListRooms()

        then:
        result.count == 1
        result.rooms[0].deviceCount == 0
        result.rooms[0].deviceIds == []
    }

    def "hub_list_rooms cursor pagination opt-in (#174)"() {
        // _paginateList contract: no cursor -> full list; '' -> bounded page with total+nextCursor; last page omits nextCursor; bad cursor throws.
        given: '150 rooms -> 2 pages of 100 + 50'
        installGetRoomsStub((0..<150).collect { i -> [id: i + 1, name: "Room-${String.format('%03d', i)}", deviceIds: []] })

        when: 'no cursor: the full list'
        def all = script.toolListRooms()

        then:
        all.rooms.size() == 150
        !all.containsKey('nextCursor')
        !all.containsKey('total')

        when: 'first page (cursor="")'
        def page1 = script.toolListRooms([cursor: ''])

        then:
        page1.rooms.size() == 100
        page1.total == 150
        page1.nextCursor == '100'

        when: 'last page'
        def page2 = script.toolListRooms([cursor: '100'])

        then:
        page2.rooms.size() == 50
        page2.total == 150
        !page2.containsKey('nextCursor')

        when: 'non-numeric cursor'
        script.toolListRooms([cursor: 'banana'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('cursor')
        ex.message.contains('hub_list_rooms')
    }

    // -------- hub_get_room: one room by id --------

    def "hub_get_room builds device rows from the Devices page and flags ids it cannot serve"() {
        given: 'a room with a page device MCP can reach, an id missing from the page, and a page device MCP cannot reach'
        installGetRoomsStub([
            [id: 6, name: 'Hall', deviceIds: [300]],
            [id: 7, name: 'Den', deviceIds: [100, 999, 200]]
        ])
        childDevicesList << new TestDevice(id: 100, name: 'den_light', label: 'Den Light')
        registerDevicesPage([
            [id: 100, name: 'Den Light', secondaryName: 'den_light', currentStates: [[key: 'switch', value: 'on'], [key: 'level', value: 40]]],
            [id: 200, name: 'Den Fan', secondaryName: 'den_fan', currentStates: [[key: 'switch', value: 'off']]]
        ])

        when:
        def result = script.toolGetRoom([roomId: '7'])

        then: 'one page read; the reachable device carries the page label, name and string states'
        hubGet.calls*.path == ['/hub2/devicesList']
        result.id == '7'
        result.name == 'Den'
        result.deviceCount == 3
        def known = result.devices.find { it.id == '100' }
        known.label == 'Den Light'
        known.name == 'den_light'
        known.currentStates == [switch: 'on', level: '40']

        and: 'the unauthorized page device is not accessible; the id missing from the page is no longer on the hub'
        result.devices.find { it.id == '200' } == [id: '200', label: '(device not accessible via MCP)', name: 'unknown', accessible: false]
        result.devices.find { it.id == '999' } == [id: '999', label: '(device no longer on the hub)', name: 'unknown', onHub: false, metadataUnavailable: true]
    }

    def "hub_get_room serves every page device when the allowlist bypass is on, and omits empty states"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        installGetRoomsStub([[id: 7, name: 'Den', deviceIds: [200, 999]]])
        registerDevicesPage([[id: 200, name: 'Den Fan', currentStates: []]])

        when:
        def result = script.toolGetRoom([roomId: '7'])

        then: 'an unlabelled device reads its name as both label and name'
        result.devices[1] == [id: '200', label: 'Den Fan', name: 'Den Fan']

        and: 'an id the page does not list is no longer on the hub'
        result.devices[0] == [id: '999', label: '(device no longer on the hub)', name: 'unknown', onHub: false, metadataUnavailable: true]
    }

    def "hub_get_room masks credential-named states when credential redaction is on"() {
        given:
        installGetRoomsStub([[id: 7, name: 'Den', deviceIds: [100]]])
        childDevicesList << new TestDevice(id: 100, name: 'den_light', label: 'Den Light')
        registerDevicesPage([[id: 100, name: 'Den Light', currentStates: [[key: 'apiToken', value: 'abc123'], [key: 'switch', value: 'on']]]])

        when:
        def masked = script.toolGetRoom([roomId: '7'])
        settingsMap.redactCredentials = false
        def plain = script.toolGetRoom([roomId: '7'])

        then:
        masked.devices[0].currentStates == [apiToken: script._appConfigRedactedMarker(), switch: 'on']
        plain.devices[0].currentStates == [apiToken: 'abc123', switch: 'on']
    }

    def "hub_get_room returns the structured error when the Devices page cannot be read"() {
        given: 'a room with a device and no /hub2/devicesList fixture, so the read fails'
        installGetRoomsStub([[id: 7, name: 'Den', deviceIds: [100]]])
        childDevicesList << new TestDevice(id: 100, name: 'den_light', label: 'Den Light')

        when:
        def result = script.toolGetRoom([roomId: '7'])

        then:
        result.success == false
        !result.containsKey('isError')
        result.error.startsWith("The hub's device list (/hub2/devicesList) could not be read: ")
        result.note == "Retry; if it persists check the hub's Devices page. See hub_get_tool_guide(section='rooms')."
    }

    @Unroll
    def "hub_get_room reports an id the Devices page does not list as no longer on the hub (#label)"() {
        given: 'the room list keeps the id of deleted device 7'
        installGetRoomsStub([[id: 7, name: 'Den', deviceIds: [100, 7]]])
        childDevicesList << new TestDevice(id: 100, name: 'den_light', label: 'Den Light')
        if (selected) childDevicesList << new TestDevice(id: 7, name: 'gone', label: 'Gone')
        registerDevicesPage([[id: 100, name: 'Den Light', currentStates: []]])

        when:
        def result = script.toolGetRoom([roomId: '7'])

        then:
        result.devices.find { it.id == '7' } == [id: '7', label: '(device no longer on the hub)', name: 'unknown', onHub: false, metadataUnavailable: true]
        result.devices.find { it.id == '100' } == [id: '100', label: 'Den Light', name: 'Den Light']

        where:
        label          | selected
        'selected'     | true
        'not selected' | false
    }

    def "hub_get_room on a room with no devices does not read the Devices page"() {
        given:
        installGetRoomsStub([[id: 7, name: 'Den', deviceIds: []]])

        when:
        def result = script.toolGetRoom([roomId: 7])

        then: 'a numeric id is read as the same string id'
        result == [id: '7', name: 'Den', deviceCount: 0, devices: []]
        hubGet.calls.empty
    }

    @Unroll
    def "hub_get_room via dispatch returns the room and flags unresolvable ids (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([[id: 7, name: 'Den', deviceIds: [100, 999]]])
        childDevicesList << new TestDevice(id: 100, name: 'den_light', label: 'Den Light')
        registerDevicesPage([[id: 100, name: 'Den Light', secondaryName: 'den_light', currentStates: [[key: 'switch', value: 'on']]]])

        when:
        def response = useGateways
            ? mcpDriver.callTool('hub_read_rooms', [tool: 'hub_get_room', args: [roomId: '7']])
            : mcpDriver.callTool('hub_get_room', [roomId: '7'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.deviceCount == 2
        inner.devices.find { it.id == '100' }.currentStates == [switch: 'on']
        inner.devices.find { it.id == '999' }.onHub == false

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_get_room via dispatch flags the Devices page failure on the envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([[id: 7, name: 'Den', deviceIds: [100]]])
        childDevicesList << new TestDevice(id: 100, name: 'den_light', label: 'Den Light')

        when:
        def response = useGateways
            ? mcpDriver.callTool('hub_read_rooms', [tool: 'hub_get_room', args: [roomId: '7']])
            : mcpDriver.callTool('hub_get_room', [roomId: '7'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('/hub2/devicesList')

        where:
        useGateways << [true, false]
    }

    def "hub_get_room refuses an unknown roomId and a missing roomId before reading anything"() {
        given:
        installGetRoomsStub([[id: 1, name: 'Kitchen', deviceIds: [5]]])

        when:
        script.toolGetRoom([roomId: '42'])

        then:
        def unknown = thrown(IllegalArgumentException)
        unknown.message == "No room has id '42'. Call hub_list_rooms for room ids."

        when:
        script.toolGetRoom([roomId: '  '])

        then:
        def blank = thrown(IllegalArgumentException)
        blank.message == "roomId is required: the room id from hub_list_rooms."
        hubGet.calls.empty
        getRoomsCalls == 1
    }

    def "hub_get_room refuses when the hub has no rooms at all"() {
        given:
        script.metaClass.getRooms = { -> null }

        when:
        script.toolGetRoom([roomId: '1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("No room has id '1'")
    }

    @Unroll
    def "#tool via dispatch: #label is an isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([[id: 1, name: 'Kitchen', deviceIds: []]])

        when:
        def response = dispatchRoomTool(tool, args, useGateways)

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains(needle)
        hubGet.calls.empty

        // Through a gateway a call without roomId gets the required-parameter list rather than the tool's own message,
        // but an unknown key is named first, in both modes, since it is usually a renamed parameter.
        where:
        tool             | label               | args                      | needle                                  | useGateways
        'hub_get_room'   | 'an unknown roomId' | [roomId: '42']            | "No room has id '42'"                   | true
        'hub_get_room'   | 'an unknown roomId' | [roomId: '42']            | "No room has id '42'"                   | false
        'hub_get_room'   | 'a missing roomId'  | [:]                       | 'Missing required parameter for hub_get_room: roomId' | true
        'hub_get_room'   | 'a missing roomId'  | [:]                       | 'roomId is required'                    | false
        'hub_get_room'   | 'a stale id'        | [id: '1']                 | 'Unknown hub_get_room argument: id'     | true
        'hub_get_room'   | 'a stale id'        | [id: '1']                 | 'Unknown hub_get_room argument: id'     | false
        'hub_get_room'   | 'a stale name'      | [room: 'Kitchen']         | 'Unknown hub_get_room argument: room'   | true
        'hub_get_room'   | 'a stale name'      | [room: 'Kitchen']         | 'Unknown hub_get_room argument: room'   | false
        'hub_get_room'   | 'a cursor'          | [roomId: '1', cursor: ''] | 'Unknown hub_get_room argument: cursor' | true
        'hub_get_room'   | 'a cursor'          | [roomId: '1', cursor: ''] | 'Unknown hub_get_room argument: cursor' | false
        'hub_list_rooms' | 'an id'             | [id: '1']                 | 'Unknown hub_list_rooms argument: id'   | true
        'hub_list_rooms' | 'an id'             | [id: '1']                 | 'Unknown hub_list_rooms argument: id'   | false
        'hub_list_rooms' | 'a stale name'      | [room: 'Kitchen']         | 'Unknown hub_list_rooms argument: room' | true
        'hub_list_rooms' | 'a stale name'      | [room: 'Kitchen']         | 'Unknown hub_list_rooms argument: room' | false
    }

    // -------- strict arguments and the gateway surface --------

    @Unroll
    def "#tool refuses an argument its schema does not declare, through its gateway, before any request"() {
        given: 'gateway mode pinned: this case calls the gateway'
        settingsMap.useGateways = true
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600]]])
        def posts = postJson([roomId: 8])

        when:
        def response = mcpDriver.callTool(gateway, [tool: tool, args: args])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("Unknown ${tool} argument: ${bad}")
        posts.empty
        hubGet.calls.empty

        where:
        tool              | gateway            | args                                               | bad
        'hub_list_rooms'  | 'hub_read_rooms'   | [room: 'Office']                                   | 'room'
        'hub_get_room'    | 'hub_read_rooms'   | [roomId: '8', room: 'Office']                      | 'room'
        'hub_create_room' | 'hub_manage_rooms' | [name: 'Den', confirm: true, roomId: 0]            | 'roomId'
        'hub_update_room' | 'hub_manage_rooms' | [roomId: '8', newName: 'Den', confirm: true]       | 'newName'
        'hub_update_room' | 'hub_manage_rooms' | [roomId: '8', id: '8', name: 'Den', confirm: true] | 'id'
        'hub_delete_room' | 'hub_manage_rooms' | [room: '8', roomId: '8', confirm: true]            | 'room'
        'hub_delete_room' | 'hub_manage_rooms' | [roomId: '8', id: '8', confirm: true]              | 'id'
    }

    def "hub_get_room through its read gateway without a roomId names the missing parameter"() {
        given: 'gateway mode pinned: this case calls the gateway'
        settingsMap.useGateways = true
        installGetRoomsStub([[id: 1, name: 'Kitchen', deviceIds: []]])

        when:
        def response = mcpDriver.callTool('hub_read_rooms', [tool: 'hub_get_room', args: [:]])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Missing required parameter for hub_get_room: roomId')
        getRoomsCalls == 0
        hubGet.calls.empty
    }

    def "the room gateways list five tools, the read gateway the two reads"() {
        expect:
        script.handleGateway('hub_manage_rooms', null, null).tools*.name == ['hub_list_rooms', 'hub_get_room', 'hub_create_room', 'hub_delete_room', 'hub_update_room']
        script.handleGateway('hub_read_rooms', null, null).tools*.name == ['hub_list_rooms', 'hub_get_room']
        script.getAllToolDefinitions().find { it.name == 'hub_get_room' }.inputSchema.required == ['roomId']
        !script.getAllToolDefinitions().find { it.name == 'hub_list_rooms' }.inputSchema.properties.containsKey('id')
    }

    def "a RETURNED isError sets top-level isError on the JSON-RPC envelope, not just content (#209)"() {
        // File-wide contract: a handler that RETURNS [isError:true, ...] MUST be flagged on the
        // JSON-RPC result's top-level isError so a spec-compliant client routes a retry.
        // Vehicle: the flat-mode gateway-name refusal, a genuine RETURNED isError.
        given: 'flat mode: a gateway-NAME call returns the "useGateways is OFF" isError envelope'
        settingsMap.useGateways = false

        when:
        def response = mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_list_rooms', args: [:]])

        then:
        response.error == null
        response.result.isError == true
        response.result.content[0].text.contains('useGateways is OFF')
    }

    // -------- hub_create_room --------

    @Unroll
    def "hub_create_room via dispatch refuses a call without confirm (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = dispatchRoomTool('hub_create_room', [name: 'Garage'], useGateways)

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('confirm')

        where:
        useGateways << [true, false]
    }

    def "hub_create_room throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolCreateRoom([name: 'Garage'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    @Unroll
    def "hub_create_room via dispatch returns an isError validation result when the Write master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = dispatchRoomTool('hub_create_room', [name: 'Garage', confirm: true], useGateways)

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_create_room refuses #label before any request"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 1, name: 'Garage', deviceIds: []]])
        def posts = postJson([roomId: 77])

        when:
        script.toolCreateRoom(args + [confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(needle)
        posts.empty

        where:
        label                       | args                                     | needle
        'a blank name'              | [name: '  ']                             | 'Room name is required.'
        'a non-numeric device id'   | [name: 'Den', deviceIds: ['100', 'abc']] | "deviceIds must be numeric device ids; got 'abc'"
        'a device id past int'      | [name: 'Den', deviceIds: ['99999999999']] | "deviceIds must be numeric device ids; got '99999999999'"
        'deviceIds that is no list' | [name: 'Den', deviceIds: '100']          | 'deviceIds must be an array'
        'a duplicate name'          | [name: 'GARAGE']                         | "A room named 'GARAGE' already exists."
    }

    def "hub_create_room makes one /room/save and takes the new id from the hub's answer"() {
        given:
        enableWrite()
        installGetRoomsStub([])
        def posts = postJson([roomId: 77])

        when:
        def result = script.toolCreateRoom([name: ' Garage ', deviceIds: [500, '501', 500], confirm: true])

        then: 'one save, roomId 0, trimmed name, integer device ids (duplicates dropped)'
        posts == [[path: '/room/save', body: [roomId: 0, name: 'Garage', deviceIds: [500, 501]]]]
        getRoomsCalls == 1
        hubGet.calls.empty

        and: 'the result is built from the answer and the inputs, without re-reading the rooms'
        result == [success: true, room: [id: '77', name: 'Garage', deviceCount: 2, deviceIds: ['500', '501']],
                   message: "Room 'Garage' created."]
    }

    @Unroll
    def "hub_create_room via dispatch creates the room (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([])
        def posts = postJson([roomId: 77])

        when:
        def response = useGateways
            ? mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_create_room', args: [name: 'Garage', confirm: true]])
            : mcpDriver.callTool('hub_create_room', [name: 'Garage', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.room == [id: '77', name: 'Garage', deviceCount: 0, deviceIds: []]
        posts.size() == 1

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_create_room reports #label as a structured failure"() {
        given:
        enableWrite()
        installGetRoomsStub([])
        httpPostHandler = handler

        when:
        def result = script.toolCreateRoom([name: 'Garage', confirm: true])

        then: 'a runtime failure: success=false with error and note (dispatch adds isError)'
        result.success == false
        !result.containsKey('isError')
        result.error.contains(errorNeedle)
        result.note.contains(noteNeedle)
        result.note.contains("hub_get_tool_guide(section='rooms')")

        where:
        label                  | handler                                                                                          | errorNeedle                         | noteNeedle
        'a hub refusal'        | { Map p, Closure cb -> cb.call([status: 200, data: '{"error":"Invalid room or device ids"}']) } | 'The hub refused to create room'    | 'Nothing was saved'
        'an answer without id' | { Map p, Closure cb -> cb.call([status: 200, data: '']) }                                       | 'carried no roomId (an empty body)' | 'may have landed'
        'a failed request'     | { Map p, Closure cb -> throw new RuntimeException('connection reset') }                         | 'Could not create room'             | 'check with hub_list_rooms'
    }

    @Unroll
    def "#tool via dispatch returns #label as an isError success=false result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([[id: 5, name: 'Old Room', deviceIds: [300]]])
        postJson([error: 'Invalid room or device ids'])
        devicesOnHub([])
        hubGet.register('/room/delete/5') { throw new RuntimeException('HTTP 500: boom') }

        when:
        def response = useGateways
            ? mcpDriver.callTool('hub_manage_rooms', [tool: tool, args: args])
            : mcpDriver.callTool(tool, args)

        then: 'the envelope carries isError; the payload does not repeat it'
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        !inner.containsKey('isError')
        inner.error.contains(errorNeedle)
        inner.note.contains("hub_get_tool_guide(section='rooms')")

        where:
        [tool, label, args, errorNeedle, useGateways] << [
            ['hub_create_room', 'a hub refusal', [name: 'Garage', deviceIds: ['99999'], confirm: true],
             "The hub refused to create room 'Garage': Invalid room or device ids. No device has id 99999 on the hub."],
            ['hub_update_room', 'a hub refusal', [roomId: '5', deviceIds: ['99999'], confirm: true],
             'The hub refused to update room 5: Invalid room or device ids. No device has id 99999 on the hub.'],
            ['hub_update_room', 'a short-rename refusal', [roomId: '5', name: 'Z', confirm: true],
             'The room still lists device 300, which is no longer on the hub'],
            ['hub_delete_room', 'a failed delete request', [roomId: '5', confirm: true], "Could not delete room 'Old Room' (5): HTTP 500: boom"]
        ].collectMany { row -> [true, false].collect { gw -> row + [gw] } }
    }

    // -------- hub_update_room --------

    @Unroll
    def "hub_update_room sends #label in one save and reports the change"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600, 601]], [id: 9, name: 'Den', deviceIds: []]])
        def posts = postJson([roomId: 8])

        when:
        def result = script.toolUpdateRoom(args + [roomId: '8', confirm: true])

        then: 'exactly one save, carrying the merged name and device list'
        posts == [[path: '/room/save', body: [roomId: 8, name: sentName, deviceIds: sentIds]]]
        getRoomsCalls == 1
        hubGet.calls.empty

        and:
        result.success == true
        result.room == room
        result.message == "Room '${sentName}' updated."

        where:
        label           | args                                   | sentName      | sentIds    | room
        'a device list' | [deviceIds: ['601', '700']]            | 'Office'      | [601, 700] | [id: '8', name: 'Office', deviceIds: ['601', '700'], deviceCount: 2, unassignedDeviceIds: ['600']]
        'both'          | [name: 'Study', deviceIds: [600, 601]] | 'Study'       | [600, 601] | [id: '8', name: 'Study', previousName: 'Office', deviceIds: ['600', '601'], deviceCount: 2]
        'an empty list' | [deviceIds: []]                        | 'Office'      | []         | [id: '8', name: 'Office', deviceIds: [], deviceCount: 0, unassignedDeviceIds: ['600', '601']]
    }

    @Unroll
    def "hub_update_room renames by name alone with one GET /room/updateName and no save (#label)"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600, 601]], [id: 9, name: 'Den', deviceIds: []]])
        def posts = postJson([roomId: 8])
        registerUpdateName([success: true, message: null])

        when:
        def result = script.toolUpdateRoom([roomId: '8', name: name, confirm: true])

        then: 'the name travels as a query parameter; no device list is sent'
        hubGet.calls*.path == ['/room/updateName']
        hubGet.calls[0].params == [id: '8', name: name]
        posts.empty
        getRoomsCalls == 1

        and:
        result == [success: true, room: [id: '8', name: name, previousName: 'Office'], message: "Room '${name}' updated."]

        where:
        label           | name
        'a rename'      | "Bob's & Co"
        'a case change' | 'OFFICE'
    }

    @Unroll
    def "hub_update_room renames to a #label name with one POST /room/save carrying the current devices"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600, 601]], [id: 9, name: 'Den', deviceIds: []]])
        def posts = postJson([roomId: 8])
        registerUpdateName([success: false, message: 'Room name is too short'])

        when:
        def result = script.toolUpdateRoom([roomId: '8', name: name, confirm: true])

        then: '/room/updateName refuses names under 3 characters, so the save sends the device list unchanged'
        hubGet.calls.empty
        posts*.body == [[roomId: 8, name: name, deviceIds: [600, 601]]]
        getRoomsCalls == 1

        and:
        result == [success: true, room: [id: '8', name: name, previousName: 'Office'], message: "Room '${name}' updated."]

        where:
        label            | name
        'two-character'  | 'ab'
        'one-character'  | 'Z'
    }

    @Unroll
    def "hub_update_room reports #label from /room/updateName as a structured failure"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600]]])
        def posts = postJson([roomId: 8])
        hubGet.register('/room/updateName', handler)
        def warnLogs = []
        script.metaClass.mcpLog = { String level, String component, String message ->
            warnLogs << [level, component, message.toString()]
        }

        when:
        def result = script.toolUpdateRoom([roomId: '8', name: 'Study', confirm: true])

        then:
        posts.empty
        result.success == false
        !result.containsKey('isError')
        result.error == error
        result.note.startsWith(noteStart)
        result.note.endsWith("See hub_get_tool_guide(section='rooms').")

        and: 'the cause reaches the MCP log at warn'
        warnLogs == [['warn', 'room', error]]

        where:
        label               | handler                                                                     | error                                                                             | noteStart
        'a refusal'         | { JsonOutput.toJson([success: false, message: 'Room name is invalid']) }   | 'The hub refused to rename room 8: Room name is invalid'                          | 'Nothing was saved.'
        'an unknown room'   | { JsonOutput.toJson([success: false, message: 'Room not found']) }         | 'The hub refused to rename room 8: Room not found'                                | 'Nothing was saved.'
        'an empty answer'   | { '' }                                                                      | "The hub's answer to rename room 8 carried no success flag (an empty body)."      | 'The rename may have landed'
        'a failed request'  | { throw new RuntimeException('HTTP 500: boom') }                            | 'Could not rename room 8: HTTP 500: boom'                                         | 'The hub may still have renamed it'
    }

    @Unroll
    def "#tool reports each device taken from another room"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 3, name: 'Kitchen', deviceIds: [26, 27]], [id: 8, name: 'Office', deviceIds: [600]],
                             [id: 9, name: 'Den', deviceIds: [40]]])
        postJson([roomId: tool == 'hub_create_room' ? 77 : 8])

        when:
        def result = tool == 'hub_create_room'
            ? script.toolCreateRoom([name: 'Garage', deviceIds: ['26', '40', '50'], confirm: true])
            : script.toolUpdateRoom([roomId: '8', deviceIds: ['600', '26', '40', '50'], confirm: true])

        then: 'a device already in this room or in no room is not reported'
        result.success == true
        result.room.movedDevices == [[deviceId: '26', fromRoomId: '3', fromRoom: 'Kitchen'],
                                     [deviceId: '40', fromRoomId: '9', fromRoom: 'Den']]

        where:
        tool << ['hub_create_room', 'hub_update_room']
    }

    // -------- the MCP device selection --------

    @Unroll
    def "hub_create_room with the bypass off refuses #label before any save"() {
        given: 'device 100 selected, 200 a child of this app, 300 and 301 on the hub but neither'
        enableWrite()
        selectOnly([100])
        childDevicesList << new TestDevice(id: 200, name: 'child', label: 'Child')
        installGetRoomsStub([])
        devicesOnHub([100, 200, 300, 301])
        def posts = postJson([roomId: 77])

        when:
        script.toolCreateRoom([name: 'Garage', deviceIds: ids, confirm: true])

        then: 'the Devices page is read once, only because some id is outside the selection'
        def ex = thrown(IllegalArgumentException)
        ex.message == message
        posts.empty
        hubGet.calls*.path == ['/hub2/devicesList']

        where:
        label                       | ids                          | message
        'an unselected id'          | ['100', '200', '300']        | "Device 300 is outside the MCP device selection, so a room cannot be created with it. Select it in the MCP app's device list, or turn on Bypass Device Allowlist."
        'two of them'               | ['301', '100', '300', '301'] | "Devices 301, 300 are outside the MCP device selection, so a room cannot be created with them. Select them in the MCP app's device list, or turn on Bypass Device Allowlist."
        'an id no device has'       | ['100', '99999']             | 'No device has id 99999 on the hub. Use hub_list_devices for device ids.'
        'two such ids beside one unselected' | ['300', '99998', '99999'] | 'No devices have ids 99998, 99999 on the hub. Use hub_list_devices for device ids.'
    }

    @Unroll
    def "with the bypass off and the Devices page unreadable, #tool treats every id outside the selection as unselected"() {
        given: 'no /hub2/devicesList fixture, so the inventory read fails'
        enableWrite()
        selectOnly([100])
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [100, 7]]])
        def posts = postJson([roomId: 77])
        hubGet.register('/room/delete/8') { '' }

        when:
        call.call(script)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == message
        posts.empty
        hubGet.calls*.path == ['/hub2/devicesList']

        where:
        tool              | call                                                                                    | message
        'hub_create_room' | { sc -> sc.toolCreateRoom([name: 'Garage', deviceIds: ['99999'], confirm: true]) }     | "Device 99999 is outside the MCP device selection, so a room cannot be created with it. Select it in the MCP app's device list, or turn on Bypass Device Allowlist. The hub's device list could not be read to rule out a deleted device; retry if it no longer exists."
        'hub_update_room' | { sc -> sc.toolUpdateRoom([roomId: '8', deviceIds: ['100', '99999'], confirm: true]) } | "Device 99999 is outside the MCP device selection, so this update cannot add it. Device 7 is outside the MCP device selection, so this update cannot remove it. Select them in the MCP app's device list, or turn on Bypass Device Allowlist. A device hub_get_room shows with accessible=false stays in the list you send. The hub's device list could not be read to rule out a deleted device; retry if any no longer exist."
        'hub_delete_room' | { sc -> sc.toolDeleteRoom([roomId: '8', confirm: true]) }                              | "Device 7 is outside the MCP device selection, so deleting this room would unassign it. Select it in the MCP app's device list, or turn on Bypass Device Allowlist. The hub's device list could not be read to rule out a deleted device; retry if it no longer exists."
    }

    @Unroll
    def "hub_create_room saves selected and child devices with the bypass off, and any device with it on (bypass=#bypass)"() {
        given:
        enableWrite()
        selectOnly([100])
        settingsMap.bypassDeviceAllowlist = bypass
        childDevicesList << new TestDevice(id: 200, name: 'child', label: 'Child')
        installGetRoomsStub([])
        def posts = postJson([roomId: 77])

        when:
        def result = script.toolCreateRoom([name: 'Garage', deviceIds: ids, confirm: true])

        then: 'every id is authorized (or nothing is checked), so the Devices page is not read'
        result.success == true
        posts*.body*.deviceIds == [ids.collect { it as Integer }]
        hubGet.calls.empty

        where:
        bypass | ids
        false  | ['100', '200']
        true   | ['100', '200', '300']
    }

    @Unroll
    def "hub_update_room with the bypass off checks only the ids whose membership changes: #label"() {
        given: 'room 8 holds 600 (not selected), 100 (selected) and 7 (a deleted device); 101 is selected, 700 is not'
        enableWrite()
        selectOnly([100, 101])
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600, 100, 7]]])
        devicesOnHub([100, 101, 600, 700])
        def posts = postJson([roomId: 8])
        registerUpdateName([success: true, message: null])

        when:
        def outcome
        try {
            outcome = script.toolUpdateRoom(args + [roomId: '8', confirm: true])
        } catch (IllegalArgumentException e) {
            outcome = e
        }

        then:
        refused ? (outcome instanceof IllegalArgumentException && outcome.message == refused) : outcome.success == true
        posts.size() == saves
        hubGet.calls.count { it.path == '/hub2/devicesList' } == reads

        where:
        label                                    | args                                                   | refused | saves | reads
        'a rename keeps the unselected device'   | [name: 'Study']                                        | null    | 0     | 0
        're-sending it with a selected addition' | [name: 'Study', deviceIds: ['600', '100', '101']]      | null    | 1     | 1
        'dropping a selected device'             | [deviceIds: ['600']]                                   | null    | 1     | 1
        'dropping the deleted device'            | [deviceIds: ['600', '100']]                            | null    | 1     | 1
        'dropping the unselected device'         | [deviceIds: ['100', '7']]                              | "Device 600 is outside the MCP device selection, so this update cannot remove it. Select it in the MCP app's device list, or turn on Bypass Device Allowlist. A device hub_get_room shows with accessible=false stays in the list you send." | 0 | 1
        'adding an unselected device'            | [deviceIds: ['600', '100', '7', '700']]                | "Device 700 is outside the MCP device selection, so this update cannot add it. Select it in the MCP app's device list, or turn on Bypass Device Allowlist." | 0 | 1
        'both at once'                           | [deviceIds: ['100', '7', '700']]                       | "Device 700 is outside the MCP device selection, so this update cannot add it. Device 600 is outside the MCP device selection, so this update cannot remove it. Select them in the MCP app's device list, or turn on Bypass Device Allowlist. A device hub_get_room shows with accessible=false stays in the list you send." | 0 | 1
        'adding an id no device has'             | [deviceIds: ['600', '100', '7', '99999']]              | 'No device has id 99999 on the hub. Use hub_list_devices for device ids.' | 0 | 1
    }

    def "hub_update_room with the bypass on moves any device"() {
        given:
        enableWrite()
        selectOnly([100])
        settingsMap.bypassDeviceAllowlist = true
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600, 100]]])
        def posts = postJson([roomId: 8])

        when:
        def result = script.toolUpdateRoom([roomId: '8', deviceIds: ['100', '700'], confirm: true])

        then:
        result.success == true
        result.room.unassignedDeviceIds == ['600']
        posts*.body == [[roomId: 8, name: 'Office', deviceIds: [100, 700]]]
    }

    @Unroll
    def "#tool via dispatch with bypass=#bypass #outcome an unselected device (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        selectOnly([100])
        settingsMap.bypassDeviceAllowlist = bypass
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [100]]])
        devicesOnHub([100, 700])
        def posts = postJson([roomId: tool == 'hub_create_room' ? 77 : 8])

        when:
        def response = dispatchRoomTool(tool, args, useGateways)

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        if (bypass) {
            assert !response.result.isError && inner.success == true && posts.size() == 1
        } else {
            assert response.result.isError == true
            assert inner.error.contains(needle)
            assert posts.empty
        }

        where:
        [tool, args, needle, bypass, useGateways] << [
            ['hub_create_room', [name: 'Garage', deviceIds: ['700'], confirm: true],
             'Device 700 is outside the MCP device selection, so a room cannot be created with it.'],
            ['hub_update_room', [roomId: '8', deviceIds: ['100', '700'], confirm: true],
             'Device 700 is outside the MCP device selection, so this update cannot add it.']
        ].collectMany { row -> [[false, true], [true, false]].combinations().collect { row + it } }
        outcome = bypass ? 'saves' : 'refuses'
    }

    @Unroll
    def "#tool via dispatch with the bypass off refuses an id no device has (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        selectOnly([100])
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [100]]])
        devicesOnHub([100])
        def posts = postJson([roomId: 8])

        when:
        def response = dispatchRoomTool(tool, args, useGateways)

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('No device has id 99999 on the hub. Use hub_list_devices for device ids.')
        posts.empty

        where:
        [tool, args, useGateways] << [
            ['hub_create_room', [name: 'Garage', deviceIds: ['99999'], confirm: true]],
            ['hub_update_room', [roomId: '8', deviceIds: ['100', '99999'], confirm: true]]
        ].collectMany { row -> [true, false].collect { gw -> row + [gw] } }
    }

    @Unroll
    def "hub_delete_room with bypass=#bypass #verdict a room holding #label"() {
        given: 'device 100 selected, 200 a child of this app, 300 neither, 7 deleted from the hub'
        enableWrite()
        selectOnly([100])
        settingsMap.bypassDeviceAllowlist = bypass
        childDevicesList << new TestDevice(id: 200, name: 'child', label: 'Child')
        installGetRoomsStub([[id: 5, name: 'Office', deviceIds: ids]])
        if (pageReadable) devicesOnHub([100, 200, 300])
        hubGet.register('/room/delete/5') { '' }

        when:
        def outcome
        try {
            outcome = script.toolDeleteRoom([roomId: '5', confirm: true])
        } catch (IllegalArgumentException e) {
            outcome = e
        }

        then:
        hubGet.calls.count { it.path == '/hub2/devicesList' } == reads
        if (refused) {
            assert outcome instanceof IllegalArgumentException
            assert outcome.message == "Device ${refused} is outside the MCP device selection, so deleting this room would unassign it. Select it in the MCP app's device list, or turn on Bypass Device Allowlist." +
                (pageReadable ? '' : " The hub's device list could not be read to rule out a deleted device; retry if it no longer exists.")
            assert !hubGet.calls*.path.contains('/room/delete/5')
        } else {
            assert outcome.success == true && outcome.devicesUnassigned == ids.size()
            assert hubGet.calls*.path.contains('/room/delete/5')
        }

        where:
        label                                     | bypass | ids        | pageReadable | refused | reads
        'an unselected device'                    | false  | [100, 300] | true         | '300'   | 1
        'a deleted device'                        | false  | [100, 7]   | true         | null    | 1
        'a deleted device, page unreadable'       | false  | [100, 7]   | false        | '7'     | 1
        'selected and child only'                 | false  | [100, 200] | true         | null    | 0
        'no devices'                              | false  | []         | true         | null    | 0
        'an unselected device'                    | true   | [100, 300] | true         | null    | 0
        verdict = refused ? 'refuses' : 'deletes'
    }

    @Unroll
    def "hub_delete_room via dispatch with bypass=#bypass #outcome a room holding an unselected device (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        selectOnly([100])
        settingsMap.bypassDeviceAllowlist = bypass
        installGetRoomsStub([[id: 5, name: 'Office', deviceIds: [100, 700]]])
        devicesOnHub([100, 700])
        hubGet.register('/room/delete/5') { '' }

        when:
        def response = dispatchRoomTool('hub_delete_room', [roomId: '5', confirm: true], useGateways)

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        if (bypass) {
            assert !response.result.isError && inner.success == true && hubGet.calls*.path == ['/room/delete/5']
        } else {
            assert response.result.isError == true
            assert inner.error.contains('Device 700 is outside the MCP device selection, so deleting this room would unassign it')
            assert !hubGet.calls*.path.contains('/room/delete/5')
        }

        where:
        [bypass, useGateways] << [[false, true], [true, false]].combinations()
        outcome = bypass ? 'deletes' : 'refuses'
    }

    def "hub_update_room holds its own lock, not the write reservation lock"() {
        expect:
        script.ROOM_UPDATE_LOCK != null
        !script.ROOM_UPDATE_LOCK.is(script.WRITE_RESERVATION_LOCK)
    }

    @Unroll
    def "hub_update_room refuses #label before any request"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600]], [id: 9, name: 'Den', deviceIds: []]])
        def posts = postJson([roomId: 8])

        when:
        script.toolUpdateRoom(args + [confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(needle)
        posts.empty

        where:
        label                       | args                             | needle
        'neither name nor devices'  | [roomId: '8']                    | 'Pass name, deviceIds, or both.'
        'a blank name'              | [roomId: '8', name: ' ']         | 'name must not be blank'
        'a non-numeric device id'   | [roomId: '8', deviceIds: ['x1']] | "deviceIds must be numeric device ids; got 'x1'"
        'a device id past int'      | [roomId: '8', deviceIds: ['2147483648']] | "deviceIds must be numeric device ids; got '2147483648'"
        'deviceIds that is no list' | [roomId: '8', deviceIds: '600']  | 'deviceIds must be an array'
        'an unknown room id'        | [roomId: '999', name: 'Attic']   | "No room has id '999'"
        'a missing roomId'          | [name: 'Attic']                  | 'roomId is required'
        'a colliding name'          | [roomId: '8', name: 'den']       | "A room named 'den' already exists."
    }

    def "hub_update_room throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolUpdateRoom([roomId: '8', name: 'Study'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_update_room reports the hub's refusal of a device id when the Devices page cannot name it"() {
        given: 'no /hub2/devicesList fixture, so the ids cannot be looked up'
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600]]])
        def posts = postJson([error: 'Invalid room or device ids'])

        when:
        def result = script.toolUpdateRoom([roomId: '8', deviceIds: ['99999'], confirm: true])

        then:
        posts.size() == 1
        result.success == false
        !result.containsKey('isError')
        result.error == 'The hub refused to update room 8: Invalid room or device ids'
        result.note == "Nothing was saved. Check the device ids with hub_list_devices. See hub_get_tool_guide(section='rooms')."
    }

    @Unroll
    def "#tool names the ids no device has when the hub refuses the save (#label)"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600]]])
        devicesOnHub([600])
        def posts = postJson([error: 'Invalid room or device ids'])

        when:
        def result = tool == 'hub_create_room'
            ? script.toolCreateRoom([name: 'Garage', deviceIds: ids, confirm: true])
            : script.toolUpdateRoom([roomId: '8', deviceIds: ids, confirm: true])

        then:
        posts.size() == 1
        result.success == false
        result.error == "The hub refused to ${action}: Invalid room or device ids. ${named}".toString()
        result.note == "Nothing was saved. Send deviceIds without ${pronoun}; hub_list_devices lists device ids. See hub_get_tool_guide(section='rooms').".toString()

        where:
        tool              | label    | ids                      | action                 | named                                         | pronoun
        'hub_create_room' | 'one'    | ['600', '99999']         | "create room 'Garage'" | 'No device has id 99999 on the hub.'          | 'it'
        'hub_update_room' | 'one'    | ['600', '99999']         | 'update room 8'        | 'No device has id 99999 on the hub.'          | 'it'
        'hub_update_room' | 'two'    | ['99998', '600', '99999'] | 'update room 8'       | 'No devices have ids 99998, 99999 on the hub.' | 'them'
    }

    @Unroll
    def "hub_update_room explains a refused short rename by the deleted devices the room still lists (#label)"() {
        given: 'the room list keeps deleted devices 7 and 8 beside device 600'
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: roomIds]])
        if (pageReadable) devicesOnHub([600])
        def posts = postJson([error: 'Invalid room or device ids'])

        when:
        def result = script.toolUpdateRoom([roomId: '8', name: 'Z', confirm: true])

        then: 'the save re-sent the room\'s own list; the caller sent no ids, so the note does not tell them to check any'
        posts*.body == [[roomId: 8, name: 'Z', deviceIds: roomIds]]
        result.success == false
        result.error == error
        result.note == note + " See hub_get_tool_guide(section='rooms')."

        where:
        label                | roomIds     | pageReadable | error | note
        'one deleted device' | [600, 7]    | true         | 'The hub refused to rename room 8: Invalid room or device ids. The room still lists device 7, which is no longer on the hub, so a rename to a name under 3 characters (which re-sends the device list) cannot be saved.' | 'Nothing was saved. Rename it to a name of 3 or more characters, or first send deviceIds without device 7.'
        'two'                | [7, 600, 8] | true         | 'The hub refused to rename room 8: Invalid room or device ids. The room still lists devices 7, 8, which are no longer on the hub, so a rename to a name under 3 characters (which re-sends the device list) cannot be saved.' | 'Nothing was saved. Rename it to a name of 3 or more characters, or first send deviceIds without devices 7, 8.'
        'page unreadable'    | [600, 7]    | false        | 'The hub refused to rename room 8: Invalid room or device ids' | 'Nothing was saved.'
    }

    @Unroll
    def "hub_update_room via dispatch sets the name and devices (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: [600]]])
        def posts = postJson([roomId: 8])
        def args = [roomId: '8', name: 'Study', deviceIds: ['601'], confirm: true]

        when:
        def response = useGateways
            ? mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_update_room', args: args])
            : mcpDriver.callTool('hub_update_room', args)

        then:
        response.error == null
        !response.result.isError
        mcpDriver.parseInner(response).room == [id: '8', name: 'Study', previousName: 'Office', deviceIds: ['601'], deviceCount: 1, unassignedDeviceIds: ['600']]
        posts*.body == [[roomId: 8, name: 'Study', deviceIds: [601]]]

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_update_room via dispatch refuses an unknown room id (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([[id: 8, name: 'Office', deviceIds: []]])
        def posts = postJson([roomId: 999])

        when:
        def args = [roomId: '999', name: 'Attic', confirm: true]
        def response = useGateways
            ? mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_update_room', args: args])
            : mcpDriver.callTool('hub_update_room', args)

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("No room has id '999'")
        posts.empty

        where:
        useGateways << [true, false]
    }

    // -------- hub_delete_room --------

    @Unroll
    def "hub_delete_room makes one GET /room/delete/<id> and reports #count unassigned"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 5, name: 'Old Room', deviceIds: ids]])
        def posts = postJson([:])
        hubGet.register('/room/delete/5') { '' }

        when:
        def result = script.toolDeleteRoom([roomId: '5', confirm: true])

        then: 'one GET, no POST, no re-read of the rooms'
        hubGet.calls*.path == ['/room/delete/5']
        posts.empty
        getRoomsCalls == 1

        and:
        result.success == true
        result.deletedRoom == [id: '5', name: 'Old Room']
        result.devicesUnassigned == count
        result.message == "Room 'Old Room' deleted. ${text} now unassigned."

        where:
        ids        | count | text
        [300, 301] | 2     | '2 devices are'
        [400]      | 1     | '1 device is'
        []         | 0     | '0 devices are'
    }

    def "hub_delete_room refuses an unknown room id or a missing roomId before any request"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 1, name: 'Kitchen', deviceIds: []]])

        when:
        script.toolDeleteRoom([roomId: '5', confirm: true])

        then:
        def unknown = thrown(IllegalArgumentException)
        unknown.message.contains("No room has id '5'")

        when:
        script.toolDeleteRoom([confirm: true])

        then:
        def missing = thrown(IllegalArgumentException)
        missing.message.contains('roomId is required')
        hubGet.calls.empty
    }

    def "hub_delete_room throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolDeleteRoom([roomId: '5'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_delete_room reports a failed delete request as a structured failure"() {
        given:
        enableWrite()
        installGetRoomsStub([[id: 5, name: 'Old Room', deviceIds: [300]]])
        hubGet.register('/room/delete/5') { throw new RuntimeException('HTTP 500: boom') }

        when:
        def result = script.toolDeleteRoom([roomId: '5', confirm: true])

        then:
        result.success == false
        !result.containsKey('isError')
        result.error == "Could not delete room 'Old Room' (5): HTTP 500: boom"
        result.note.contains('hub_list_rooms')
        result.note.contains("hub_get_tool_guide(section='rooms')")
    }

    @Unroll
    def "hub_delete_room via dispatch deletes the room (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([[id: 5, name: 'Old Room', deviceIds: [300, 301]]])
        hubGet.register('/room/delete/5') { '' }

        when:
        def response = useGateways
            ? mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_delete_room', args: [roomId: '5', confirm: true]])
            : mcpDriver.callTool('hub_delete_room', [roomId: '5', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.deletedRoom == [id: '5', name: 'Old Room']
        inner.devicesUnassigned == 2
        hubGet.calls*.path == ['/room/delete/5']

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_delete_room via dispatch refuses an unknown room id (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([[id: 1, name: 'Kitchen', deviceIds: []]])

        when:
        def args = [roomId: '5', confirm: true]
        def response = useGateways
            ? mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_delete_room', args: args])
            : mcpDriver.callTool('hub_delete_room', args)

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("No room has id '5'")
        hubGet.calls.empty

        where:
        useGateways << [true, false]
    }
}
