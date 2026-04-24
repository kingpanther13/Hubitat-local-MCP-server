package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

/**
 * Base class for server and rule-engine specs. Loads the real Groovy app
 * file into a HubitatCI sandbox once per spec class (in {@code setupSpec})
 * and reuses the compiled script across feature methods, with {@code setup}
 * clearing the shared fixture collections so tests stay isolated.
 *
 * Before this refactor, {@code setup()} re-read, re-parsed, re-AST-validated
 * and re-compiled the 8000+ line {@code hubitat-mcp-server.groovy} file on
 * every single feature method — ~3 seconds of pure compile overhead per
 * test, which added up to most of the CI test-job runtime. Caching the
 * sandbox+script at spec-class scope removes that overhead.
 *
 * Isolation contract: subclasses interact with the same set of protected
 * fields as before ({@code script}, {@code appExecutor}, {@code stateMap},
 * {@code atomicStateMap}, {@code settingsMap}, {@code childDevicesList},
 * {@code childAppsList}, {@code mockChildAppForCreate}, {@code hubGet}).
 * The collection fields keep stable references across tests — only the
 * contents reset between features — so any closure, delegate or metaClass
 * hook set up in {@code setupSpec} continues to see the current test's
 * state through the live map/list.
 *
 * Uses {@code sandbox.run()} with an explicit {@link PassThroughAppValidator}
 * (not {@code sandbox.compile()}) for two reasons:
 *
 * 1. {@code PassThroughAppValidator} swaps in {@code PassThroughSandboxClassLoader}
 *    so sandbox-loaded script references to {@code hubitat.helper.RMUtils} /
 *    {@code NetworkUtils} resolve to our literal-named main-source-set stubs
 *    instead of failing the JVM's §5.3.5 name-equality check against
 *    eighty20results' remapped {@code common_api.RMUtils}. Without this, any
 *    sandbox-compiled spec that calls into {@code hubitat.helper.*} (e.g. PR
 *    #79's {@code manage_rule_machine} gateway tools) throws NCDFE at runtime.
 * 2. {@code sandbox.compile()} eagerly adds {@code DontRunScript} to
 *    {@code validationFlags}, which flips {@code readValidator}'s precedence
 *    and silently discards the {@code validator:} option — so even if we pass
 *    {@code validator: new PassThroughAppValidator(...)}, {@code compile()}
 *    throws it away. {@code sandbox.run()} preserves the validator; we include
 *    {@code Flags.DontRunScript} in the validator's own flag set to keep
 *    {@code setupImpl} from invoking {@code script.run()}.
 *
 * {@code settings} is wired via userSettingValues (AppPreferencesReader
 * holds onto the Map reference from the {@code sandbox.run()} call, so
 * clearing and re-populating {@code settingsMap} from a spec's
 * {@code given:} block updates what {@code script.settings.foo} sees).
 * {@code getState}/{@code getAtomicState}/{@code getChildDevices}/
 * {@code now}/{@code getLog} are wired through the AppExecutor mock —
 * eighty20results' AppChildExecutor leaves these on the {@code @Delegate}
 * path to the supplied AppExecutor. Stubs on the shared mock return the
 * stable collection references, so clearing the collections each test
 * gives each feature a fresh view. {@code hubInternalGet} isn't on
 * AppExecutor's interface so it's metaclass-injected on the script.
 *
 * {@code addChildApp} and {@code getChildApps} can't be intercepted via
 * the AppExecutor mock: eighty20results' AppChildExecutor excludes both
 * from its {@code @Delegate} chain, and HubitatAppScript defines concrete
 * methods for each that route through private closure fields
 * ({@code childAppFactory}, {@code childAppAccessor}). Per-instance
 * metaClass overrides on those concrete superclass methods are skipped
 * by the script body's intra-class dispatch (verified empirically —
 * metaClass.addChildApp was bypassed in PR #100's first CI run). Instead,
 * we reflect into the private closure fields on HubitatAppScript and
 * replace them with test closures so the script's own
 * {@code addChildApp} / {@code getChildApps} / {@code getChildAppById}
 * implementations route to the spec's fixture maps.
 *
 * {@code childAppResolver} is still supplied so eighty20results' options
 * validator accepts the sandbox options; its closure body throws
 * {@code IllegalStateException} instead of returning null, because the
 * replacement {@code childAppFactory} should short-circuit before any
 * real child-script resolution ever happens — if it fires, that's a
 * loud signal the harness needs updating for an eighty20results API
 * change.
 */
abstract class HarnessSpec extends Specification {
    // Shared across every feature method in a given spec class — the
    // sandbox parse+compile (~3s for the 8000-line server file) is the
    // dominant cost, so amortising it across all tests in a class keeps
    // the CI job from scaling linearly with test count.
    @Shared protected AppExecutor appExecutor
    @Shared protected script
    @Shared private PermissiveLog sharedLog = new PermissiveLog()

    // Stable references — contents mutated by tests and cleared in setup().
    // The AppExecutor mock's stubs capture these references in setupSpec,
    // so per-test content updates are visible via the normal stub paths.
    @Shared protected final Map stateMap = [:]
    @Shared protected final Map atomicStateMap = [:]
    // Must be non-empty at sandbox.run() time — HubitatCI's
    // readUserSettingValues does a Groovy truthy check on the passed map
    // and silently swaps in a fresh empty Map when it's empty, breaking
    // the shared reference that specs rely on to mutate settings from
    // their given: blocks. setup() restores this seed entry after
    // clearing so every test starts from the same baseline.
    @Shared protected final Map settingsMap = [selectedDevices: []]
    @Shared protected final List childDevicesList = []
    @Shared protected final List childAppsList = []
    @Shared protected final HubInternalGetMock hubGet = new HubInternalGetMock()
    // Drives handleMcpRequest end-to-end: push a JSON body, let the
    // script's `request.JSON` resolve through here, capture the script's
    // `render(...)` call. Shared across features; reset() in setup() keeps
    // per-test state clean while the instance reference stays stable for
    // the metaClass + Mock stubs wired in setupSpec.
    @Shared protected final McpRequestDriver mcpDriver = new McpRequestDriver()

    // Per-test fixture — specs assign in given: blocks to drive
    // addChildApp's return value. Not @Shared: wireScriptOverrides() runs
    // in setup() each test, so the reflective childAppFactory closure
    // captures the current feature instance and reads this field from it.
    protected def mockChildAppForCreate

    def setupSpec() {
        def sandbox = new HubitatAppSandbox(new File('hubitat-mcp-server.groovy'))
        appExecutor = Mock(AppExecutor) {
            _ * getState() >> stateMap
            _ * getAtomicState() >> atomicStateMap
            _ * getChildDevices() >> childDevicesList
            _ * now() >> 1234567890000L
            _ * getLog() >> sharedLog
        }
        // render(Map) is declared on AppExecutor — class-2 of the dispatch
        // cheat sheet. Install a permanent dispatcher stub that routes every
        // render call into the McpRequestDriver, where specs can read back
        // the captured status/contentType/data. Script code like
        // `return render(...)` still gets a value back (the driver returns
        // the same Map). Without this, `handleMcpRequest()` short-circuits
        // with MissingMethodException inside the Mock's @Delegate chain.
        appExecutor.render(_) >> { args -> mcpDriver.captureRender(args[0] as Map) }
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
    }

    def setup() {
        // Reset every mutable fixture before each feature. Collections
        // keep their identity (the AppExecutor mock's stubs captured
        // references in setupSpec), so clear-and-repopulate rather than
        // reassign.
        stateMap.clear()
        atomicStateMap.clear()
        settingsMap.clear()
        settingsMap.selectedDevices = []
        childDevicesList.clear()
        childAppsList.clear()
        hubGet.reset()
        mcpDriver.reset()
        // Drop any per-test metaClass writes installed on the shared
        // script by previous features (e.g. individual specs' given:
        // blocks that do `script.metaClass.getRooms = { ... }`).
        // Without this the @Shared script would carry accumulated
        // overrides into the next feature, making tests order-dependent.
        // The standard hooks installed by wireScriptOverrides() below are
        // re-applied immediately so they're always present.
        GroovySystem.metaClassRegistry.removeMetaClass(script.getClass())
        // Re-run metaClass + reflective wires in setup (not setupSpec) so
        // closures capture the *current* Specification instance. This lets
        // subclasses override wireScriptOverrides() with closures that
        // reference non-@Shared spec fields (e.g. per-feature stubs) and
        // still see their own test's values.
        wireScriptOverrides()
    }

    protected void wireScriptOverrides() {
        def hubGetRef = hubGet
        def childAppsRef = childAppsList
        def self = this
        // `request` resolution inside the script: HubitatAppScript has an
        // @CompileStatic `getProperty(String)` override that short-circuits
        // the name "request" to `injectedMappingHandlerData['request']`
        // (verified via `javap -c` on HubitatAppScript.class — falls
        // through to MOP lookup only when that map is null). metaClass
        // hooks are never consulted for this name, so the per-feature
        // McpRequestDriver.request map is installed directly into that
        // private field. The map reference is stable across tests
        // (pushBody / reset mutate in place), so this wire-up is
        // idempotent — the reflective write just ensures we survive the
        // setup()-time metaClass wipe and any test that cleared the map
        // for its own reasons. See support/McpRequestDriver for why the
        // map is kept live rather than reassigned.
        def injectedField = me.biocomp.hubitat_ci.app.HubitatAppScript
            .getDeclaredField('injectedMappingHandlerData')
        injectedField.accessible = true
        Map injectedMap = injectedField.get(script) as Map
        if (injectedMap == null) {
            injectedMap = [:]
            injectedField.set(script, injectedMap)
        }
        injectedMap['request'] = mcpDriver.request
        // hubInternalGet has no declaration on HubitatAppScript — it's
        // pure dynamic Groovy resolved through metaClass, so the
        // per-instance metaClass write here intercepts cleanly. The
        // captured hubGetRef is the @Shared HubInternalGetMock whose
        // internal maps get reset() between tests, so a single wire-up
        // in setupSpec routes all tests to a fresh-feeling stub.
        script.metaClass.hubInternalGet = { String p, Map pp = [:], Integer t = 30 ->
            hubGetRef.call(p, pp)
        }
        // Replace HubitatAppScript's private factory closures so the
        // script's own concrete addChildApp / getChildApps / getChildAppById
        // route to spec-controlled fixtures. See class javadoc for why
        // metaClass overrides don't work here. Wired once against the
        // shared script; the closures read from @Shared fields so each
        // test's fixture content is visible without re-wiring.
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
