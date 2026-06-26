package server

import support.ToolSpecBase

/**
 * The load-bearing #include proof (issue #209): the harness compiles hubitat-mcp-server.groovy
 * with every `#include mcp.*` resolved by support.IncludeResolver (the same path the hub takes).
 * If an include didn't resolve, the library's methods would not exist on the compiled app and a
 * call would throw MissingMethodException -- exactly the "library failed to load" failure this
 * smoke test is meant to catch. Asserting a method contributed by a real domain library (the Rooms
 * module, the first extracted module) keeps this honest about the production #include chain.
 */
class McpIncludeSmokeSpec extends ToolSpecBase {

    def "a method from an #include'd library is inlined onto the compiled app"() {
        expect: 'toolListRooms (defined in McpRoomsLib, pulled in via #include) resolves on the app'
        script.respondsTo('toolListRooms')
    }
}
