package rules

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.Location
import me.biocomp.hubitat_ci.api.common_api.Log
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification
import support.PassThroughAppValidator
import support.TestChildApp

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

    // Stable references captured by setupSpec's stubs; reset in setup().
    @Shared protected final Map stateMap = [:]
    @Shared protected final Map atomicStateMap = [:]
    // Must be non-empty at sandbox.run() time — see HarnessSpec.
    @Shared protected final Map settingsMap = [_harness: true]

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
        def logMock = Mock(Log)
        appExecutor = Mock(AppExecutor) {
            _ * getState() >> stateMap
            _ * getAtomicState() >> atomicStateMap
            // app / location are in HubitatCI's AppExecutor interface (so they
            // resolve via @Delegate, not metaClass). Script code calls
            // `app.id` (ruleLog) and `location.mode` (substituteVariables)
            // unconditionally; provide non-null stubs typed to the interface
            // Spock expects (raw Map returns trip a GroovyCastException).
            _ * getApp() >> new TestChildApp(id: 1L, label: 'TestRuleApp')
            _ * getLocation() >> ([
                getMode    : { -> 'Home' },
                getCurrentMode: { -> null },
                getModes   : { -> [] },
                getName    : { -> 'TestLocation' },
                getId      : { -> 1L }
            ] as Location)
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
        // Propagate unconditionally so the script's parent exactly matches
        // a freshly-reset `_parent` (null) on entry to each test.
        // eighty20results' sandbox installs a default InstalledAppWrapperImpl
        // when options.parent is absent at run() time, so without this reset
        // a spec that expects `parent == null` would see the default wrapper
        // instead — or worse, the previous test's parent.
        _parent = null
        script.setParent(null)
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
