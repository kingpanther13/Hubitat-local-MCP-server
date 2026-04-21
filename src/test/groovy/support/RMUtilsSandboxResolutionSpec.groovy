package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Regression spec for the SandboxClassLoader gap on
 * `hubitat.helper.{RMUtils,NetworkUtils}` that level99's
 * `RMUtilsSandboxGapDiagnosticSpec` (PR #79 thread) surfaced.
 *
 * Background: HubitatCI's `SandboxClassLoader.mapClassName` rewrites
 * `hubitat.<x>.<Y>` → `me.biocomp.hubitat_ci.api.<x>.<Y>` for any
 * sandbox-loaded reference. For platform helpers like
 * `hubitat.helper.RMUtils` that we provide as literal stubs, the JVM
 * (hotspot's `SystemDictionary::load_instance_class`) rejects the
 * remap because the returned class's name doesn't match the requested
 * name → `NoClassDefFoundError` for any tool that invokes
 * `hubitat.helper.X.method(...)` from the sandbox.
 *
 * Fix: {@link PassThroughSandboxClassLoader} bypasses `mapClassName`
 * for our specific helper-class names, and {@link PassThroughAppValidator}
 * threads it into HubitatCI's compile path. This spec proves the wiring:
 * load a probe script via the sandbox and verify both helper calls
 * resolve.
 *
 * NOTE: must use `sandbox.run(...)` not `sandbox.compile(...)` — see
 * `PassThroughAppValidator`'s class doc for the full readValidator
 * precedence trap.
 */
class RMUtilsSandboxResolutionSpec extends Specification {

    def "sandbox-loaded script resolves hubitat.helper.RMUtils + NetworkUtils"() {
        given: 'a permissive AppExecutor + log shim — the probe script does no state access'
        def appExecutor = Mock(AppExecutor) {
            _ * getState() >> [:]
            _ * getAtomicState() >> [:]
            _ * getChildDevices() >> []
            _ * getChildApps() >> []
            _ * now() >> 1234567890000L
            _ * getLog() >> new PermissiveLog()
        }

        and: 'sandbox-loaded probe via PassThroughAppValidator — see its class doc for the run() vs compile() precedence trap'
        def sandbox = new HubitatAppSandbox(new File('src/test/resources/sandbox-rmutils-probe.groovy'))
        def validator = new PassThroughAppValidator([
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRunScript
        ])
        def script = sandbox.run(
            api: appExecutor,
            userSettingValues: [_harness: true],
            validator: validator
        )

        when: 'sandbox-loaded methods invoke the helpers'
        def rules = script.callRmUtilsGetRuleList()
        def netResult = script.callNetworkUtils()

        then: 'no NoClassDefFoundError; stubs return their default no-op values'
        notThrown(Throwable)
        rules == []
        netResult == null
    }
}
