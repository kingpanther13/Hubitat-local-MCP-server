package support

/**
 * Base class for server-tool specs. Extends HarnessSpec with per-spec fixtures
 * the tool handlers need: settings map (selectedDevices, enableHubAdminRead,
 * etc.), child devices list (getChildDevices()), child apps list
 * (getChildApps()), and a deterministic now().
 *
 * Subclasses seed these in their `given:` blocks before calling tool methods.
 */
abstract class ToolSpecBase extends HarnessSpec {
    protected Map settingsMap = [selectedDevices: []]
    protected List childDevicesList = []
    protected List childAppsList = []

    @Override
    protected void wireScriptOverrides() {
        super.wireScriptOverrides()
        def settingsRef = settingsMap
        def childDevicesRef = childDevicesList
        def childAppsRef = childAppsList
        script.metaClass.getSettings = { -> settingsRef }
        script.metaClass.getChildDevices = { -> childDevicesRef }
        script.metaClass.getChildApps = { -> childAppsRef }
        script.metaClass.now = { -> 1234567890000L }
        script.metaClass.getLog = { -> new LogStub() }
    }

    /** No-op logger so the server's log.info / log.debug / etc. calls don't NPE. */
    static class LogStub {
        def info(Object m) {}
        def debug(Object m) {}
        def warn(Object m) {}
        def error(Object m) {}
        def trace(Object m) {}
    }
}
