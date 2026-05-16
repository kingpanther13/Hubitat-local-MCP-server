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
 *
 * Each direct-call feature has a parallel "via dispatch" feature that fires
 * the same tool through {@code mcpDriver.callTool} so the production
 * envelope path (JSON-RPC parse → tools/call → executeTool routing → error
 * mapping → response wrapping) is covered alongside the unit-level tool
 * internals. Dispatch features are @Unroll'd across useGateways true/false;
 * the JSON-RPC dispatch routes sub-tool names directly through the
 * executeTool switch regardless of the gateway flag.
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch returns -32602 envelope when Built-in App Read is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = false

        when:
        def response = mcpDriver.callTool('list_installed_apps', [:])

        then:
        response.error.code == -32602
        response.error.message.contains('Built-in App')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch flattens 2-level tree with parentId wiring (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true
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
        def response = mcpDriver.callTool('list_installed_apps', [filter: 'all'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 3
        inner.totalOnHub == 3
        inner.filter == 'all'
        def parent = inner.apps.find { it.id == 10 }
        parent.parentId == null
        parent.hasChildren == true
        parent.childCount == 2
        inner.apps.find { it.id == 11 }.parentId == 10
        inner.apps.find { it.id == 12 }.parentId == 10

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch filter=builtin returns only non-user apps (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true
        def treeJson = JsonOutput.toJson([
            apps: [
                [data: [id: 1, name: 'Builtin App', type: 'Builtin', user: false, disabled: false, hidden: false], children: []],
                [data: [id: 2, name: 'User App', type: 'UserApp', user: true, disabled: false, hidden: false], children: []]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def response = mcpDriver.callTool('list_installed_apps', [filter: 'builtin'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.apps.size() == 1
        inner.apps[0].id == 1

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch filter=user returns only user apps (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true
        def treeJson = JsonOutput.toJson([
            apps: [
                [data: [id: 1, name: 'Builtin App', type: 'Builtin', user: false, disabled: false, hidden: false], children: []],
                [data: [id: 2, name: 'User App', type: 'UserApp', user: true, disabled: false, hidden: false], children: []]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def response = mcpDriver.callTool('list_installed_apps', [filter: 'user'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.apps.size() == 1
        inner.apps[0].id == 2

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch filter=disabled returns only disabled apps (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true
        def treeJson = JsonOutput.toJson([
            apps: [
                [data: [id: 1, name: 'Active App', type: 'X', user: false, disabled: false, hidden: false], children: []],
                [data: [id: 2, name: 'Disabled App', type: 'X', user: false, disabled: true, hidden: false], children: []]
            ]
        ])
        hubGet.register('/hub2/appsList') { params -> treeJson }

        when:
        def response = mcpDriver.callTool('list_installed_apps', [filter: 'disabled'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.apps.size() == 1
        inner.apps[0].id == 2

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch filter=parents returns only apps with children (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('list_installed_apps', [filter: 'parents'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.apps.size() == 1
        inner.apps[0].id == 10

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch filter=children returns only apps with parentId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('list_installed_apps', [filter: 'children'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.apps.size() == 1
        inner.apps[0].id == 11

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch excludes hidden parent and promotes child to root (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('list_installed_apps', [filter: 'all'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        !inner.apps.any { it.id == 20 }
        def child = inner.apps.find { it.id == 21 }
        child != null
        child.parentId == null

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch promotes grandchildren past hidden middle ancestor (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { _ ->
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
        def response = mcpDriver.callTool('list_installed_apps', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.apps*.id == [1, 3]
        inner.apps.find { it.id == 3 }.parentId == 1
        inner.totalOnHub == 2

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch includeHidden=true includes hidden parent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('list_installed_apps', [filter: 'all', includeHidden: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.apps.any { it.id == 20 }
        inner.apps.find { it.id == 21 }.parentId == 20

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch returns -32602 envelope for invalid filter (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('list_installed_apps', [filter: 'bogus'])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('invalid filter')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch returns success=false envelope when hub body is empty (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params -> '' }

        when:
        def response = mcpDriver.callTool('list_installed_apps', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error?.toLowerCase()?.contains('empty') == true

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_installed_apps via dispatch returns success=false envelope when hub body is non-JSON (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params -> 'not json at all' }

        when:
        def response = mcpDriver.callTool('list_installed_apps', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error != null

        where:
        useGateways << [true, false]
    }

    def "cursor=null returns the full filtered list (backward compatible)"() {
        // No cursor means full list, no nextCursor field, no total field --
        // pre-#174 callers see no change. The universal size guard is the only
        // safety net active in this mode.
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: (0..<10).collect {
                [data: [id: it, name: "App-${it}", type: 'X', user: false, disabled: false, hidden: false], children: []]
            }])
        }

        when:
        def result = script.toolListInstalledApps([:])

        then:
        result.apps.size() == 10
        result.count == 10
        result.containsKey('nextCursor') == false
        result.containsKey('total') == false
    }

    def "cursor='' starts at page 1 of 50 and emits nextCursor when more pages remain (#174)"() {
        given:
        settingsMap.enableBuiltinApp = true
        // 120 apps -> 3 pages of 50 (50 + 50 + 20)
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: (0..<120).collect {
                [data: [id: it, name: "App-${it}", type: 'X', user: false, disabled: false, hidden: false], children: []]
            }])
        }

        when:
        def result = script.toolListInstalledApps([cursor: ''])

        then:
        result.apps.size() == 50
        result.count == 50
        result.total == 120
        result.nextCursor == '50'
        result.apps[0].id == 0
        result.apps[49].id == 49
    }

    def "iterating cursor across pages returns each app exactly once and the last page omits nextCursor"() {
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: (0..<120).collect {
                [data: [id: it, name: "App-${it}", type: 'X', user: false, disabled: false, hidden: false], children: []]
            }])
        }

        when: 'iterate every page'
        def collected = []
        String cursor = ''
        int pages = 0
        while (true) {
            def page = script.toolListInstalledApps([cursor: cursor])
            collected.addAll(page.apps)
            pages++
            if (!page.nextCursor) break
            cursor = page.nextCursor
            assert pages < 10 : "pagination runaway"
        }

        then: 'exactly 3 pages, no duplicates, every app surfaced exactly once'
        pages == 3
        collected.size() == 120
        (collected*.id as Set).size() == 120

        and: 'last page omits nextCursor'
        def lastPage = script.toolListInstalledApps([cursor: '100'])
        lastPage.apps.size() == 20
        !lastPage.containsKey('nextCursor')
    }

    def "cursor='not-a-number' throws IllegalArgumentException so dispatch surfaces -32602"() {
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: 1, name: 'App', type: 'X', user: false, disabled: false, hidden: false], children: []]]])
        }

        when:
        script.toolListInstalledApps([cursor: 'banana'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('cursor')
        ex.message.contains('list_installed_apps')
    }

    def "negative cursor is rejected as out of range (matches PR #180 contract)"() {
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: [[data: [id: 1, name: 'App', type: 'X', user: false, disabled: false, hidden: false], children: []]]])
        }

        when:
        script.toolListInstalledApps([cursor: '-1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('out of range')
    }

    def "cursor past the end of a non-empty list is rejected as out of range"() {
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps: (0..<5).collect {
                [data: [id: it, name: "App-${it}", type: 'X', user: false, disabled: false, hidden: false], children: []]
            }])
        }

        when:
        script.toolListInstalledApps([cursor: '999'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('out of range')
    }

    def "cursor='0' on an empty filtered list returns an empty page with no nextCursor (no IOOBE)"() {
        // Regression guard: a too-loose empty-list check would let cursor>0 through, then
        // subList(N, 0) would throw IndexOutOfBoundsException with a gibberish message.
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params -> JsonOutput.toJson([apps: []]) }

        when:
        def result = script.toolListInstalledApps([cursor: '0'])

        then:
        result.apps == []
        result.total == 0
        !result.containsKey('nextCursor')
    }

    def "cursor='1' on an empty filtered list throws IllegalArgumentException with the friendly out-of-range message"() {
        // The would-be bug: without the offset>0 clause, the empty-list short-circuit lets
        // cursor=1 through, then subList(1, 0) throws java.lang.IllegalArgumentException
        // "fromIndex(1) > toIndex(0)" -- which surfaces to the LLM as "Invalid params:
        // fromIndex(1) > toIndex(0)" with no clue the real cause was a stale cursor.
        given:
        settingsMap.enableBuiltinApp = true
        hubGet.register('/hub2/appsList') { params -> JsonOutput.toJson([apps: []]) }

        when:
        script.toolListInstalledApps([cursor: '1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('out of range')
        ex.message.contains('size=0')
        // Should NOT be the gibberish JVM message
        !ex.message.contains('fromIndex')
    }

    def "cursor pagination respects filter (paginates the filtered set, not the raw catalog)"() {
        given:
        settingsMap.enableBuiltinApp = true
        // 60 builtin + 60 user apps -> filter=user should paginate 60 apps (not 120)
        hubGet.register('/hub2/appsList') { params ->
            JsonOutput.toJson([apps:
                (0..<60).collect {
                    [data: [id: it, name: "Builtin-${it}", type: 'X', user: false, disabled: false, hidden: false], children: []]
                } +
                (100..<160).collect {
                    [data: [id: it, name: "User-${it}", type: 'X', user: true, disabled: false, hidden: false], children: []]
                }
            ])
        }

        when:
        def page1 = script.toolListInstalledApps([filter: 'user', cursor: ''])

        then: 'page is 50 user apps, total is 60 (filtered set), nextCursor=50'
        page1.apps.size() == 50
        page1.total == 60
        page1.nextCursor == '50'
        page1.apps.every { it.id >= 100 }

        when:
        def page2 = script.toolListInstalledApps([filter: 'user', cursor: '50'])

        then: 'remaining 10 user apps, no nextCursor'
        page2.apps.size() == 10
        !page2.containsKey('nextCursor')
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
