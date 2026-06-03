package rules

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification
import support.PassThroughAppValidator
import support.PermissiveLog
import support.SubscriptionRecorder
import support.TestChildApp
import support.TestLocation

/**
 * groovy2x-spock lane variant of {@code rules.RuleHarnessSpec} (issue #230).
 *
 * Loads hubitat-mcp-rule.groovy under joelwetzel/hubitat_ci on Groovy 2.5,
 * caching the compiled script per JVM exactly like the root variant. The
 * only fork-specific differences:
 *
 *  - {@code sandbox.run()} + {@link PassThroughAppValidator}, same as the root
 *    lane — the PassThrough classloader forces {@code hubitat.helper.*} to
 *    resolve from the parent stubs (class identity for any helper the rule
 *    file touches). {@code DontValidateSubscriptions} is kept so the rule
 *    engine can {@code subscribe(...)} plain TestDevice POJOs.
 *  - {@code parent} resolves through the AppExecutor mock's
 *    {@code getParent()} (biocomp routes the script's {@code parent} property
 *    via its {@code @Delegate} to AppExecutor — no {@code @CompileStatic}
 *    getProperty override, so {@code setParent} reflection is not needed).
 *
 * Every recorder stub (runIn, schedule, subscribe, httpGet/httpPost, sunrise-sunset)
 * and the dual metaClass wipe match the root variant and run against the same specs.
 */
abstract class RuleHarnessSpec extends Specification {
    private static HubitatAppScript SHARED_SCRIPT
    private static final Object COMPILE_LOCK = new Object()
    private static final java.lang.reflect.Field API_FIELD = {
        def f = HubitatAppScript.getDeclaredField('api')
        f.accessible = true
        f
    }()

    // The currently-running feature instance, so the @Shared AppExecutor mock's
    // getParent() stub can read this feature's `parent` without rebuilding the
    // mock. setup() points it at the active feature; cleanup() nulls it so a
    // stale parent can't leak across the spec-class boundary (the gap before the
    // next class's setup()) — this is what replaces the root harness's defensive
    // setParent(null) in setupSpec. The static hand-off is only safe because
    // specs run sequentially (maxParallelForks=1).
    private static RuleHarnessSpec CURRENT_FEATURE

    @Shared protected AppExecutor appExecutor
    @Shared protected script
    @Shared private PermissiveLog sharedLog = new PermissiveLog()

    @Shared protected final Map stateMap = [:]
    @Shared protected final Map atomicStateMap = [:]
    // Must be non-empty at sandbox.run() time — see HarnessSpec.
    @Shared protected final Map settingsMap = [_harness: true]
    @Shared protected final TestLocation testLocation = new TestLocation()

    // Call recorders for AppExecutor methods the rule engine dispatches
    // through @Delegate. Spock interaction verification on a @Shared mock from
    // given:/then: doesn't fire reliably — stubs live in setupSpec and tests
    // assert on these lists. All cleared in setup().
    @Shared protected final List<List<Object>> runInCalls = []
    @Shared protected final List<List<Object>> runOnceCalls = []
    @Shared protected final List<List<Object>> scheduleCalls = []
    @Shared protected int unscheduleAllCount = 0
    @Shared protected final List<String> unscheduleCalls = []
    @Shared protected int unsubscribeCount = 0
    @Shared protected final List<Map> sendLocationEventCalls = []
    @Shared protected final List<List<Object>> httpGetCalls = []
    @Shared protected final List<List<Object>> httpPostCalls = []
    @Shared protected final SubscriptionRecorder subscriptions = new SubscriptionRecorder()

    @Shared protected Boolean stubTimeOfDayResult = false
    @Shared protected Map<String, Date> stubSunriseSunset = null
    @Shared protected Throwable stubHttpGetException = null

    // Per-feature backing field — read by the @Shared mock's getParent() stub
    // via CURRENT_FEATURE at invocation time.
    private Object _parent

    /** Assigning `parent = foo` in a given: block routes through here. */
    void setParent(Object p) { _parent = p }

    Object getParent() { _parent }

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
            _ * getState() >> stateMap
            _ * getAtomicState() >> atomicStateMap
            _ * getApp() >> new TestChildApp(id: 1L, label: 'TestRuleApp')
            _ * getLocation() >> testLocation
            // biocomp routes the script's `parent` property through @Delegate
            // to AppExecutor.getParent(); read the running feature's value.
            _ * getParent() >> { CURRENT_FEATURE?.getParent() }
            _ * now() >> 1234567890000L
            _ * getLog() >> sharedLog
            _ * getSettings() >> settingsMap
        }
        mock.runIn(*_) >> { args -> runInCalls << (args as List) }
        mock.runOnce(*_) >> { args -> runOnceCalls << (args as List) }
        mock.schedule(*_) >> { args -> scheduleCalls << (args as List) }
        mock.unsubscribe() >> { unsubscribeCount++ }
        mock.unschedule() >> { unscheduleAllCount++ }
        mock.unschedule(_ as String) >> { args -> unscheduleCalls << (args[0] as String) }
        mock.sendLocationEvent(_) >> { args -> sendLocationEventCalls << (args[0] as Map) }
        mock.httpGet(_, _) >> { args ->
            httpGetCalls << (args as List)
            if (stubHttpGetException) throw stubHttpGetException
        }
        mock.httpPost(_, _) >> { args -> httpPostCalls << (args as List) }
        mock.subscribe(_, _ as String, _ as String) >> { args ->
            subscriptions.record(args[0], args[1] as String, args[2] as String)
        }
        mock.subscribe(*_) >> { args ->
            if (args.size() == 3 && args[1] instanceof String && args[2] instanceof String) {
                return
            }
            throw new IllegalStateException(
                "Unstubbed subscribe() overload — ${args.size()} args: ${args}. " +
                "Extend SubscriptionRecorder + the RuleHarnessSpec subscribe stubs " +
                "if a new overload is in production use. See " +
                "src/test/groovy/support/SubscriptionRecorder.groovy.")
        }
        mock.timeOfDayIsBetween(_, _, _) >> { args -> stubTimeOfDayResult }
        mock.timeOfDayIsBetween(_, _, _, _) >> { args -> stubTimeOfDayResult }
        mock.getSunriseAndSunset(_) >> { args -> stubSunriseSunset }
        mock.getSunriseAndSunset() >> { stubSunriseSunset }
        return mock
    }

    private void compileSharedScript() {
        def sandbox = new HubitatAppSandbox(new File('hubitat-mcp-rule.groovy'))
        // run() + PassThroughAppValidator (not compile(), which discards the
        // validator) — keeps helper class-identity consistent with the server
        // harness. DontRunScript replaces what compile() would add;
        // DontValidateSubscriptions lets the rule engine subscribe() plain
        // TestDevice POJOs (the validator otherwise demands a DeviceWrapper).
        def validator = new PassThroughAppValidator([
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRunScript,
            Flags.DontValidateSubscriptions
        ])
        SHARED_SCRIPT = sandbox.run(
            api: appExecutor,
            userSettingValues: settingsMap,
            validator: validator
        )
    }

    def setup() {
        CURRENT_FEATURE = this
        stateMap.clear()
        atomicStateMap.clear()
        settingsMap.clear()
        settingsMap._harness = true
        testLocation.setMode('Home')
        testLocation.modeSetCalls.clear()
        testLocation.sunrise = null
        testLocation.sunset = null
        testLocation.hsmStatus = null
        appExecutor.getApp().settingsStore.clear()
        runInCalls.clear()
        runOnceCalls.clear()
        scheduleCalls.clear()
        unscheduleAllCount = 0
        unscheduleCalls.clear()
        unsubscribeCount = 0
        sendLocationEventCalls.clear()
        httpGetCalls.clear()
        httpPostCalls.clear()
        subscriptions.reset()
        stubTimeOfDayResult = false
        stubSunriseSunset = null
        stubHttpGetException = null
        _parent = null
        // Drop per-test metaClass writes from the previous feature before
        // re-installing hooks. Both wipes matter when SHARED_SCRIPT is reused
        // across spec classes; see support.HarnessSpec.setup().
        GroovySystem.metaClassRegistry.removeMetaClass(script.getClass())
        script.setMetaClass(null)
        support.HarnessSpec.checkMetaClassClean(script, 'RuleHarnessSpec')
        wireOverrides()
    }

    def cleanup() {
        // Release the per-feature instance so the getParent() stub can't read a
        // stale parent in the gap before the next spec class's setup(), and the
        // static doesn't pin the last spec for the JVM's lifetime.
        CURRENT_FEATURE = null
    }

    protected void wireOverrides() {
        // All runtime shims (state, atomicState, parent, log, now) route
        // through the AppExecutor mock. Subclasses override to add
        // script-method overrides that can't be stubbed via the mock.
    }
}
