package server

import support.ToolSpecBase

class RequiredParamsMemorySpec extends ToolSpecBase {
    def "warm required-parameter lookup does not rebuild the catalog or persist derived metadata"() {
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
}
