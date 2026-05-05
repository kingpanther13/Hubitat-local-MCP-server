package server

import spock.lang.Shared
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Contract spec asserting that {@code customRuleEngineEnabled} and
 * {@code developerModeEnabled} are present in both {@code getHubInfo()} and
 * {@code getHubDetails()} responses.
 *
 * Both fields are read by {@code .github/scripts/mcp_setup_env.sh} to capture
 * pre-run state before enabling toggles. If either field is dropped or renamed,
 * the setup script silently misreads pre-state, enabling unexpected settings
 * permanently on the test hub.
 *
 * Mocking strategy:
 *   - location.hub -> appExecutor.getLocation() returns sharedLocation
 *   - toolGetHubDetails requires enableHubAdminRead; set in given: per-test
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

    // -------- toolGetHubDetails --------

    def "getHubDetails includes customRuleEngineEnabled=true when enableCustomRuleEngine is true"() {
        given:
        settingsMap.enableHubAdminRead = true
        settingsMap.enableCustomRuleEngine = true
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubDetails([:])

        then:
        result.containsKey('customRuleEngineEnabled')
        result.customRuleEngineEnabled == true
    }

    def "getHubDetails includes developerModeEnabled=true when enableDeveloperMode is true"() {
        given:
        settingsMap.enableHubAdminRead = true
        settingsMap.enableDeveloperMode = true
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubDetails([:])

        then:
        result.containsKey('developerModeEnabled')
        result.developerModeEnabled == true
    }

    def "getHubDetails includes both fields as false when toggles are off"() {
        given:
        settingsMap.enableHubAdminRead = true
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableDeveloperMode = false
        sharedLocation.hub = new TestHub()

        when:
        def result = script.toolGetHubDetails([:])

        then:
        result.containsKey('customRuleEngineEnabled')
        result.customRuleEngineEnabled == false
        result.containsKey('developerModeEnabled')
        result.developerModeEnabled == false
    }

    def "getHubDetails throws when Hub Admin Read is disabled"() {
        given:
        // enableHubAdminRead not set

        when:
        script.toolGetHubDetails([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }
}
