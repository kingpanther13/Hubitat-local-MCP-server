package server

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Unroll
import support.PassThroughAppValidator
import support.PermissiveLog

/**
 * Hub-less coverage of the standalone E2E Dead-Man Watchdog's restoreApp confirmation gate.
 * e2e-deadman-watchdog.groovy is NOT in the server harness, so its hardening -- "a reported success
 * that doesn't ADVANCE the code-class version means the push didn't land" -- had no per-run test
 * after the one-time live fire-test was removed. This compiles the watchdog in a sandbox and drives
 * restoreApp with stubbed I/O so the version-advance logic is exercised on every build.
 */
class WatchdogRestoreSpec extends Specification {
    HubitatAppScript script

    def setup() {
        File appFile = new File('e2e-deadman-watchdog.groovy')
        def sandbox = new HubitatAppSandbox(appFile.getText('UTF-8'))
        script = sandbox.run(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> [hubSecurityEnabled: false]
            },
            userSettingValues: [hubSecurityEnabled: false],
            validator: new PassThroughAppValidator([
                Flags.DontValidatePreferences,
                Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy,
                Flags.DontRunScript
            ])
        )
    }

    @Unroll
    def "restoreApp confirms a restore only when the code-class version advances (#oldV -> #newV => #expected)"() {
        given:
        def versions = [oldV, newV]
        int call = 0
        script.metaClass.readHubFileText = { String fn -> 'x' * 100 }   // a valid >50-char backup
        script.metaClass.appCodeVersion = { String id -> versions[call++] }
        script.metaClass.hubPostForm = { String p, Map b, int t = 420 -> [status: 200, data: '{"status":"success"}'] }

        expect:
        script.restoreApp('178', 'mcp-source-app-178.groovy') == expected

        where:
        oldV  | newV  || expected
        209   | 210   || true     // advanced -> confirmed
        209   | 209   || false    // reported success but no advance (silent no-op) -> NOT confirmed
        209   | 208   || false    // version went backwards -> NOT confirmed
        '209' | '210' || true     // stringified numerics still compare numerically
        209   | null  || false    // missing new version -> NOT confirmed
        209   | 'x'   || false     // non-numeric version -> unconfirmed, not a thrown/swallowed false
    }

    def "restoreApp refuses to push when the backup is missing or too small"() {
        given:
        script.metaClass.readHubFileText = { String fn -> backup }

        expect:
        !script.restoreApp('178', 'mcp-source-app-178.groovy')

        where:
        backup << [null, '', 'too short']
    }

    def "restoreApp reports failure when the hub does not report success"() {
        given:
        script.metaClass.readHubFileText = { String fn -> 'x' * 100 }
        script.metaClass.appCodeVersion = { String id -> 209 }
        script.metaClass.hubPostForm = { String p, Map b, int t = 420 -> [status: 200, data: '{"status":"error"}'] }

        expect:
        !script.restoreApp('178', 'mcp-source-app-178.groovy')
    }
}
