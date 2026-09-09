package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.ToolSpecBase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CapturedStateStorageSpec extends ToolSpecBase {
    @Shared TestChildApp captureApp = new TestChildApp(id: 402L)
    Map files = [:]
    int uploads = 0
    int downloads = 0

    def setupSpec() { appExecutor.getApp() >> captureApp }

    def setup() {
        wireFiles(script)
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson(files.keySet().collect { [name: it] })
        }
    }

    private void wireFiles(peer) {
        peer.metaClass.uploadHubFile = { String name, byte[] bytes ->
            uploads++
            files[name] = new String(bytes, 'UTF-8')
        }
        peer.metaClass.downloadHubFile = { String name ->
            downloads++
            files[name]?.getBytes('UTF-8')
        }
        peer.metaClass.deleteHubFile = { String name -> files.remove(name) }
    }

    def "capture payload is verified in a file and excluded from durable app state"() {
        given:
        Map payload = ['42': [switch: 'on', level: 0, enabled: false,
            note: '雪' + ('large-value-' * 1000), absent: null]]

        when:
        def saved = script.saveCapturedState('scene/雪', payload)

        then:
        saved.stateId == 'scene/雪'
        saved.deviceCount == 1
        saved.totalStored == 1
        uploads == 1
        downloads >= 1
        files.size() == 1
        !JsonOutput.toJson(atomicStateMap).contains('large-value-')
        script.getCapturedState('scene/雪') == payload
        script.listCapturedStates()*.stateId == ['scene/雪']
    }

    def "an upload failure cannot replace the previously committed capture"() {
        given:
        Map old = ['1': [switch: 'off', level: 0]]
        script.saveCapturedState('scene', old)
        script.metaClass.uploadHubFile = { String name, byte[] bytes ->
            throw new IllegalStateException('disk unavailable')
        }

        when:
        script.saveCapturedState('scene', ['1': [switch: 'on', level: 90]])

        then:
        thrown(IllegalStateException)
        script.getCapturedState('scene') == old
    }

    def "capture listing requires no file reads after save"() {
        given:
        script.saveCapturedState('empty', [:])
        downloads = 0

        expect:
        script.listCapturedStates().find { it.stateId == 'empty' }.deviceCount == 0
        downloads == 0
        script.getCapturedState('empty') == [:]
    }

    def "ordinary ping does not read or write capture files"() {
        given:
        atomicStateMap.capturedDeviceStates = [old: [devices: ['1': [level: 1]], timestamp: 1L]]
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'ping'])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.parseResponseJson().error == null
        uploads == 0
        downloads == 0
    }

    def "initialization resumes cleanup for an empty committed index without file IO"() {
        given:
        atomicStateMap.captureIndex = [schema: 1, revision: 'committed', entries: [:]]
        stateMap.accessToken = 'tok'
        stateMap.updateCheck = [checkedAt: 1L]
        script.metaClass._subscribeToAllHubVariables = { -> }
        script.metaClass._refreshHubVarInUseRegistrations = { -> }

        when:
        script.initialize()

        then:
        runInMillisCalls.any { it[1] == 'captureMigrationStep' }
        uploads == 0
        downloads == 0
    }

    def "cold orphan cleanup only deletes this app's unreferenced generated files"() {
        given:
        script.saveCapturedState('scene', ['1': [level: 1]])
        String oldName = files.keySet().first()
        script.metaClass.deleteHubFile = { String name -> throw new IllegalStateException('busy') }
        script.saveCapturedState('scene', ['1': [level: 2]])
        files['mcp-capture-999-00000000-0000-0000-0000-000000000001.json'] = '{}'
        files['mcp-capture-402-user.json'] = '{}'
        (scriptStaticField('CAPTURE_STORES') as Map).clear()
        wireFiles(script)

        when:
        script.captureMigrationStep()

        then:
        !files.containsKey(oldName)
        files.containsKey('mcp-capture-999-00000000-0000-0000-0000-000000000001.json')
        files.containsKey('mcp-capture-402-user.json')
        script.getCapturedState('scene') == ['1': [level: 2]]
    }

    def "cold restore preserves values and returned mutable data cannot poison the cache"() {
        given:
        Map payload = ['3': [enabled: false, level: 0, temperature: 20.125G, missing: null, text: '雪☃']]
        script.saveCapturedState('cold', payload)
        script.getCapturedState('cold')['3'].text = 'changed'
        (scriptStaticField('CAPTURE_STORES') as Map).clear()
        downloads = 0

        expect:
        script.getCapturedState('cold') == payload
        downloads == 1
        script.getCapturedState('cold') == payload
        downloads == 1
    }

    @Unroll
    def "missing or corrupt committed payload fails explicitly after cache reset (#damage)"() {
        given:
        script.saveCapturedState('saved', ['1': [switch: 'on']])
        String name = files.keySet().first()
        if (damage == 'missing') files.remove(name)
        else files[name] = '{}'
        (scriptStaticField('CAPTURE_STORES') as Map).clear()

        when:
        script.getCapturedState('saved')

        then:
        thrown(IllegalStateException)
        script.listCapturedStates()*.stateId == ['saved']

        where:
        damage << ['missing', 'corrupt']
    }

    def "verification failure preserves old generation and never publishes corrupt data"() {
        given:
        script.saveCapturedState('scene', ['1': [level: 10]])
        String oldName = files.keySet().first()
        script.metaClass.downloadHubFile = { String name ->
            name == oldName ? files[name].getBytes('UTF-8') : '{}'.getBytes('UTF-8')
        }

        when:
        script.saveCapturedState('scene', ['1': [level: 20]])

        then:
        thrown(IllegalStateException)
        files.containsKey(oldName)
        script.getCapturedState('scene') == ['1': [level: 10]]
    }

    def "legacy maps and list entries migrate one at a time without losing distinct IDs"() {
        given:
        stateMap.capturedDeviceStates = [older: [devices: ['1': [level: 0]], timestamp: 1L],
            same: [devices: ['1': [level: 99]], timestamp: 2L]]
        atomicStateMap.capturedDeviceStates = [same: [devices: ['1': [level: 10]], timestamp: 3L],
            list: [[id: '2', value: false]]]

        when:
        script.listCapturedStates()
        (1..4).each { script.captureMigrationStep() }
        (scriptStaticField('CAPTURE_STORES') as Map).clear()

        then:
        script.getCapturedState('older') == ['1': [level: 0]]
        script.getCapturedState('same') == ['1': [level: 10]]
        script.getCapturedState('list') == [[id: '2', value: false]]
        script.listCapturedStates()*.stateId == ['same', 'older', 'list']
        !stateMap.capturedDeviceStates
        !atomicStateMap.capturedDeviceStates
        files.size() == 3
    }

    def "failed legacy migration remains readable and retries without reviving deleted captures"() {
        given:
        stateMap.capturedDeviceStates = [keep: [devices: ['1': [level: 5]], timestamp: 1L],
            gone: [devices: ['2': [level: 6]], timestamp: 2L]]
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> throw new IllegalStateException('offline') }

        when:
        script.captureMigrationStep()

        then:
        stateMap.capturedDeviceStates.keep.devices == ['1': [level: 5]]
        script.getCapturedState('keep') == ['1': [level: 5]]

        when:
        script.deleteCapturedState('gone')
        (scriptStaticField('CAPTURE_STORES') as Map).clear()
        wireFiles(script)
        (1..3).each { script.captureMigrationStep() }

        then:
        script.listCapturedStates()*.stateId == ['keep']
        script.getCapturedState('gone') == null
        script.getCapturedState('keep') == ['1': [level: 5]]
    }

    def "retention reduction and overwrite preserve the latest committed capture and clean owned files"() {
        given:
        settingsMap.maxCapturedStates = 3
        ['a', 'b', 'c'].each { script.saveCapturedState(it, ['1': [level: 1]]) }
        files['unrelated.json'] = '{}'
        settingsMap.maxCapturedStates = 1

        when:
        script.saveCapturedState('c', ['1': [level: 20]])

        then:
        script.listCapturedStates()*.stateId == ['c']
        script.getCapturedState('c') == ['1': [level: 20]]
        files.size() == 2
        files.containsKey('unrelated.json')

        when:
        def cleared = script.clearAllCapturedStates()
        (scriptStaticField('CAPTURE_STORES') as Map).clear()

        then:
        cleared.cleared == 1
        script.listCapturedStates() == []
        files == ['unrelated.json': '{}']
    }

    @Unroll
    def "failed index publication retains a recoverable generation (persisted=#persisted)"() {
        given:
        def backing = new PublicationStore()
        def peer = newCompiledScriptInstance(app: captureApp, state: [:], atomicState: backing)
        wireFiles(peer)
        peer.saveCapturedState('scene', ['1': [level: 10]])
        backing.@failNext = true
        backing.@persistBeforeFailure = persisted
        boolean failed = false

        when:
        try { peer.saveCapturedState('scene', ['1': [level: 20]]) }
        catch (IllegalStateException expected) { failed = true }
        (scriptStaticField('CAPTURE_STORES') as Map).clear()

        then:
        failed == !persisted
        peer.getCapturedState('scene') == ['1': [level: persisted ? 20 : 10]]

        where:
        persisted << [false, true]
    }

    def "unverifiable committed index keeps both files and cold recovery uses durable truth"() {
        given:
        def backing = new PublicationStore()
        def peer = newCompiledScriptInstance(app: captureApp, state: [:], atomicState: backing)
        wireFiles(peer)
        peer.saveCapturedState('scene', ['1': [level: 1]])
        backing.@failReadAfterWrite = true

        when:
        peer.saveCapturedState('scene', ['1': [level: 2]])

        then:
        thrown(IllegalStateException)
        files.size() == 2

        when:
        (scriptStaticField('CAPTURE_STORES') as Map).clear()

        then:
        peer.getCapturedState('scene') == ['1': [level: 2]]
    }

    def "migration preserves pending payloads when both legacy accessors share one store"() {
        given:
        def backing = new PublicationStore()
        backing.capturedDeviceStates = [a: [devices: ['1': [level: 1]], timestamp: 1L],
            b: [devices: ['2': [level: 2]], timestamp: 2L]]
        def peer = newCompiledScriptInstance(app: captureApp, state: backing, atomicState: backing)
        wireFiles(peer)

        when:
        peer.captureMigrationStep()

        then:
        backing.capturedDeviceStates.size() == 1
        backing.capturedDeviceStates.values().first().devices in [['1': [level: 1]], ['2': [level: 2]]]

        when:
        peer.captureMigrationStep()
        (scriptStaticField('CAPTURE_STORES') as Map).clear()

        then:
        !backing.capturedDeviceStates
        peer.getCapturedState('a') == ['1': [level: 1]]
        peer.getCapturedState('b') == ['2': [level: 2]]
    }

    @Unroll
    def "serialized capture index stays small as payload grows (#count captures #devices devices)"() {
        given:
        settingsMap.maxCapturedStates = count
        Map payload = (1..devices).collectEntries { n ->
            [(n.toString()): [switch: 'on', level: n % 100, hue: 25, saturation: 50, text: '雪' * width]]
        }
        Map legacy = (1..count).collectEntries { n ->
            [("scene${n}".toString()): [devices: payload, timestamp: 1234567890000L, deviceCount: devices]]
        }

        when:
        legacy.each { id, entry -> script.saveCapturedState(id, entry.devices) }
        int before = JsonOutput.toJson(legacy).getBytes('UTF-8').length
        int after = JsonOutput.toJson(atomicStateMap).getBytes('UTF-8').length
        println "CAPTURE_STORAGE captures=${count} devices=${devices} width=${width} legacyBytes=${before} indexBytes=${after} uploads=${uploads} downloads=${downloads}"

        then:
        after < before / 5
        script.listCapturedStates().size() == count
        uploads == count
        downloads == count

        where:
        count | devices | width
        20    | 50      | 0
        100   | 200     | 16
    }

    def "concurrent separate executions preserve both writers and same-ID overwrite is whole"() {
        given:
        def backing = new PublicationStore()
        def peers = (1..2).collect {
            def peer = newCompiledScriptInstance(app: captureApp, state: [:], atomicState: backing)
            wireFiles(peer)
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
        (scriptStaticField('CAPTURE_STORES') as Map).clear()

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

    private static class PublicationStore extends LinkedHashMap {
        boolean failNext
        boolean persistBeforeFailure
        boolean failReadAfterWrite
        boolean failRead
        Object get(Object key) {
            if (key == 'captureIndex' && failRead) {
                failRead = false
                throw new IllegalStateException('injected readback failure')
            }
            return super.get(key)
        }
        Object put(Object key, Object value) {
            def copy = new JsonSlurper().parseText(JsonOutput.toJson(value))
            if (key == 'captureIndex' && failNext) {
                failNext = false
                if (persistBeforeFailure) super.put(key, copy)
                throw new IllegalStateException('injected publication failure')
            }
            def previous = super.put(key, copy)
            if (key == 'captureIndex' && failReadAfterWrite) {
                failReadAfterWrite = false
                failRead = true
            }
            return previous
        }
    }
}
