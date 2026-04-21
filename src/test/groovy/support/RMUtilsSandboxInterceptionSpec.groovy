package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Regression spec proving sandbox-loaded code can call
 * `hubitat.helper.RMUtils.X(...)` and have `RMUtilsMock` intercept the
 * call — the whole shape of test PR #79's `manage_rule_machine` gateway
 * tools will need.
 *
 * Two things have to line up:
 * 1. `support.PassThroughSandboxClassLoader` bypasses eighty20results'
 *    `mapClassName` remap for `hubitat.helper.RMUtils` so the JVM's
 *    §5.3.5 name-equality check doesn't reject the class at resolution.
 *    Without this, the call site throws
 *    `NoClassDefFoundError: hubitat/helper/RMUtils` before dispatch.
 * 2. The call goes through `sandbox.run(...)` with
 *    `support.PassThroughAppValidator` — `sandbox.compile(...)` would
 *    silently discard the validator (see PassThroughAppValidator's
 *    Javadoc for the readValidator precedence trap).
 *
 * If this spec fails after an eighty20results bump, the PassThrough
 * scaffold needs attention before PR #79-shaped specs can trust
 * `RMUtilsMock`.
 */
class RMUtilsSandboxInterceptionSpec extends Specification {

    def "RMUtilsMock intercepts sandbox-loaded hubitat.helper.RMUtils calls via PassThrough classloader"() {
        given: 'a mock with a stubbed rule list'
        def rmUtils = new RMUtilsMock()
        rmUtils.stubRuleList = [[id: 1L, label: 'Probe Rule']]
        rmUtils.install()

        and: 'a permissive AppExecutor — the probe does no state access'
        def appExecutor = Mock(AppExecutor) {
            _ * getState() >> [:]
            _ * getAtomicState() >> [:]
            _ * getChildDevices() >> []
            _ * now() >> 1234567890000L
            _ * getLog() >> new PermissiveLog()
        }

        and: 'a sandbox-loaded probe wired through PassThroughAppValidator'
        def sandbox = new HubitatAppSandbox(new File('src/test/resources/sandbox-rmutils-probe.groovy'))
        def validator = new PassThroughAppValidator([
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRunScript
        ])
        def script = sandbox.run(
            api: appExecutor,
            userSettingValues: [_probe: true],
            childAppResolver: { String ns, String name ->
                throw new IllegalStateException("unexpected childAppResolver call for ${ns}:${name}")
            } as Closure,
            validator: validator
        )

        when: 'sandbox-loaded methods invoke every RMUtils helper the server uses'
        def rules = script.probeGetRuleList('5.0')
        script.probeSendAction(42L, 'run')
        script.probePauseRule(43L)
        script.probeResumeRule(44L)
        script.probeSetRuleBoolean(45L, true)

        then: 'the stubbed return value flows back through the mock'
        rules == [[id: 1L, label: 'Probe Rule']]

        and: 'the mock recorded every sandbox-side call'
        rmUtils.calls == [
            [method: 'getRuleList',     version: '5.0'],
            [method: 'sendAction',      ruleId: 42L, action: 'run'],
            [method: 'pauseRule',       ruleId: 43L],
            [method: 'resumeRule',      ruleId: 44L],
            [method: 'setRuleBoolean',  ruleId: 45L, value: true]
        ]

        cleanup:
        rmUtils?.uninstall()
    }
}
