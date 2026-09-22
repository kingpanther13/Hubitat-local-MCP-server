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
    }

    @Unroll
    def "first access protects the actual instance #instanceId once"() {
        given:
        ownApp.id = instanceId

        expect:
        script._protectedAppIds() == [instanceId.toString()] as Set
        settingsMap.protectedAppIds == [instanceId.toString()]
        atomicStateMap.protectedAppsInitialized == true

        when:
        settingsMap.protectedAppIds = ['82']

        then:
        script._protectedAppIds() == ['82'] as Set
        ownApp.settingsStore.protectedAppIds.value == [instanceId.toString()]

        where:
        instanceId << [194L, 902L]
    }

    @Unroll
    def "an initialized empty selection #selection stays empty"() {
        given:
        atomicStateMap.protectedAppsInitialized = true
        settingsMap.protectedAppIds = selection

        expect:
        script._protectedAppIds().isEmpty()
        ownApp.settingsStore.isEmpty()

        where:
        selection << [[], null, '']
    }

    def "an existing explicit selection is preserved when initializing the marker"() {
        given:
        settingsMap.protectedAppIds = ['82', '91']

        expect:
        script._protectedAppIds() == ['82', '91'] as Set
        settingsMap.protectedAppIds == ['82', '91']
        atomicStateMap.protectedAppsInitialized == true
    }

    def "failed default persistence still protects self and retries later"() {
        given:
        ownApp.failUpdates = true

        expect:
        script._protectedAppIds() == ['194'] as Set
        atomicStateMap.protectedAppsInitialized != true

        when:
        ownApp.failUpdates = false

        then:
        script._protectedAppIds() == ['194'] as Set
        atomicStateMap.protectedAppsInitialized == true
        settingsMap.protectedAppIds == ['194']
    }

    @Unroll
    def "protection canonicalizes target #target and ignores developer mode #developer"() {
        given:
        atomicStateMap.protectedAppsInitialized = true
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
        atomicStateMap.protectedAppsInitialized = true
        settingsMap.protectedAppIds = ['194', '999']
        script.metaClass.hubInternalGet = { String path ->
            assert path == '/hub2/appsList'
            '{"apps":[{"data":{"id":82,"name":"Parent"},"children":[{"data":{"id":91,"name":"Child"}}]}]}'
        }

        when:
        def options = script._protectedAppOptions()

        then:
        options['82'].contains('Parent')
        options['91'].contains('Child')
        options['194'].contains('MCP Rule Server')
        options['999'].contains('999')
    }

    def "unavailable inventory preserves current picker selections"() {
        given:
        atomicStateMap.protectedAppsInitialized = true
        settingsMap.protectedAppIds = ['194', '82']
        script.metaClass.hubInternalGet = { String path -> throw new IOException('offline') }

        expect:
        script._protectedAppOptions().keySet().containsAll(['194', '82'])
        settingsMap.protectedAppIds == ['194', '82']
    }

    static class ProtectedSettingsApp extends TestChildApp {
        Map liveSettings
        boolean failUpdates

        @Override
        void updateSetting(String key, Map value) {
            if (failUpdates) throw new IllegalStateException('setting persistence failed')
            super.updateSetting(key, value)
            liveSettings[key] = value.value
        }
    }
}
