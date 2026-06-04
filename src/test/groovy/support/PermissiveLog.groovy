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
    // Records emitted lines as "level:message" so specs can assert on log content
    // (e.g. URL redaction on the success-path log.debug). Recording is additive —
    // every method still accepts the call and never throws. The harness clears this
    // between feature methods. Coerce to plain String so GString identity quirks
    // don't bite under either Groovy runtime.
    final List<String> messages = []

    @Override void error(String message) { messages << ("error:" + message) }
    @Override void warn(String message) { messages << ("warn:" + message) }
    @Override void info(String message) { messages << ("info:" + message) }
    @Override void debug(String message) { messages << ("debug:" + message) }
    @Override void trace(String message) { messages << ("trace:" + message) }

    void error(String message, Throwable t) { messages << ("error:" + message) }
    void warn(String message, Throwable t) { messages << ("warn:" + message) }
    void info(String message, Throwable t) { messages << ("info:" + message) }
    void debug(String message, Throwable t) { messages << ("debug:" + message) }
    void trace(String message, Throwable t) { messages << ("trace:" + message) }
}
