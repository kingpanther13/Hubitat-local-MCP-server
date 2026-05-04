package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolListInstalledApps (hubitat-mcp-server.groovy approx line 7483).
 * Gateway: manage_installed_apps -> list_installed_apps.
 *
 * Covers: gate-throw, golden path flattening with parentId wiring, the
 * includeHidden promotion logic, each filter value, invalid filter rejection,
 * empty and non-JSON response bodies.
 */
class ToolListInstalledAppsSpec extends ToolSpecBase {

    def "throws when Built-in App Read is disabled"() {
        given:
        settingsMap.enableBuiltinApp = false

        when:
        script.toolListInstalledApps([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    def "golden path: 2-level tree flattens with parentId wiring"() {
        given:
        settingsMap.enableBuiltinApp = true

        and: 'hub returns a parent with 2 children'
        def treeJson = JsonOutput.toJson([
            apps: [
                [
                    data: [id: 10, name: 'Rule Machine', type: 'Rule Machine', user: false, disabled: false, hidden: false],
                    children: [
                        [data: [id: 11, name: 'My Rule', type: 'Rule-5.1', user: false, disabled: false, hidden: false], children: []],
                        [data: [id: 12, name: 'Another Rule', type: 'Rule-5.1', user: false, disabled: false, hidden: false], children: []]
                    ]
                ]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def result = script.toolListInstalledApps([filter: 'all'])

        then:
        result.count == 3
        result.totalOnHub == 3
        result.filter == 'all'

        and: 'parent entry wired correctly'
        def parent = result.apps.find { it.id == 10 }
        parent != null
        parent.parentId == null
        parent.hasChildren == true
        parent.childCount == 2

        and: 'children have parentId pointing to parent'
        def child11 = result.apps.find { it.id == 11 }
        child11.parentId == 10

        def child12 = result.apps.find { it.id == 12 }
        child12.parentId == 10
    }

    def "filter=builtin returns only non-user apps"() {
        given:
        settingsMap.enableBuiltinApp = true

        def treeJson = JsonOutput.toJson([
            apps: [
                [data: [id: 1, name: 'Builtin App', type: 'Builtin', user: false, disabled: false, hidden: false], children: []],
                [data: [id: 2, name: 'User App', type: 'UserApp', user: true, disabled: false, hidden: false], children: []]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def result = script.toolListInstalledApps([filter: 'builtin'])

        then:
        result.apps.size() == 1
        result.apps[0].id == 1
    }

    def "filter=user returns only user apps"() {
        given:
        settingsMap.enableBuiltinApp = true

        def treeJson = JsonOutput.toJson([
            apps: [
                [data: [id: 1, name: 'Builtin App', type: 'Builtin', user: false, disabled: false, hidden: false], children: []],
                [data: [id: 2, name: 'User App', type: 'UserApp', user: true, disabled: false, hidden: false], children: []]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def result = script.toolListInstalledApps([filter: 'user'])

        then:
        result.apps.size() == 1
        result.apps[0].id == 2
    }

    def "filter=disabled returns only disabled apps"() {
        given:
        settingsMap.enableBuiltinApp = true

        def treeJson = JsonOutput.toJson([
            apps: [
                [data: [id: 1, name: 'Active App', type: 'X', user: false, disabled: false, hidden: false], children: []],
                [data: [id: 2, name: 'Disabled App', type: 'X', user: false, disabled: true, hidden: false], children: []]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def result = script.toolListInstalledApps([filter: 'disabled'])

        then:
        result.apps.size() == 1
        result.apps[0].id == 2
    }

    def "filter=parents returns only apps with children"() {
        given:
        settingsMap.enableBuiltinApp = true

        def treeJson = JsonOutput.toJson([
            apps: [
                [
                    data: [id: 10, name: 'Parent App', type: 'X', user: false, disabled: false, hidden: false],
                    children: [
                        [data: [id: 11, name: 'Child App', type: 'X', user: false, disabled: false, hidden: false], children: []]
                    ]
                ]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def result = script.toolListInstalledApps([filter: 'parents'])

        then:
        result.apps.size() == 1
        result.apps[0].id == 10
    }

    def "filter=children returns only apps with a non-null parentId"() {
        given:
        settingsMap.enableBuiltinApp = true

        def treeJson = JsonOutput.toJson([
            apps: [
                [
                    data: [id: 10, name: 'Parent App', type: 'X', user: false, disabled: false, hidden: false],
                    children: [
                        [data: [id: 11, name: 'Child App', type: 'X', user: false, disabled: false, hidden: false], children: []]
                    ]
                ]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def result = script.toolListInstalledApps([filter: 'children'])

        then:
        result.apps.size() == 1
        result.apps[0].id == 11
    }

    def "hidden parent excluded by default and child is promoted to root (parentId=null)"() {
        given:
        settingsMap.enableBuiltinApp = true

        and: 'hidden parent with one visible child'
        def treeJson = JsonOutput.toJson([
            apps: [
                [
                    data: [id: 20, name: 'Hidden Parent', type: 'X', user: false, disabled: false, hidden: true],
                    children: [
                        [data: [id: 21, name: 'Visible Child', type: 'X', user: false, disabled: false, hidden: false], children: []]
                    ]
                ]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when: 'default includeHidden=false'
        def result = script.toolListInstalledApps([filter: 'all'])

        then: 'hidden parent excluded'
        !result.apps.any { it.id == 20 }

        and: 'visible child promoted to root with parentId null'
        def child = result.apps.find { it.id == 21 }
        child != null
        child.parentId == null
    }

    def "hidden parent in the middle of a three-level tree: grandchildren promote to nearest visible ancestor"() {
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { _ ->
            // grandparent (visible, id=1)
            //   -> parent (hidden, id=2)
            //     -> child (visible, id=3)  -- should resolve to parentId=1, not 2 (hidden) or null (root)
            JsonOutput.toJson([apps: [[
                data: [id: 1, name: 'GrandParent', type: 'Group', disabled: false, user: false, hidden: false],
                children: [[
                    data: [id: 2, name: 'HiddenParent', type: 'Group', disabled: false, user: false, hidden: true],
                    children: [[
                        data: [id: 3, name: 'Child', type: 'Rule', disabled: false, user: false, hidden: false],
                        children: []
                    ]]
                ]]
            ]]])
        }

        when:
        def result = script.toolListInstalledApps([:])  // includeHidden defaults to false

        then:
        result.apps*.id == [1, 3]                                       // HiddenParent excluded
        result.apps.find { it.id == 3 }.parentId == 1                   // grandchild promoted to grandparent, NOT null and NOT 2
        result.totalOnHub == 2
    }

    def "includeHidden=true includes hidden parent and wires child parentId to it"() {
        given:
        settingsMap.enableBuiltinApp = true

        def treeJson = JsonOutput.toJson([
            apps: [
                [
                    data: [id: 20, name: 'Hidden Parent', type: 'X', user: false, disabled: false, hidden: true],
                    children: [
                        [data: [id: 21, name: 'Visible Child', type: 'X', user: false, disabled: false, hidden: false], children: []]
                    ]
                ]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def result = script.toolListInstalledApps([filter: 'all', includeHidden: true])

        then: 'hidden parent included'
        result.apps.any { it.id == 20 }

        and: 'child parentId points to the hidden parent'
        def child = result.apps.find { it.id == 21 }
        child.parentId == 20
    }

    def "throws IllegalArgumentException for an invalid filter value"() {
        given:
        settingsMap.enableBuiltinApp = true
        // Hub endpoint must be registered even though it won't be reached;
        // the validation throws before the HTTP call.

        when:
        script.toolListInstalledApps([filter: 'bogus'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('invalid filter')
    }

    def "returns success=false with empty-response error when hub body is empty"() {
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params -> '' }

        when:
        def result = script.toolListInstalledApps([:])

        then:
        result.success == false
        result.error?.toLowerCase()?.contains('empty') == true
    }

    def "returns success=false with parse error when hub body is non-JSON"() {
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params -> 'not json at all' }

        when:
        def result = script.toolListInstalledApps([:])

        then:
        result.success == false
        result.error != null
    }

    def "gateway dispatch via handleGateway also returns apps list"() {
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: []])
        }

        when:
        def result = script.handleGateway('manage_installed_apps', 'list_installed_apps', [filter: 'all'])

        then:
        result.apps instanceof List
        result.filter == 'all'
    }
}
