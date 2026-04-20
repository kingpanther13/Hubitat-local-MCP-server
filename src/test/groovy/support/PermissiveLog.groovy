package support

import me.biocomp.hubitat_ci.api.common_api.Log

/**
 * Test-only Log implementation that accepts both the single-arg
 * signatures declared on HubitatCI's Log interface and the
 * (String, Throwable) overloads that the real Hubitat runtime
 * accepts but the CI interface omits.
 *
 * Without this, calls like log.error("msg", exception) from
 * production code fail under a Mock(Log) or a Map-as-Log proxy
 * with MissingMethodException, making any code path that logs
 * an exception object untestable.
 */
class PermissiveLog implements Log {
    @Override void error(String message) {}
    @Override void warn(String message) {}
    @Override void info(String message) {}
    @Override void debug(String message) {}
    @Override void trace(String message) {}

    void error(String message, Throwable t) {}
    void warn(String message, Throwable t) {}
    void info(String message, Throwable t) {}
    void debug(String message, Throwable t) {}
    void trace(String message, Throwable t) {}
}
