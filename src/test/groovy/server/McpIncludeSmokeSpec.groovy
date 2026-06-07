package server

import support.ToolSpecBase

/**
 * The load-bearing #include proof (issue #209): the harness compiles hubitat-mcp-server.groovy
 * with `#include mcp.McpSmokeTestLib` resolved by support.IncludeResolver (the same path the hub
 * takes). If the include didn't resolve, mcpSmokeTestMarker() would not exist on the compiled app
 * and this would throw MissingMethodException -- exactly the "library failed to load" failure the
 * smoke test is meant to catch.
 */
class McpIncludeSmokeSpec extends ToolSpecBase {

    def "the #include'd McpSmokeTestLib marker is inlined onto the compiled app"() {
        expect:
        script.mcpSmokeTestMarker() == 'smoke-ok-v1'
    }
}
