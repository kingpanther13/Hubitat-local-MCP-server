package support

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import spock.lang.Shared
import spock.lang.Unroll

class WatchdogCompilationSpec extends WatchdogSpec {
    @Shared private Class previousClass

    private def load(Map settings, Map state) {
        loadWatchdog(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> settings
                _ * getAtomicState() >> state
            }, userSettingValues: settings)
    }

    @Unroll
    def 'watchdog reuses compiled code with fresh test state (load #iteration)'() {
        given:
        def first = load([hubSecurityEnabled: false, marker: 'first'], [owner: 'first'])
        assert previousClass == null || previousClass.is(first.getClass())
        previousClass = first.getClass()
        assert first.LOOPBACK == [failStreak: 0, lastOkAt: null, streakStartedAt: null]
        assert first.MANUAL_WRITE.empty
        assert first.PACKAGE_PROGRESS.empty
        assert first.HEALTH_TICK.empty
        assert !first.metaClass.respondsTo(first, 'cacheLeakProbe')
        first.metaClass.getAdminToolDefinitions = { -> [[name: 'override']] }

        when:
        def second = load([hubSecurityEnabled: false, marker: 'second'], [owner: 'second'])

        then:
        first.getClass().is(second.getClass())
        !first.is(second)
        first.settings.marker == 'first'
        second.settings.marker == 'second'
        first.atomicState.owner == 'first'
        second.atomicState.owner == 'second'
        second.getAdminToolDefinitions()*.name.contains('hub_update_package')
        first.getAdminToolDefinitions()*.name == ['override']

        when: 'one case leaves dirty static state and a class override behind'
        first.LOOPBACK.failStreak = 99
        first.MANUAL_WRITE.put('owner', 'previous test')
        first.PACKAGE_PROGRESS.put('phase', 'previous test')
        first.HEALTH_TICK.put('running', true)
        first.getClass().metaClass.cacheLeakProbe = { -> 'previous test' }

        then: 'peer instances share this code load, but the next iteration must start clean'
        second.LOOPBACK.failStreak == 99
        second.MANUAL_WRITE.owner == 'previous test'

        where:
        iteration << [1, 2, 3]
    }
}
