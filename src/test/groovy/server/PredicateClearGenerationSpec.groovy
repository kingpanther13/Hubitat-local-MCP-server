package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.TestChildApp
import support.ToolSpecBase

class PredicateClearGenerationSpec extends ToolSpecBase {
    private static class FailingPredicateState extends LinkedHashMap {
        boolean fail
        Object put(Object key, Object value) {
            if (key == 'predClearPending' && fail) throw new IllegalStateException('predicate state unavailable')
            super.put(key, value)
        }
    }

    private String pageJson(String page, boolean confirmed = true) {
        List inputs = []
        if (page == 'STPage' && confirmed) inputs = [[name: 'cancelST', type: 'button'], [name: 'editST', type: 'button']]
        if (page == 'selectActions') inputs = [[name: 'N', type: 'button']]
        if (page == 'doActPage') inputs = [
            [name: 'actType.1', type: 'enum', options: [condActs: 'Conditional Actions']],
            [name: 'actSubType.1', type: 'enum', options: [getIfThen: 'IF Expression THEN']],
            [name: 'actionCancel', type: 'button']]
        JsonOutput.toJson([app: [id: 100, name: 'Rule-5.1', label: 'r', installed: true,
            version: '7', appType: [name: 'Rule-5.1', namespace: 'hubitat']],
            configPage: [name: page, install: page == 'mainPage', sections: [[input: inputs]]],
            settings: [:], childApps: []])
    }

    private void installPages(boolean confirmed = true) {
        hubGet.register('/installedapp/configure/json/100') { params -> pageJson('mainPage') }
        ['mainPage', 'selectActions', 'doActPage', 'STPage'].each { String page ->
            hubGet.register("/installedapp/configure/json/100/${page}".toString()) { params -> pageJson(page, confirmed) }
        }
        hubGet.register('/installedapp/statusJson/100') { params ->
            JsonOutput.toJson([installedApp: [id: 100], appSettings: [], appState: [:],
                eventSubscriptions: [], scheduledJobs: [], childAppCount: 0, childDeviceCount: 0])
        }
    }

    @Unroll
    def 'deferred clear preserves a newer generation when the ghost operation fails=#fails and token=#token'() {
        given:
        atomicStateMap.predClearPending = ['100': token]
        installPages()
        String newer = null
        List buttons = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            if (path == '/installedapp/btn') buttons << body.name
            if (body.name == 'N') {
                script._rmMarkPredClearPending(100)
                newer = script._rmPendingPredClearSnapshot().get('100')
                if (fails) throw new IOException('ghost request failed')
            }
            [status: 200, location: null, data: '']
        }

        when:
        script._rmRunPendingPredCapabsClear(100)

        then:
        newer != null && newer != token
        atomicStateMap.predClearPending.get('100') == newer
        script._rmPendingPredClearSnapshot().get('100') == newer
        fails || buttons.contains('actionCancel')

        where:
        [fails, token] << [[false, true], ['first-generation', true]].combinations()
    }

    @Unroll
    def 'deferred clear consumes its unchanged token=#token'() {
        given:
        atomicStateMap.predClearPending = ['100': token]
        installPages()
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            [status: 200, location: null, data: '']
        }

        when:
        script._rmRunPendingPredCapabsClear(100)

        then:
        !atomicStateMap.predClearPending.containsKey('100')
        !script._rmPendingPredClearSnapshot().containsKey('100')

        where:
        token << ['first-generation', true]
    }

    def 'deferred clear remains best effort when predicate bookkeeping cannot persist'() {
        given:
        def backing = new FailingPredicateState()
        backing.put('predClearPending', ['100': 'first-generation'])
        def peer = newCompiledScriptInstance([app: new TestChildApp(id: 1L),
            state: stateMap, atomicState: backing])
        installPages()
        List buttons = []
        peer.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            if (path == '/installedapp/btn') buttons << body.name
            [status: 200, location: null, data: '']
        }
        backing.@fail = true

        when:
        peer._rmRunPendingPredCapabsClear(100)

        then:
        noExceptionThrown()
        buttons.contains('actionCancel')
        backing.predClearPending.get('100') == 'first-generation'
        peer._rmPendingPredClearSnapshot().get('100') == 'first-generation'
    }
}
