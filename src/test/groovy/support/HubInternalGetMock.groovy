package support

/**
 * Programmable stub for hubInternalGet(path, params).
 * Call register(path, responseClosure) to define a stubbed response;
 * calling hubInternalGet with a registered path returns the closure's
 * result; unregistered paths throw so tests fail loudly.
 */
class HubInternalGetMock {
    private final Map<String, Closure> handlers = [:]
    final List<Map> calls = []

    void register(String path, Closure responder) {
        handlers[path] = responder
    }

    Object call(String path, Map params = [:]) {
        calls << [path: path, params: params]
        def handler = handlers[path]
        if (!handler) {
            throw new IllegalStateException("Unstubbed hubInternalGet: ${path} (params=${params})")
        }
        return handler(params)
    }
}
