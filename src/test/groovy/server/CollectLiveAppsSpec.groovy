package server

import support.ToolSpecBase
import groovy.json.JsonOutput

/**
 * _collectLiveApps is the absence proof behind native-rule restores: a null return means
 * "cannot confirm", so the walk must reject only genuinely malformed nodes. A leaf that
 * carries "children": null is an empty container, which the pre-strictness walk tolerated.
 */
class CollectLiveAppsSpec extends ToolSpecBase {

    def setup() {
        settingsMap.enableRead = true
    }

    def "a null children field is an empty container, not a malformed node"() {
        given:
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [
                [data: [id: 21, name: 'Rule Machine', disabled: false],
                 children: [[data: [id: 150, name: 'Leaf rule', disabled: null], children: null]]]
            ]])
        }

        when:
        def apps = script._collectLiveApps()

        then: 'the inventory is complete and both ids are present'
        apps != null
        apps.keySet() == ([21, 150] as Set)
        apps.get(150).name == 'Leaf rule'
    }

    def "a present non-List children field still marks the inventory incomplete"() {
        given:
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: 21, name: 'Rule Machine'], children: 'broken']]])
        }

        when:
        def apps = script._collectLiveApps()

        then:
        apps == null
    }
}
