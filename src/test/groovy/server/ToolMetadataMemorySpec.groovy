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
    }

    def "cleanup retries a failed removal and warm calls stop accessing state"() {
        given:
        def persisted = new FailingLegacyState()
        persisted.toolSearchCorpus = ['old']
        def peer = newCompiledScriptInstance(app: new TestChildApp(id: 402L), state: [:], atomicState: persisted)

        when:
        peer._cleanupRetiredToolState()

        then:
        persisted.toolSearchCorpus == ['old']

        when:
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

    private static class FailingLegacyState extends LinkedHashMap {
        int removals = 0
        Object remove(Object key) {
            removals++
            if (removals == 1) throw new IllegalStateException('temporary storage failure')
            return super.remove(key)
        }
    }
}
