package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Base class for server and rule-engine specs. Loads the real Groovy app
 * file into a HubitatCI sandbox on each test so specs exercise actual
 * handler code, not re-implementations.
 *
 * Uses sandbox.compile() (NOT run()) because the server uses multi-page
 * form `preferences { page(name: "mainPage") }` which HubitatCI's
 * AppPreferencesReader.page() mishandles. compile() sets DontRunScript
 * internally — definitions compile, preferences body doesn't execute.
 *
 * `settings` is wired via userSettingValues (AppPreferencesReader holds
 * onto the Map reference, so mutating it from a spec's `given:` block
 * updates what `script.settings.foo` sees). `getState`/`getAtomicState`/
 * `getChildDevices`/`now`/`getLog` are wired through the AppExecutor mock
 * — eighty20results' AppChildExecutor leaves these on the @Delegate path
 * to the supplied AppExecutor. `hubInternalGet` isn't on AppExecutor's
 * interface so it's metaclass-injected on the script.
 *
 * `addChildApp` and `getChildApps` are overridden on the script's
 * metaClass, not on the AppExecutor mock. eighty20results' HubitatAppScript
 * defines both as concrete methods that route through private factory
 * closures (childAppFactory → childAppRegistry), bypassing the mock's
 * @Delegate path. A per-instance metaClass override intercepts the
 * script-body dynamic dispatch that production tools use.
 *
 * `childAppResolver` is supplied so eighty20results' options validator
 * accepts the sandbox options; its closure body is never executed
 * because our addChildApp override short-circuits before any real
 * child-script resolution would occur.
 */
abstract class HarnessSpec extends Specification {
    protected AppExecutor appExecutor
    protected script
    protected Map stateMap = [:]
    protected Map atomicStateMap = [:]
    // Must be non-empty at setup() time — HubitatCI's readUserSettingValues
    // uses a Groovy truthy check on the passed map and silently swaps in a
    // fresh empty Map when it's empty, breaking the shared reference that
    // specs rely on to mutate settings from their `given:` blocks.
    protected Map settingsMap = [selectedDevices: []]
    protected List childDevicesList = []
    protected List childAppsList = []
    protected def mockChildAppForCreate  // tests set this to drive addChildApp's return value
    protected HubInternalGetMock hubGet = new HubInternalGetMock()

    def setup() {
        def sandbox = new HubitatAppSandbox(new File('hubitat-mcp-server.groovy'))
        def stateRef = stateMap
        def atomicStateRef = atomicStateMap
        def childDevicesRef = childDevicesList
        def self = this
        // Permissive log shim instead of Mock(Log): HubitatCI's Log
        // interface only declares single-arg level methods, but the real
        // Hubitat runtime also accepts (String, Throwable). A Proxy-based
        // Mock — or a Map coerced with `as Log` — rejects the 2-arg call
        // with MissingMethodException, which makes
        // handleToolsCall's generic-catch path (log.error(msg, e) at line
        // 415) untestable. A concrete class that implements Log and adds
        // the 2-arg overloads dispatches correctly under dynamic Groovy.
        // No spec currently asserts log interactions, so no behaviour regresses.
        def logMock = new PermissiveLog()
        appExecutor = Mock(AppExecutor) {
            _ * getState() >> stateRef
            _ * getAtomicState() >> atomicStateRef
            _ * getChildDevices() >> childDevicesRef
            _ * now() >> 1234567890000L
            _ * getLog() >> logMock
        }
        script = sandbox.compile(
            api: appExecutor,
            userSettingValues: settingsMap,
            childAppResolver: { String ns, String name -> null } as Closure,
            validationFlags: [
                Flags.DontValidatePreferences,
                Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy
            ]
        )
        wireScriptOverrides()
    }

    protected void wireScriptOverrides() {
        def hubGetRef = hubGet
        def childAppsRef = childAppsList
        def self = this
        script.metaClass.hubInternalGet = { String p, Map pp = [:], Integer t = 30 ->
            hubGetRef.call(p, pp)
        }
        // AppChildExecutor's addChildApp routes through eighty20results'
        // childAppBuilder closure, which requires a real on-disk child-app
        // source file and creates a wrapper with a sandbox-assigned id.
        // Tests want deterministic ids and a direct hook — override on
        // the script's metaClass so script-body calls to addChildApp hit
        // `mockChildAppForCreate` before the factory machinery runs.
        // Varargs intercepts both the 3-arg and 4-arg (with props Map)
        // overloads the production code uses.
        script.metaClass.addChildApp = { Object... args -> self.mockChildAppForCreate }
        // eighty20results' HubitatAppScript.getChildApps routes through a
        // private childAppAccessor closure backed by ChildAppRegistry —
        // tests can't stuff pre-existing mocks into that registry without
        // going through addChildApp (which assigns sandbox-controlled ids).
        // Override metaClass to return the spec-mutable childAppsList.
        script.metaClass.getChildApps = { -> childAppsRef }
    }
}
