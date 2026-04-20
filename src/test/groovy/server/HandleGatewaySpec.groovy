package server

import support.ToolSpecBase

/**
 * Spec for hubitat-mcp-server.groovy::handleGateway.
 *
 * Covers catalog mode (no toolName), unknown-gateway and unknown-tool
 * errors, the effective rejection of gateway-as-tool, missing-required
 * soft error (Option D: isError response, not an exception), and valid
 * dispatch delegating to executeTool.
 */
class HandleGatewaySpec extends ToolSpecBase {

    def "catalog mode returns gateway's tool schemas when no toolName is supplied"() {
        when:
        def result = script.handleGateway('manage_rooms', null, null)

        then:
        result.gateway == 'manage_rooms'
        result.mode == 'catalog'
        result.tools instanceof List
        result.tools*.name == ['list_rooms', 'get_room', 'create_room', 'delete_room', 'rename_room']
        result.tools.every { it.description && it.inputSchema }
    }

    def "throws IllegalArgumentException for unknown gateway"() {
        when:
        script.handleGateway('manage_fake_gateway', 'any_tool', [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Unknown gateway: manage_fake_gateway'
    }

    def "throws IllegalArgumentException for a tool that is not in the called gateway"() {
        when:
        // list_files belongs to manage_files, not manage_rooms
        script.handleGateway('manage_rooms', 'list_files', [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.startsWith("Unknown tool 'list_files' in manage_rooms")
        ex.message.contains('Available:')
        ex.message.contains('list_rooms')
    }

    def "using a gateway name as a tool fails"() {
        // handleGateway's defensive recursive-call guard
        // ("Cannot call a gateway from within a gateway") is unreachable
        // given current configs — gateway names and tool names are
        // disjoint namespaces, so the unknown-tool check fires first.
        // This test pins the effective behaviour: attempting to invoke a
        // gateway by name as a tool is rejected.
        when:
        script.handleGateway('manage_rooms', 'manage_rooms', [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.startsWith("Unknown tool 'manage_rooms' in manage_rooms")
        ex.message.contains('Available:')
    }

    def "missing required parameters returns isError response (does NOT throw)"() {
        when:
        // get_room requires `room`; omit it.
        def result = script.handleGateway('manage_rooms', 'get_room', [:])

        then: 'Option D behaviour: soft error response, not an exception'
        notThrown(IllegalArgumentException)
        result.isError == true
        result.tool == 'get_room'
        result.error.contains('Missing required parameter')
        result.error.contains('room')
        result.parameters.contains('room')
    }

    def "valid dispatch delegates to executeTool"() {
        given: 'list_rooms calls getRooms() on the Hubitat SDK — stub it on the script'
        script.metaClass.getRooms = { ->
            [
                [id: 1, name: 'Kitchen', deviceIds: []],
                [id: 2, name: 'Living Room', deviceIds: []]
            ]
        }

        when:
        def result = script.handleGateway('manage_rooms', 'list_rooms', [:])

        then: 'the sub-tool ran and its return shape came through'
        !result.isError
        result.rooms instanceof List
        result.rooms.size() == 2
        result.rooms*.name.containsAll(['Kitchen', 'Living Room'])
        result.count == 2
    }
}
