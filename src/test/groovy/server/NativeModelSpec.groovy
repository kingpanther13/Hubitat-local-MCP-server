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
        nativeRule.getAllLocalVars = { -> locals.collectEntries { localName, variable -> [(localName): [type: variable.type, value: variable.value]] } }
        nativeRule.createLocalVar = { String name, Object value, String type -> locals.put(name, [name: name, value: value, type: type]) }
        nativeRule.setLocalVar = { String name, Object value -> locals.get(name).value = value }
        nativeRule.deleteLocalVar = { String name -> locals.remove(name) }
        nativeRule.selectConditions = { Map params ->
            assert params == [pred: false]
            if (nativeState.hasAll == true) nativeState.capabsfalse.put('4', 'Switch A on')
            authorForm([[name: 'state_4', type: 'enum', multiple: false, options: ['on', 'off']], [name: 'rCapab_5', type: 'enum', options: ['Switch']]])
        }
        nativeRule.selectTriggers = { ->
            if (nativeState.hasAll == true) nativeState.capabstrue = ['4': 'Switch A on']
            authorForm([[name: 'tstate4', type: 'enum', multiple: false, options: ['on', 'off']]])
        }
        script.metaClass._nrHandle = { Long id -> nativeRule }
        script.metaClass._nrState = { Long id -> [:] + nativeState }
        script.metaClass._nrStatePatch = { Long id, Map values, List remove = [] -> remove.each { nativeState.remove(it) }; nativeState.putAll(values); writes << values; [:] }
        script.metaClass._nrWriteSettings = { Long id, List rows ->
            rows.each { settings.put(it.name, [type: it.type, value: it.value]) }
            writes << rows
            [success: true]
        }
        script.metaClass._nrSettings = { Long id, List names -> settings.subMap(names) }
        script.metaClass.hubInternalGet = { String path -> [settings: settings.collect { name, row ->
            [name: name, type: row.type, value: row.value, multiple: row.multiple, deviceList: row.deviceList]
        }] }
    }

    private Map nativeForm(List descriptors) {
        List inputs = descriptors.collect { [element: 'input'] + it }
        [sections: [[input: inputs]], body: [[element: 'section', children: inputs]]]
    }

    private Map authorForm(List descriptors) {
        List selectors = [
            [name: 'rCapab_4', type: 'enum', multiple: false, options: ['Switch'], submitOnChange: true],
            [name: 'tCapab4', type: 'enum', multiple: false, options: ['Switch'], submitOnChange: true],
            [name: 'actType.1', type: 'enum', multiple: false, options: ['messageActs', 'delayActs', 'modeActs'], submitOnChange: true],
            [name: 'actSubType.1', type: 'enum', multiple: false, options: ['getLogMsg', 'getWaitEvents', 'getSetVariable'], submitOnChange: true]
        ]
        if (nativeState.waitNdx instanceof Map) nativeState.waitNdx.values().flatten().unique().each { eventId ->
            selectors << [name: "tCapab-${eventId}".toString(), type: 'enum', multiple: false, options: ['Switch'], submitOnChange: true]
        }
        nativeForm(selectors + descriptors)
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

    def 'required expression binds reused condition IDs and finalizes before returning to actions'() {
        given:
        List pages = []
        nativeRule.STPage = { Map params ->
            pages << 'STPage'
            if (nativeState.doneST == true) nativeState.hasPredicate = true
        }
        nativeRule.mainPage = { -> pages << 'mainPage' }
        nativeRule.selectActions = { Map params -> pages << 'selectActions' }
        when:
        def result = script._nmSetRequired(42L, ['1', 'AND', '2', 'OR', '1'])
        then:
        result.success
        result.predicateVerified
        nativeState.predCapabs == [1, 2]
        nativeState.firstR == ['0': false]
        nativeState.eval['0'] == ['1', 'AND', '2', 'OR', '1']
        pages == ['STPage', 'STPage', 'STPage', 'mainPage', 'selectActions']
        nativeState.keep == [unknown: true]
    }

    def 'required expression does not call a swallowed native finalize success'() {
        given:
        nativeRule.STPage = { Map params -> }
        nativeRule.mainPage = { -> }
        nativeRule.selectActions = { Map params -> }
        when:
        def result = script._nmSetRequired(42L, ['1'])
        then:
        !result.success
        result.partial
        !result.predicateVerified
    }

    def 'invalid required expression refuses before enabling predicate settings'() {
        when:
        script._nmSetRequired(42L, ['1', 'AND'])
        then:
        thrown(IllegalArgumentException)
        settings.isEmpty()
        writes.empty
    }

    def 'invalid native local types values and names refuse without creating locals'() {
        when:
        script._nmLocal(42L, 'create', name, value, type)
        then:
        thrown(IllegalArgumentException)
        locals.isEmpty()
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
        settings.isEmpty()
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
        locals.isEmpty()
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
        locals.isEmpty()
    }

    def 'local verification compares projected inventory fields and rejects a conflicting value'() {
        given:
        nativeRule.getAllLocalVars = { -> locals.collectEntries { localName, variable -> [(localName): [type: variable.type, value: variable.value + 1]] } }
        when:
        def result = script._nmLocal(42L, 'create', 'Counter', 1, 'integer')
        then:
        !result.success
        result.variable == [name: 'Counter', type: 'integer', value: 1]
        locals.Counter.value == 1
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
        nativeRule.selectTriggers = { -> nativeState.capabstrue = ['4': '']; authorForm([[name: 'tstate4', type: 'enum', multiple: false, options: ['on', 'off']]]) }
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
        nativeRule.doActPage = { -> contexts << nativeState.doActN; authorForm([[name: 'logmsg.1', type: 'text', multiple: false]]) }
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
        contexts == ['N', 'N', 'N', 'N', 'N']
        settings['logmsg.1'].value == 'hello'
        nativeState.keep == [unknown: true]
    }

    def 'action editing reuses native address and swallowed commit cannot pass'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg', label: 'old', cond: null]]
        nativeState.actionList = ['1']
        nativeRule.doActPage = { -> authorForm([[name: 'logmsg.1', type: 'text', multiple: false]]) }
        nativeRule.appButtonHandler = { String name -> nativeState.actions = [:] }
        when:
        def result = script._nmUpsertAction(42L, [id: '1', type: 'messageActs', subtype: 'getLogMsg', fields: [[name: 'logmsg.#', type: 'text', value: 'new']]])
        then:
        !result.success
        result.partial
        nativeState.doActN == '1.0.false'
        nativeState.actNdx == 1
    }

    private void configureWaitNative(boolean derive) {
        nativeRule.doActPage = { ->
            if (!(nativeState.waitNdx instanceof Map)) nativeState.waitNdx = ['.1': [7]]
            if (!(nativeState.waitDone instanceof Map)) nativeState.waitDone = ['.1': [:]]
            if (nativeState.anotherWait == true) {
                def ids = nativeState.waitNdx.get('.1')
                ids.add(ids.last() + 1)
                nativeState.remove('anotherWait')
            }
            if (derive) {
                nativeState.waitNdx.get('.1').each { eventId ->
                    def hours = settings.get("SHours-${eventId}".toString())?.value ?: 0
                    def minutes = settings.get("SMins-${eventId}".toString())?.value ?: 0
                    def seconds = settings.get("SSecs-${eventId}".toString())?.value ?: 0
                    nativeState.put("SHours-${eventId}".toString(), hours)
                    nativeState.put("SMins-${eventId}".toString(), minutes)
                    nativeState.put("SSecs-${eventId}".toString(), seconds)
                    nativeState.put("hhmmss-${eventId}".toString(), "${hours}:${minutes}:${seconds}".toString())
                }
            }
            List descriptors = []
            nativeState.waitNdx.get('.1').each { eventId ->
                descriptors << [name: "tstate-${eventId}".toString(), type: 'enum', multiple: false, options: ['on', 'off']]
                descriptors << [name: "stays-${eventId}".toString(), type: 'bool', multiple: false]
                ['SHours', 'SMins', 'SSecs'].each { component -> descriptors << [name: "${component}-${eventId}".toString(), type: 'number', multiple: false] }
            }
            authorForm(descriptors)
        }
        nativeRule.appButtonHandler = { String name ->
            if (name == 'hasAll') nativeState.waitDone.get('.1').put(nativeState.waitNdx.get('.1').last().toString(), true)
            if (name == 'actionDone') {
                nativeState.actions = ['1': [method: 'getWaitEvents', indent: '', wait: new ArrayList(nativeState.waitNdx.get('.1'))]]
                if (!(nativeState.actionList instanceof List)) nativeState.actionList = ['1']
            }
        }
    }

    def 'wait events allocate offset native IDs and render duration before committing all events'() {
        given:
        configureWaitNative(true)
        when:
        def result = script._nmUpsertAction(42L, [type: 'delayActs', subtype: 'getWaitEvents', fields: [], events: [
                [capability: 'Switch', fields: [[name: 'tstate#', type: 'enum', value: 'on'], [name: 'stays#', type: 'bool', value: true], [name: 'SMins#', type: 'number', value: 1], [name: 'SSecs#', type: 'number', value: 5]]],
                [capability: 'Switch', fields: [[name: 'tstate#', type: 'enum', value: 'off']]]]])
        then:
        result.success
        result.waitsVerified
        nativeState.actions.get('1').wait == [7, 8]
        nativeState.get('SSecs-7') == 5
        nativeState.get('SMins-7') == 1
        settings.get('tCapab-7').value == 'Switch'
        settings.get('tstate-8').value == 'off'
        nativeState.keep == [unknown: true]
    }

    def 'persisted wait settings without native derived duration cannot report success'() {
        given:
        configureWaitNative(false)
        nativeState.put('SSecs-7', 0)
        when:
        def result = script._nmUpsertAction(42L, [type: 'delayActs', subtype: 'getWaitEvents', fields: [], events: [
                [capability: 'Switch', fields: [[name: 'stays#', type: 'bool', value: true], [name: 'SSecs#', type: 'number', value: 5]]]]])
        then:
        !result.success
        result.partial
        result.waitEvents.error.contains('duration')
        settings.get('SSecs-7').value == 5
        !nativeState.actions
    }

    def 'same-count wait edits preserve native identities and recompute derived duration'() {
        given:
        configureWaitNative(true)
        nativeState.actions = ['1': [method: 'getWaitEvents', indent: '', wait: [7]]]
        nativeState.actionList = ['1']
        nativeState.waitNdx = ['.1': [7]]
        nativeState.waitDone = ['.1': ['7': true]]
        when:
        def result = script._nmUpsertAction(42L, [id: '1', type: 'delayActs', subtype: 'getWaitEvents', fields: [], events: [
                [capability: 'Switch', fields: [[name: 'stays#', type: 'bool', value: true], [name: 'SSecs#', type: 'number', value: 9]]]]])
        then:
        result.success
        nativeState.doActN == '1.0.false'
        nativeState.actionList == ['1']
        nativeState.actions.get('1').wait == [7]
        nativeState.get('SSecs-7') == 9
    }

    def 'invalid later wait events refuse before any earlier action or event write'() {
        when:
        script._nmUpsertAction(42L, [type: 'delayActs', subtype: 'getWaitEvents', fields: [], events: [
                [capability: 'Switch', fields: [[name: 'tstate#', type: 'enum', value: 'on']]],
                [capability: 'Switch', fields: [[name: 'tDev#', type: 'capability.switch', value: [0]]]]]])
        then:
        thrown(IllegalArgumentException)
        writes.empty
        settings.isEmpty()
    }

    def 'changing wait event count is refused before writing existing action'() {
        given:
        nativeState.actions = ['1': [method: 'getWaitEvents', wait: [7, 8]]]
        when:
        script._nmUpsertAction(42L, [id: '1', type: 'delayActs', subtype: 'getWaitEvents', fields: [], events: [[capability: 'Switch', fields: []]]])
        then:
        thrown(IllegalArgumentException)
        writes.empty
        settings.isEmpty()
    }

    def 'action deletion verifies arbitrary action settings removed and surviving order retained'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg'], '2': [method: 'getLogMsg']]
        nativeState.actionList = ['2', '1']
        settings.put('actSubType.1', [type: 'enum', value: 'getLogMsg'])
        settings.put('unknownActionField.1', [type: 'text', value: 'owned'])
        settings.put('unknownOtherField', [type: 'text', value: 'retain'])
        nativeRule.selectActions = { Map params ->
            assert nativeState.delAct == '1'
            nativeState.actions.remove(nativeState.delAct)
            nativeState.actionList.remove(nativeState.delAct)
            settings.keySet().findAll { it.endsWith('.1') }.each { settings.remove(it) }
            nativeState.remove('delAct')
        }
        when:
        def result = script._nmDeleteRow(42L, 'action', '1')
        then:
        result.success && result.deleted && result.settingsVerified
        nativeState.actionList == ['2']
        nativeState.actions.keySet() == ['2'] as Set
        settings.keySet() == ['unknownOtherField'] as Set
        nativeState.keep == [unknown: true]
    }

    def 'native deletion leaving settings behind reports partial rather than clean success'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg']]
        nativeState.actionList = ['1']
        settings.put('unknownActionField.1', [type: 'text', value: 'stale'])
        nativeRule.selectActions = { Map params -> nativeState.actions.remove(nativeState.delAct); nativeState.actionList.remove(nativeState.delAct); nativeState.remove('delAct') }
        when:
        def result = script._nmDeleteRow(42L, 'action', '1')
        then:
        !result.success && result.partial && result.deleted
        !result.settingsVerified
    }

    def 'unreferenced condition deletes with native condition selector and settings cleanup'() {
        given:
        settings.put('rCapab_2', [type: 'enum', value: 'Switch'])
        settings.put('state_2', [type: 'enum', value: 'off'])
        nativeRule.selectConditions = { Map params ->
            assert params == [pred: false] && nativeState.deleteCon == 2
            nativeState.capabsfalse.remove('2')
            settings.remove('rCapab_2')
            settings.remove('state_2')
            nativeState.remove('deleteCon')
        }
        when:
        def result = script._nmDeleteRow(42L, 'condition', '2')
        then:
        result.success
        nativeState.capabsfalse == ['1': 'Switch A on']
        nativeState.eval == ['0': ['1']]
    }

    def 'trigger deletion uses native delete callback and verifies setting cleanup'() {
        given:
        nativeState.capabstrue = ['2': 'Switch A off']
        settings.put('tCapab2', [type: 'enum', value: 'Switch'])
        settings.put('tstate2', [type: 'enum', value: 'off'])
        nativeRule.selectTriggers = { ->
            assert nativeState.deleteCon == 2
            nativeState.capabstrue.remove('2')
            settings.remove('tCapab2')
            settings.remove('tstate2')
            nativeState.remove('deleteCon')
        }
        when:
        def result = script._nmDeleteRow(42L, 'trigger', '2')
        then:
        result.success && result.deleted && result.settingsVerified
        nativeState.capabstrue.isEmpty()
        !nativeState.containsKey('deleteCon')
    }

    def 'condition deletion cannot succeed over removed settings while native condition remains'() {
        given:
        settings.put('rCapab_2', [type: 'enum', value: 'Switch'])
        nativeRule.selectConditions = { Map params -> settings.remove('rCapab_2'); nativeState.remove('deleteCon') }
        when:
        def result = script._nmDeleteRow(42L, 'condition', '2')
        then:
        !result.success && result.partial && !result.deleted
        result.settingsVerified
        nativeState.capabsfalse.containsKey('2')
    }

    def 'referenced conditions and missing rows refuse before any native write'() {
        given:
        nativeRule.deleteCapab = { Object deletedId, Object trigger, Object action -> writes << 'unexpected' }
        when:
        script._nmDeleteRow(42L, part, id)
        then:
        thrown(IllegalArgumentException)
        writes.empty
        where:
        part        | id
        'condition' | '1'
        'condition' | '99'
        'action'    | '99'
        'trigger'   | '0'
    }

    def 'action order requires exact permutation before state writes'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg'], '2': [method: 'getLogMsg']]
        nativeState.actionList = ['1', '2']
        when:
        script._nmOrderActions(42L, order)
        then:
        thrown(IllegalArgumentException)
        writes.empty
        nativeState.actionList == ['1', '2']
        where:
        order << [['1'], ['1', '1'], ['1', '99'], ['1', '2', '3'], ['0', '2']]
    }

    def 'action order changes native runtime sequence without dropping action contracts'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg', indent: ''], '2': [method: 'getLogMsg', indent: '']]
        nativeState.actionList = ['1', '2']
        nativeRule.selectActions = { Map params -> }
        nativeRule.markIfs = { -> nativeState.actions.get('1').quick = true }
        when:
        def result = script._nmOrderActions(42L, ['2', '1'])
        then:
        result.success && result.orderVerified && result.actionsPreserved
        nativeState.actionList == ['2', '1']
        nativeState.actions.keySet() == ['1', '2'] as Set
        nativeState.keep == [unknown: true]
    }

    def 'native structural flags reject unfinished IF and repeat without relying on broken flag'() {
        given:
        nativeRule.selectActions = { Map params -> nativeState.inIf = inIf; nativeState.inRepeat = inRepeat }
        when:
        def result = script._nmValidateStructure(42L)
        then:
        result.success == healthy
        result.structuralIssues.size() == count
        where:
        inIf | inRepeat | healthy | count
        true | false    | false   | 1
        false| true     | false   | 1
        true | true     | false   | 2
        false| false    | true    | 0
    }

    def 'native input validation uses enum option keys and deduplicates section and body descriptors'() {
        given:
        def page = nativeForm([[name: 'state_4', type: 'enum', multiple: false, options: options]])
        when:
        def valid = script._nmValidateInputs(page, [[name: 'state_4', type: 'enum', value: 'on']])
        def invalid = script._nmValidateInputs(page, [[name: 'state_4', type: 'enum', value: 'INVALID']])
        then:
        valid.success
        valid.verifiedFields == ['state_4']
        !invalid.success
        invalid.fieldErrors.size() == 1
        invalid.error.contains('state_4')
        where:
        options << [['on', 'off'], [on: 'On', off: 'Off'], [[on: 'On'], [off: 'Off']]]
    }

    def 'native input validation rejects type value cardinality and unsupported enum descriptors'() {
        when:
        def result = script._nmValidateInputs(nativeForm([descriptor]), [field])
        then:
        !result.success
        result.error.contains(field.name)
        result.fieldErrors.size() == 1
        where:
        descriptor                                                                 | field
        [name: 'device', type: 'capability.switch', multiple: false]                 | [name: 'device', type: 'capability.switch', value: [1]]
        [name: 'device', type: 'capability.switch', multiple: true]                  | [name: 'device', type: 'capability.switch', value: 1]
        [name: 'amount', type: 'decimal', multiple: false]                          | [name: 'amount', type: 'number', value: 1]
        [name: 'amount', type: 'decimal', multiple: false]                          | [name: 'amount', type: 'decimal', value: 'not a number']
        [name: 'enabled', type: 'bool', multiple: false]                            | [name: 'enabled', type: 'bool', value: 'true']
        [name: 'choice', type: 'enum', multiple: false]                             | [name: 'choice', type: 'enum', value: 'on']
        [name: 'choice', type: 'enum', multiple: true, required: true, options: []]  | [name: 'choice', type: 'enum', value: []]
        [name: 'choice', type: 'enum', options: ['on']]                             | [name: 'choice', type: 'enum', value: ['on']]
    }

    def 'native input validation ignores option metadata and unmarked name type maps'() {
        given:
        def page = [sections: [[input: [[name: 'unmarked', type: 'text']]]], options: [[element: 'input', name: 'hidden', type: 'text']]]
        when:
        def result = script._nmValidateInputs(page, [[name: 'unmarked', type: 'text', value: 'x'], [name: 'hidden', type: 'text', value: 'x']])
        then:
        !result.success
        result.fieldErrors*.field == ['unmarked', 'hidden']
        result.error.contains('not exposed')
    }

    def 'single device scalar matches native cardinality without category mappings'() {
        expect:
        script._nmValidateInputs(nativeForm([[name: 'valDev.3', type: 'capability.temperatureMeasurement', multiple: false]]),
                [[name: 'valDev.3', type: 'capability.temperatureMeasurement', value: 9]]).success
    }

    def 'invalid condition enum never persists and stops before native hasAll commit'() {
        given:
        List renders = []
        nativeRule.selectConditions = { Map params ->
            renders << nativeState.hasAll
            if (nativeState.hasAll == true) nativeState.capabsfalse.put('4', 'must not commit')
            authorForm([[name: 'state_4', type: 'enum', multiple: false, options: ['on', 'off']]])
        }
        when:
        def result = script._nmUpsertCondition(42L, false, [capability: 'Switch', fields: [[name: 'state_#', type: 'enum', value: 'INVALID']]])
        then:
        !result.success && result.partial
        result.error.contains('state_4')
        result.fieldErrors*.field == ['state_4']
        renders == [false, false]
        nativeState.capabsfalse.keySet() == ['1', '2'] as Set
        !settings.containsKey('state_4')
    }

    def 'invalid or unrevealed action input stops before actionDone with explicit native field error'() {
        given:
        List buttons = []
        nativeRule.doActPage = { -> authorForm([[name: 'numOp.1', type: 'enum', multiple: false, options: ['number', 'add number', 'variable']]]) }
        nativeRule.appButtonHandler = { String button -> buttons << button }
        when:
        def result = script._nmUpsertAction(42L, [type: 'modeActs', subtype: 'getSetVariable', fields: [[name: fieldTemplate, type: 'enum', value: 'INVALID']]])
        then:
        !result.success && result.partial
        result.error.contains(fieldTemplate.replace('#', '1'))
        !settings.containsKey(fieldTemplate.replace('#', '1'))
        buttons.empty
        !nativeState.actions
        !nativeState.actionList
        where:
        fieldTemplate << ['numOp.#', 'unrevealed.#']
    }

    def 'invalid wait enum stops before native waitDone and action commit'() {
        given:
        configureWaitNative(true)
        when:
        def result = script._nmUpsertAction(42L, [type: 'delayActs', subtype: 'getWaitEvents', fields: [], events: [
                [capability: 'Switch', fields: [[name: 'tstate#', type: 'enum', value: 'INVALID']]]]])
        then:
        !result.success && result.partial
        result.error.contains('tstate-7')
        result.fieldErrors*.field == ['tstate-7']
        !settings.containsKey('tstate-7')
        nativeState.waitDone.get('.1').isEmpty()
        !nativeState.actions
    }
    def 'dependent enum is validated against freshly rendered choices before its value can persist'() {
        given:
        int renders = 0
        nativeRule.doActPage = { ->
            renders++
            authorForm([
                [name: 'numOp.1', type: 'enum', multiple: false, options: ['number', 'variable'], submitOnChange: true],
                [name: 'xVar3.1', type: 'enum', multiple: false,
                    options: settings.get('numOp.1')?.value == 'variable' ? ['Counter'] : ['staleChoice']]
            ])
        }
        List buttons = []
        nativeRule.appButtonHandler = { String button -> buttons << button }
        when:
        def result = script._nmUpsertAction(42L, [type: 'modeActs', subtype: 'getSetVariable', fields: [
            [name: 'xVar3.#', type: 'enum', value: 'staleChoice'],
            [name: 'numOp.#', type: 'enum', value: 'variable']]])
        then:
        !result.success && result.partial
        result.error.contains('xVar3.1')
        settings.get('numOp.1').value == 'variable'
        !settings.containsKey('xVar3.1')
        buttons.empty
        renders == 4
        writes.findAll { it instanceof List }.collectMany { it }.every { it.value != 'staleChoice' }
    }

    def 'dependent fields progress through selector renders and batch ordinary fields together'() {
        given:
        int renders = 0
        nativeRule.doActPage = { ->
            renders++
            List descriptors = []
            if (!settings.containsKey('actType.1')) descriptors << [name: 'actType.1', type: 'enum', multiple: false, options: ['messageActs'], submitOnChange: true]
            else if (!settings.containsKey('actSubType.1')) descriptors << [name: 'actSubType.1', type: 'enum', multiple: false, options: ['getLogMsg'], submitOnChange: true]
            else descriptors.addAll([[name: 'logmsg.1', type: 'text', multiple: false], [name: 'description.1', type: 'text', multiple: false]])
            nativeForm(descriptors)
        }
        nativeRule.appButtonHandler = { String button ->
            if (button == 'actionDone') { nativeState.actions = ['1': [method: 'getLogMsg']]; nativeState.actionList = ['1'] }
        }
        when:
        def result = script._nmUpsertAction(42L, [type: 'messageActs', subtype: 'getLogMsg', fields: [
            [name: 'description.#', type: 'text', value: 'second'], [name: 'logmsg.#', type: 'text', value: 'first']]])
        then:
        result.success
        writes.findAll { it instanceof List }*.size() == [1, 1, 2]
        writes.findAll { it instanceof List }.last()*.name == ['logmsg.1', 'description.1']
        renders == 5
    }

    def 'hidden unchanged selectors are preserved but changed hidden selectors stop without settings writes'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg']]
        nativeState.actionList = ['1']
        settings.put('actType.1', [type: 'enum', value: 'messageActs'])
        settings.put('actSubType.1', [type: 'enum', value: 'getLogMsg'])
        nativeRule.doActPage = { -> nativeForm([[name: 'logmsg.1', type: 'text', multiple: false]]) }
        List buttons = []
        nativeRule.appButtonHandler = { String button -> buttons << button }
        when:
        def result = script._nmUpsertAction(42L, [id: '1', type: 'modeActs', subtype: 'getLogMsg', fields: [[name: 'logmsg.#', type: 'text', value: 'must not write']]])
        then:
        !result.success && result.partial
        result.error.contains('actType.1')
        !writes.any { it instanceof List }
        !settings.containsKey('logmsg.1')
        settings.get('actType.1').value == 'messageActs'
        buttons.empty
    }

    def 'invalid exposed capability selector never reaches settings or native completion'() {
        given:
        List renders = []
        nativeRule.selectConditions = { Map params ->
            renders << nativeState.hasAll
            nativeForm([[name: 'rCapab_4', type: 'enum', multiple: false, options: ['Switch'], submitOnChange: true]])
        }
        when:
        def result = script._nmUpsertCondition(42L, false, [capability: 'INVALID', fields: []])
        then:
        !result.success && result.partial
        result.fieldErrors*.field == ['rCapab_4']
        !settings.containsKey('rCapab_4')
        !writes.any { it instanceof List }
        renders == [false]
        nativeState.capabsfalse.keySet() == ['1', '2'] as Set
    }

}
