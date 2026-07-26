package support

/**
 * Programmable stub for hubInternalGet(path, params). {@code register(key, closure)} defines a
 * response; an unregistered call throws so tests fail loudly. A key is either the bare path or
 * {@code path?k=v&k=v} -- production passes query parameters as a MAP, so {@link #composeKey}
 * recomposes that form and it is tried first, keeping a query-bearing registration meaningful.
 * Key values are RAW; the query map does the wire encoding, so registrations never pre-encode.
 *
 * {@link #call} re-enforces {@code _hubRequest}'s ?-in-path guard: the harness metaClass-shadows
 * hubInternalGet so production's guard never runs in a spec, and without it an embedded call and
 * a query-map call compose to the SAME key, leaving every conversion unpinned. Keys legitimately
 * contain '?', so only the path ARGUMENT is gated.
 */
class HubInternalGetMock {
    private final Map<String, Closure> handlers = [:]
    final List<Map> calls = []

    void register(String path, Closure responder) {
        handlers[path] = responder
    }

    /**
     * Recompose the wire form a query map produces: {@code path?k=v&k=v} in the map's
     * insertion order (Groovy map literals preserve it, so the key is deterministic). Values
     * are rendered as plain text and NOT URL-encoded, matching the query map's own contract --
     * production must not pre-encode, so a registration key must not either.
     */
    static String composeKey(String path, Map params) {
        if (!params) return path
        return path + '?' + params.collect { k, v -> "${k}=${v == null ? '' : v}" }.join('&')
    }

    Object call(String path, Map params = [:]) {
        // Mirror production's _hubRequest guard, which the harness's metaClass shadow bypasses.
        // Same exception type as production so a spec asserting the guard sees the real thing.
        if (path?.contains('?')) {
            throw new IllegalStateException(
                "hubInternal* path must not contain a querystring: '${path}'. The platform client " +
                "escapes an embedded '?' into the literal path (exact routes then 404) -- pass the " +
                "parameters as the query map instead. This mock enforces the same guard as " +
                "_hubRequest, which the harness metaClass shadow would otherwise bypass.")
        }
        String composed = composeKey(path, params)
        // `path` is what production actually passed (bare, no querystring -- that is the
        // contract now). `key` is the recomposed path?query form, which is what a spec
        // asserting "this exact call was made with these exact parameters" wants.
        calls << [path: path, params: params, key: composed]
        def handler = handlers[composed] ?: handlers[path]
        if (!handler) {
            throw new IllegalStateException(
                "Unstubbed hubInternalGet: ${path} (params=${params}). Tried keys: '${composed}' then '${path}'.")
        }
        return handler(params)
    }

    /** Clears registered handlers and recorded calls so a shared instance is safe to reuse across tests. */
    void reset() {
        handlers.clear()
        calls.clear()
    }
}
