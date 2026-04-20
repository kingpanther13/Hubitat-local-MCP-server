package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Base class for server and rule-engine specs. Loads the real Groovy app
 * file into a HubitatCI sandbox on each test so specs exercise actual
 * handler code, not re-implementations. state/atomicState/hubInternalGet
 * overrides are wired AFTER sandbox load via Groovy metaclass on the
 * returned script object.
 */
abstract class HarnessSpec extends Specification {
    protected AppExecutor appExecutor
    protected script
    protected Map stateMap = [:]
    protected Map atomicStateMap = [:]
    protected HubInternalGetMock hubGet = new HubInternalGetMock()

    def setup() {
        def sandbox = new HubitatAppSandbox(new File('hubitat-mcp-server.groovy'))
        appExecutor = Mock(AppExecutor) {
            _ * getState() >> stateMap
            _ * getAtomicState() >> atomicStateMap
        }
        // compile() skips running the preferences/definition blocks but still
        // compiles all `def method() { }` definitions into the returned script
        // object. HubitatCI's page(name:...) handling doesn't tolerate the
        // multi-page form the server uses (page(name:"mainPage") without a
        // content closure), so running the body fails with "wrong number of
        // arguments" even with DontValidate* flags set. compile() avoids the
        // run path entirely; tests only need the tool-handler methods, not
        // the preferences tree.
        script = sandbox.compile(
            api: appExecutor,
            validationFlags: [
                Flags.DontValidatePreferences,
                Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy
            ]
        )
        wireScriptOverrides()
    }

    protected void wireScriptOverrides() {
        def stateRef = stateMap
        def atomicStateRef = atomicStateMap
        def hubGetRef = hubGet
        script.metaClass.getState = { -> stateRef }
        script.metaClass.getAtomicState = { -> atomicStateRef }
        script.metaClass.hubInternalGet = { String p, Map pp = [:] -> hubGetRef.call(p, pp) }
    }
}
