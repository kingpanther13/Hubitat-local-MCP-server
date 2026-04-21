package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Regression spec: when sandbox-loaded production code references
 * `hubitat.helper.RMUtils`, eighty20results' SandboxClassLoader rewrites
 * the symbol to `me.biocomp.hubitat_ci.api.common_api.RMUtils`. The
 * fork's own `common_api.RMUtils` class only ships one real method
 * (`getRule(String)`) — it is intentionally a shell. `RMUtilsMock` must
 * therefore install its metaClass on BOTH the raw `hubitat.helper.RMUtils`
 * stub AND the mapped class so production-under-sandbox calls land on
 * the mock.
 *
 * This spec proves the dual-install works end-to-end by loading a
 * minimal probe app through HubitatAppSandbox and verifying that calls
 * the probe makes to `hubitat.helper.RMUtils.X(...)` reach
 * `RMUtilsMock.calls`. If a future eighty20results bump changes the
 * sandbox mapping target (e.g. to `common_api.RuleMachineUtils` or
 * similar), this spec fails rather than PR #79's gateway-tool tests
 * silently no-op-ing.
 */
class RMUtilsSandboxInterceptionSpec extends Specification {

    def "RMUtilsMock intercepts sandbox-mapped hubitat.helper.RMUtils calls"() {
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

        and: 'a sandbox-loaded probe that references hubitat.helper.RMUtils'
        def sandbox = new HubitatAppSandbox(new File('src/test/resources/sandbox-rmutils-probe.groovy'))
        def script = sandbox.compile(
            api: appExecutor,
            userSettingValues: [_probe: true],
            childAppResolver: { String ns, String name -> null } as Closure,
            validationFlags: [
                Flags.DontValidatePreferences,
                Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy
            ]
        )

        when: 'sandbox-loaded methods invoke every RMUtils helper the server uses'
        def rules = script.probeGetRuleList('5.0')
        script.probeSendAction(42L, 'run')
        script.probePauseRule(43L)
        script.probeResumeRule(44L)
        script.probeSetRuleBoolean(45L, true)

        then: 'the stubbed return value flows back'
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
