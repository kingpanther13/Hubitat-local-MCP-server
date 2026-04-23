package rules

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification
import support.PassThroughAppValidator
import support.PermissiveLog
import support.TestChildApp
import support.TestLocation

/**
 * Loads hubitat-mcp-rule.groovy (the rule-engine child app — separate
 * from the MCP server app) into a HubitatCI sandbox once per spec class
 * and reuses the compiled script across feature methods. See the Javadoc
 * on {@link support.HarnessSpec} for the broader rationale; the rule
 * file is ~4000 lines, and paying its parse+compile once per Spock spec
 * class (instead of once per feature method) is where the bulk of the
 * test-job speedup comes from for rule specs.
 *
 * Same load/wiring approach as {@link support.HarnessSpec}:
 * {@code sandbox.run()} with an explicit {@link PassThroughAppValidator}
 * (see HarnessSpec Javadoc for the compile-vs-run precedence trap),
 * state/atomicState/settings/now/log flow through the AppExecutor mock
 * + userSettingValues + metaclass.
 *
 * {@code parent} is exposed as a writable property — specs assign it in
 * their {@code given:} block (e.g. {@code parent = new SmokeParent(...)}),
 * and the setter propagates the value to the shared script via
 * {@code HubitatAppScript.setParent()} so that {@code parent.findDevice(id)}
 * inside the rule engine resolves to the spec's stub. eighty20results'
 * HubitatAppScript defines {@code parent} as a private field accessed via
 * its {@code @CompileStatic getProperty("parent")} override, so mocking
 * {@code AppExecutor.getParent()} no longer intercepts property access —
 * {@code setParent} is the only reliable hook. {@code setup()} resets
 * {@code _parent} and re-applies it to the shared script so specs that
 * expected {@code parent == null} on entry don't see the previous test's
 * value.
 */
abstract class RuleHarnessSpec extends Specification {
    // Shared across the spec class — see HarnessSpec for why.
    @Shared protected AppExecutor appExecutor
    @Shared protected script
    // Matches HarnessSpec's PermissiveLog usage — a concrete shim that
    // accepts both the single-arg methods on HubitatCI's Log interface and
    // the 2-arg (String, Throwable) overloads the real Hubitat runtime
    // supports. A Spock Mock(Log) would throw MissingMethodException on
    // script code like `log.error(msg, e)` under dynamic Groovy dispatch.
    @Shared private PermissiveLog sharedLog = new PermissiveLog()

    // Stable references captured by setupSpec's stubs; reset in setup().
    @Shared protected final Map stateMap = [:]
    @Shared protected final Map atomicStateMap = [:]
    // Must be non-empty at sandbox.run() time — see HarnessSpec.
    @Shared protected final Map settingsMap = [_harness: true]
    /**
     * Shared Location stub returned by the AppExecutor mock. Mutable fields
     * (`mode`, `sunrise`, `sunset`, `hsmStatus`) are reset in {@code setup()}
     * so per-test state does not leak across feature methods. Specs that
     * need to drive these in {@code given:} set them directly on this
     * instance (e.g. {@code testLocation.mode = 'Night'}).
     */
    @Shared protected final TestLocation testLocation = new TestLocation()

    // Call recorders for AppExecutor methods the rule engine dispatches
    // through @Delegate. Spock's interaction verification (`1 * appExecutor.foo(...)`)
    // on a @Shared mock declared in given:/then: doesn't work reliably —
    // stubs only apply when installed in setupSpec. Tests assert on these
    // lists instead of using Spock interactions. All are cleared in setup().
    @Shared protected final List<List<Object>> runInCalls = []
    @Shared protected final List<List<Object>> runOnceCalls = []
    // Split to disambiguate no-arg `unschedule()` (blanket cancel, everything)
    // from targeted `unschedule('handlerName')` — the engine uses both and
    // lumping them together would let a regression swap one for the other
    // without any assertion failing.
    @Shared protected int unscheduleAllCount = 0
    @Shared protected final List<String> unscheduleCalls = []
    @Shared protected int unsubscribeCount = 0
    @Shared protected final List<Map> sendLocationEventCalls = []
    @Shared protected final List<List<Object>> httpGetCalls = []
    @Shared protected final List<List<Object>> httpPostCalls = []

    // Mutable @Shared fields reading from tests' given: blocks. The stub
    // closures in setupSpec read these at invocation time.
    @Shared protected Boolean stubTimeOfDayResult = false
    @Shared protected Map<String, Date> stubSunriseSunset = null
    @Shared protected Throwable stubHttpGetException = null

    // Per-feature backing field — each test gets its own instance, so
    // resetting _parent to null in setup() guarantees isolation without
    // having to @Shared the state.
    private Object _parent

    /** Assigning `parent = foo` in a given: block routes through here. */
    void setParent(Object p) {
        _parent = p
        if (script) {
            script.setParent(p)
        }
    }

    Object getParent() { _parent }

    def setupSpec() {
        def sandbox = new HubitatAppSandbox(new File('hubitat-mcp-rule.groovy'))
        appExecutor = Mock(AppExecutor) {
            _ * getState() >> stateMap
            _ * getAtomicState() >> atomicStateMap
            // app / location are in HubitatCI's AppExecutor interface (so they
            // resolve via @Delegate, not metaClass). Script code calls
            // `app.id` (ruleLog) and `location.mode` (substituteVariables)
            // unconditionally; provide non-null stubs typed to the interface
            // Spock expects (raw Map returns trip a GroovyCastException).
            _ * getApp() >> new TestChildApp(id: 1L, label: 'TestRuleApp')
            // Shared TestLocation — mutable fields (mode, sunrise, sunset,
            // hsmStatus) reset in setup(), so specs can drive them per-test
            // without the closure-based Map-as-Location trick from the original
            // harness (which returned a fresh frozen view on every call).
            _ * getLocation() >> testLocation
            _ * now() >> 1234567890000L
            _ * getLog() >> sharedLog
        }
        // Permanent recording stubs for AppExecutor methods the rule engine
        // dispatches via @Delegate. Tests assert on the recorded *Calls lists
        // (Spock's `1 * appExecutor.foo(...)` verification on a @Shared mock
        // from given:/then: doesn't work reliably — stubs must live here to
        // take effect). Mutable @Shared fields (stubTimeOfDayResult etc.) are
        // read at invocation time so tests can drive behaviour per-feature.
        appExecutor.runIn(*_) >> { args -> runInCalls << (args as List) }
        appExecutor.runOnce(*_) >> { args -> runOnceCalls << (args as List) }
        appExecutor.unsubscribe() >> { unsubscribeCount++ }
        appExecutor.unschedule() >> { unscheduleAllCount++ }
        appExecutor.unschedule(_ as String) >> { args -> unscheduleCalls << (args[0] as String) }
        appExecutor.sendLocationEvent(_) >> { args -> sendLocationEventCalls << (args[0] as Map) }
        appExecutor.httpGet(_, _) >> { args ->
            httpGetCalls << (args as List)
            if (stubHttpGetException) throw stubHttpGetException
        }
        appExecutor.httpPost(_, _) >> { args -> httpPostCalls << (args as List) }
        appExecutor.timeOfDayIsBetween(_, _, _) >> { args -> stubTimeOfDayResult }
        appExecutor.timeOfDayIsBetween(_, _, _, _) >> { args -> stubTimeOfDayResult }
        appExecutor.getSunriseAndSunset(_) >> { args -> stubSunriseSunset }
        appExecutor.getSunriseAndSunset() >> { stubSunriseSunset }
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
                    "childAppResolver fired for ${ns}:${name} — RuleHarnessSpec " +
                    "does not expect child-app creation from the rule engine.")
            } as Closure,
            validator: validator
        )
    }

    def setup() {
        stateMap.clear()
        atomicStateMap.clear()
        settingsMap.clear()
        settingsMap._harness = true
        // Reset the shared TestLocation's mutable fields so a spec that mutates
        // mode / sunrise / hsmStatus doesn't leak state into the next feature.
        testLocation.setMode('Home')
        testLocation.modeSetCalls.clear()
        testLocation.sunrise = null
        testLocation.sunset = null
        testLocation.hsmStatus = null
        // Clear TestChildApp.settingsStore so auto-disable writes from a
        // prior feature's loop-guard trip don't leak forward. The Mock's
        // `>> new TestChildApp(...)` is evaluated once at construction, so
        // the same instance is returned every call — mutations persist
        // across tests without an explicit reset.
        appExecutor.getApp().settingsStore.clear()
        // Reset AppExecutor call recorders + stub-driver fields so they
        // don't leak between feature methods.
        runInCalls.clear()
        runOnceCalls.clear()
        unscheduleAllCount = 0
        unscheduleCalls.clear()
        unsubscribeCount = 0
        sendLocationEventCalls.clear()
        httpGetCalls.clear()
        httpPostCalls.clear()
        stubTimeOfDayResult = false
        stubSunriseSunset = null
        stubHttpGetException = null
        // Propagate unconditionally so the script's parent exactly matches
        // a freshly-reset `_parent` (null) on entry to each test.
        // eighty20results' sandbox installs a default InstalledAppWrapperImpl
        // when options.parent is absent at run() time, so without this reset
        // a spec that expects `parent == null` would see the default wrapper
        // instead — or worse, the previous test's parent.
        _parent = null
        script.setParent(null)
        // Drop any per-test metaClass writes from the previous feature
        // before re-installing the standard hooks below — prevents
        // per-feature stubs (e.g. `script.metaClass.getGlobalVar = { ... }`
        // installed directly in given: blocks) from leaking forward.
        GroovySystem.metaClassRegistry.removeMetaClass(script.getClass())
        // Re-run per-test wires (metaClass overrides etc.) after clearing
        // state. Called from setup() rather than setupSpec() so subclass
        // overrides can capture the current feature instance's fields.
        wireOverrides()
    }

    protected void wireOverrides() {
        // All runtime shims (state, atomicState, log, now) route through
        // the AppExecutor mock. Subclasses override to add script-method overrides
        // that can't be stubbed via the mock.
    }
}
