package server

import support.ToolSpecBase

class NativeModelSpec extends ToolSpecBase {
    Map nativeState
    Map locals
    Map settings
    List writes
    Expando nativeRule

    void setup() {
        nativeState = [keep: [unknown: true], ndx: 4, actNdx: 1,
                       capabsfalse: ['1': 'Switch A on', '2': 'Switch B off'], eval: ['0': ['1']]]
        locals = [:]
        settings = [:]
        writes = []
        nativeRule = new Expando()
        nativeRule.checkTokens = { Object id -> '' }
        nativeRule.getLocalVar = { String name -> locals.get(name) }
        nativeRule.getAllLocalVars = { -> [:] + locals }
        nativeRule.createLocalVar = { String name, Object value, String type -> locals.put(name, [name: name, value: value, type: type]) }
        nativeRule.setLocalVar = { String name, Object value -> locals.get(name).value = value }
        nativeRule.deleteLocalVar = { String name -> locals.remove(name) }
        nativeRule.selectConditions = { Map ignored -> nativeState.capabsfalse['4'] = 'Switch A on' }
        nativeRule.selectTriggers = { -> nativeState.capabstrue = ['4': 'Switch A on'] }
        script.metaClass._nrHandle = { Long id -> nativeRule }
        script.metaClass._nrState = { Long id -> [:] + nativeState }
        script.metaClass._nrStatePatch = { Long id, Map values, List remove = [] -> remove.each { nativeState.remove(it) }; nativeState.putAll(values); writes << values; [:] }
        script.metaClass._nrWriteSettings = { Long id, List rows ->
            rows.each { settings.put(it.name, [type: it.type, value: it.value]) }
            writes << rows
            [success: true]
        }
        script.metaClass._nrSettings = { Long id, List names -> settings.subMap(names) }
    }

    def 'malformed expressions refuse before state write even when native validation would accept'() {
        when:
        script._nmSetExpression(42L, '0', tokens)
        then:
        thrown(IllegalArgumentException)
        writes.empty
        nativeState.eval['0'] == ['1']
        where:
        tokens << [[], ['1', 'AND'], ['(', '1'], ['1', ')'], ['1', '2'], ['999'], ['AND', '1']]
    }

    def 'full expression replaces tokens at the same identity preserving unknown state'() {
        when:
        def result = script._nmSetExpression(42L, '0', ['(', '1', 'OR', 'NOT', '2', ')', 'AND', '1'])
        then:
        result.success
        nativeState.eval.keySet() == ['0'] as Set
        nativeState.eval['0'] == ['(', '1', 'OR', 'NOT', '2', ')', 'AND', '1']
        nativeState.keep == [unknown: true]
    }

    def 'native token rejection restores original while preserving concurrent unrelated expressions'() {
        given:
        nativeRule.checkTokens = { Object id -> nativeState.eval['9'] = ['2']; 'native rejection' }
        when:
        def result = script._nmSetExpression(42L, '0', ['2'])
        then:
        !result.success
        result.originalPreserved
        !result.partial
        nativeState.eval == ['0': ['1'], '9': ['2']]
    }

    def 'empty native error does not hide swallowed expression persistence'() {
        given:
        nativeRule.checkTokens = { Object id -> nativeState.eval['0'] = ['1']; '' }
        when:
        def result = script._nmSetExpression(42L, '0', ['2'])
        then:
        !result.success
        result.originalPreserved
        nativeState.eval['0'] == ['1']
    }

    def 'null native token validation is failure even if tokens were persisted'() {
        given:
        nativeRule.checkTokens = { Object id -> null }
        when:
        def result = script._nmSetExpression(42L, '0', ['2'])
        then:
        !result.success
        result.originalPreserved
        nativeState.eval['0'] == ['1']
    }

    def 'invalid native local types values and names refuse without creating locals'() {
        when:
        script._nmLocal(42L, 'create', name, value, type)
        then:
        thrown(IllegalArgumentException)
        locals.empty
        where:
        name    | value | type
        'value' | null  | 'string'
        'value' | '1'   | 'integer'
        'value' | 1.5G  | 'integer'
        'value' | 1     | 'boolean'
        'value' | true  | 'Boolean'
        ' bad'  | ''    | 'string'
        'bad%'  | ''    | 'string'
        null    | ''    | 'string'
    }

    def 'incomplete typed field rows and zero collection identities refuse before writes'() {
        when:
        script._nmUpsertCondition(42L, false, row)
        then:
        thrown(IllegalArgumentException)
        writes.empty
        settings.empty
        where:
        row << [[capability: 'Switch', fields: [[name: 'state_#', type: 'enum']]],
                [capability: 'Switch', fields: [[name: 'state_#', value: 'on']]],
                [id: '0', capability: 'Switch', fields: []]]
    }

    def 'local CRUD preserves correctly typed false zero empty decimal and datetime values'() {
        when:
        def created = script._nmLocal(42L, 'create', 'value', value, type)
        def read = script._nmLocal(42L, 'get', 'value')
        def listed = script._nmLocal(42L, 'list')
        def removed = script._nmLocal(42L, 'delete', 'value')
        then:
        created.success && read.success && listed.success && removed.success
        read.variable.value == value
        listed.localVariables.value.type == type
        removed.deleted
        locals.empty
        where:
        value | type
        false | 'boolean'
        0     | 'integer'
        ''    | 'string'
        1.25G | 'bigdecimal'
        0L    | 'datetime'
    }

    def 'swallowed local write cannot report success and duplicate create cannot overwrite'() {
        given:
        locals.value = [name: 'value', type: 'integer', value: 7]
        nativeRule.setLocalVar = { String name, Object value -> }
        when:
        def result = script._nmLocal(42L, 'set', 'value', 9)
        then:
        !result.success
        locals.value.value == 7
        when:
        script._nmLocal(42L, 'create', 'value', 9, 'integer')
        then:
        thrown(IllegalArgumentException)
        locals.value.value == 7
    }

    def 'unavailable local inventory cannot report success or permit mutation'() {
        given:
        nativeRule.getAllLocalVars = { -> null }
        when:
        def listed = script._nmLocal(42L, 'list')
        then:
        !listed.success
        when:
        script._nmLocal(42L, 'create', 'value', 1, 'integer')
        then:
        thrown(IllegalStateException)
        locals.empty
    }

    def 'condition compile uses native selector and placeholder fields preserves unrelated state'() {
        when:
        def result = script._nmUpsertCondition(42L, false, [capability: 'Switch', fields: [[name: 'state_#', type: 'enum', value: 'on']]])
        then:
        result.success
        result.id == '4'
        settings.rCapab_4.value == 'Switch'
        settings.state_4.value == 'on'
        nativeState.keep == [unknown: true]
        nativeState.ndx == 4
    }

    def 'blank native compiled label is failure despite persisted settings'() {
        given:
        nativeRule.selectTriggers = { -> nativeState.capabstrue = ['4': ''] }
        when:
        def result = script._nmUpsertCondition(42L, true, [capability: 'Switch', fields: [[name: 'tstate#', type: 'enum', value: 'on']]])
        then:
        !result.success
        result.partial
        result.settingsVerified
        settings.tCapab4.value == 'Switch'
        !settings.containsKey('tCapab_4')
    }

    def 'terminator must actually enter native action list with correct method'() {
        given:
        nativeRule.addAction = { String subtype -> nativeState.actions = ['1': [method: 'getEndIf', label: 'END-IF']]; nativeState.actionList = ['1'] }
        when:
        def result = script._nmAddTerminator(42L, 'endIf')
        then:
        result.success
        when:
        nativeRule.addAction = { String subtype -> }
        nativeState.actions = [:]
        nativeState.actionList = []
        def ignored = script._nmAddTerminator(42L, 'endIf')
        then:
        !ignored.success
    }

    def 'new action commits through native context and verifies persisted inputs and compiled method'() {
        given:
        List contexts = []
        nativeRule.doActPage = { -> contexts << nativeState.doActN }
        nativeRule.appButtonHandler = { String name ->
            if (name == 'actionDone') {
                nativeState.actions = ['1': [method: 'getLogMsg', indent: '']]
                nativeState.actionList = ['1']
            }
        }
        when:
        def result = script._nmUpsertAction(42L, [type: 'messageActs', subtype: 'getLogMsg', fields: [[name: 'logmsg.#', type: 'text', value: 'hello']]])
        then:
        result.success
        result.settingsVerified
        contexts == ['N', 'N']
        settings['logmsg.1'].value == 'hello'
        nativeState.keep == [unknown: true]
    }

    def 'action editing reuses native address and swallowed commit cannot pass'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg', label: 'old', cond: null]]
        nativeState.actionList = ['1']
        nativeRule.doActPage = { -> }
        nativeRule.appButtonHandler = { String name -> nativeState.actions = [:] }
        when:
        def result = script._nmUpsertAction(42L, [id: '1', type: 'messageActs', subtype: 'getLogMsg', fields: [[name: 'logmsg.#', type: 'text', value: 'new']]])
        then:
        !result.success
        result.partial
        nativeState.doActN == '1.0.false'
        nativeState.actNdx == 1
    }
}
