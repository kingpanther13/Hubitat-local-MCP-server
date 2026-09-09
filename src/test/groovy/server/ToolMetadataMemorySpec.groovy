package server

import spock.lang.Unroll
import support.TestChildApp
import support.ToolSpecBase

class ToolMetadataMemorySpec extends ToolSpecBase {
    def "warm required lookup avoids catalog construction and persisted state"() {
        given:
        int builds = 0
        script.metaClass.getAllToolDefinitions = {
            builds++
            [[name: 'hub_example', inputSchema: [required: ['value']]]]
        }

        when:
        def first = script.requiredParamsByTool()
        def second = script.requiredParamsByTool()

        then:
        first.hub_example == ['value']
        second == first
        builds == 1
        !atomicStateMap.containsKey('requiredParamsByTool')
        !atomicStateMap.containsKey('requiredParamsByToolFingerprint')

        when: 'another execution has no reason to read persisted metadata on the warm path'
        def peer = newCompiledScriptInstance(app: new TestChildApp(id: 402L), state: [:],
            atomicState: { throw new IllegalStateException('unexpected durable read') })

        then:
        peer.requiredParamsByTool().hub_example == ['value']
    }

    @Unroll
    def "cached #method metadata cannot be mutated by a caller"() {
        when:
        mutate.call(script."$method"())

        then:
        thrown(UnsupportedOperationException)

        where:
        method                       | mutate
        'getGatewayConfig'           | { it.hub_manage_rooms.tools.clear() }
        'getToolDisplayMeta'         | { it.hub_get_room.title = 'poisoned' }
        'getReadOnlyToolNames'        | { it.clear() }
        'getIdempotentWriteToolNames' | { it.clear() }
        'getIdempotentToolNames'      | { it.clear() }
        'getOpenWorldToolNames'       | { it.clear() }
        'getDeveloperModeOnlyToolNames' | { it.clear() }
        'requiredParamsByTool'       | { it.hub_get_room.clear() }
    }

    def "warm visibility uses tool names without rebuilding definitions and honors changed settings"() {
        given:
        settingsMap.enableWrite = false
        script.getHiddenToolNames()
        script.metaClass.getAllToolDefinitions = { throw new AssertionError('warm catalog rebuild') }

        expect:
        script.getHiddenToolNames().contains('hub_create_room')
        !script.getHiddenToolNames().contains('hub_get_room')

        when:
        settingsMap.enableWrite = true
        settingsMap.disabled_tools = ['hub_get_room']

        then:
        !script.getHiddenToolNames().contains('hub_create_room')
        script.getHiddenToolNames().contains('hub_get_room')
    }

    def "metadata invalidation refreshes required names on a same-version deployment"() {
        given:
        def required = ['old']
        script.metaClass.getAllToolDefinitions = {
            [[name: 'hub_example', inputSchema: [required: required]]]
        }
        script.requiredParamsByTool()

        when:
        required = ['new']
        script._invalidateToolMetadata()

        then:
        script.requiredParamsByTool().hub_example == ['new']
    }

    def "flat description transforms do not contaminate gateway or search definitions"() {
        given:
        def description = script.getAllToolDefinitions().find { it.name == 'hub_get_device' }.description
        settingsMap.useGateways = false
        script.getToolDefinitions()

        when:
        settingsMap.useGateways = true
        script.getToolDefinitions()
        script.toolSearchTools([query: 'device', maxResults: 5])

        then:
        script.getAllToolDefinitions().find { it.name == 'hub_get_device' }.description == description
    }

    def "ordinary ping removes retired state without building a catalog"() {
        given:
        atomicStateMap.toolSearchCorpus = ['large old corpus']
        atomicStateMap.toolSearchTokens = [['old', 'tokens']]
        atomicStateMap.requiredParamsByTool = [hub_get_room: ['obsolete']]
        atomicStateMap.requiredParamsByToolFingerprint = 'old'
        atomicStateMap.capturedDeviceStates = [saved: [devices: [[id: '1']]]]
        stateMap.toolSearchCorpus = ['legacy state corpus']
        stateMap.requiredParamsByTool = [hub_get_room: ['obsolete']]
        stateMap.ruleVariables = [enabled: false, count: 0]
        script.metaClass.getAllToolDefinitions = { throw new AssertionError('cleanup built catalog') }
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'ping'])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.parseResponseJson().error == null
        !atomicStateMap.containsKey('toolSearchCorpus')
        !atomicStateMap.containsKey('toolSearchTokens')
        !atomicStateMap.containsKey('requiredParamsByTool')
        !atomicStateMap.containsKey('requiredParamsByToolFingerprint')
        atomicStateMap.capturedDeviceStates.saved.devices == [[id: '1']]
        !stateMap.containsKey('toolSearchCorpus')
        !stateMap.containsKey('requiredParamsByTool')
        stateMap.ruleVariables == [enabled: false, count: 0]
    }

    def "cleanup retries a failed removal and warm calls stop accessing state"() {
        given:
        long clock = 1000L
        def warnings = []
        def persisted = new FailingLegacyState()
        persisted.toolSearchCorpus = ['old']
        def peer = newCompiledScriptInstance(app: new TestChildApp(id: 402L), state: [:], atomicState: persisted)
        peer.metaClass.now = { clock }
        peer.metaClass.mcpLog = { level, category, message -> warnings << [level, category, message] }

        when:
        peer._cleanupRetiredToolState()

        then:
        persisted.toolSearchCorpus == ['old']
        warnings.size() == 1
        warnings[0][0] == 'warn'

        when: 'requests during backoff neither retry nor repeat the warning'
        10.times { peer._cleanupRetiredToolState() }

        then:
        persisted.@removals == 1
        warnings.size() == 1

        when: 'the next request after backoff retries only remaining keys'
        clock += 60000L
        peer._cleanupRetiredToolState()
        int removed = persisted.@removals
        peer._cleanupRetiredToolState()

        then:
        !persisted.containsKey('toolSearchCorpus')
        persisted.@removals == removed

        when: 'a replacement installation shares the compiled class'
        def other = [toolSearchCorpus: ['other']]
        newCompiledScriptInstance(app: new TestChildApp(id: 403L), state: [:], atomicState: other)._cleanupRetiredToolState()

        then:
        !other.containsKey('toolSearchCorpus')
    }

    def "empty stores incur no removal calls and null-valued retired keys are removed"() {
        given:
        def legacy = new CountingLegacyState()
        def atomic = new CountingLegacyState()
        def peer = newCompiledScriptInstance(app: new TestChildApp(id: 402L), state: legacy, atomicState: atomic)

        when:
        peer._cleanupRetiredToolState()

        then:
        legacy.@removals == 0
        atomic.@removals == 0

        when: 'another installation contains only retired null markers'
        legacy.put('requiredParamsByTool', null)
        atomic.put('toolSearchCorpusVersion', null)
        atomic.put('unrelated', false)
        newCompiledScriptInstance(app: new TestChildApp(id: 403L), state: legacy, atomicState: atomic)._cleanupRetiredToolState()

        then:
        legacy.isEmpty()
        atomic == [unrelated: false]
        legacy.@removals == 1
        atomic.@removals == 1
    }

    def "direct search shares retired-key cleanup including required metadata"() {
        given:
        atomicStateMap.requiredParamsByTool = [obsolete: ['value']]
        stateMap.toolSearchCorpusVersion = 'old'

        when:
        script.toolSearchTools([query: 'room'])

        then:
        !atomicStateMap.containsKey('requiredParamsByTool')
        !stateMap.containsKey('toolSearchCorpusVersion')
    }

    private static class CountingLegacyState extends LinkedHashMap {
        int removals = 0
        Object remove(Object key) {
            removals++
            return super.remove(key)
        }
    }

    private static class FailingLegacyState extends LinkedHashMap {
        int removals = 0
        Object remove(Object key) {
            removals++
            if (removals == 1) throw new IllegalStateException('temporary storage failure')
            return super.remove(key)
        }
    }
}
