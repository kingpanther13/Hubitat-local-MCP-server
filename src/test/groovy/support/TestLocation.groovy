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
 * Rule-engine specs (issue #75) additionally read/mutate {@code mode},
 * {@code sunrise}, {@code sunset}, and {@code hsmStatus}. {@code hsmStatus} is
 * not declared on HubitatCI's {@code Location} interface, but real Hubitat
 * exposes it dynamically; Groovy property dispatch resolves the field on this
 * class at runtime. The {@code set_mode} action calls {@code setMode(...)} on
 * the Location, so setMode captures invocations into {@code modeSetCalls} for
 * spec assertions without relying on Spock interaction counts on a
 * delegated-property accessor.
 *
 * {@code hub} is typed as {@link Hub} so Groovy auto-generates a {@code Hub getHub()}
 * that satisfies the interface (a raw {@code def hub} generates
 * {@code Object getHub()} and fails to override the interface method). Tests assign
 * a {@link TestHub} instance with the fields their tool under test reads (e.g.
 * {@code zwaveVersion}, {@code uptime}).
 */
@AutoImplement
class TestLocation implements Location {
    /** Populated by tests; read by the server via `location.hub`. */
    Hub hub

    // Backing field for mode so getMode/setMode can be implemented explicitly
    // (the interface declares both, and capturing setMode calls requires
    // the hand-written setter below).
    private String _mode = 'Home'

    /** setMode() invocations recorded for set_mode action assertions. */
    List<String> modeSetCalls = []

    /** Mutable — specs assign in given: to drive sun_position / time-related logic. */
    Date sunrise

    /** Mutable — specs assign in given: to drive sun_position / time-related logic. */
    Date sunset

    /**
     * Mutable. Not declared on the Location interface, but `location.hsmStatus`
     * in the rule engine resolves via Groovy property dispatch on the concrete
     * object, which finds this field.
     */
    String hsmStatus

    @Override String getMode() { _mode }
    @Override void setMode(String m) { _mode = m; modeSetCalls << m }
    @Override String getName() { 'TestLocation' }
    @Override Long getId() { 1L }
}
