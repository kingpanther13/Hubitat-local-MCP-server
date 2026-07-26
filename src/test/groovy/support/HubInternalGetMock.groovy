package support

/**
 * Programmable stub for hubInternalGet(path, params).
 * Call register(path, responseClosure) to define a stubbed response;
 * calling hubInternalGet with a registered path returns the closure's
 * result; unregistered paths throw so tests fail loudly.
 *
 * A registration key may be either the bare path ({@code '/app/ajax/code'}) or the path with
 * its querystring ({@code '/device/updateLabel?deviceId=5&label=Den'}). Production passes
 * query parameters as a MAP -- an embedded '?' in the path is escaped into the literal path
 * by the platform client and 404s exact hub routes, which is why the query map is mandatory
 * (see the guard in {@code _hubRequest}) -- so this mock recomposes {@code path?k=v&k=v} from
 * the map and tries that key FIRST, falling back to the bare path. That keeps a
 * query-bearing registration meaningful: it pins the exact parameters production sent, and it
 * matches the pre-query-map spelling byte for byte, so a conversion that changed the wire
 * form would fail its existing test rather than silently pass.
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
