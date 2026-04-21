package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Regression spec for the SandboxClassLoader gap on
 * hubitat.helper.{RMUtils,NetworkUtils}.
 *
 * Background: HubitatCI's SandboxClassLoader (extends
 * java.lang.ClassLoader, not URLClassLoader) doesn't see classes
 * compiled from src/test/groovy. Earlier versions of this repo kept
 * the stubs under src/test/groovy/support/stubs/, which worked for
 * direct test-JVM calls but raised NoClassDefFoundError when sandbox-
 * loaded production code (e.g., toolListRmRules in PR #79) called
 * RMUtils.
 *
 * The fix moves the stubs to src/main/groovy/hubitat/helper/ so they
 * compile under the main source-set, which IS on the sandbox's parent
 * classloader chain. This spec loads a minimal probe script via
 * HubitatAppSandbox and calls into both stubs to confirm resolution.
 *
 * Diagnostic origin: level99's RMUtilsSandboxGapDiagnosticSpec on
 * the diag/rmutils-sandbox-gap branch (PR #79 thread).
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

        and: 'sandbox-compiled probe — must be a real File, HubitatAppSandbox does not accept strings'
        def sandbox = new HubitatAppSandbox(new File('src/test/resources/sandbox-rmutils-probe.groovy'))
        def script = sandbox.compile(
            api: appExecutor,
            userSettingValues: [_harness: true],
            validationFlags: [
                Flags.DontValidatePreferences,
                Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy
            ]
        )

        and: 'classloader chain diagnostics for the sandbox-compiled script'
        System.err.println("=== SPEC-DIAG: script.class=${script.class.name}")
        def cl = script.class.classLoader
        int depth = 0
        while (cl != null) {
            System.err.println("=== SPEC-DIAG: script CL chain[${depth}] = ${cl.class.name} id=${System.identityHashCode(cl)}")
            cl = cl.parent
            depth++
        }
        System.err.println("=== SPEC-DIAG: about to call sandbox-loaded callRmUtilsGetRuleList ===")

        when: 'sandbox-loaded methods invoke the helpers'
        def rules = script.callRmUtilsGetRuleList()
        def netResult = script.callNetworkUtils()

        then: 'no NoClassDefFoundError; stubs return their default no-op values'
        notThrown(NoClassDefFoundError)
        rules == []
        netResult == null
    }
}
