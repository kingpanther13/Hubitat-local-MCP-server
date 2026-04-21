package server

import groovy.json.JsonSlurper
import spock.lang.Shared

import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for the manage_rooms gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolListRooms   -> list_rooms
 * - toolGetRoom     -> get_room
 * - toolCreateRoom  -> create_room   (destructive — Hub Admin Write + confirm)
 * - toolDeleteRoom  -> delete_room   (destructive — Hub Admin Write + confirm)
 * - toolRenameRoom  -> rename_room   (destructive — Hub Admin Write + confirm)
 *
 * Mocking strategy: see docs/testing.md "Which interception point to use".
 * This spec is the canonical example of the setupSpec-dispatcher pattern
 * for platform methods on BaseExecutor — httpPost is intercepted via a
 * permanent `_ *` stub in setupSpec() that dispatches to a per-feature
 * @Shared Closure handler (httpPostHandler). getRooms / getHubSecurityCookie
 * are purely dynamic, stubbed per-test on script.metaClass.
 *
 * Destructive tools require:
 *   settingsMap.enableHubAdminWrite = true
 *   stateMap.lastBackupTimestamp    = current 'now' (1234567890000L)
 *   args.confirm                    = true
 */
class ToolRoomsSpec extends ToolSpecBase {

    private void installGetRoomsStub(List<Map> roomsList) {
        script.metaClass.getRooms = { -> roomsList }
    }

    private void installCookieStub() {
        script.metaClass.getHubSecurityCookie = { -> null }
    }

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
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

    def "list_rooms returns an empty result when no rooms are configured"() {
        given:
        script.metaClass.getRooms = { -> null }

        when:
        def result = script.toolListRooms()

        then:
        result.rooms == []
        result.count == 0
        result.message.contains('No rooms')
    }

    def "list_rooms returns sorted rooms with device counts"() {
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

    def "list_rooms handles rooms that omit deviceIds entirely"() {
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

    // -------- toolGetRoom --------

    def "get_room returns room details when located by name (case-insensitive)"() {
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

    def "get_room returns room details when located by id"() {
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

    def "get_room throws with available-rooms list when the identifier is unknown"() {
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

    def "get_room throws when room identifier is empty"() {
        when:
        script.toolGetRoom('')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Room name or ID is required')
    }

    def "get_room expands deviceIds into device details and flags unresolvable ids as not-accessible"() {
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

    // -------- toolCreateRoom --------

    def "create_room throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolCreateRoom([name: 'Garage'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "create_room throws when Hub Admin Write is disabled"() {
        when:
        script.toolCreateRoom([name: 'Garage', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "create_room throws when required name is blank"() {
        given:
        enableHubAdminWrite()
        installGetRoomsStub([])

        when:
        script.toolCreateRoom([name: '  ', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Room name is required')
    }

    def "create_room posts to /room/save and reports success"() {
        given:
        enableHubAdminWrite()
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

    def "create_room rejects duplicate room names (case-insensitive)"() {
        given:
        enableHubAdminWrite()
        installGetRoomsStub([
            [id: 1, name: 'Garage', deviceIds: []]
        ])

        when:
        script.toolCreateRoom([name: 'GARAGE', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("already exists")
    }

    // -------- toolDeleteRoom --------

    def "delete_room throws when room identifier is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteRoom([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Room name or ID is required')
    }

    def "delete_room posts to /room/delete/<id> and reports devices unassigned"() {
        given:
        enableHubAdminWrite()
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
        rooms == []
    }

    def "delete_room falls back to GET /room/delete/<id> when POST fails"() {
        given:
        enableHubAdminWrite()
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

    def "delete_room throws when the target room does not exist"() {
        given:
        enableHubAdminWrite()
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

    // -------- toolRenameRoom --------

    def "rename_room posts to /room/save with the existing id and reports success"() {
        given:
        enableHubAdminWrite()
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

    def "rename_room locates the target room by case-insensitive name"() {
        given:
        enableHubAdminWrite()
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

    def "rename_room throws when newName is missing"() {
        given:
        enableHubAdminWrite()
        installGetRoomsStub([
            [id: 8, name: 'Office', deviceIds: []]
        ])

        when:
        script.toolRenameRoom([room: '8', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('New room name is required')
    }

    def "rename_room rejects a name that would collide with a different room"() {
        given:
        enableHubAdminWrite()
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
}
