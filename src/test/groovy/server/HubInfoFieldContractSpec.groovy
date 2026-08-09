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

    def "includeRecentOps hides write records while the Write master is off"() {
        // A write record's token replays that write's buffered result, so listing it with
        // writes disabled would hand back the very thing the master is withholding.
        given:
        sharedLocation.hub = new TestHub()
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        atomicStateMap.opTokens = [
            readtoken123: [state: 'complete', tool: 'hub_get_info', isError: false, startedAt: 1000L],
            writetoken12: [state: 'complete', tool: 'hub_create_room', isError: false, startedAt: 2000L]
        ]

        when:
        def result = script.toolGetHubInfo([includeRecentOps: true])

        then: "only the read record is listed, and the total reflects the filtered set"
        result.recentOps*.opToken == ['readtoken123']
        result.recentOpsTotal == 1
    }

    def "includeRecentOps lists write records when the Write master is on"() {
        given:
        sharedLocation.hub = new TestHub()
        settingsMap.enableRead = true
        settingsMap.enableWrite = true
        atomicStateMap.opTokens = [
            readtoken123: [state: 'complete', tool: 'hub_get_info', isError: false, startedAt: 1000L],
            writetoken12: [state: 'complete', tool: 'hub_create_room', isError: false, startedAt: 2000L]
        ]

        when:
        def result = script.toolGetHubInfo([includeRecentOps: true])

        then: "both records are listed, newest first"
        result.recentOps*.opToken == ['writetoken12', 'readtoken123']
        result.recentOpsTotal == 2
    }

    def "recentOpsLimit caps the listed records while recentOpsTotal keeps the true count"() {
        given:
        sharedLocation.hub = new TestHub()
        settingsMap.enableRead = true
        settingsMap.enableWrite = true
        def recs = [:]
        (1..30).each { recs["token${it.toString().padLeft(8, '0')}".toString()] = [state: 'complete', tool: 'hub_get_info', isError: false, startedAt: (1000L + it)] }
        atomicStateMap.opTokens = recs

        when: 'no limit -- the default cap applies'
        def defaulted = script.toolGetHubInfo([includeRecentOps: true])

        then:
        defaulted.recentOps.size() == 25
        defaulted.recentOpsTotal == 30

        when: 'an explicit smaller limit'
        def limited = script.toolGetHubInfo([includeRecentOps: true, recentOpsLimit: 3])

        then:
        limited.recentOps.size() == 3
        limited.recentOpsTotal == 30
    }

    @spock.lang.Unroll
    def "a non-integer recentOpsLimit (#badLimit) is refused instead of silently falling back"() {
        // The old try/catch-ignore fell back to the default cap, so a typo'd limit read as
        // "the hub only has 25 records" -- a wrong answer dressed as a right one.
        given:
        sharedLocation.hub = new TestHub()
        settingsMap.enableRead = true
        atomicStateMap.opTokens = [
            readtoken123: [state: 'complete', tool: 'hub_get_info', isError: false, startedAt: 1000L]
        ]

        when:
        script.toolGetHubInfo([includeRecentOps: true, recentOpsLimit: badLimit])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('recentOpsLimit')

        where:
        badLimit << ['abc', 0, -5, 2.5]
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
