package rules

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
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
    protected Map settingsMap = [:]
    protected def parent  // subclasses assign a Spock Mock

    def setup() {
        def sandbox = new HubitatAppSandbox(new File('hubitat-mcp-rule.groovy'))
        def stateRef = stateMap
        def atomicStateRef = atomicStateMap
        appExecutor = Mock(AppExecutor) {
            _ * getState() >> stateRef
            _ * getAtomicState() >> atomicStateRef
            _ * now() >> 1234567890000L
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
        def parentRef = parent
        script.metaClass.getParent = { -> parentRef }
        script.metaClass.getLog = { -> new support.ToolSpecBase.LogStub() }
    }
}
