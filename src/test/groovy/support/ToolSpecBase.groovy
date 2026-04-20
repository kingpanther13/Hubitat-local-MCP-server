package support

/**
 * Base class for server-tool specs. Extends HarnessSpec — the fixture maps
 * (settingsMap, childDevicesList, childAppsList) already live there since
 * they're wired into the sandbox at compile time. This subclass just adds
 * a no-op log stub via metaclass so the server's `log.info` / `log.debug`
 * / etc. calls don't NPE.
 *
 * Subclasses seed fixtures in their `given:` blocks before calling tool
 * methods on `script`.
 */
abstract class ToolSpecBase extends HarnessSpec {

    @Override
    protected void wireScriptOverrides() {
        super.wireScriptOverrides()
        script.metaClass.getLog = { -> new LogStub() }
    }

    /** No-op logger so the server's log.* calls don't NPE. */
    static class LogStub {
        def info(Object m) {}
        def debug(Object m) {}
        def warn(Object m) {}
        def error(Object m) {}
        def trace(Object m) {}
    }
}
