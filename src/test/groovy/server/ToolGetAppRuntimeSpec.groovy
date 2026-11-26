package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/** Bounded statusJson projection for custom installed apps. */
class ToolGetAppRuntimeSpec extends ToolSpecBase {

    private String statusJson(Map overrides = [:]) {
        def base = [
            installedApp: [id: 1306, appTypeId: 534, name: 'BedJet Source Event Relay'],
            appState: [
                [name: 'relayReady', value: true],
                [name: 'sampleEpoch', value: '12345678-1234-4234-8234-1234567890ab'],
                [name: 'accessToken', value: 'do-not-return']
            ],
            eventSubscriptions: [
                [id: 1, deviceId: 539, attribute: 'temperature', handler: 'onTemperature', token: 'secret'],
                [id: 2, deviceId: 729, attribute: 'event', handler: 'onRoomEvent']
            ],
            scheduledJobs: [[name: 'sampleControlInputs']],
            stopped: false,
            settings: [access_token: 'do-not-return']
        ]
        overrides.each { key, value ->
            if (key == 'stopped' && value == '__missing__') base.remove('stopped')
            else base[key] = value
        }
        JsonOutput.toJson(base)
    }

    private void registerStatus(String body, int appId = 1306) {
        hubGet.register("/installedapp/statusJson/${appId}") { body }
    }

    def "projects safe runtime fields and withholds raw app state and settings"() {
        given:
        settingsMap.enableRead = true
        registerStatus(statusJson())

        when:
        def result = script.toolGetAppRuntime([appId: 1306])

        then:
        result.success == true
        result.stateReadable == true
        result.sourceIdentity.id == '1306'
        result.sourceIdentity.codeId == '534'
        result.relayReady == true
        result.sampleEpoch == '12345678-1234-4234-8234-1234567890ab'
        result.eventSubscriptionCount == 2
        result.scheduledJobCount == 1
        result.stopped == false
        result.subscriptionIdentities*.deviceId == ['539', '729']
        result.subscriptionIdentities.every { !(it.containsKey('token')) }
        !result.containsKey('appState')
        !result.containsKey('settings')
        !result.containsKey('accessToken')
    }

    def "missing or malformed runtime fields remain unknown"() {
        given:
        settingsMap.enableRead = true
        registerStatus(statusJson([
            appState: [[name: 'relayReady', value: 'true'], [name: 'sampleEpoch', value: 'not-a-uuid']],
            eventSubscriptions: 'unknown', scheduledJobs: null, stopped: '__missing__'
        ]))

        when:
        def result = script.toolGetAppRuntime([appId: 1306])

        then:
        result.success == true
        result.stateReadable == true
        result.relayReady == null
        result.relayReadyInvalid == true
        result.sampleEpoch == null
        result.sampleEpochInvalid == true
        result.eventSubscriptionCount == null
        result.subscriptionIdentitiesReadable == false
        result.scheduledJobCount == null
        result.stopped == null
    }

    def "unreadable status fails closed without synthetic zero counts"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/statusJson/1306') { throw new IOException('status unavailable') }

        when:
        def result = script.toolGetAppRuntime([appId: 1306])

        then:
        result.success == false
        result.unreadable == true
        result.eventSubscriptionCount == null
        result.scheduledJobCount == null
        !result.containsKey('relayReady')
    }

    @spock.lang.Unroll
    def "dispatch exposes bounded runtime read through both gateway modes (useGateways=#useGateways)"() {
        given:
        settingsMap.enableRead = true
        settingsMap.useGateways = useGateways
        registerStatus(statusJson())

        when:
        def response = mcpDriver.callTool('hub_get_app_runtime', [appId: 1306])
        def result = mcpDriver.parseInner(response)

        then:
        response.error == null
        result.success == true
        result.appId == 1306
        result.relayReady == true
        result.eventSubscriptionCount == 2

        where:
        useGateways << [true, false]
    }

    def "validates positive integer app id before status read"() {
        given:
        settingsMap.enableRead = true

        when:
        script.toolGetAppRuntime([appId: 'not-an-id'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('positive integer')
    }
}
