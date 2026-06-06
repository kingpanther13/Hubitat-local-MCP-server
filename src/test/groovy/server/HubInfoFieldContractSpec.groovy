package server

import spock.lang.Shared
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Contract spec asserting that {@code customRuleEngineEnabled} and
 * {@code developerModeEnabled} are present in the {@code getHubInfo()} response
 * (the merged successor to the removed {@code getHubDetails()}).
 *
 * Both fields are read by {@code .github/scripts/mcp_setup_env.sh} to capture
 * pre-run state before enabling toggles. If either field is dropped or renamed,
 * the setup script silently misreads pre-state, enabling unexpected settings
 * permanently on the test hub.
 *
 * Mocking strategy:
 *   - location.hub -> appExecutor.getLocation() returns sharedLocation
 */
class HubInfoFieldContractSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanup() {
        sharedLocation.hub = null
    }

    // -------- toolGetHubInfo --------

    def "getHubInfo includes customRuleEngineEnabled=true when enableCustomRuleEngine is true"() {
        given:
        settingsMap.enableCustomRuleEngine = true
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubInfo()

        then:
        result.containsKey('customRuleEngineEnabled')
        result.customRuleEngineEnabled == true
    }

    def "getHubInfo includes developerModeEnabled=true when enableDeveloperMode is true"() {
        given:
        settingsMap.enableDeveloperMode = true
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubInfo()

        then:
        result.containsKey('developerModeEnabled')
        result.developerModeEnabled == true
    }

    // issue #237: a self-deploy can't return its result on the deploy call (success reloads the app;
    // a big-file compile failure 504s), so toolUpdateItemCodeInner records the hub's verbatim outcome
    // to atomicState.lastSelfDeploy and hub_get_info surfaces it for a follow-up read (CI recovers the
    // real compile error this way). These pin that the field is exposed when set and omitted otherwise.
    def "getHubInfo exposes atomicState.lastSelfDeploy when present (issue #237)"() {
        given:
        sharedLocation.hub = new TestHub()
        atomicStateMap.lastSelfDeploy = [success: false, error: 'name cannot be empty in definition section',
                                         sourceMode: 'importUrl', importUrl: 'https://x/app.groovy', at: 1234567890000L]

        when:
        def result = script.toolGetHubInfo()

        then:
        result.lastSelfDeploy?.success == false
        result.lastSelfDeploy.error == 'name cannot be empty in definition section'
        result.lastSelfDeploy.importUrl == 'https://x/app.groovy'
        // freshness affordance: ageMs (now - at) is computed at read so a consumer can spot a STALE
        // record (lastSelfDeploy persists in atomicState across reloads and is not cleared on update).
        result.lastSelfDeploy.ageMs instanceof Number
        result.lastSelfDeploy.ageMs >= 0
        // computed on a copy -- the persisted atomicState record itself is not mutated.
        !atomicStateMap.lastSelfDeploy.containsKey('ageMs')
    }

    def "getHubInfo omits lastSelfDeploy when the app has never self-deployed"() {
        given:
        sharedLocation.hub = new TestHub()
        atomicStateMap.lastSelfDeploy = null

        when:
        def result = script.toolGetHubInfo()

        then:
        !result.containsKey('lastSelfDeploy')
    }

    def "getHubInfo surfaces the #include'd smoke-test marker (issue #209)"() {
        given:
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubInfo()

        then: 'the McpSmokeTestLib marker, pulled in via #include and folded into the info output'
        result.smokeTestMarker == 'smoke-ok-v1'
    }

    def "getHubInfo includes both fields as false when toggles are off"() {
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableDeveloperMode = false
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubInfo()

        then:
        result.containsKey('customRuleEngineEnabled')
        result.customRuleEngineEnabled == false
        result.containsKey('developerModeEnabled')
        result.developerModeEnabled == false
    }

    // -------- toolGetHubInfo identify-LED --------

    def "getHubInfo identifyHub=true fires /hub/advanced/blinkLED and reports identifyHubTriggered=true"() {
        given:
        sharedLocation.hub = new TestHub()
        hubGet.register('/hub/advanced/blinkLED') { params -> 'true' }

        when:
        def result = script.toolGetHubInfo([identifyHub: true])

        then:
        result.identifyHubTriggered == true
        !result.containsKey('identifyHubError')
        hubGet.calls.any { it.path == '/hub/advanced/blinkLED' }
    }

    def "getHubInfo without identifyHub arg does not hit the blinkLED endpoint or emit the field"() {
        given:
        sharedLocation.hub = new TestHub()
        // No hubGet.register — the no-call assertion is the strict-stronger guarantee;
        // field-absent alone would miss a fire-and-forget regression that calls the
        // endpoint without ever writing the result field.

        when:
        def result = script.toolGetHubInfo()

        then:
        !result.containsKey('identifyHubTriggered')
        !result.containsKey('identifyHubError')
        !hubGet.calls.any { it.path == '/hub/advanced/blinkLED' }
    }

    def "getHubInfo identifyHub=false does not hit the blinkLED endpoint"() {
        given:
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubInfo([identifyHub: false])

        then:
        !result.containsKey('identifyHubTriggered')
        !hubGet.calls.any { it.path == '/hub/advanced/blinkLED' }
    }

    def "getHubInfo identifyHub=true with endpoint failure surfaces identifyHubTriggered=false and identifyHubError"() {
        given:
        sharedLocation.hub = new TestHub()
        hubGet.register('/hub/advanced/blinkLED') { params -> throw new RuntimeException('LED endpoint missing on this firmware') }

        when:
        def result = script.toolGetHubInfo([identifyHub: true])

        then:
        result.identifyHubTriggered == false
        result.identifyHubError == 'LED endpoint missing on this firmware'
    }

    def "getHubInfo identifyHub=true with null-message exception falls back to e.toString()"() {
        given:
        sharedLocation.hub = new TestHub()
        hubGet.register('/hub/advanced/blinkLED') { params -> throw new IOException() }

        when:
        def result = script.toolGetHubInfo([identifyHub: true])

        then:
        result.identifyHubTriggered == false
        result.identifyHubError != null
        result.identifyHubError.toLowerCase().contains('ioexception')
    }

    // -------- toolGetHubInfo toggle-field contract (was toolGetHubDetails, merged in) --------

    def "getHubInfo (merged from getHubDetails) includes customRuleEngineEnabled=true when enableCustomRuleEngine is true"() {
        given:
        settingsMap.enableCustomRuleEngine = true
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubInfo()

        then:
        result.containsKey('customRuleEngineEnabled')
        result.customRuleEngineEnabled == true
    }

    def "getHubInfo (merged from getHubDetails) includes developerModeEnabled=true when enableDeveloperMode is true"() {
        given:
        settingsMap.enableDeveloperMode = true
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubInfo()

        then:
        result.containsKey('developerModeEnabled')
        result.developerModeEnabled == true
    }

    def "getHubInfo (merged from getHubDetails) includes both fields as false when toggles are off"() {
        given:
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableDeveloperMode = false
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubInfo()

        then:
        result.containsKey('customRuleEngineEnabled')
        result.customRuleEngineEnabled == false
        result.containsKey('developerModeEnabled')
        result.developerModeEnabled == false
    }

    def "getHubInfo includes readEnabled/writeEnabled=true by default (masters unset)"() {
        given:
        sharedLocation.hub = new TestHub()
        // enableRead/enableWrite unset -- both masters default ON.

        when:
        def result = script.toolGetHubInfo()

        then:
        result.containsKey('readEnabled')
        result.readEnabled == true
        result.containsKey('writeEnabled')
        result.writeEnabled == true
        // The legacy field names are gone.
        !result.containsKey('hubAdminReadEnabled')
        !result.containsKey('hubAdminWriteEnabled')
        !result.containsKey('builtinAppEnabled')
    }

    def "getHubInfo includes the PII keys by default (Read master ON when unset)"() {
        given:
        sharedLocation.hub = new TestHub()
        // enableRead unset -- the PII block runs by default, so the PII keys are
        // present (values come from the stubs) and no read-disabled note is emitted.

        when:
        def result = script.toolGetHubInfo()

        then:
        result.containsKey('name')
        result.containsKey('localIP')
        result.containsKey('timeZone')
        !result.containsKey('readDisabledNote')
    }

    def "getHubInfo does NOT throw when Read master is OFF; excludes PII and surfaces readDisabledNote"() {
        given:
        sharedLocation.hub = new TestHub()
        settingsMap.enableRead = false
        // toolGetHubInfo gates only PII when the Read master is off (the tool itself
        // is reachable via the central gate only when Read is on; called directly here
        // it surfaces a readDisabledNote and still returns the toggle fields). PII is
        // included by default and excluded ONLY when enableRead == false.

        when:
        def result = script.toolGetHubInfo()

        then:
        noExceptionThrown()
        result.containsKey('readDisabledNote')
        result.readDisabledNote.contains('Read master')
        !result.containsKey('name')
        !result.containsKey('localIP')
        result.containsKey('customRuleEngineEnabled')
        result.readEnabled == false
    }
}
