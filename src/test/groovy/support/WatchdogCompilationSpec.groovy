package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import spock.lang.Specification
import spock.lang.Unroll

class WatchdogCompilationSpec extends Specification {
    private def load(String version, Map settings, Map state) {
        new HubitatAppSandbox(new File("e2e-deadman-watchdog-${version}.groovy").getText('UTF-8')).run(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> settings
                _ * getAtomicState() >> state
            }, userSettingValues: settings, validator: new WatchdogAppValidator())
    }

    @Unroll
    def 'watchdog #version reuses compiled code with fresh instances and settings'() {
        given:
        def first = load(version, [hubSecurityEnabled: false, marker: 'first'], [owner: 'first'])
        first.metaClass.getAdminToolDefinitions = { -> [[name: 'override']] }

        when:
        def second = load(version, [hubSecurityEnabled: false, marker: 'second'], [owner: 'second'])

        then:
        first.getClass().is(second.getClass())
        !first.is(second)
        first.settings.marker == 'first'
        second.settings.marker == 'second'
        first.atomicState.owner == 'first'
        second.atomicState.owner == 'second'
        second.getAdminToolDefinitions()*.name.contains('hub_update_package')
        first.getAdminToolDefinitions()*.name == ['override']

        where:
        version << ['v3', 'v3-1']
    }
}
