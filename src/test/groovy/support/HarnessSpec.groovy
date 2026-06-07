package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

/**
 * Base class for server-side specs. Loads the real Groovy app file into a
 * HubitatCI sandbox ONCE per JVM (first subclass's {@code setupSpec} fires
 * the compile, the result is cached as {@code SHARED_SCRIPT} for every
 * subsequent subclass to reuse). Every spec's {@code setupSpec} then builds
 * a fresh {@code AppExecutor} Mock and reflectively rebinds it onto
 * {@code SHARED_SCRIPT.api} via {@code API_FIELD}; per-test {@code setup}
 * clears the JVM-shared fixture collections and wipes both metaClass
 * levels so subclass-installed overrides don't leak across specs.
 *
 * Sandbox parse + AST + compile is multi-second on the large server file
 * and used to fire once per spec class — amortising it across every
 * subclass in the JVM is the dominant suite-time win.
 *
 * Isolation contract: subclasses interact with the same set of protected
 * fields as before ({@code script}, {@code appExecutor}, {@code stateMap},
 * {@code atomicStateMap}, {@code settingsMap}, {@code childDevicesList},
 * {@code childAppsList}, {@code mockChildAppForCreate}, {@code hubGet}).
 * Collection fields are JVM-static singletons (the {@code SHARED_*}
 * constants), aliased into each spec instance via {@code @Shared}; their
 * contents reset between features so any closure, delegate or metaClass
 * hook set up at compile time continues to see the current test's state
 * through the live map/list.
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
    // Per-JVM cache for the compiled script. sandbox.run() (parse + AST
    // + compile) is multi-second on the large server file and used to
    // fire once per spec class — amortising it across every subclass
    // is the dominant test-suite speedup.
    //
    // The AppExecutor Mock is built fresh per spec class (Spock 2.x
    // ties Mocks to their creating Spec's MockController via thread-
    // local registration in setupSpec, so a Mock created by spec A
    // has no controller wired up when spec B's setupSpec runs and
    // re-stubbing on it would no-op). Each spec's setupSpec builds
    // its own Mock and reflectively rebinds it onto SHARED_SCRIPT.api.
    // The script's userSettingsMap / preferencesReader wiring (bound
    // at sandbox.run time using the JVM-shared SHARED_SETTINGS_MAP)
    // stays valid because the Map reference is the same across specs.
    private static HubitatAppScript SHARED_SCRIPT
    private static final Object COMPILE_LOCK = new Object()
    private static final java.lang.reflect.Field API_FIELD = {
        def f = HubitatAppScript.getDeclaredField('api')
        f.accessible = true
        f
    }()
    private static final PermissiveLog SHARED_LOG = new PermissiveLog()
    private static final Map SHARED_STATE_MAP = [:]
    private static final Map SHARED_ATOMIC_STATE_MAP = [:]
    // Must be non-empty at sandbox.run() time — HubitatCI's
    // readUserSettingValues does a Groovy truthy check on the passed map
    // and silently swaps in a fresh empty Map when it's empty, breaking
    // the shared reference that specs rely on to mutate settings from
    // their given: blocks. setup() restores this seed entry after
    // clearing so every test starts from the same baseline.
    private static final Map SHARED_SETTINGS_MAP = [selectedDevices: []]
    private static final List SHARED_CHILD_DEVICES_LIST = []
    private static final List SHARED_CHILD_APPS_LIST = []
    private static final HubInternalGetMock SHARED_HUB_GET = new HubInternalGetMock()
    private static final McpRequestDriver SHARED_MCP_DRIVER = new McpRequestDriver()

    // Records parent-app unsubscribe() calls. A `1 * appExecutor.unsubscribe()`
    // cardinality check from a then-block doesn't fire reliably on the @Shared
    // AppExecutor mock (see RuleHarnessSpec's note), so route the call through this
    // counter and assert on it; lifecycle specs reset it in given: with .set(0).
    protected static final java.util.concurrent.atomic.AtomicInteger UNSUBSCRIBE_CALL_COUNT = new java.util.concurrent.atomic.AtomicInteger(0)

    @Shared protected AppExecutor appExecutor
    @Shared protected script
    @Shared protected final Map stateMap = SHARED_STATE_MAP
    @Shared protected final Map atomicStateMap = SHARED_ATOMIC_STATE_MAP
    @Shared protected final Map settingsMap = SHARED_SETTINGS_MAP
    @Shared protected final List childDevicesList = SHARED_CHILD_DEVICES_LIST
    @Shared protected final List childAppsList = SHARED_CHILD_APPS_LIST
    @Shared protected final HubInternalGetMock hubGet = SHARED_HUB_GET
    // Drives handleMcpRequest end-to-end: push a JSON body, let the
    // script's `request.JSON` resolve through here, capture the script's
    // `render(...)` call. Reset between features via mcpDriver.reset();
    // the instance reference stays stable across the JVM so wire-ups
    // installed against it remain valid.
    @Shared protected final McpRequestDriver mcpDriver = SHARED_MCP_DRIVER

    // Per-test fixture — specs assign in given: blocks to drive
    // addChildApp's return value. Not @Shared: wireScriptOverrides() runs
    // in setup() each test, so the reflective childAppFactory closure
    // captures the current feature instance and reads this field from it.
    protected def mockChildAppForCreate

    def setupSpec() {
        appExecutor = buildAppExecutorMock()
        // build.gradle pins maxParallelForks=1 and Spock's default test
        // ordering is sequential, so contention here is theoretical
        // today. The synchronized block keeps the cache correct if a
        // future config change ever enables parallel forks/threads
        // within one JVM.
        synchronized (COMPILE_LOCK) {
            if (SHARED_SCRIPT == null) {
                compileSharedScript()
            } else {
                rebindApi(appExecutor)
            }
        }
        script = SHARED_SCRIPT
    }

    private void rebindApi(AppExecutor mock) {
        try {
            API_FIELD.set(SHARED_SCRIPT, mock)
        } catch (Throwable t) {
            // Loud, contextual failure. If eighty20results renames or
            // re-types HubitatAppScript.api on an upgrade, the static
            // initializer for API_FIELD will throw at class-load time
            // (which surfaces clearly). But if the field exists with a
            // different shape (final modifier, incompatible type, etc.),
            // .set() fails here — and that happens AFTER the first spec
            // has run successfully, so a maintainer needs a hint to look
            // at HarnessSpec rather than their own spec.
            throw new IllegalStateException(
                "Failed to rebind AppExecutor on cached SHARED_SCRIPT for " +
                "${this.class.simpleName}. An eighty20results upgrade may have " +
                "changed HubitatAppScript.api field shape; see HarnessSpec " +
                "for the rebind contract.", t)
        }
    }

    private AppExecutor buildAppExecutorMock() {
        // Don't add `getApp() >> X` here — several specs (e.g. ToolManageLogsSpec,
        // ToolUpdateMcpSettingsSpec, AppLifecycleMigrationSpec) layer their own
        // additive `appExecutor.getApp() >> sharedAppStub` in setupSpec. The
        // Mock is per-spec, so stacking would happen WITHIN one spec (base
        // stub here + additive stub in subclass), and Spock's resolution order
        // between the two is version-dependent. To find current consumers if
        // the list above goes stale, grep `appExecutor.getApp() >>` under
        // src/test/groovy/.
        def mock = Mock(AppExecutor) {
            _ * getState() >> SHARED_STATE_MAP
            _ * getAtomicState() >> SHARED_ATOMIC_STATE_MAP
            _ * getChildDevices() >> SHARED_CHILD_DEVICES_LIST
            _ * now() >> 1234567890000L
            _ * getLog() >> SHARED_LOG
            // Script delegates `settings` reads to api.getSettings(). When we
            // rebind api per spec, we have to stub this explicitly — the
            // sandbox.run() path that normally wires it only runs on the
            // first spec.
            _ * getSettings() >> SHARED_SETTINGS_MAP
        }
        // render(Map) is declared on AppExecutor — class-2 of the dispatch
        // cheat sheet. Install a permanent dispatcher stub that routes every
        // render call into the McpRequestDriver, where specs can read back
        // the captured status/contentType/data. Script code like
        // `return render(...)` still gets a value back (the driver returns
        // the same Map). Without this, `handleMcpRequest()` short-circuits
        // with MissingMethodException inside the Mock's @Delegate chain.
        mock.render(_) >> { args -> SHARED_MCP_DRIVER.captureRender(args[0] as Map) }
        // No-arg render() isn't used by any production path today, but
        // AppExecutor declares both overloads. Fail loudly if a future
        // handler picks up the no-arg form — silent default (null return,
        // no capture) would leave parseResponseJson reading the *previous*
        // test's state, which is exactly the kind of cross-test leak this
        // harness is built to prevent.
        mock.render() >> {
            throw new IllegalStateException(
                "No-arg render() is not wired into McpRequestDriver. If a new " +
                "handler path needs it, extend the driver to capture the no-arg " +
                "call and relax this stub. See src/test/groovy/support/HarnessSpec.groovy.")
        }
        // Record unsubscribe() so lifecycle specs (e.g. initialize) can assert it.
        mock.unsubscribe() >> { UNSUBSCRIBE_CALL_COUNT.incrementAndGet() }
        return mock
    }

    private void compileSharedScript() {
        // Resolve Hubitat `#include namespace.Name` directives the way the hub does before parse
        // (issue #209): the raw file's `#include` lines are not valid Groovy, so compile the
        // hub-equivalent inlined source. No-op when the file carries no #include.
        File appFile = new File('hubitat-mcp-server.groovy')
        String resolvedSource = IncludeResolver.resolve(
            appFile.getText('UTF-8'), new File(appFile.absoluteFile.parentFile, 'libraries'))
        def sandbox = new HubitatAppSandbox(resolvedSource)
        def validator = new PassThroughAppValidator([
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRunScript
        ])
        SHARED_SCRIPT = sandbox.run(
            api: appExecutor,
            userSettingValues: SHARED_SETTINGS_MAP,
            childAppResolver: { String ns, String name ->
                throw new IllegalStateException(
                    "childAppResolver fired for ${ns}:${name} — HarnessSpec's " +
                    "reflective childAppFactory replacement should have short-circuited " +
                    "first. Check that factoryField.set() succeeded.")
            } as Closure,
            validator: validator
        )
        SHARED_MCP_DRIVER.boundScript = SHARED_SCRIPT
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
        // CI matrix dispatch-mode dimension: when set, forces useGateways
        // default per-test. Tests that explicitly pin useGateways in given:
        // still win (the given: assignment overrides).
        def defaultGateways = System.getProperty('harness.useGateways')
        if (defaultGateways != null) {
            settingsMap.useGateways = (defaultGateways == 'true')
        }
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
        //
        // Both removals matter when SHARED_SCRIPT is reused across spec
        // classes: removeMetaClass(class) clears the class-level
        // ExpandoMetaClass, and script.setMetaClass(null) clears the
        // per-instance one. Tests that do `script.metaClass.mcpLog = ...`
        // attach to the per-instance EMC; without the second wipe, the
        // closure (which captured the previous spec's local list) keeps
        // intercepting calls in subsequent specs.
        GroovySystem.metaClassRegistry.removeMetaClass(script.getClass())
        script.setMetaClass(null)
        checkMetaClassClean(script, 'HarnessSpec')
        // Re-run metaClass + reflective wires in setup (not setupSpec) so
        // closures capture the *current* Specification instance. This lets
        // subclasses override wireScriptOverrides() with closures that
        // reference non-@Shared spec fields (e.g. per-feature stubs) and
        // still see their own test's values.
        wireScriptOverrides()
    }

    /**
     * Strict-mode invariant check: after both metaClass wipes, before any
     * harness re-installs, the script's metaClass should have no per-
     * instance ExpandoMetaClass entries left over from prior tests. Off by
     * default (saves a small per-test cost); enable with
     * `-PharnessStrictMetaClass=true` when chasing a metaClass-leak
     * regression. Loud failure pinpoints which dynamic surface escaped the
     * wipe so future maintainers can extend setup() rather than archaeology
     * a flaky cross-spec test.
     *
     * Static so {@link StrictMetaClassCheckSpec} can exercise the failure
     * path directly without standing up a full Spec lifecycle.
     */
    static void checkMetaClassClean(Object scriptInstance, String specName) {
        if (System.getProperty('harnessStrictMetaClass') != 'true') return
        // `getMetaClass()` returns a HandleMetaClass wrapper (a
        // DelegatingMetaClass) for objects whose metaClass has been
        // touched via the DSL; unwrap to the underlying ExpandoMetaClass
        // before inspecting expando entries. Without this unwrap the
        // instanceof check returns false on real script instances and
        // the strict assertion silently no-ops — exactly the failure
        // mode this helper exists to surface.
        def mc = scriptInstance.getMetaClass()
        while (mc instanceof groovy.lang.DelegatingMetaClass) {
            mc = mc.getAdaptee()
        }
        // ExpandoMetaClass extends MetaClassImpl, so check EMC first.
        if (mc instanceof ExpandoMetaClass) {
            // fall through to expando-entry inspection
        } else if (mc instanceof groovy.lang.MetaClassImpl) {
            // Pristine default — no per-instance EMC was ever attached
            // because nothing did `script.metaClass.X = closure`. Safe.
            return
        } else {
            // Something else entirely — a future Groovy upgrade or an
            // eighty20results-installed custom MetaClass. We can't
            // introspect it for leaks, so fail loudly rather than
            // silently passing (the silent-no-op trap this helper
            // exists to avoid).
            throw new IllegalStateException(
                "Unexpected metaClass type ${mc.getClass().name} after dual wipe in ${specName}.setup(). " +
                "checkMetaClassClean only knows how to introspect ExpandoMetaClass and the default " +
                "MetaClassImpl. Extend the helper for the new shape, or strict-mode silently no-ops.")
        }
        def methods = mc.expandoMethods
        def props = mc.expandoProperties
        if (!methods.isEmpty() || !props.isEmpty()) {
            throw new IllegalStateException(
                "Per-instance metaClass not clean after dual wipe in ${specName}.setup(). " +
                "Surviving expando methods=${methods*.name}, expando properties=${props*.name}. " +
                "Some override escaped both removeMetaClass(class) and setMetaClass(null) — " +
                "likely set on a supertype (HubitatAppScript.metaClass.X instead of " +
                "script.metaClass.X) or via a static holder. Extend setup() to cover the " +
                "new surface.")
        }
    }

    protected void wireScriptOverrides() {
        def hubGetRef = hubGet
        def childAppsRef = childAppsList
        def self = this
        // `request` resolution inside the script: HubitatAppScript has an
        // @CompileStatic `getProperty(String)` override that short-circuits
        // the name "request" to `injectedMappingHandlerData['request']`
        // (verified via `javap -c` on HubitatAppScript.class — falls
        // through to MOP lookup only when that map itself is null, not
        // when the map is non-null and missing the 'request' key).
        // metaClass hooks are never consulted for this name, so the
        // McpRequestDriver's stable ScriptRequestProxy instance is
        // installed directly into that private field. The proxy reads
        // driver state at each getJSON() access, so tests can call
        // pushBody / pushBodyThrowing from their given: block and have
        // the change take effect without re-running this wire step.
        // wireScriptOverrides() runs in setup() each test (not in
        // setupSpec()) because setup() wipes the script's metaClass;
        // the reflective write here survives that wipe and is idempotent
        // against the stable proxy instance.
        def injectedField = me.biocomp.hubitat_ci.app.HubitatAppScript
            .getDeclaredField('injectedMappingHandlerData')
        injectedField.accessible = true
        Map injectedMap = injectedField.get(script) as Map
        if (injectedMap == null) {
            injectedMap = [:]
            injectedField.set(script, injectedMap)
        }
        injectedMap['request'] = mcpDriver.scriptRequest
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
