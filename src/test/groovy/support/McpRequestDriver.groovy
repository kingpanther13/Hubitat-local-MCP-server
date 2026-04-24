package support

import groovy.json.JsonSlurper

/**
 * Drives {@code handleMcpRequest()} end-to-end through the HTTP pipeline
 * the MCP app actually presents on a real hub — parse {@code request.JSON},
 * dispatch JSON-RPC, write back through {@code render(...)}.
 *
 * The existing server specs call JSON-RPC-layer methods directly
 * ({@code script.handleToolsCall(msg)} etc.), which covers the JSON-RPC
 * dispatch + tool execution, but skips two hub-runtime seams:
 *
 *   1. {@code request.JSON} — the sandbox dynamic property the hub populates
 *      with the parsed POST body. Production code must survive missing,
 *      unparsable, and weirdly-shaped bodies without crashing.
 *   2. {@code render(Map)} — the hub-side response writer. Production code
 *      assembles status / contentType / data and hands them to render; tests
 *      that bypass render never verify the envelope the hub actually sends.
 *
 * Both are in the {@code handleMcpRequest()} path (see
 * hubitat-mcp-server.groovy around line 284). This driver plugs into the
 * harness so specs can push a body, invoke {@code handleMcpRequest}, and
 * read the captured render args — no JSON-RPC-layer short-cutting.
 *
 * Wiring (done by {@link HarnessSpec}):
 *   - {@code request} is purely dynamic (absent from every eighty20results
 *     interface and from HubitatAppScript) — installed via
 *     {@code script.metaClass.getRequest} returning {@link #request}.
 *   - {@code render(Map)} is declared on {@code AppExecutor} — stubbed in
 *     {@code setupSpec()} via the {@code >>} dispatcher pattern that
 *     captures the Map into {@link #lastRenderArgs}. This matches the
 *     dispatch cheat sheet in docs/testing.md.
 *
 * State lifecycle: {@link #reset()} is called from the harness's per-test
 * {@code setup()} so each feature starts with an empty pending body and no
 * captured render output. The instance itself is {@code @Shared}; its map
 * references stay stable while their contents get cleared.
 */
class McpRequestDriver {

    private static final JsonSlurper SLURPER = new JsonSlurper()

    /**
     * Fake request object the script sees when it calls
     * {@code request.JSON}. A Map is sufficient — Groovy property dispatch
     * on a Map resolves {@code map.JSON} to {@code map.get('JSON')}.
     *
     * This reference is wired into
     * {@code HubitatAppScript.injectedMappingHandlerData['request']} once
     * in {@code HarnessSpec.setupSpec()}. The harness's {@code setup()}
     * re-populates the map every test but keeps the same reference, so
     * mutations via {@code pushBody()} are always visible to the script's
     * {@code getProperty("request")} path without re-installing the hook.
     * Reassigning this field instead of mutating would dangle the old map
     * in the script — always mutate-in-place via {@code pushBody()}.
     */
    final Map request = [JSON: null]

    /**
     * Last {@code render(Map)} call args captured from the script. Tests
     * read this after invoking {@code handleMcpRequest()} to verify the
     * status / contentType / data that real hub clients would see.
     */
    Map lastRenderArgs = null

    /**
     * Stage a JSON-RPC request body to be returned from {@code request.JSON}
     * on the next {@code handleMcpRequest()} call. Accepts a Map (standard
     * request), a List (batch request), or {@code null} to simulate an
     * empty body (production handles that via the -32700 parse-error path).
     *
     * Mutates {@link #request} in place so the Map reference wired into
     * {@code injectedMappingHandlerData['request']} stays live across tests.
     */
    void pushBody(Object body) {
        request.clear()
        request.JSON = body
    }

    /**
     * Clear per-test fixture state. Called from the harness's {@code setup()}.
     * Mutates the existing {@link #request} map so the reference stays
     * stable for the {@code injectedMappingHandlerData['request']} wiring.
     */
    void reset() {
        request.clear()
        request.JSON = null
        lastRenderArgs = null
    }

    /**
     * Record the render call. Wired into the AppExecutor Mock's {@code >>}
     * stub — every {@code render(Map)} from the script lands here. Production
     * code's {@code return render(...)} expects a value back; the hub's real
     * render returns a response object, but from the script's perspective the
     * return value is ignored on the last line of a handler. Returning the
     * args Map lets specs that do introspect the return see something
     * identical to what was captured.
     */
    Object captureRender(Map args) {
        lastRenderArgs = args
        return args
    }

    /**
     * Parse the captured {@code data} as JSON and return the result. The
     * production code passes {@code data: groovy.json.JsonOutput.toJson(...)}
     * when the response has a body; for empty responses (204) {@code data}
     * is an empty string and this returns {@code null}. Throws if render
     * was never called (which means {@code handleMcpRequest} returned before
     * reaching any render — a bug to surface loudly, not swallow).
     */
    Object parseResponseJson() {
        if (lastRenderArgs == null) {
            throw new IllegalStateException(
                "No render() call captured. Did handleMcpRequest() short-circuit " +
                "before writing a response? Check the driver wiring in HarnessSpec " +
                "or the test's pushBody() call.")
        }
        String data = lastRenderArgs.data as String
        if (data == null || data.isEmpty()) {
            return null
        }
        return SLURPER.parseText(data)
    }
}
