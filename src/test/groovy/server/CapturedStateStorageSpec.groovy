package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.ToolSpecBase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CapturedStateStorageSpec extends ToolSpecBase {
    @Shared TestChildApp captureApp = new TestChildApp(id: 402L)
    def setupSpec() { appExecutor.getApp() >> captureApp }

    private void forbidFiles(peer) {
        peer.metaClass.uploadHubFile = { String name, byte[] bytes -> throw new IllegalStateException('capture upload') }
        peer.metaClass.downloadHubFile = { String name -> throw new IllegalStateException('capture download') }
        peer.metaClass.deleteHubFile = { String name -> throw new IllegalStateException('capture delete') }
    }

    def "separate requests share detached captures without durable writes or file IO"() {
        given:
        def peer = newCompiledScriptInstance(app: captureApp, state: stateMap, atomicState: atomicStateMap)
        [script, peer].each { forbidFiles(it) }
        Map payload = ['42': [switch: 'on', level: 0, enabled: false, note: '\u96ea', absent: null]]
        String before = JsonOutput.toJson([stateMap, atomicStateMap])

        when:
        def saved = script.saveCapturedState('scene/\u96ea', payload)
        payload['42'].level = 100
        def restored = peer.getCapturedState('scene/\u96ea')
        restored['42'].note = 'changed'

        then:
        saved.deviceCount == 1
        saved.totalStored == 1
        peer.getCapturedState('scene/\u96ea') == ['42': [switch: 'on', level: 0, enabled: false, note: '\u96ea', absent: null]]
        peer.listCapturedStates()*.stateId == ['scene/\u96ea']
        peer.countCapturedStates() == 1
        JsonOutput.toJson([stateMap, atomicStateMap]) == before

        when:
        peer.clearAllCapturedStates()

        then:
        script.getCapturedState('scene/\u96ea') == null
    }

    def "legacy captures migrate once without revival from stale execution state"() {
        given:
        stateMap.capturedDeviceStates = [scene: [devices: ['1': [level: 1]], timestamp: 1L]]
        atomicStateMap.capturedDeviceStates = [scene: [devices: ['1': [level: 37]], timestamp: 2L]]
        def staleState = [capturedDeviceStates: stateMap.capturedDeviceStates]
        def peer = newCompiledScriptInstance(app: captureApp, state: staleState, atomicState: atomicStateMap)
        [script, peer].each { forbidFiles(it) }

        expect:
        script.getCapturedState('scene') == ['1': [level: 37]]
        script.listCapturedStates().first().timestamp == 2L
        !stateMap.containsKey('capturedDeviceStates')
        !atomicStateMap.containsKey('capturedDeviceStates')

        when:
        script.deleteCapturedState('scene')

        then:
        peer.getCapturedState('scene') == null
        !staleState.containsKey('capturedDeviceStates')
    }

    @Unroll
    def "initialize removes empty legacy capture keys from #legacyLocation (value #legacyValue)"() {
        given:
        stateMap.accessToken = 'tok'
        stateMap.updateCheck = [checkedAt: 1L]
        script.metaClass._subscribeToAllHubVariables = { -> }
        script.metaClass._refreshHubVarInUseRegistrations = { -> }
        if (legacyLocation in ['state', 'both']) stateMap.capturedDeviceStates = legacyValue
        if (legacyLocation in ['atomicState', 'both']) atomicStateMap.capturedDeviceStates = legacyValue
        forbidFiles(script)

        when:
        script.initialize()

        then:
        !stateMap.containsKey('capturedDeviceStates')
        !atomicStateMap.containsKey('capturedDeviceStates')
        script.countCapturedStates() == 0

        where:
        legacyLocation << ['state', 'atomicState', 'both', 'state', 'atomicState', 'both']
        legacyValue << [[:], [:], [:], null, null, null]
    }

    def "class reload loses captures without rehydrating persisted payloads"() {
        given:
        atomicStateMap.capturedDeviceStates = [old: [devices: ['1': [level: 37]], timestamp: 1L]]
        script.saveCapturedState('new', ['2': [level: 68]])
        assert script.countCapturedStates() == 2

        when:
        (scriptStaticField('CAPTURE_STORES') as Map).clear()
        def peer = newCompiledScriptInstance(app: captureApp, state: stateMap, atomicState: atomicStateMap)

        then:
        peer.listCapturedStates() == []
        peer.getCapturedState('old') == null
        peer.getCapturedState('new') == null
    }

    def "retention reduction preserves an overwrite and apps cannot share captures"() {
        given:
        settingsMap.maxCapturedStates = 3
        ['a', 'b', 'c'].each { script.saveCapturedState(it, ['1': [level: 1]]) }
        settingsMap.maxCapturedStates = 1
        def other = newCompiledScriptInstance(app: new TestChildApp(id: 403L), state: [:], atomicState: [:])

        when:
        def result = script.saveCapturedState('a', ['1': [level: 68]])

        then:
        result.deletedStates.toSet() == ['b', 'c'].toSet()
        script.listCapturedStates()*.stateId == ['a']
        script.getCapturedState('a') == ['1': [level: 68]]
        other.getCapturedState('a') == null
        other.countCapturedStates() == 0
    }

    @Unroll
    def "capture payload growth adds zero persisted bytes (#count captures #devices devices)"() {
        given:
        settingsMap.maxCapturedStates = count
        Map payload = (1..devices).collectEntries { n ->
            [(n.toString()): [switch: 'on', level: n % 100, hue: 25, saturation: 50, text: '\u96ea' * width]]
        }
        Map legacy = (1..count).collectEntries { n ->
            [("scene${n}".toString()): [devices: payload, timestamp: 1234567890000L, deviceCount: devices]]
        }
        String before = JsonOutput.toJson([stateMap, atomicStateMap])
        forbidFiles(script)

        when:
        legacy.each { id, entry -> script.saveCapturedState(id, entry.devices) }
        String after = JsonOutput.toJson([stateMap, atomicStateMap])
        println "CAPTURE_MEMORY captures=${count} devices=${devices} legacyBytes=${JsonOutput.toJson(legacy).getBytes('UTF-8').length} addedPersistedBytes=${after.getBytes('UTF-8').length - before.getBytes('UTF-8').length}"

        then:
        after == before
        script.listCapturedStates().size() == count
        script.getCapturedState('scene1') == payload

        where:
        count | devices | width
        20    | 50      | 0
        100   | 200     | 16
    }

    def "concurrent separate executions preserve both writers and same-ID overwrite is whole"() {
        given:
        def backing = [:]
        def peers = (1..2).collect {
            def peer = newCompiledScriptInstance(app: captureApp, state: [:], atomicState: backing)
            peer
        }
        def start = new CountDownLatch(1)
        def done = new CountDownLatch(2)
        def failures = Collections.synchronizedList([])
        def workers = peers.withIndex().collect { peer, i ->
            new Thread({
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException('start timeout')
                    (1..4).each { n -> peer.saveCapturedState("${i}-${n}".toString(), [writer: [id: i, n: n]]) }
                    peer.saveCapturedState('shared', [writer: [id: i, tag: "writer${i}".toString()]])
                } catch (Throwable e) { failures << e }
                finally { done.countDown() }
            } as Runnable)
        }

        when:
        workers.each { it.start() }
        start.countDown()
        boolean finished = done.await(20, TimeUnit.SECONDS)

        then:
        finished
        failures.empty
        peers[0].listCapturedStates().size() == 9
        (0..1).every { i -> (1..4).every { n ->
            peers[0].getCapturedState("${i}-${n}".toString()) == [writer: [id: i, n: n]]
        } }
        peers[0].getCapturedState('shared') in [[writer: [id: 0, tag: 'writer0']], [writer: [id: 1, tag: 'writer1']]]

        cleanup:
        start.countDown()
        workers.each { it.join(1000) }
    }

}
