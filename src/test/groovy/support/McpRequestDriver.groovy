package support

import groovy.json.JsonSlurper

/**
 * Drives {@code handleMcpRequest()} through its in-process request pipeline:
 * the script reads {@code request.JSON}, dispatches JSON-RPC, and writes back
 * through {@code render(Map)}. This is the tier-2 integration dispatch seam —
 * one JVM, Spock Mocks, no real HTTP boundary — distinct from the tier-1
 * unit specs that call {@code script.handleToolsCall(msg)} directly and from
 * the tier-3 fake-hub work tracked in #77.
 *
 * The existing server specs call JSON-RPC-layer methods directly
 * ({@code script.handleToolsCall(msg)} etc.), which covers the JSON-RPC
 * dispatch + tool execution, but skips two hub-runtime seams:
 *
 *   1. {@code request.JSON} — the sandbox dynamic property the hub populates
 *      with the parsed POST body. Production code must survive missing,
 *      unparsable, and weirdly-shaped bodies without crashing. The getter
 *      itself can throw on malformed bodies (see {@link #pushBodyThrowing});
 *      the app wraps the read in try/catch to turn that into a
 *      JSON-RPC -32700 response.
 *   2. {@code render(Map)} — the hub-side response writer. Production code
 *      assembles status / contentType / data and hands them to render; tests
 *      that bypass render never verify the envelope the hub actually sends.
 *
 * Both are in the {@code handleMcpRequest()} path (see
 * hubitat-mcp-server.groovy around line 284). This driver plugs into the
 * harness so specs can push a body, invoke {@code handleMcpRequest}, and
 * read the captured render args.
 *
 * Wiring (done by {@link HarnessSpec}):
 *   - {@code render(Map)} is declared on {@code AppExecutor} — stubbed in
 *     {@code setupSpec()} via the {@code >>} dispatcher pattern that
 *     captures the Map into {@link #lastRenderArgs}. This matches the
 *     dispatch cheat sheet in docs/testing.md.
 *   - {@code request} is resolved via {@code HubitatAppScript}'s
 *     {@code @CompileStatic getProperty(String)} override, which
 *     short-circuits the name {@code "request"} to
 *     {@code injectedMappingHandlerData['request']} before MOP dispatch.
 *     metaClass hooks are never consulted for this name, so the driver's
 *     {@link #scriptRequest} proxy is written directly into that private
 *     field via reflection. The write happens per-test in
 *     {@code HarnessSpec.setup()} (via {@code wireScriptOverrides()}) —
 *     not once in {@code setupSpec()} — because {@code setup()} wipes the
 *     script's metaClass and the re-wire guarantees the field survives.
 *     Since {@link #scriptRequest} is a stable instance that dispatches
 *     its {@code getJSON()} at access time based on {@link #throwingRequest}
 *     / {@link #request}, state changes made in a test's {@code given:}
 *     block ({@link #pushBody} / {@link #pushBodyThrowing}) take effect
 *     without re-wiring.
 *
 * State lifecycle: {@link #reset()} is called from the harness's per-test
 * {@code setup()} so each feature starts with an empty pending body and no
 * captured render output. The instance itself is {@code @Shared}; its
 * references stay stable while their contents get cleared.
 */
class McpRequestDriver {

    private static final JsonSlurper SLURPER = new JsonSlurper()

    /**
     * Backing store for the parsed body the script sees when it reads
     * {@code request.JSON}. {@link #pushBody} mutates this in place so the
     * reference stays stable across tests — the harness wires
     * {@link #scriptRequest} (which reads this) into the script's
     * {@code injectedMappingHandlerData['request']} slot.
     */
    final Map request = [JSON: null]

    /**
     * If non-null, {@link #scriptRequest#getJSON()} throws this instead of
     * returning {@link #request}.JSON. Set by {@link #pushBodyThrowing} to
     * exercise the {@code handleMcpRequest} try/catch -32700 branch at
     * hubitat-mcp-server.groovy:286-292; cleared by {@link #reset}.
     */
    Throwable throwingRequest = null

    /**
     * Proxy the harness wires into {@code injectedMappingHandlerData['request']}.
     * Its {@code getJSON()} dispatches dynamically at access time: if
     * {@link #throwingRequest} is set it throws, otherwise it returns the
     * current {@link #request}.JSON value. The indirection is what lets a
     * test's {@code given:} block call {@link #pushBody} or
     * {@link #pushBodyThrowing} and have it take effect without re-running
     * the harness's {@code wireScriptOverrides()}.
     */
    final ScriptRequestProxy scriptRequest = new ScriptRequestProxy(this)

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
        throwingRequest = null
        request.clear()
        request.JSON = body
    }

    /**
     * Stage a {@code request.JSON} access that throws the given Throwable.
     * Covers the {@code handleMcpRequest()} try/catch branch at
     * hubitat-mcp-server.groovy:286-292, which turns hub-side JSON parse
     * failures into JSON-RPC -32700 responses. {@link #pushBody}(null) only
     * hits the subsequent {@code requestBody == null} branch — it does not
     * exercise the catch.
     */
    void pushBodyThrowing(Throwable t) {
        throwingRequest = t
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
        throwingRequest = null
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
     * reaching any render — a bug to surface loudly, not swallow). Throws
     * with the captured args echoed if the body was non-empty but unparseable
     * JSON, so diagnosis points at the render layer rather than swallowing
     * the parse error.
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
        try {
            return SLURPER.parseText(data)
        } catch (Exception e) {
            throw new IllegalStateException(
                "render() data did not parse as JSON. Captured render args: " +
                "${lastRenderArgs}. Underlying parse error: ${e.message}", e)
        }
    }

    /**
     * Stand-in for the hub's {@code request} that the script's
     * {@code @CompileStatic getProperty("request")} returns (after the
     * harness wires this into {@code injectedMappingHandlerData['request']}).
     * Exposes a single {@code getJSON()} method that dispatches dynamically
     * at access time — necessary because the harness wires once per test in
     * {@code setup()} (before {@code given:}), and test blocks need to be
     * able to stage a throwing or non-throwing body without re-running the
     * wire step.
     */
    static class ScriptRequestProxy {
        private final McpRequestDriver driver

        ScriptRequestProxy(McpRequestDriver driver) {
            this.driver = driver
        }

        /**
         * Called at each {@code request.JSON} access. Throws
         * {@link McpRequestDriver#throwingRequest} when set, otherwise
         * returns the currently-staged {@link McpRequestDriver#request}.JSON.
         */
        Object getJSON() {
            if (driver.throwingRequest != null) {
                throw driver.throwingRequest
            }
            return driver.request.JSON
        }
    }
}
