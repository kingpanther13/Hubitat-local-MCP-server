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

    @Shared protected AppExecutor appExecutor
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
            _ * now() >> 1234567890000L
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
        stateMap.clear()
        atomicStateMap.clear()
        settingsMap.clear()
        settingsMap.selectedDevices = []
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

    def cleanup() {
        // Release the per-feature instance: prevents the static from pinning the
        // last spec for the JVM's lifetime and stops a stale feature's fixture
        // from being read in the gap before the next spec class's setup() runs.
        CURRENT_FEATURE = null
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
        def hubGetRef = hubGet
        // `request` resolution inside the script: HubitatAppScript reads the
        // name "request" from its private injectedMappingHandlerData map, so
        // install the McpRequestDriver's stable proxy directly into that field.
        // The proxy reads driver state at each getJSON() access, so tests can
        // call pushBody from their given: block without re-running this wire.
        def injectedField = me.biocomp.hubitat_ci.app.HubitatAppScript
            .getDeclaredField('injectedMappingHandlerData')
        injectedField.accessible = true
        Map injectedMap = injectedField.get(script) as Map
        if (injectedMap == null) {
            injectedMap = [:]
            injectedField.set(script, injectedMap)
        }
        injectedMap['request'] = mcpDriver.scriptRequest
        // hubInternalGet has no declaration on HubitatAppScript — pure dynamic
        // Groovy resolved through metaClass, so the per-instance write here
        // intercepts cleanly. The captured hubGetRef is the @Shared
        // HubInternalGetMock whose maps reset between tests.
        script.metaClass.hubInternalGet = { String p, Map pp = [:], Integer t = 30 ->
            hubGetRef.call(p, pp)
        }
    }
}
