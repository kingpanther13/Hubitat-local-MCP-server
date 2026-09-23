package server

import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.ToolSpecBase

class ProtectedAppsSettingsSpec extends ToolSpecBase {
    @Shared private ProtectedSettingsApp ownApp = new ProtectedSettingsApp(id: 194L, label: 'MCP Rule Server')

    def setupSpec() {
        appExecutor.getApp() >> ownApp
    }

    def setup() {
        ownApp.id = 194L
        ownApp.settingsStore.clear()
        ownApp.liveSettings = settingsMap
        ownApp.failUpdates = false
        ownApp.publishUpdates = true
    }

    @Unroll
    def "first access protects the actual instance #instanceId once"() {
        given:
        ownApp.id = instanceId

        expect:
        script._protectedAppIds() == [instanceId.toString()] as Set
        settingsMap.protectedAppIds == [instanceId.toString()]
        atomicStateMap.protectedAppsPolicy == [ids: [instanceId.toString()]]

        when:
        settingsMap.protectedAppIds = ['82']
        script._protectedAppIds(true)

        then:
        script._protectedAppIds() == ['82'] as Set
        ownApp.settingsStore.protectedAppIds.value == [instanceId.toString()]

        where:
        instanceId << [194L, 902L]
    }

    def "a delayed settings snapshot cannot leave the first request unprotected"() {
        given:
        ownApp.publishUpdates = false
        settingsMap.protectedAppIds = null

        expect:
        script._protectedAppIds() == ['194'] as Set
        atomicStateMap.protectedAppsPolicy == [ids: ['194']]

        when:
        script._requireUnprotectedAppMutation(194, 'edit')

        then:
        thrown(IllegalArgumentException)

        when:
        settingsMap.protectedAppIds = ['194']

        then:
        script._protectedAppIds() == ['194'] as Set
        atomicStateMap.protectedAppsPolicy == [ids: ['194']]
    }

    @Unroll
    def "an initialized empty selection #selection stays empty"() {
        given:
        atomicStateMap.protectedAppsPolicy = [ids: ['194']]
        settingsMap.protectedAppIds = selection
        script._protectedAppIds(true)

        expect:
        script._protectedAppIds().isEmpty()
        atomicStateMap.protectedAppsPolicy == [ids: []]
        ownApp.settingsStore.isEmpty()

        where:
        selection << [[], null, '']
    }

    def "an existing explicit selection is preserved when initializing the policy"() {
        given:
        settingsMap.protectedAppIds = ['82', '91']

        expect:
        script._protectedAppIds() == ['82', '91'] as Set
        settingsMap.protectedAppIds == ['82', '91']
        atomicStateMap.protectedAppsPolicy == [ids: ['82', '91']]
    }

    def "failed default persistence still protects self and retries later"() {
        given:
        ownApp.failUpdates = true

        expect:
        script._protectedAppIds() == ['194'] as Set
        atomicStateMap.protectedAppsPolicy == null

        when:
        ownApp.failUpdates = false

        then:
        script._protectedAppIds() == ['194'] as Set
        atomicStateMap.protectedAppsPolicy == [ids: ['194']]
        settingsMap.protectedAppIds == ['194']
    }

    @Unroll
    def "protection canonicalizes target #target and ignores developer mode #developer"() {
        given:
        atomicStateMap.protectedAppsPolicy = [ids: ['194']]
        settingsMap.protectedAppIds = ['194']
        settingsMap.enableDeveloperMode = developer

        when:
        script._requireUnprotectedAppMutation(target, 'edit')

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('194')
        error.message.contains('protected')
        error.message.contains('Hubitat app UI')

        where:
        [target, developer] << [[194, '194', '0194'], [false, true]].combinations()
    }

    def "picker includes nested installed apps and preserves selected unavailable IDs"() {
        given:
        atomicStateMap.protectedAppsPolicy = [ids: ['194', '999']]
        settingsMap.protectedAppIds = ['194', '999']
        script.metaClass.hubInternalGet = { String path ->
            assert path == '/hub2/appsList'
            '{"apps":[{"data":{"id":82,"name":"<b>Parent</b>"},"children":[{"data":{"id":91,"name":"Heat On &lt;67"}}]}]}'
        }

        when:
        def choices = script._protectedAppChoices()
        def options = choices.options

        then:
        options['82'].contains('Parent')
        options['91'] == 'Heat On <67 (ID 91)'
        options['194'].contains('MCP Rule Server')
        options['999'].contains('999')
        choices.inventoryUnavailable == false
    }

    def "unavailable inventory preserves current picker selections"() {
        given:
        atomicStateMap.protectedAppsPolicy = [ids: ['194', '82']]
        settingsMap.protectedAppIds = ['194', '82']
        script.metaClass.hubInternalGet = { String path -> throw new IOException('offline') }

        expect:
        script._protectedAppChoices().options.keySet().containsAll(['194', '82'])
        script._protectedAppChoices().inventoryUnavailable == true
        settingsMap.protectedAppIds == ['194', '82']
    }

    def "a pre-initialization request snapshot cannot bypass another request's published policy"() {
        given:
        def olderSnapshot = [:] + settingsMap
        def currentSnapshot = [:] + settingsMap
        currentSnapshot.protectedAppIds = ['194']
        settingsMap.clear()
        settingsMap.putAll(currentSnapshot)

        when: 'the newer request publishes initialization'
        script._protectedAppIds()
        settingsMap.clear()
        settingsMap.putAll(olderSnapshot)
        script._requireUnprotectedAppMutation(194, 'edit')

        then: 'the older handler reads the shared effective selection, not its stale null'
        def error = thrown(IllegalArgumentException)
        error.message.contains('194')
        atomicStateMap.protectedAppsPolicy == [ids: ['194']]
        ownApp.settingsStore.isEmpty()
    }

    def "an old nonempty request snapshot cannot restore a selection cleared through the UI"() {
        given:
        atomicStateMap.protectedAppsPolicy = [ids: ['194']]
        settingsMap.protectedAppIds = []

        when:
        script._protectedAppIds(true)
        settingsMap.protectedAppIds = ['194']

        then:
        script._protectedAppIds().isEmpty()
        atomicStateMap.protectedAppsPolicy == [ids: []]
    }

    @Unroll
    def "first-install Done applies #selection after the picker has published defaults"() {
        given:
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":194,"name":"MCP"}},{"data":{"id":82,"name":"Rule"}}]}'
        }
        script._protectedAppChoices()
        assert atomicStateMap.protectedAppsPolicy == [ids: ['194']]
        settingsMap.protectedAppIds = selection
        ownApp.settingsStore.clear()
        def policyAtInitialize = null
        script.metaClass.initialize = { -> policyAtInitialize = script._protectedAppIds() }

        when:
        script.installed()

        then:
        policyAtInitialize == expected as Set
        atomicStateMap.protectedAppsPolicy == [ids: expected]
        ownApp.settingsStore.isEmpty()

        when: 'a request still holds the preview settings'
        settingsMap.protectedAppIds = ['194']

        then:
        script._protectedAppIds() == expected as Set

        where:
        selection     | expected
        ['194', '82'] | ['194', '82']
        ['82']        | ['82']
        []            | []
        null          | []
        ''            | []
    }

    @Unroll
    def "runtime initialization preserves saved #selection despite a stale settings snapshot"() {
        given:
        atomicStateMap.protectedAppsPolicy = [ids: selection]
        settingsMap.protectedAppIds = ['194']
        stateMap.accessToken = 'existing-token'
        stateMap.updateCheck = [checkedAt: 1L]
        script.metaClass._subscribeToAllHubVariables = { -> }
        script.metaClass._refreshHubVarInUseRegistrations = { -> }

        when:
        script.initialize()
        script.initialize()

        then:
        script._protectedAppIds() == selection as Set
        atomicStateMap.protectedAppsPolicy == [ids: selection]
        ownApp.settingsStore.isEmpty()
        stateMap.accessToken == 'existing-token'

        where:
        selection << [['82'], []]
    }

    def "Done publishes the UI selection and later request snapshots cannot override it"() {
        given:
        atomicStateMap.protectedAppsPolicy = [ids: ['194']]
        settingsMap.protectedAppIds = ['82']
        stateMap.customEngineMigrated = true
        script.metaClass.initialize = { -> }

        when:
        script.updated()
        settingsMap.protectedAppIds = ['194']

        then:
        atomicStateMap.protectedAppsPolicy == [ids: ['82']]
        script._protectedAppIds() == ['82'] as Set
    }

    static class ProtectedSettingsApp extends TestChildApp {
        Map liveSettings
        boolean failUpdates
        boolean publishUpdates = true

        @Override
        void updateSetting(String key, Map value) {
            if (failUpdates) throw new IllegalStateException('setting persistence failed')
            super.updateSetting(key, value)
            if (publishUpdates) liveSettings[key] = value.value
        }
    }
}
