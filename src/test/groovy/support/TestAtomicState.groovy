package support

/**
 * The harness's atomicState backing map, extended with the hub's
 * atomicState.updateMapValue(stateKey, key, value) shortcut (firmware 2.3.2+):
 * fetch the Map stored at stateKey, set one entry, store it back -- so specs
 * exercise the same per-entry write path production takes on a real hub. The
 * production callers guard with an init-if-null before calling, so the
 * missing-map branch here just mirrors that defensive shape.
 *
 * updateMapValueCalls counts invocations so a spec can pin that production
 * actually routed through the per-entry API (read it via .@updateMapValueCalls
 * -- plain property access on a Map subclass resolves to get(key)).
 */
class TestAtomicState extends LinkedHashMap {
    public int updateMapValueCalls = 0

    def updateMapValue(Object stateKey, Object key, Object value) {
        updateMapValueCalls++
        def m = this[stateKey]
        m = (m instanceof Map) ? new LinkedHashMap(m) : new LinkedHashMap()
        m[key] = value
        this[stateKey] = m
    }
}
