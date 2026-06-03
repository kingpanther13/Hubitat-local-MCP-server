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

    def "getHubInfo does NOT throw when Hub Admin Read is disabled and surfaces hubAdminReadRequired"() {
        given:
        sharedLocation.hub = new TestHub()
        // enableHubAdminRead not set -- toolGetHubInfo gates only PII (unlike the
        // removed toolGetHubDetails which threw via requireHubAdminRead()); it
        // surfaces a hubAdminReadRequired notice and still returns the toggle fields.

        when:
        def result = script.toolGetHubInfo()

        then:
        noExceptionThrown()
        result.containsKey('hubAdminReadRequired')
        result.hubAdminReadRequired.contains('Hub Admin Read')
        result.containsKey('customRuleEngineEnabled')
    }
}
