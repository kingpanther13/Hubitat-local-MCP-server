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
 * `getChildDevices`/`getChildApps`/`addChildApp`/`now` are wired through
 * the AppExecutor mock (Groovy resolves script.state, script.getChildDevices(),
 * etc. via the script's @Delegate to AppExecutor). `hubInternalGet` isn't
 * on AppExecutor's interface so it's metaclass-injected on the script.
 */
abstract class HarnessSpec extends Specification {
    protected AppExecutor appExecutor
    protected script
    protected Map stateMap = [:]
    protected Map atomicStateMap = [:]
    protected Map settingsMap = [:]
    protected List childDevicesList = []
    protected List childAppsList = []
    protected def mockChildAppForCreate  // tests set this to drive addChildApp's return value
    protected HubInternalGetMock hubGet = new HubInternalGetMock()

    def setup() {
        def sandbox = new HubitatAppSandbox(new File('hubitat-mcp-server.groovy'))
        def stateRef = stateMap
        def atomicStateRef = atomicStateMap
        def childDevicesRef = childDevicesList
        def childAppsRef = childAppsList
        def self = this
        appExecutor = Mock(AppExecutor) {
            _ * getState() >> stateRef
            _ * getAtomicState() >> atomicStateRef
            _ * getChildDevices() >> childDevicesRef
            _ * getChildApps() >> childAppsRef
            _ * addChildApp(_, _, _) >> { args -> self.mockChildAppForCreate }
            _ * now() >> 1234567890000L
        }
        script = sandbox.compile(
            api: appExecutor,
            userSettingValues: settingsMap,
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
        script.metaClass.hubInternalGet = { String p, Map pp = [:], Integer t = 30 ->
            hubGetRef.call(p, pp)
        }
    }
}
