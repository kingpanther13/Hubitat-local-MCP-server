package server

import groovy.json.JsonSlurper
import spock.lang.Shared

import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for the hub_manage_rooms gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolListRooms   -> hub_list_rooms
 * - toolGetRoom     -> hub_get_room
 * - toolCreateRoom  -> hub_create_room   (destructive — Write master + confirm + 24h backup)
 * - toolDeleteRoom  -> hub_delete_room   (destructive — Write master + confirm + 24h backup)
 * - toolRenameRoom  -> hub_update_room   (destructive — Write master + confirm + 24h backup)
 *
 * Mocking strategy: see docs/testing.md "Which interception point to use".
 * This spec is the canonical example of the setupSpec-dispatcher pattern
 * for platform methods on BaseExecutor — httpPost is intercepted via a
 * permanent `_ *` stub in setupSpec() that dispatches to a per-feature
 * @Shared Closure handler (httpPostHandler). getRooms / getHubSecurityCookie
 * are purely dynamic, stubbed per-test on script.metaClass.
 *
 * Destructive tools require (the Write master gates centrally at executeTool and
 * defaults ON, so it needs no seed; requireDestructiveConfirm needs confirm + 24h backup):
 *   stateMap.lastBackupTimestamp    = current 'now' (1234567890000L)
 *   args.confirm                    = true
 *
 * Each direct-call feature has a parallel "via dispatch" feature that fires
 * the same tool through {@code mcpDriver.callTool} so the production
 * envelope path (JSON-RPC parse → tools/call → gateway routing → error
 * mapping → response wrapping) is covered alongside the unit-level
 * tool internals. Dispatch features are @Unroll'd across useGateways
 * true/false; when useGateways=true the JSON-RPC dispatch still routes
 * sub-tool names (hub_list_rooms etc.) directly through the executeTool
 * switch — they remain callable by name, the gateway only affects which
 * names tools/list advertises.
 */
class ToolRoomsSpec extends ToolSpecBase {

    private void installGetRoomsStub(List<Map> roomsList) {
        script.metaClass.getRooms = { -> roomsList }
    }

    private void installCookieStub() {
        script.metaClass.getHubSecurityCookie = { -> null }
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
    }

    /**
     * Per-feature handler for httpPost calls. Tests assign this in their
     * given: block with a closure taking (Map params, Closure responseCb)
     * and emulate the hub response by calling responseCb with a map-backed
     * resp stub, e.g. cb.call([status: 200, data: '']).
     *
     * A permanent {@code _ * appExecutor.httpPost(_, _) >> { ... }} stub
     * installed in {@code setupSpec} dispatches to this field. The Spock
     * mock stub IS the interception point hubitat_ci intends — httpPost
     * bottoms out at an invokeinterface call on the Mock(AppExecutor) JDK
     * Proxy. Setting the stub in {@code setupSpec} (baseline, permanent)
     * works reliably where setting it in {@code given:} on the @Shared
     * mock does not.
     */
    @Shared Closure httpPostHandler = null

    def setupSpec() {
        // No explicit super.setupSpec() call needed — Spock auto-invokes
        // superclass fixtures in declaration order (HarnessSpec.setupSpec
        // runs first, wiring appExecutor + the shared script, then this
        // body layers the httpPost dispatcher onto the already-built mock).
        appExecutor.httpPost(_, _) >> { args ->
            if (httpPostHandler) {
                httpPostHandler.call(args[0], args[1])
            }
        }
    }

    def cleanup() {
        httpPostHandler = null
    }

    // -------- toolListRooms --------

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

    @spock.lang.Unroll
    def "hub_list_rooms via dispatch returns empty result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getRooms = { -> null }

        when:
        def response = mcpDriver.callTool('hub_list_rooms', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rooms == []
        inner.count == 0
        inner.message.contains('No rooms')

        where:
        useGateways << [true, false]
    }

    def "hub_list_rooms returns sorted rooms with device counts"() {
        given:
        installGetRoomsStub([
            [id: 2, name: 'Kitchen', deviceIds: [100, 101, 102]],
            [id: 1, name: 'Bedroom', deviceIds: [200]],
            [id: 3, name: 'Living Room', deviceIds: []]
        ])

        when:
        def result = script.toolListRooms()

        then:
        result.count == 3
        result.rooms*.name == ['Bedroom', 'Kitchen', 'Living Room']
        result.rooms[0].deviceCount == 1
        result.rooms[1].deviceCount == 3
        result.rooms[2].deviceCount == 0
        result.rooms[0].deviceIds == ['200']
    }

    @spock.lang.Unroll
    def "hub_list_rooms via dispatch returns sorted rooms with device counts (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([
            [id: 2, name: 'Kitchen', deviceIds: [100, 101, 102]],
            [id: 1, name: 'Bedroom', deviceIds: [200]],
            [id: 3, name: 'Living Room', deviceIds: []]
        ])

        when:
        def response = mcpDriver.callTool('hub_list_rooms', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 3
        inner.rooms*.name == ['Bedroom', 'Kitchen', 'Living Room']
        inner.rooms[0].deviceCount == 1
        inner.rooms[1].deviceCount == 3
        inner.rooms[2].deviceCount == 0
        inner.rooms[0].deviceIds == ['200']

        where:
        useGateways << [true, false]
    }

    def "hub_list_rooms handles rooms that omit deviceIds entirely"() {
        given: 'a room with no deviceIds field — the defensive ?: [] branches must not NPE'
        installGetRoomsStub([
            [id: 9, name: 'Garage']  // no deviceIds key
        ])

        when:
        def result = script.toolListRooms()

        then:
        result.count == 1
        result.rooms[0].deviceCount == 0
        result.rooms[0].deviceIds == []
    }

    @spock.lang.Unroll
    def "hub_list_rooms via dispatch handles rooms that omit deviceIds entirely (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([
            [id: 9, name: 'Garage']
        ])

        when:
        def response = mcpDriver.callTool('hub_list_rooms', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 1
        inner.rooms[0].deviceCount == 0
        inner.rooms[0].deviceIds == []

        where:
        useGateways << [true, false]
    }

    def "hub_list_rooms cursor pagination opt-in (#174)"() {
        // Representative spec for the cursor-helper rollout: every tool that wires
        // _paginateList behaves identically -- no cursor returns the full list, cursor=''
        // emits a bounded page with total + nextCursor, last page omits nextCursor,
        // non-numeric/out-of-range cursors throw IllegalArgumentException so dispatch
        // surfaces -32602. The per-tool spec is exercised exhaustively for hub_list_rooms
        // here; the other paginated tools rely on the shared _paginateList contract
        // pinned by ToolListInstalledAppsSpec.
        given: '150 rooms -> 2 pages of 100 + 50'
        installGetRoomsStub((0..<150).collect { i -> [id: i + 1, name: "Room-${String.format('%03d', i)}", deviceIds: []] })

        when: 'no cursor: backward-compatible full list'
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

    // -------- toolGetRoom --------

    def "hub_get_room returns room details when located by name (case-insensitive)"() {
        given:
        installGetRoomsStub([
            [id: 1, name: 'Kitchen', deviceIds: []]
        ])

        when:
        def result = script.toolGetRoom('kitchen')

        then:
        result.id == '1'
        result.name == 'Kitchen'
        result.deviceCount == 0
        result.devices == []
    }

    @spock.lang.Unroll
    def "hub_get_room via dispatch returns room details when located by name (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([
            [id: 1, name: 'Kitchen', deviceIds: []]
        ])

        when:
        def response = mcpDriver.callTool('hub_get_room', [room: 'kitchen'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.id == '1'
        inner.name == 'Kitchen'
        inner.deviceCount == 0
        inner.devices == []

        where:
        useGateways << [true, false]
    }

    def "hub_get_room returns room details when located by id"() {
        given:
        installGetRoomsStub([
            [id: 42, name: 'Office', deviceIds: []]
        ])

        when:
        def result = script.toolGetRoom('42')

        then:
        result.id == '42'
        result.name == 'Office'
    }

    @spock.lang.Unroll
    def "hub_get_room via dispatch returns room details when located by id (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([
            [id: 42, name: 'Office', deviceIds: []]
        ])

        when:
        def response = mcpDriver.callTool('hub_get_room', [room: '42'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.id == '42'
        inner.name == 'Office'

        where:
        useGateways << [true, false]
    }

    def "hub_get_room throws with available-rooms list when the identifier is unknown"() {
        given:
        installGetRoomsStub([
            [id: 1, name: 'Kitchen', deviceIds: []],
            [id: 2, name: 'Bedroom', deviceIds: []]
        ])

        when:
        script.toolGetRoom('Basement')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Basement')
        ex.message.contains('Kitchen')
        ex.message.contains('Bedroom')
    }

    @spock.lang.Unroll
    def "hub_get_room via dispatch returns -32602 envelope with available rooms when unknown (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([
            [id: 1, name: 'Kitchen', deviceIds: []],
            [id: 2, name: 'Bedroom', deviceIds: []]
        ])

        when:
        def response = mcpDriver.callTool('hub_get_room', [room: 'Basement'])

        then:
        response.error.code == -32602
        response.error.message.contains('Basement')
        response.error.message.contains('Kitchen')
        response.error.message.contains('Bedroom')

        where:
        useGateways << [true, false]
    }

    def "hub_get_room throws when room identifier is empty"() {
        when:
        script.toolGetRoom('')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Room name or ID is required')
    }

    @spock.lang.Unroll
    def "hub_get_room via dispatch returns -32602 envelope when identifier is empty (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_get_room', [room: ''])

        then:
        response.error.code == -32602
        response.error.message.contains('Room name or ID is required')

        where:
        useGateways << [true, false]
    }

    def "hub_get_room expands deviceIds into device details and flags unresolvable ids as not-accessible"() {
        given: 'a room referencing a resolvable child device and an unresolvable id'
        installGetRoomsStub([
            [id: 7, name: 'Den', deviceIds: [100, 999]]
        ])
        and: 'a child device findable at id 100 with a current state'
        childDevicesList << new TestDevice(
            id: 100, name: 'den_light', label: 'Den Light',
            currentStates: [[name: 'switch', value: 'on']]
        )

        when:
        def result = script.toolGetRoom('7')

        then: 'both deviceIds produce an entry, resolvable one carries details, unresolvable one flagged'
        result.deviceCount == 2
        def known = result.devices.find { it.id == '100' }
        known != null
        known.label == 'Den Light'
        known.name == 'den_light'
        known.currentStates == [switch: 'on']

        def unknown = result.devices.find { it.id == '999' }
        unknown != null
        unknown.label == '(device not accessible via MCP)'
        unknown.accessible == false
    }

    def "hub_get_room tolerates a device whose currentStates read throws (entry still returned, no propagation)"() {
        given: 'a room with a device whose currentStates getter throws'
        installGetRoomsStub([[id: 7, name: 'Den', deviceIds: [100]]])
        def dev = new TestDevice(id: 100, name: 'den_light', label: 'Den Light',
            currentStates: [[name: 'switch', value: 'on']])
        dev.metaClass.getCurrentStates = { -> throw new RuntimeException('states unavailable') }
        childDevicesList << dev

        when:
        def result = script.toolGetRoom('7')

        then: 'the device entry is still returned (id/label/name) with no currentStates, and nothing propagates'
        result.deviceCount == 1
        def d = result.devices.find { it.id == '100' }
        d != null
        d.label == 'Den Light'
        d.name == 'den_light'
        !d.containsKey('currentStates')
    }

    @spock.lang.Unroll
    def "hub_get_room via dispatch expands deviceIds and flags unresolvable ids (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        installGetRoomsStub([
            [id: 7, name: 'Den', deviceIds: [100, 999]]
        ])
        and:
        childDevicesList << new TestDevice(
            id: 100, name: 'den_light', label: 'Den Light',
            currentStates: [[name: 'switch', value: 'on']]
        )

        when:
        def response = mcpDriver.callTool('hub_get_room', [room: '7'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.deviceCount == 2
        def known = inner.devices.find { it.id == '100' }
        known != null
        known.label == 'Den Light'
        known.name == 'den_light'
        known.currentStates == [switch: 'on']
        def unknown = inner.devices.find { it.id == '999' }
        unknown != null
        unknown.label == '(device not accessible via MCP)'
        unknown.accessible == false

        where:
        useGateways << [true, false]
    }

    // -------- toolCreateRoom --------

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

    @spock.lang.Unroll
    def "hub_create_room via dispatch returns -32602 envelope when confirm missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Garage'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')
        response.error.message.contains('confirm=true')

        where:
        useGateways << [true, false]
    }

    def "hub_create_room throws when the Write master is disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_create_room', [name: 'Garage', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_create_room via dispatch returns -32602 envelope when the Write master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Garage', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_create_room throws when required name is blank"() {
        given:
        enableWrite()
        installGetRoomsStub([])

        when:
        script.toolCreateRoom([name: '  ', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Room name is required')
    }

    def "hub_create_room rejects a non-numeric deviceId with a clean validation error (not an opaque coercion failure)"() {
        given:
        enableWrite()
        installGetRoomsStub([])

        when:
        script.toolCreateRoom([name: 'Den', deviceIds: ['100', 'abc'], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceIds must be numeric')
        ex.message.contains('abc')
    }

    @spock.lang.Unroll
    def "hub_create_room via dispatch returns -32602 envelope when name is blank (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([])

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: '  ', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Room name is required')

        where:
        useGateways << [true, false]
    }

    def "hub_create_room posts to /room/save and reports success"() {
        given:
        enableWrite()
        def rooms = []
        installGetRoomsStub(rooms)
        installCookieStub()

        and: 'httpPost handler appends the new room and invokes the response callback'
        def capturedBody = null
        httpPostHandler = { Map params, Closure cb ->
            capturedBody = new JsonSlurper().parseText(params.body)
            if (params.path == '/room/save' && capturedBody.roomId == 0) {
                rooms << [id: 77, name: capturedBody.name, deviceIds: capturedBody.deviceIds]
            }
            cb.call([status: 200, data: ''])
        }

        when:
        def result = script.toolCreateRoom([name: 'Garage', deviceIds: [500, 501], confirm: true])

        then:
        capturedBody.name == 'Garage'
        capturedBody.deviceIds == [500, 501]
        capturedBody.roomId == 0
        result.success == true
        result.room.id == '77'
        result.room.name == 'Garage'
        result.room.deviceCount == 2
    }

    @spock.lang.Unroll
    def "hub_create_room via dispatch posts to /room/save and reports success (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def rooms = []
        installGetRoomsStub(rooms)
        installCookieStub()

        and:
        def capturedBody = null
        httpPostHandler = { Map params, Closure cb ->
            capturedBody = new JsonSlurper().parseText(params.body)
            if (params.path == '/room/save' && capturedBody.roomId == 0) {
                rooms << [id: 77, name: capturedBody.name, deviceIds: capturedBody.deviceIds]
            }
            cb.call([status: 200, data: ''])
        }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Garage', deviceIds: [500, 501], confirm: true])

        then:
        capturedBody.name == 'Garage'
        capturedBody.deviceIds == [500, 501]
        capturedBody.roomId == 0
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.room.id == '77'
        inner.room.name == 'Garage'
        inner.room.deviceCount == 2

        where:
        useGateways << [true, false]
    }

    def "hub_create_room rejects duplicate room names (case-insensitive)"() {
        given:
        enableWrite()
        installGetRoomsStub([
            [id: 1, name: 'Garage', deviceIds: []]
        ])

        when:
        script.toolCreateRoom([name: 'GARAGE', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("already exists")
    }

    @spock.lang.Unroll
    def "hub_create_room via dispatch returns -32602 envelope on duplicate name (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([
            [id: 1, name: 'Garage', deviceIds: []]
        ])

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'GARAGE', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('already exists')

        where:
        useGateways << [true, false]
    }

    // -------- toolDeleteRoom --------

    def "hub_delete_room throws when room identifier is missing"() {
        given:
        enableWrite()

        when:
        script.toolDeleteRoom([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Room name or ID is required')
    }

    @spock.lang.Unroll
    def "hub_delete_room via dispatch returns -32602 envelope when identifier missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_room', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Room name or ID is required')

        where:
        useGateways << [true, false]
    }

    def "hub_delete_room posts to /room/delete/<id> and reports devices unassigned"() {
        given:
        enableWrite()
        def rooms = [
            [id: 5, name: 'Old Room', deviceIds: [300, 301]]
        ]
        installGetRoomsStub(rooms)
        installCookieStub()

        and: 'httpPost handler removes the room on delete-path requests'
        httpPostHandler = { Map params, Closure cb ->
            if (params.path == '/room/delete/5') {
                rooms.removeAll { it.id == 5 }
            }
            cb.call([status: 200, data: ''])
        }

        when:
        def result = script.toolDeleteRoom([room: '5', confirm: true])

        then:
        result.success == true
        result.deletedRoom.id == '5'
        result.deletedRoom.name == 'Old Room'
        result.devicesUnassigned == 2
        // W-spec-deleteRoom-text: pin the count-aware message text, not just the
        // structured field. A regression that drops the count-aware ternary would
        // surface as "1 devices are now unassigned" / "2 device is now unassigned"
        // -- structured asserts above would still pass.
        result.message.contains('2 devices are now unassigned')
        rooms == []
    }

    def "hub_delete_room with exactly one device renders 'device is' singular"() {
        // W-spec-deleteRoom-text (singular side): exercises the count-aware ternary
        // at L12702-12703 for the deviceCount==1 branch.
        // Both-ways pending (orchestrator).
        given:
        enableWrite()
        def rooms = [
            [id: 5, name: 'Solo Room', deviceIds: [400]]
        ]
        installGetRoomsStub(rooms)
        installCookieStub()

        and:
        httpPostHandler = { Map params, Closure cb ->
            if (params.path == '/room/delete/5') {
                rooms.removeAll { it.id == 5 }
            }
            cb.call([status: 200, data: ''])
        }

        when:
        def result = script.toolDeleteRoom([room: '5', confirm: true])

        then:
        result.success == true
        result.devicesUnassigned == 1
        result.message.contains('1 device is now unassigned')
        !result.message.contains('devices are')
    }

    @spock.lang.Unroll
    def "hub_delete_room via dispatch posts to /room/delete and reports devices unassigned (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def rooms = [
            [id: 5, name: 'Old Room', deviceIds: [300, 301]]
        ]
        installGetRoomsStub(rooms)
        installCookieStub()

        and:
        httpPostHandler = { Map params, Closure cb ->
            if (params.path == '/room/delete/5') {
                rooms.removeAll { it.id == 5 }
            }
            cb.call([status: 200, data: ''])
        }

        when:
        def response = mcpDriver.callTool('hub_delete_room', [room: '5', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.deletedRoom.id == '5'
        inner.deletedRoom.name == 'Old Room'
        inner.devicesUnassigned == 2
        rooms == []

        where:
        useGateways << [true, false]
    }

    def "hub_delete_room falls back to GET /room/delete/<id> when POST fails"() {
        given:
        enableWrite()
        def rooms = [
            [id: 12, name: 'Sunroom', deviceIds: [400]]
        ]
        installGetRoomsStub(rooms)
        installCookieStub()

        and: 'POST fails loudly — should force the tool to try the GET fallback'
        httpPostHandler = { Map params, Closure cb ->
            throw new RuntimeException('Simulated POST failure')
        }

        and: 'the GET fallback mutates the rooms list so the post-delete verification passes'
        hubGet.register('/room/delete/12') { params ->
            rooms.removeAll { it.id == 12 }
            return ''
        }

        when:
        def result = script.toolDeleteRoom([room: '12', confirm: true])

        then: 'deletion succeeds via the GET path'
        result.success == true
        result.deletedRoom.id == '12'
        rooms == []
    }

    @spock.lang.Unroll
    def "hub_delete_room via dispatch falls back to GET when POST fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def rooms = [
            [id: 12, name: 'Sunroom', deviceIds: [400]]
        ]
        installGetRoomsStub(rooms)
        installCookieStub()

        and:
        httpPostHandler = { Map params, Closure cb ->
            throw new RuntimeException('Simulated POST failure')
        }

        and:
        hubGet.register('/room/delete/12') { params ->
            rooms.removeAll { it.id == 12 }
            return ''
        }

        when:
        def response = mcpDriver.callTool('hub_delete_room', [room: '12', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.deletedRoom.id == '12'
        rooms == []

        where:
        useGateways << [true, false]
    }

    def "hub_delete_room throws when the target room does not exist"() {
        given:
        enableWrite()
        installGetRoomsStub([
            [id: 1, name: 'Kitchen', deviceIds: []]
        ])

        when:
        script.toolDeleteRoom([room: 'Garage', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("not found")
        ex.message.contains('Kitchen')
    }

    @spock.lang.Unroll
    def "hub_delete_room via dispatch returns -32602 envelope when target room does not exist (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([
            [id: 1, name: 'Kitchen', deviceIds: []]
        ])

        when:
        def response = mcpDriver.callTool('hub_delete_room', [room: 'Garage', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found')
        response.error.message.contains('Kitchen')

        where:
        useGateways << [true, false]
    }

    // -------- toolRenameRoom --------

    def "hub_update_room posts to /room/save with the existing id and reports success"() {
        given:
        enableWrite()
        def rooms = [
            [id: 8, name: 'Office', deviceIds: [600]]
        ]
        installGetRoomsStub(rooms)
        installCookieStub()

        and: 'httpPost handler renames the existing room entry'
        def capturedBody = null
        httpPostHandler = { Map params, Closure cb ->
            capturedBody = new JsonSlurper().parseText(params.body)
            if (params.path == '/room/save' && capturedBody.roomId != 0) {
                def room = rooms.find { it.id == capturedBody.roomId }
                if (room) room.name = capturedBody.name
            }
            cb.call([status: 200, data: ''])
        }

        when:
        def result = script.toolRenameRoom([room: '8', newName: 'Home Office', confirm: true])

        then:
        capturedBody.roomId == 8
        capturedBody.name == 'Home Office'
        rooms[0].name == 'Home Office'
        result.success == true
    }

    @spock.lang.Unroll
    def "hub_update_room via dispatch posts to /room/save with existing id and reports success (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def rooms = [
            [id: 8, name: 'Office', deviceIds: [600]]
        ]
        installGetRoomsStub(rooms)
        installCookieStub()

        and:
        def capturedBody = null
        httpPostHandler = { Map params, Closure cb ->
            capturedBody = new JsonSlurper().parseText(params.body)
            if (params.path == '/room/save' && capturedBody.roomId != 0) {
                def room = rooms.find { it.id == capturedBody.roomId }
                if (room) room.name = capturedBody.name
            }
            cb.call([status: 200, data: ''])
        }

        when:
        def response = mcpDriver.callTool('hub_update_room', [room: '8', newName: 'Home Office', confirm: true])

        then:
        capturedBody.roomId == 8
        capturedBody.name == 'Home Office'
        rooms[0].name == 'Home Office'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true

        where:
        useGateways << [true, false]
    }

    def "hub_update_room locates the target room by case-insensitive name"() {
        given:
        enableWrite()
        def rooms = [
            [id: 15, name: 'Playroom', deviceIds: [700]]
        ]
        installGetRoomsStub(rooms)
        installCookieStub()

        and: 'httpPost handler captures the body and mutates the rooms list'
        def capturedBody = null
        httpPostHandler = { Map params, Closure cb ->
            capturedBody = new JsonSlurper().parseText(params.body)
            def room = rooms.find { it.id == capturedBody.roomId }
            if (room) room.name = capturedBody.name
            cb.call([status: 200, data: ''])
        }

        when: 'looked up by lowercased name (not by id)'
        def result = script.toolRenameRoom([room: 'playroom', newName: 'Kids Room', confirm: true])

        then: 'the existing room id is used in the save body and the rename takes effect'
        capturedBody.roomId == 15
        capturedBody.name == 'Kids Room'
        rooms[0].name == 'Kids Room'
        result.success == true
    }

    @spock.lang.Unroll
    def "hub_update_room via dispatch locates the target room by case-insensitive name (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def rooms = [
            [id: 15, name: 'Playroom', deviceIds: [700]]
        ]
        installGetRoomsStub(rooms)
        installCookieStub()

        and:
        def capturedBody = null
        httpPostHandler = { Map params, Closure cb ->
            capturedBody = new JsonSlurper().parseText(params.body)
            def room = rooms.find { it.id == capturedBody.roomId }
            if (room) room.name = capturedBody.name
            cb.call([status: 200, data: ''])
        }

        when:
        def response = mcpDriver.callTool('hub_update_room', [room: 'playroom', newName: 'Kids Room', confirm: true])

        then:
        capturedBody.roomId == 15
        capturedBody.name == 'Kids Room'
        rooms[0].name == 'Kids Room'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true

        where:
        useGateways << [true, false]
    }

    def "hub_update_room throws when newName is missing"() {
        given:
        enableWrite()
        installGetRoomsStub([
            [id: 8, name: 'Office', deviceIds: []]
        ])

        when:
        script.toolRenameRoom([room: '8', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('New room name is required')
    }

    @spock.lang.Unroll
    def "hub_update_room via dispatch returns -32602 envelope when newName is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([
            [id: 8, name: 'Office', deviceIds: []]
        ])

        when:
        def response = mcpDriver.callTool('hub_update_room', [room: '8', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('New room name is required')

        where:
        useGateways << [true, false]
    }

    def "hub_update_room rejects a name that would collide with a different room"() {
        given:
        enableWrite()
        installGetRoomsStub([
            [id: 1, name: 'Kitchen', deviceIds: []],
            [id: 2, name: 'Bedroom', deviceIds: []]
        ])

        when:
        script.toolRenameRoom([room: '1', newName: 'BEDROOM', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("already exists")
    }

    @spock.lang.Unroll
    def "hub_update_room via dispatch returns -32602 envelope on colliding name (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        installGetRoomsStub([
            [id: 1, name: 'Kitchen', deviceIds: []],
            [id: 2, name: 'Bedroom', deviceIds: []]
        ])

        when:
        def response = mcpDriver.callTool('hub_update_room', [room: '1', newName: 'BEDROOM', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('already exists')

        where:
        useGateways << [true, false]
    }
}
