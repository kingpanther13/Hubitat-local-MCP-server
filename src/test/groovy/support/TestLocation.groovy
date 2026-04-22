package support

import groovy.transform.AutoImplement
import me.biocomp.hubitat_ci.api.common_api.Hub
import me.biocomp.hubitat_ci.api.common_api.Location

/**
 * Location stub for server tool specs that exercise `location.hub`, `location.mode`,
 * or other Location properties. `@AutoImplement` fills in the large Location interface
 * with default null/zero returns — tests override the handful of methods they care about
 * by constructing instances or assigning to `hub` directly.
 *
 * `hub` is typed as {@link Hub} so Groovy auto-generates a {@code Hub getHub()} that
 * satisfies the interface (a raw {@code def hub} generates {@code Object getHub()} and
 * fails to override the interface method). Tests assign a {@link TestHub} instance
 * with the fields their tool under test reads (e.g. {@code zwaveVersion}, {@code uptime}).
 */
@AutoImplement
class TestLocation implements Location {
    /** Populated by tests; read by the server via `location.hub`. */
    Hub hub

    // Common overrides with sensible defaults — tests can override per-instance
    // via `metaClass.getMode = { -> 'Night' }` when they need to.
    @Override String getMode() { 'Home' }
    @Override String getName() { 'TestLocation' }
    @Override Long getId() { 1L }
}
