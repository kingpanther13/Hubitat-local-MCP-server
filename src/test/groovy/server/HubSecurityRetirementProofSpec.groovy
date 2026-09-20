package server

import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Executable proof for each Hub Security retirement review finding. Every feature here fails
 * while the defect is present and passes once it is fixed, so "this finding is real" is a test
 * result rather than a reading of the source.
 */
class HubSecurityRetirementProofSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')
    @Shared private TestLocation sharedLocation = new TestLocation()

    /** location.hub whose firmware getter throws, for the empty-catch path. */
    static class ThrowingHub extends TestHub {
        @Override String getFirmwareVersionString() {
            throw new IllegalStateException('firmware unreadable')
        }
    }

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedAppStub.settingsStore.clear()
        sharedLocation.hub = null
        mcpDriver.reset()
    }

    private List captureLogs() {
        def entries = []
        script.metaClass.mcpLog = { String level, String component, String message,
                                    String ruleId = null, Map extra = null ->
            entries << [level: level, component: component, message: message]
        }
        return entries
    }

    private void retiredHubWithCredentials() {
        sharedLocation.hub = new TestHub(firmwareVersionString: '2.5.1.181')
        settingsMap.hubSecurityEnabled = true
        settingsMap.hubSecurityUser = 'hubadmin'
        settingsMap.hubSecurityPassword = 'hunter2'
        sharedAppStub.settingsStore.hubSecurityUser = 'hubadmin'
        sharedAppStub.settingsStore.hubSecurityPassword = 'hunter2'
    }

    // ---- CRITICAL: the shed must not take down request handling ----

    /**
     * TestChildApp.removeSetting delegates to settingsStore.remove, so a store whose remove()
     * throws reproduces a durable-store failure mid-shed without touching metaClass (a
     * per-instance metaClass override does NOT intercept the call -- an earlier version of this
     * spec tried that and passed vacuously).
     */
    private Map throwingStore() {
        return new HashMap() {
            @Override Object remove(Object key) { throw new IllegalStateException('storage unavailable') }
        }
    }

    def "a durable-store failure during the shed does not escape to the caller"() {
        given:
        retiredHubWithCredentials()
        captureLogs()
        sharedAppStub.settingsStore = throwingStore()

        when:
        script._retireHubSecuritySettings()

        then: 'a cosmetic credential shed never fails the request it rode in on'
        noExceptionThrown()

        cleanup:
        sharedAppStub.settingsStore = [:]
    }

    def "a failed shed is reported, not swallowed"() {
        given:
        retiredHubWithCredentials()
        def logs = captureLogs()
        sharedAppStub.settingsStore = throwingStore()

        when:
        script._retireHubSecuritySettings()

        then: 'the failure is visible -- a partial shed can leave the password on disk'
        logs.any { it.level == 'error' && it.component == 'hub-admin' }

        cleanup:
        sharedAppStub.settingsStore = [:]
    }

    // ---- CRITICAL: the per-request wiring itself ----

    def "driving a real MCP request sheds the credentials -- proves the handleMcpRequest hook"() {
        given: 'a hub that upgraded and whose owner never opened the app page'
        retiredHubWithCredentials()
        captureLogs()
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'initialize',
                            params: [protocolVersion: '2026-07-28']])

        when: 'one ordinary MCP request, no updated() anywhere'
        script.handleMcpRequest()

        then: 'the shed rode in on the request'
        stateMap.hubSecurityRetired == true
        !sharedAppStub.settingsStore.containsKey('hubSecurityUser')
        !sharedAppStub.settingsStore.containsKey('hubSecurityPassword')
    }

    // ---- HIGH: the retirement notice must survive the default log threshold ----

    def "the credential deletion leaves a record a default install can actually see"() {
        given: 'the shipped default -- mcpLogLevel "error" filters warn and below'
        settingsMap.mcpLogLevel = 'error'
        retiredHubWithCredentials()
        def logs = captureLogs()

        when:
        script._retireHubSecuritySettings()

        then: 'the notice is raised above info -- info is the level nothing keeps'
        def notice = logs.find { it.message?.contains('retired') }
        notice != null
        notice.level == 'warn'

        and: 'and the durable record does not depend on the log threshold at all'
        script.toolGetHubInfo([:]).hubSecurityRetired == true
    }

    // ---- HIGH: an unreadable firmware string must not be silent ----

    def "an unreadable firmware version keeps the credential path AND says so"() {
        given:
        sharedLocation.hub = new ThrowingHub()
        settingsMap.hubSecurityEnabled = true
        def logs = captureLogs()

        when:
        def obsolete = script._hubSecurityObsolete()

        then: 'the escape hatch holds'
        obsolete == false

        and: 'and the reason is announced, as the helper this was copied from does'
        logs.any { it.level == 'warn' && it.component == 'hub-admin' }
    }

    // ---- MEDIUM: the common case -- nothing stored ----

    def "a hub that never configured credentials sheds silently"() {
        given:
        sharedLocation.hub = new TestHub(firmwareVersionString: '2.5.1.181')
        def logs = captureLogs()

        when:
        script._retireHubSecuritySettings()

        then: 'the marker is stamped so the hook goes quiet'
        stateMap.hubSecurityRetired == true

        and: 'but no "credentials retired" line for a user who had none'
        !logs.any { it.message?.contains('retired') }
    }

    // ---- MEDIUM: diagnostics must distinguish retired from never-configured ----

    def "hub_get_info distinguishes a retired hub from one that never configured credentials"() {
        given:
        retiredHubWithCredentials()
        captureLogs()
        script._retireHubSecuritySettings()

        when:
        def info = script.toolGetHubInfo([:])

        then: 'a bug report from a 2.5.0+ hub can tell the two apart'
        info.hubSecurityRetired == true
    }

    // ---- The predicate itself, across the cutoff ----

    @Unroll
    def "_hubSecurityObsolete(#fw) == #expected"() {
        given:
        sharedLocation.hub = new TestHub(firmwareVersionString: fw)

        expect:
        script._hubSecurityObsolete() == expected

        where:
        fw          || expected
        '2.5.0'     || true
        '2.5.0.123' || true
        '2.5.1.181' || true
        '2.10'      || true
        '2.10.0.1'  || true
        '2.4.9.999' || false
        '2.4.99.99' || false
        '2.3.8.108' || false
        null        || false
        ''          || false
        '   '       || false
    }
}
