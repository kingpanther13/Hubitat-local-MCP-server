package rules

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.Location
import me.biocomp.hubitat_ci.api.common_api.Log
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import support.TestChildApp

/**
 * Loads hubitat-mcp-rule.groovy (the rule-engine child app — separate
 * from the MCP server app) into a HubitatCI sandbox for unit testing.
 *
 * Same load/wiring approach as support.HarnessSpec: compile() avoids the
 * multi-page preferences form issue, state/atomicState/settings/now/log
 * flow through the AppExecutor mock + userSettingValues + metaclass.
 *
 * `parent` is exposed as a writable property — specs assign it in their
 * `given:` block (e.g. `parent = new SmokeParent(...)`), and the setter
 * propagates the value to the script via `HubitatAppScript.setParent()`
 * so that `parent.findDevice(id)` inside the rule engine resolves to the
 * spec's stub. eighty20results' HubitatAppScript defines `parent` as a
 * private field accessed via its @CompileStatic `getProperty("parent")`
 * override, so mocking `AppExecutor.getParent()` no longer intercepts
 * property access — setParent is the only reliable hook.
 */
abstract class RuleHarnessSpec extends Specification {
    protected AppExecutor appExecutor
    protected script
    protected Map stateMap = [:]
    protected Map atomicStateMap = [:]
    // Must be non-empty — see HarnessSpec for why.
    protected Map settingsMap = [_harness: true]

    private Object _parent

    /** Assigning `parent = foo` in a given: block routes through here. */
    void setParent(Object p) {
        _parent = p
        if (script) {
            script.setParent(p)
        }
    }

    Object getParent() { _parent }

    def setup() {
        def sandbox = new HubitatAppSandbox(new File('hubitat-mcp-rule.groovy'))
        def stateRef = stateMap
        def atomicStateRef = atomicStateMap
        def logMock = Mock(Log)
        appExecutor = Mock(AppExecutor) {
            _ * getState() >> stateRef
            _ * getAtomicState() >> atomicStateRef
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
        script = sandbox.compile(
            api: appExecutor,
            userSettingValues: settingsMap,
            childAppResolver: { String ns, String name -> null } as Closure,
            validationFlags: [
                Flags.DontValidatePreferences,
                Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy
            ]
        )
        // Propagate unconditionally so the script's parent exactly matches
        // the spec's `_parent` — including null. eighty20results' sandbox
        // supplies a default `InstalledAppWrapperImpl` when options.parent
        // is absent, so without this reset a spec that expects
        // `parent == null` would see the default wrapper instead.
        script.setParent(_parent)
        wireOverrides()
    }

    protected void wireOverrides() {
        // All runtime shims (state, atomicState, log, now) route through
        // the AppExecutor mock. Subclasses override to add script-method overrides
        // that can't be stubbed via the mock.
    }
}
