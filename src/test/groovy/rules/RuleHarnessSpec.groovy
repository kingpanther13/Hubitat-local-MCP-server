package rules

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.Log
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Loads hubitat-mcp-rule.groovy (the rule-engine child app — separate
 * from the MCP server app) into a HubitatCI sandbox for unit testing.
 *
 * Same load/wiring approach as support.HarnessSpec: compile() avoids the
 * multi-page preferences form issue, state/atomicState/settings/now/log
 * flow through the AppExecutor mock + userSettingValues + metaclass, and
 * a mockable `parent` is exposed (the server reference the rule engine
 * reads from for findDevice etc.).
 */
abstract class RuleHarnessSpec extends Specification {
    protected AppExecutor appExecutor
    protected script
    protected Map stateMap = [:]
    protected Map atomicStateMap = [:]
    // Must be non-empty — see HarnessSpec for why.
    protected Map settingsMap = [_harness: true]
    protected def parent  // subclasses assign via given: block; wireOverrides() re-reads current value

    def setup() {
        def sandbox = new HubitatAppSandbox(new File('hubitat-mcp-rule.groovy'))
        def stateRef = stateMap
        def atomicStateRef = atomicStateMap
        def specInstance = this
        def logMock = Mock(Log)
        appExecutor = Mock(AppExecutor) {
            _ * getState() >> stateRef
            _ * getAtomicState() >> atomicStateRef
            // getParent() goes through @Delegate → AppExecutor, not metaclass.
            // The closure re-reads the current value each call so specs can
            // assign `parent` in their given: block after setup() ran.
            _ * getParent() >> { specInstance.parent }
            _ * now() >> 1234567890000L
            _ * getLog() >> logMock
        }
        script = sandbox.compile(
            api: appExecutor,
            userSettingValues: settingsMap,
            validationFlags: [
                Flags.DontValidatePreferences,
                Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy
            ]
        )
        wireOverrides()
    }

    protected void wireOverrides() {
        // All runtime shims (state, atomicState, parent, log, now) route through
        // the AppExecutor mock. Subclasses override to add script-method overrides
        // that can't be stubbed via the mock.
    }
}
