package support

/**
 * Base class for server-tool specs. Extends HarnessSpec — the fixture maps
 * (settingsMap, childDevicesList, childAppsList) and all runtime shims
 * (state, atomicState, log, now) are already wired via HarnessSpec's
 * AppExecutor mock + userSettingValues. This class currently just exists
 * so tests can clearly express "this is a server tool spec" and we have a
 * place to add tool-test-specific helpers later.
 */
abstract class ToolSpecBase extends HarnessSpec {
}
