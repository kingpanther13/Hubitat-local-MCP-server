package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

/**
 * groovy2x-spock lane variant of {@code support.HarnessSpec} (issue #230).
 *
 * Identical contract and per-JVM script caching to the root (Groovy 3.0 /
 * eighty20results) HarnessSpec, but wired for joelwetzel/hubitat_ci on
 * Groovy 2.5. The only differences from the shared variant are the
 * fork-specific child-app plumbing:
 *
 *  - {@code sandbox.run()} + {@link PassThroughAppValidator}, same as the root
 *    lane. joelwetzel does NOT remap {@code hubitat.helper.*}, but the sandbox
 *    still needs the PassThrough classloader to force those names to resolve
 *    from the PARENT classloader (the main-source-set stubs) — otherwise
 *    {@code RMUtilsMock}'s static-metaclass injection lands on a different
 *    class instance than the one the sandbox loads, and {@code NetworkUtils}
 *    isn't visible at all (NCDFE). {@code compile()} would discard the
 *    validator (its precedence trap), so {@code run()} with
 *    {@code DontRunScript} in the validator flags is required. No
 *    {@code childAppResolver} is passed — joelwetzel/biocomp doesn't need one.
 *  - {@code getChildApps()} / {@code addChildApp(...)} / {@code deleteChildApp(...)}
 *    are stubbed on the AppExecutor mock. biocomp's HubitatAppScript routes all
 *    of them through its {@code @Delegate} to AppExecutor (no concrete-method/
 *    private-factory interception, so eighty20results' reflective childAppFactory /
 *    childAppAccessor replacement does not apply and {@code childAppResolver}
 *    is not required).
 *
 * Everything else — the shared-script cache, per-spec AppExecutor rebind via
 * the {@code api} field, the {@code request} injection through
 * {@code injectedMappingHandlerData}, the {@code hubInternalGet} metaClass
 * shim, and the dual metaClass wipe + strict check — is the same mechanism
 * as the root variant and is exercised against the same specs.
 */
abstract class HarnessSpec extends Specification {
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
    // Must be non-empty at sandbox.run() time — HubitatCI's readUserSettingValues
    // does a Groovy truthy check on the passed map and silently swaps in a
    // fresh empty Map when it's empty, breaking the shared reference specs
    // rely on to mutate settings from given: blocks. setup() restores the
    // seed entry after clearing.
    private static final Map SHARED_SETTINGS_MAP = [selectedDevices: []]
    private static final List SHARED_CHILD_DEVICES_LIST = []
    private static final List SHARED_CHILD_APPS_LIST = []
    private static final HubInternalGetMock SHARED_HUB_GET = new HubInternalGetMock()
    private static final McpRequestDriver SHARED_MCP_DRIVER = new McpRequestDriver()
    private static final List SHARED_RUN_IN_CALLS = java.util.Collections.synchronizedList([])
    private static final List SHARED_RUN_IN_MILLIS_CALLS = java.util.Collections.synchronizedList([])

    // The currently-running feature instance. The @Shared AppExecutor mock's
    // addChildApp stub (built once in setupSpec) reads mockChildAppForCreate
    // off this so the per-feature value is visible without rebuilding the
    // mock. setup() points it at the active feature; cleanup() nulls it so it
    // neither leaks the last instance for the JVM's lifetime nor lets a stale
    // feature's fixture be read across the spec-class boundary. The static
    // hand-off is only safe because specs run sequentially (maxParallelForks=1).
    private static HarnessSpec CURRENT_FEATURE

    // Records parent-app unsubscribe() calls. A `1 * appExecutor.unsubscribe()`
    // cardinality check from a then-block doesn't fire reliably on the @Shared
    // AppExecutor mock, so route the call through this counter and assert on
    // it; lifecycle specs reset it in given: with .set(0).
    protected static final java.util.concurrent.atomic.AtomicInteger UNSUBSCRIBE_CALL_COUNT = new java.util.concurrent.atomic.AtomicInteger(0)
    // Virtual-time and scheduler seams used by the contention regressions.
    // The lane replaces the root HarnessSpec with this scaffold, so these
    // controls must exist here as well as in src/test/groovy/support.
    protected static final java.util.concurrent.atomic.AtomicReference NOW_OVERRIDE = new java.util.concurrent.atomic.AtomicReference(null)
    // Optional per-feature pause seam. HubitatAppScript.pauseExecution delegates
    // directly to AppExecutor, so a script metaClass override does not reliably
    // intercept compiled peer instances.
    protected static final java.util.concurrent.atomic.AtomicReference PAUSE_EXECUTION_OVERRIDE = new java.util.concurrent.atomic.AtomicReference(null)
    // One holder per scheduling API: the closure sees only the argument list, so a
    // single shared holder would let a runIn override swallow runInMillis schedules
    // (and vice versa) and a delay-unit regression would pass unnoticed.
    protected static final java.util.concurrent.atomic.AtomicReference RUN_IN_OVERRIDE = new java.util.concurrent.atomic.AtomicReference(null)
    protected static final java.util.concurrent.atomic.AtomicReference RUN_IN_MILLIS_OVERRIDE = new java.util.concurrent.atomic.AtomicReference(null)

    @Shared protected AppExecutor appExecutor
    // Every runIn(delay, handler[, opts]) the script scheduled this test, newest last.
    // Static-backed like the other fixtures so the stub closure built in setupSpec and the
    // spec reading it always reach the same list. The ci/groovy2x-spock lane overrides this
    // file with its own scaffold copy, so a recorder added to one is invisible to the other --
    // keep the two in lockstep.
    @Shared protected final List<List<Object>> runInCalls = SHARED_RUN_IN_CALLS
    @Shared protected final List<List<Object>> runInMillisCalls = SHARED_RUN_IN_MILLIS_CALLS
    @Shared protected script
    @Shared protected final Map stateMap = SHARED_STATE_MAP
    @Shared protected final Map atomicStateMap = SHARED_ATOMIC_STATE_MAP
    @Shared protected final Map settingsMap = SHARED_SETTINGS_MAP
    @Shared protected final List childDevicesList = SHARED_CHILD_DEVICES_LIST
    @Shared protected final List childAppsList = SHARED_CHILD_APPS_LIST
    @Shared protected final HubInternalGetMock hubGet = SHARED_HUB_GET
    @Shared protected final McpRequestDriver mcpDriver = SHARED_MCP_DRIVER

    // Per-test fixture — specs assign in given: blocks to drive addChildApp's
    // return value. Read by the @Shared mock's addChildApp stub via
    // CURRENT_FEATURE at invocation time.
    protected def mockChildAppForCreate

    def setupSpec() {
        appExecutor = buildAppExecutorMock()
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
            throw new IllegalStateException(
                "Failed to rebind AppExecutor on cached SHARED_SCRIPT for " +
                "${this.class.simpleName}. A joelwetzel/hubitat_ci upgrade may have " +
                "changed HubitatAppScript.api field shape; see this lane's HarnessSpec " +
                "for the rebind contract.", t)
        }
    }

    private AppExecutor buildAppExecutorMock() {
        def mock = Mock(AppExecutor) {
            _ * getState() >> SHARED_STATE_MAP
            _ * getAtomicState() >> SHARED_ATOMIC_STATE_MAP
            _ * getChildDevices() >> SHARED_CHILD_DEVICES_LIST
            // biocomp routes getChildApps()/addChildApp() through the script's
            // @Delegate to AppExecutor (unlike eighty20results, which defines
            // concrete methods over private factory closures). Stub both here.
            _ * getChildApps() >> SHARED_CHILD_APPS_LIST
            _ * now() >> { def ov = NOW_OVERRIDE.get(); ov != null ? (ov.call() as Long) : 1234567890000L }
            _ * getLog() >> SHARED_LOG
            _ * getSettings() >> SHARED_SETTINGS_MAP
        }
        mock.render(_) >> { args -> SHARED_MCP_DRIVER.captureRender(args[0] as Map) }
        mock.render() >> {
            throw new IllegalStateException(
                "No-arg render() is not wired into McpRequestDriver. If a new " +
                "handler path needs it, extend the driver to capture the no-arg " +
                "call and relax this stub. See ci/groovy2x-spock/scaffold/support/HarnessSpec.groovy.")
        }
        mock.unsubscribe() >> { UNSUBSCRIBE_CALL_COUNT.incrementAndGet() }
        mock.pauseExecution(_) >> { args ->
            def ov = PAUSE_EXECUTION_OVERRIDE.get()
            if (ov != null) ov.call(args[0] as Long)
        }
        // Attached HERE with the other permanent stubs: one added from a later setupSpec does
        // not reliably take, and runIn is an AppExecutor method, so a script.metaClass override
        // never intercepts it.
        mock.runIn(*_) >> { args ->
            def ov = RUN_IN_OVERRIDE.get()
            if (ov != null) return ov.call(args as List)
            SHARED_RUN_IN_CALLS << (args as List)
        }
        mock.runInMillis(*_) >> { args ->
            def ov = RUN_IN_MILLIS_OVERRIDE.get()
            if (ov != null) return ov.call(args as List)
            SHARED_RUN_IN_MILLIS_CALLS << (args as List)
        }
        // addChildApp routes via @Delegate to AppExecutor under joelwetzel. *_
        // covers the 3-arg and 4-arg(props) overloads production code uses.
        // Read the running feature's fixture so the value set in a spec's
        // given: block is honoured.
        mock.addChildApp(*_) >> { args ->
            def cf = CURRENT_FEATURE
            if (cf?.mockChildAppForCreate == null) {
                throw new IllegalStateException(
                    "Spec invoked addChildApp(${args}) but mockChildAppForCreate was " +
                    "not assigned. Set `mockChildAppForCreate = new TestChildApp(...)` in given:.")
            }
            cf.mockChildAppForCreate
        }
        // deleteChildApp routes via @Delegate to AppExecutor too. Mirror the
        // root harness's childAppAccessor 'delete' op: drop the matching child
        // from the shared list so delete_rule specs see it removed.
        mock.deleteChildApp(_) >> { args ->
            SHARED_CHILD_APPS_LIST.removeAll { it.id?.toString() == args[0]?.toString() }
            return null
        }
        return mock
    }

    private void compileSharedScript() {
        // Resolve Hubitat `#include namespace.Name` directives before parse (issue #209) -- the
        // raw `#include` lines are not valid Groovy. IncludeResolver is imported from the shared
        // src/test/groovy/support corpus (this lane Syncs it in). No-op without #include.
        File appFile = new File('hubitat-mcp-server.groovy')
        String resolvedSource = IncludeResolver.resolve(
            appFile.getText('UTF-8'), new File(appFile.absoluteFile.parentFile, 'libraries'))
        def sandbox = new HubitatAppSandbox(resolvedSource)
        // PassThroughAppValidator swaps in a classloader that resolves
        // hubitat.helper.{RMUtils,NetworkUtils} from the parent (the
        // main-source stubs), so RMUtilsMock's static-metaclass injection
        // reaches sandbox-loaded calls and NetworkUtils resolves. Must use
        // run() (not compile(), which discards the validator). DontRunScript
        // replaces what compile() would have added; DontValidatePreferences
        // sidesteps the multi-page form. No childAppResolver under joelwetzel.
        def validator = new PassThroughAppValidator([
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRunScript
        ])
        SHARED_SCRIPT = sandbox.run(
            api: appExecutor,
            userSettingValues: SHARED_SETTINGS_MAP,
            validator: validator
        )
        SHARED_MCP_DRIVER.boundScript = SHARED_SCRIPT
    }

    def setup() {
        CURRENT_FEATURE = this
        runInCalls.clear()
        runInMillisCalls.clear()
        stateMap.clear()
        atomicStateMap.clear()
        // The production app deliberately keeps exact terminal-generation
        // evidence in a class-static map so a Hubitat disable/enable bounce can
        // repair an older atomicState snapshot.  The shared compiled script and
        // fixed test clock would otherwise retain that evidence across features.
        (scriptStaticField('MRTR_TERMINAL_EVIDENCE') as Map).clear()
        // Ordinary-write leases are class-static only -- nothing in atomicState mirrors
        // them, so a feature that reserves without releasing would otherwise hand the
        // next feature a write slot that is already spent.
        (scriptStaticField('WRITE_REQUEST_LEASES') as Map).clear()
        // The per-rule baseline mirror is JVM truth beside the manifest; a leftover
        // handle would satisfy reuse for a rule id a later feature reuses.
        (scriptStaticField('RM_BASELINE_HANDLES') as Map).clear()
        // The write-reservation machinery serves mrtrRequests / packageDeployInFlight
        // from a class-static snapshot of atomicState. Clearing atomicStateMap above
        // without this would leave the previous feature's snapshot as the read path --
        // the same reload the hub gets from a recompile/restart.
        script._writeStateCacheInvalidate()
        settingsMap.clear()
        settingsMap.selectedDevices = []
        // The issue #299 best-practice gate ships ON (settings.enableMandatoryBPS != false), which
        // would block every keyless write the specs exercise. Pin it OFF by default here -- mirrors
        // the root support/HarnessSpec; ExecuteToolMandatoryBpsGateSpec sets it explicitly to test it.
        settingsMap.enableMandatoryBPS = false
        // CI matrix dispatch-mode dimension: when set, forces useGateways
        // default per-test. Tests that explicitly pin useGateways in given:
        // still win.
        def defaultGateways = System.getProperty('harness.useGateways')
        if (defaultGateways != null) {
            settingsMap.useGateways = (defaultGateways == 'true')
        }
        childDevicesList.clear()
        childAppsList.clear()
        hubGet.reset()
        mcpDriver.reset()
        NOW_OVERRIDE.set(null)
        PAUSE_EXECUTION_OVERRIDE.set(null)
        RUN_IN_OVERRIDE.set(null)
        RUN_IN_MILLIS_OVERRIDE.set(null)
        // Drop per-test metaClass writes from previous features before
        // re-installing the standard hooks. Both wipes matter when
        // SHARED_SCRIPT is reused across spec classes: removeMetaClass(class)
        // clears the class-level ExpandoMetaClass; setMetaClass(null) clears
        // the per-instance one.
        GroovySystem.metaClassRegistry.removeMetaClass(script.getClass())
        script.setMetaClass(null)
        checkMetaClassClean(script, 'HarnessSpec')
        wireScriptOverrides()
    }

    /**
     * The app's class-static fields, which no public method exposes. A recompile is the
     * only thing that clears them on a hub, so setup() stands in for that boundary — and
     * a spec needs the same reach to model an execution that died before its finally.
     */
    protected Object scriptStaticField(String fieldName) {
        def f = script.getClass().getDeclaredField(fieldName)
        f.accessible = true
        return f.get(null)
    }

    /**
     * Create a peer execution of the compiled app with the same AppExecutor
     * and atomicState backing. Class-static production fields remain shared.
     *
     * The peer gets only the standard harness wires, NOT a subclass's
     * {@code wireScriptOverrides()} additions — those land on {@code script}
     * alone, so a spec that stubs a surface there must stub it on the peer too
     * rather than assume parity.
     */
    protected Object newCompiledScriptInstance() {
        HubitatAppScript peer = script.getClass().getDeclaredConstructor().newInstance() as HubitatAppScript
        peer.initialize(script as HubitatAppScript)
        wireInstanceOverrides(peer)
        return peer
    }

    def cleanup() {
        // Release the per-feature instance: prevents the static from pinning the
        // last spec for the JVM's lifetime and stops a stale feature's fixture
        // from being read in the gap before the next spec class's setup() runs.
        CURRENT_FEATURE = null
        NOW_OVERRIDE.set(null)
        PAUSE_EXECUTION_OVERRIDE.set(null)
        RUN_IN_OVERRIDE.set(null)
        RUN_IN_MILLIS_OVERRIDE.set(null)
    }

    /**
     * Strict-mode invariant check: after both metaClass wipes, before any
     * harness re-installs, the script's metaClass should have no per-instance
     * ExpandoMetaClass entries left over from prior tests. Off by default;
     * enable with {@code -PharnessStrictMetaClass=true}. Static so
     * {@code StrictMetaClassCheckSpec} can exercise the failure path directly.
     */
    static void checkMetaClassClean(Object scriptInstance, String specName) {
        if (System.getProperty('harnessStrictMetaClass') != 'true') return
        def mc = scriptInstance.getMetaClass()
        while (mc instanceof groovy.lang.DelegatingMetaClass) {
            mc = mc.getAdaptee()
        }
        if (mc instanceof ExpandoMetaClass) {
            // fall through to expando-entry inspection
        } else if (mc instanceof groovy.lang.MetaClassImpl) {
            return
        } else {
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
                "Some override escaped both removeMetaClass(class) and setMetaClass(null). Extend setup().")
        }
    }

    protected void wireScriptOverrides() {
        wireInstanceOverrides(script)
    }

    /** Apply every per-instance harness seam to a compiled execution instance. */
    private void wireInstanceOverrides(Object target) {
        def hubGetRef = hubGet
        // `request` resolution inside the script: HubitatAppScript reads the
        // name "request" from its private injectedMappingHandlerData map, so
        // install the McpRequestDriver's stable proxy directly into that field.
        // The proxy reads driver state at each getJSON() access, so tests can
        // call pushBody from their given: block without re-running this wire.
        wireRequestProxy(target)
        // hubInternalGet has no declaration on HubitatAppScript — pure dynamic
        // Groovy resolved through metaClass, so the per-instance write here
        // intercepts cleanly. The captured hubGetRef is the @Shared
        // HubInternalGetMock whose maps reset between tests.
        target.metaClass.hubInternalGet = { String p, Map pp = [:], Integer t = 30 ->
            hubGetRef.call(p, pp)
        }
    }

    /** Install the live request/header proxy on any compiled app instance. */
    private void wireRequestProxy(Object target) {
        def injectedField = me.biocomp.hubitat_ci.app.HubitatAppScript
            .getDeclaredField('injectedMappingHandlerData')
        injectedField.accessible = true
        Map injectedMap = injectedField.get(target) as Map
        if (injectedMap == null) {
            injectedMap = [:]
            injectedField.set(target, injectedMap)
        }
        injectedMap['request'] = mcpDriver.scriptRequest
    }
}
