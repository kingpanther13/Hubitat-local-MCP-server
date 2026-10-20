package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class SetRuleAccessSpec extends ToolSpecBase {
    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 7L, label: 'MCP')
    List writes

    void setupSpec() { appExecutor.getApp() >> sharedAppStub }

    void setup() {
        script.callerClient = null
        settingsMap.enableRead = true
        settingsMap.enableWrite = true
        settingsMap.enableMandatoryBPS = true
        writes = []
        script.metaClass.toolGetRuleDocument = { Map args -> [rule_id: args.rule_id, actions: []] }
        script.metaClass.toolUpdateRuleDocument = { Map args -> writes << args; [success: true] }
    }

    void cleanup() { script.callerClient = null }

    private static Map update(boolean confirm = true) {
        [operation: 'update', rule_id: 42, confirm: confirm,
         args: [changes: [[part: 'required', value: [tokens: ['1']]]]]]
    }

    def 'read and schema discovery work with Write off and mandatory acknowledgment on'() {
        given:
        settingsMap.enableWrite = false
        when:
        def result = script.executeTool('hub_set_rule', request)
        then:
        result != null
        writes.empty
        !atomicStateMap.mrtrRequests
        where:
        request << [[:], [operation: 'read', rule_id: 42], [operation: 'create'],
                    [operation: 'update', confirm: false], [operation: 'form']]
    }

    def 'Read off blocks reads and schema discovery even when Write is on'() {
        given:
        settingsMap.enableRead = false
        when:
        script.executeTool('hub_set_rule', request)
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Read tools are disabled')
        writes.empty
        where:
        request << [[:], [operation: 'read', rule_id: 42], [operation: 'update']]
    }

    def 'confirmed writes require Write and the correct guide acknowledgment'() {
        given:
        settingsMap.enableWrite = false
        when:
        script.executeTool('hub_set_rule', update())
        then:
        def master = thrown(IllegalArgumentException)
        master.message.contains('Write tools are disabled')
        writes.empty
        when:
        settingsMap.enableWrite = true
        script.executeTool('hub_set_rule', update())
        then:
        def acknowledgment = thrown(IllegalArgumentException)
        acknowledgment.message.contains('set_rule_reference')
        writes.empty
        when:
        def result = script.executeTool('hub_set_rule', update() + [bestPracticeKey: script.hubBpsGuideKey('set_rule_reference')])
        then:
        result.success
        writes.size() == 1
        writes[0].confirm == true
    }

    def 'false confirmation discovers schema while malformed confirmation cannot dispatch a writer'() {
        when:
        def help = script.executeTool('hub_set_rule', update(false))
        then:
        help.confirm_required
        writes.empty
        when:
        script.executeTool('hub_set_rule', update() + [confirm: 'true'])
        then:
        thrown(IllegalArgumentException)
        writes.empty
    }

    def 'MRTR continuation and write reservation classify the selected invocation'() {
        expect:
        script._mrtrEligibleCall('hub_set_rule', 'hub_set_rule', request) == write
        script._isActualWriteCall('hub_set_rule', 'hub_set_rule', request) == write
        where:
        request                                    | write
        [:]                                        | false
        [operation: 'read', rule_id: 42]            | false
        [operation: 'update', confirm: false]       | false
        [operation: 'form']                        | false
        update()                                   | true
    }

    def 'gateway MRTR classification unwraps the rule operation arguments'() {
        given:
        settingsMap.useGateways = true
        expect:
        !script._mrtrEligibleCall('hub_manage_rule_machine', 'hub_set_rule', [args: [operation: 'read', rule_id: 42]])
        !script._isActualWriteCall('hub_manage_rule_machine', 'hub_set_rule', [args: [operation: 'update']])
        script._mrtrEligibleCall('hub_manage_rule_machine', 'hub_set_rule', [args: update()])
        script._isActualWriteCall('hub_manage_rule_machine', 'hub_set_rule', [args: update()])
    }

    def 'per-client Read and Write switches gate distinct set rule invocations'() {
        given:
        script.callerClient = [id: '1', label: 'Reader']
        settingsMap.clientTools_1 = ['hub_set_rule']
        settingsMap.clientRead_1 = true
        settingsMap.clientWrite_1 = false
        expect:
        script._clientToolDenial('hub_set_rule', [:]) == null
        script._clientToolDenial('hub_set_rule', [operation: 'read', rule_id: 42]) == null
        script._clientToolDenial('hub_set_rule', update()).contains('Write tools are disabled')
        when:
        settingsMap.clientRead_1 = false
        settingsMap.clientWrite_1 = true
        then:
        script._clientToolDenial('hub_set_rule', [:]).contains('Read tools are disabled')
        script._clientToolDenial('hub_set_rule', update()) == null
    }

    def 'a client cannot create a native child under this MCP instance through nested args'() {
        given:
        script.callerClient = [id: '1', label: 'Writer']
        settingsMap.clientTools_1 = ['hub_set_rule']
        when:
        script.executeTool('hub_set_rule', [operation: 'create', confirm: true,
            args: [parent_id: 7, name: 'Wrong parent', changes: []]])
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('cannot target the MCP Rule Server app itself')
        writes.empty
    }

    def 'catalog retains write annotations and visibility for a client allowed only reads'() {
        given:
        settingsMap.useGateways = false
        settingsMap.enableWrite = false
        script.callerClient = [id: '1', label: 'Reader']
        settingsMap.clientTools_1 = ['hub_set_rule']
        settingsMap.clientRead_1 = true
        settingsMap.clientWrite_1 = false
        when:
        def tool = script.getToolDefinitions().find { it.name == 'hub_set_rule' }
        then:
        tool != null
        tool.annotations.readOnlyHint == false
        tool.annotations.destructiveHint == true
        tool.annotations.idempotentHint == false
        tool.annotations.openWorldHint == false
    }
}
