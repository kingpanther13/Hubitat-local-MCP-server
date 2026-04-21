package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Base class for server and rule-engine specs. Loads the real Groovy app
 * file into a HubitatCI sandbox on each test so specs exercise actual
 * handler code, not re-implementations.
 *
 * Uses `sandbox.run()` with an explicit `PassThroughAppValidator` (not
 * `sandbox.compile()`) for two reasons:
 *
 * 1. `PassThroughAppValidator` swaps in `PassThroughSandboxClassLoader`
 *    so sandbox-loaded script references to `hubitat.helper.RMUtils` /
 *    `NetworkUtils` resolve to our literal-named main-source-set stubs
 *    instead of failing the JVM's §5.3.5 name-equality check against
 *    eighty20results' remapped `common_api.RMUtils`. Without this, any
 *    sandbox-compiled spec that calls into `hubitat.helper.*` (e.g. PR
 *    #79's `manage_rule_machine` gateway tools) throws NCDFE at runtime.
 * 2. `sandbox.compile()` eagerly adds `DontRunScript` to
 *    `validationFlags`, which flips `readValidator`'s precedence and
 *    silently discards the `validator:` option — so even if we pass
 *    `validator: new PassThroughAppValidator(...)`, `compile()` throws
 *    it away. `sandbox.run()` preserves the validator; we include
 *    `Flags.DontRunScript` in the validator's own flag set to keep
 *    `setupImpl` from invoking `script.run()`.
 *
 * `settings` is wired via userSettingValues (AppPreferencesReader holds
 * onto the Map reference, so mutating it from a spec's `given:` block
 * updates what `script.settings.foo` sees). `getState`/`getAtomicState`/
 * `getChildDevices`/`now`/`getLog` are wired through the AppExecutor mock
 * — eighty20results' AppChildExecutor leaves these on the @Delegate path
 * to the supplied AppExecutor. `hubInternalGet` isn't on AppExecutor's
 * interface so it's metaclass-injected on the script.
 *
 * `addChildApp` and `getChildApps` can't be intercepted via the
 * AppExecutor mock: eighty20results' AppChildExecutor excludes both from
 * its @Delegate chain, and HubitatAppScript defines concrete methods for
 * each that route through private closure fields (`childAppFactory`,
 * `childAppAccessor`). Per-instance metaClass overrides on those
 * concrete superclass methods are skipped by the script body's
 * intra-class dispatch (verified empirically — metaClass.addChildApp was
 * bypassed in PR #100's first CI run). Instead, we reflect into the
 * private closure fields on HubitatAppScript and replace them with test
 * closures so the script's own `addChildApp` / `getChildApps` /
 * `getChildAppById` implementations route to the spec's fixture maps.
 *
 * `childAppResolver` is still supplied so eighty20results' options
 * validator accepts the sandbox options; its closure body throws
 * `IllegalStateException` instead of returning null, because the
 * replacement `childAppFactory` should short-circuit before any real
 * child-script resolution ever happens — if it fires, that's a loud
 * signal the harness needs updating for an eighty20results API change.
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
        def validator = new PassThroughAppValidator([
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRunScript
        ])
        script = sandbox.run(
            api: appExecutor,
            userSettingValues: settingsMap,
            childAppResolver: { String ns, String name ->
                throw new IllegalStateException(
                    "childAppResolver fired for ${ns}:${name} — HarnessSpec's " +
                    "reflective childAppFactory replacement should have short-circuited " +
                    "first. Check that factoryField.set() succeeded.")
            } as Closure,
            validator: validator
        )
        wireScriptOverrides()
    }

    protected void wireScriptOverrides() {
        def hubGetRef = hubGet
        def childAppsRef = childAppsList
        def self = this
        // hubInternalGet has no declaration on HubitatAppScript — it's
        // pure dynamic Groovy resolved through metaClass, so the
        // per-instance metaClass write here intercepts cleanly.
        script.metaClass.hubInternalGet = { String p, Map pp = [:], Integer t = 30 ->
            hubGetRef.call(p, pp)
        }
        // Replace HubitatAppScript's private factory closures so the
        // script's own concrete addChildApp / getChildApps / getChildAppById
        // route to spec-controlled fixtures. See class javadoc for why
        // metaClass overrides don't work here.
        def factoryField = HubitatAppScript.getDeclaredField('childAppFactory')
        factoryField.accessible = true
        factoryField.set(script, { String ns, String name, String label, Map props = [:] ->
            if (self.mockChildAppForCreate == null) {
                throw new IllegalStateException(
                    "Spec invoked addChildApp(${ns}, ${name}, ${label}) but " +
                    "mockChildAppForCreate was not assigned. Set " +
                    "`mockChildAppForCreate = new TestChildApp(...)` in given:.")
            }
            self.mockChildAppForCreate
        } as Closure)

        def accessorField = HubitatAppScript.getDeclaredField('childAppAccessor')
        accessorField.accessible = true
        accessorField.set(script, { String op, Object arg = null ->
            switch (op) {
                case 'list':
                    return childAppsRef
                case 'get':
                    return childAppsRef.find { it.id?.toString() == arg?.toString() }
                case 'getByLabel':
                    return childAppsRef.find { it.label == arg }
                case 'delete':
                    childAppsRef.removeAll { it.id?.toString() == arg?.toString() }
                    return null
                default:
                    // Loud failure beats silent null-return if a future
                    // eighty20results release adds a new `op` verb.
                    throw new IllegalStateException(
                        "Unknown childAppAccessor op: '${op}' — the harness " +
                        "mock needs a new case; see src/test/groovy/support/HarnessSpec.groovy")
            }
        } as Closure)
    }
}
