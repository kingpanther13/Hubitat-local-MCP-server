package server

import groovy.json.JsonOutput
import support.ToolSpecBase

class CapturedStateStorageSpec extends ToolSpecBase {
    Map files = [:]
    int uploads = 0
    int downloads = 0

    def setup() {
        script.metaClass.uploadHubFile = { String name, byte[] bytes ->
            uploads++
            files[name] = new String(bytes, 'UTF-8')
        }
        script.metaClass.downloadHubFile = { String name ->
            downloads++
            files[name]?.getBytes('UTF-8')
        }
        script.metaClass.deleteHubFile = { String name -> files.remove(name) }
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
}
