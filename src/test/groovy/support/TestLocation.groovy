package support

import groovy.transform.AutoImplement
import me.biocomp.hubitat_ci.api.common_api.Location

/**
 * Location stub for server tool specs that exercise `location.hub`, `location.mode`,
 * or other Location properties. `@AutoImplement` fills in the large Location interface
 * with default null/zero returns — tests override the handful of methods they care about
 * by constructing instances with overridden closures or setting `hub` directly.
 *
 * `hub` is a non-interface field because Hubitat's real Location exposes `hub` as a
 * magic property (single-hub convenience over getHubs().first()) that eighty20results'
 * `Location` interface doesn't declare. Tests set `new TestLocation(hub: [...])` to
 * drive `location.hub` reads in tools like toolGetZwaveDetails / toolGetZigbeeDetails /
 * toolGetHubPerformance. Methods declared on the Location interface stay overridable
 * via subclass or `metaClass` assignment.
 */
@AutoImplement
class TestLocation implements Location {
    /** Populated by tests; read by the server via `location.hub`. Accepts any Map / Expando /
     *  POGO with the fields the tool under test consumes (e.g. zwaveVersion, uptime). */
    def hub

    // Common overrides with sensible defaults — tests can override per-instance
    // via `metaClass.getMode = { -> 'Night' }` when they need to.
    @Override String getMode() { 'Home' }
    @Override String getName() { 'TestLocation' }
    @Override Long getId() { 1L }
}
