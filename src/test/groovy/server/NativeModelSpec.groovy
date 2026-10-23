package server

import support.ToolSpecBase
import support.TestLocation
import spock.lang.Shared

class NativeModelSpec extends ToolSpecBase {
    @Shared TestLocation sharedLocation = new TestLocation()
    Map nativeState
    Map locals
    Map settings
    List writes
    Expando nativeRule
    String nativeType

    void setupSpec() {
        appExecutor.getLocation() >> sharedLocation
        appExecutor.toDateTime(_ as String) >> { String value ->
            Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", value)
        }
    }

    void setup() {
        sharedLocation.timeZone = TimeZone.getTimeZone('UTC')
        nativeType = 'Rule-5.1'
        nativeState = [keep: [unknown: true], ndx: 4, actNdx: 1,
                       capabsfalse: ['1': 'Switch A on', '2': 'Switch B off'], eval: ['0': ['1']]]
        locals = [:]
        settings = [:]
        writes = []
        nativeRule = new Expando()
        nativeRule.checkTokens = { Object id -> '' }
        nativeRule.thisActionLabel = { Object actionId -> 'Native action ' + actionId }
        nativeRule.actionLabelText = { -> '' }
        nativeRule.broken = { -> nativeState.broken = false; false }
        nativeRule.selectActions = { Map params -> nativeState.broken = false; nativeForm([]) }
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
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: nativeType]] }
        script.metaClass._nrState = { Long id -> [:] + nativeState }
        script.metaClass._nrStatePatch = { Long id, Map values, List remove = [] -> remove.each { nativeState.remove(it) }; nativeState.putAll(values); writes << values; [:] }
        script.metaClass._nrWriteSettings = { Long id, List rows ->
            rows.each { settings.put(it.name, [type: it.type, value: it.value]) }
            writes << rows
            [success: true]
        }
        script.metaClass._nrSettings = { Long id, List names -> settings.subMap(names) }
        script.metaClass.hubInternalGet = { String path, Map query = null ->
            if (path == '/device/listJson') return query?.capability == 'capability.temperatureMeasurement' ? [[id: 9]] : (query?.capability == 'capability.switch' ? [[id: 7], [id: 8]] : [])
            if (path.startsWith('/app/ruleBuilderJson/')) return [broken: nativeState.broken == true]
            [settings: settings.collect { name, row ->
                [name: name, type: row.type, value: row.value, multiple: row.multiple, deviceList: row.deviceList]
            }]
        }
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

    def 'Button Rule required expression returns through its primary and action editor pages'() {
        given:
        nativeType = 'Button Rule-5.1'
        List pages = []
        nativeRule.STPage = { Map params -> if (nativeState.doneST == true) nativeState.hasPredicate = true }
        nativeRule.mainPage = { -> throw new AssertionError('Button Rule has no mainPage') }
        nativeRule.selectActions = { -> pages << 'selectActions' }
        nativeRule.selectActionsX = { -> pages << 'selectActionsX' }
        when:
        def result = script._nmSetRequired(42L, ['1'])
        then:
        result.success
        pages == ['selectActions', 'selectActionsX']
        nativeState.keep == [unknown: true]
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
        'value' | -1L   | 'datetime'
        'value' | '2026-02-30T12:30:00.000-0500' | 'datetime'
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
        !created.partial && !removed.partial
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
        '2026-01-01T12:30:00.000-0500' | 'datetime'
        '2026-01-01T99:99:99.999-9999' | 'datetime'
        '9999-99-99T12:30:00.000-0500' | 'datetime'
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

    def 'failed local write readback reports partial after create set or delete mutates native storage'() {
        given:
        if (operation != 'create') locals.Counter = [name: 'Counter', type: 'integer', value: 7]
        boolean mutated = false
        nativeRule.createLocalVar = { String name, Object value, String type ->
            locals[name] = [name: name, type: type, value: value]; mutated = true
        }
        nativeRule.setLocalVar = { String name, Object value -> locals[name].value = value; mutated = true }
        nativeRule.deleteLocalVar = { String name -> locals.remove(name); mutated = true }
        nativeRule.getAllLocalVars = { ->
            if (mutated) return operation == 'delete' ? [Counter: [type: 'integer', value: 7]] : [Counter: [type: 'integer', value: 99]]
            locals.collectEntries { localName, variable -> [(localName): [type: variable.type, value: variable.value]] }
        }
        when:
        def result = script._nmLocal(42L, operation, 'Counter', 9, 'integer')
        then:
        mutated
        !result.success && result.partial && result.verificationNeeded
        !result.deleted
        if (operation == 'delete') assert !locals.containsKey('Counter')
        else assert locals.Counter.value == 9
        where:
        operation << ['create', 'set', 'delete']
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
        !result.rolledBack
        result.cleanupError
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

    def 'wait expression supplies timing before expression Done automatically commits and advances'() {
        given:
        nativeState.ruleNdx = 5
        boolean expressionDone = false
        int completionRenders = 0
        nativeRule.doActPage = { ->
            if (expressionDone && ++completionRenders == 2) {
                nativeState.actions = ['1': [method: 'getWaitRule', rule: 5, delay: "0:00:0${seconds}".toString()]]
                nativeState.actionList = ['1']
                nativeState.actNdx = 2
            }
            nativeForm([
                [name: 'actType.1', type: 'enum', options: ['delayActs'], submitOnChange: true],
                [name: 'actSubType.1', type: 'enum', options: ['getWaitRule'], submitOnChange: true]
            ] + (nativeState.eval.'5' && !expressionDone ? [
                [name: 'durChoice.1', type: 'bool', submitOnChange: true],
                [name: 'delayAct.1', type: 'enum', options: ['none', 'hrs:min:sec'], submitOnChange: true],
                [name: 'delaySec.1', type: 'decimal']
            ] : []))
        }
        nativeRule.appButtonHandler = { String name ->
            if (name == 'hasRule') {
                assert nativeState.eval.'5' == ['1']
                assert settings.'delaySec.1'.value == seconds
                expressionDone = true
                nativeState.actionDone = true
            }
            assert name != 'actionDone'
        }
        when:
        def result = script._nmUpsertAction(42L, [type: 'delayActs', subtype: 'getWaitRule', expression: ['1'], fields: [
            [name: 'durChoice.#', type: 'bool', value: duration],
            [name: 'delayAct.#', type: 'enum', value: 'hrs:min:sec'],
            [name: 'delaySec.#', type: 'decimal', value: seconds]
        ]])
        then:
        result.success
        result.expressionVerified
        nativeState.actions.'1'.rule == 5
        settings.'durChoice.1'.value == duration
        completionRenders == 2
        nativeState.actionDone == true
        where:
        duration | seconds
        false    | 4
        true     | 2
    }

    def 'wait expression edit retains its expression identity and native expression edit token'() {
        given:
        nativeState.ruleNdx = 99
        nativeState.actions = ['1': [method: 'getWaitRule', rule: 5]]
        nativeState.actionList = ['1']
        nativeState.eval.'5' = ['1']
        nativeRule.doActPage = { ->
            assert nativeState.doActN == '1.0.true'
            nativeForm([[name: 'actType.1', type: 'enum', options: ['delayActs']],
                        [name: 'actSubType.1', type: 'enum', options: ['getWaitRule']]])
        }
        nativeRule.appButtonHandler = { String name -> }
        when:
        def result = script._nmUpsertAction(42L, [id: '1', type: 'delayActs', subtype: 'getWaitRule', expression: ['2'], fields: []])
        then:
        result.success
        nativeState.eval.'5' == ['2']
        !nativeState.eval.containsKey('99')
        nativeState.actionList == ['1']
    }

    def 'wait completion rendering is bounded and a missing compiled row cannot pass'() {
        given:
        nativeState.ruleNdx = 5
        nativeState.actions = [:]
        nativeState.actionList = []
        int completionRenders = 0
        nativeRule.doActPage = { ->
            if (nativeState.actionCancel) nativeState.remove('actionCancel')
            else if (nativeState.actionDone) completionRenders++
            nativeForm([[name: 'actType.1', type: 'enum', options: ['delayActs']],
                        [name: 'actSubType.1', type: 'enum', options: ['getWaitRule']]])
        }
        nativeRule.appButtonHandler = { String name ->
            assert name in ['hasRule', 'actionCancel']
            if (name == 'hasRule') nativeState.actionDone = true
            else nativeState.actionCancel = true
        }
        when:
        def result = script._nmUpsertAction(42L, [type: 'delayActs', subtype: 'getWaitRule', expression: ['2'], fields: []])
        then:
        !result.success
        result.partial
        completionRenders == 2
        nativeState.actionList == []
    }

    def 'simple conditional action uses native condition selection and verifies compiled binding'() {
        given:
        if (editing) {
            nativeState.actions = ['1': [method: 'getLogMsg', cond: '1']]
            nativeState.actionList = ['1']
        }
        List buttons = []
        nativeRule.doActPage = { ->
            if (editing) nativeState.hasCondition = true
            List inputs = [
                [name: 'actType.1', type: 'enum', options: ['condActs', 'messageActs'], submitOnChange: true],
                [name: 'actSubType.1', type: 'enum', options: ['getCondAct', 'getLogMsg'], submitOnChange: true]
            ]
            if (settings.'actSubType.1'?.value == 'getCondAct') {
                inputs << [name: 'useCond.1', type: 'bool', submitOnChange: true]
                if (settings.'useCond.1'?.value == true) inputs << [name: 'useCondNdx.1', type: 'enum', options: ['1', '2'], submitOnChange: true]
            } else if (nativeState.hasCondition) inputs << [name: 'logmsg.1', type: 'text']
            nativeForm(inputs)
        }
        nativeRule.appButtonHandler = { String name ->
            buttons << name
            if (name == 'hasAll') nativeState.hasCondition = true
            if (name == 'actionDone') {
                nativeState.actions = ['1': [method: 'getLogMsg', cond: persist ? (editing ? nativeState.doActN.tokenize('.')[1] : settings.'useCondNdx.1'.value) : '1']]
                nativeState.actionList = ['1']
            }
        }
        when:
        def row = [type: 'messageActs', subtype: 'getLogMsg', condition: '2', fields: [[name: 'logmsg.#', type: 'text', value: 'conditional']]]
        if (editing) row.id = '1'
        def result = script._nmUpsertAction(42L, row)
        then:
        result.success == persist
        result.conditionVerified == persist
        buttons == (editing ? ['actionDone'] : ['hasAll', 'actionDone']) + (persist ? [] : ['actionCancel'])
        nativeState.actionList == ['1']
        editing || settings.'useCondNdx.1'.value == '2'
        !editing || nativeState.doActN == '1.2.false'
        nativeState.keep == [unknown: true]
        where:
        editing | persist
        false   | true
        true    | true
        true    | false
    }

    def 'unknown simple action condition refuses before native editor or settings mutation'() {
        when:
        script._nmUpsertAction(42L, [type: 'messageActs', subtype: 'getLogMsg', condition: '999', fields: []])
        then:
        thrown(IllegalArgumentException)
        writes.empty
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

    def 'deleting after a log edit accepts native derived quick on a surviving action'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg', indent: ''], '2': [method: 'getLogMsg']]
        nativeState.actionList = ['1', '2']
        nativeRule.selectActions = { Map params ->
            nativeState.actions.remove(nativeState.delAct)
            nativeState.actionList.remove(nativeState.delAct)
            nativeState.actions['1'].quick = true
            nativeState.remove('delAct')
        }
        when:
        def result = script._nmDeleteRow(42L, 'action', '2')
        then:
        result.success && result.rowsPreserved
        nativeState.actions == ['1': [method: 'getLogMsg', indent: '', quick: true]]
    }

    def 'native deletion still rejects any changed surviving action payload beyond top level quick'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg', cond: '1', rule: '7', wait: ['8'], delay: 5,
            modes: ['Home'], label: 'Original', indent: '', unknown: [nested: [quick: true, value: 'original']]],
            '2': [method: 'getLogMsg']]
        nativeState.actionList = ['1', '2']
        nativeRule.selectActions = { Map params ->
            nativeState.actions.remove(nativeState.delAct)
            nativeState.actionList.remove(nativeState.delAct)
            nativeState.actions['1'].quick = true
            if (field == 'unknown') nativeState.actions['1'].unknown.nested.quick = false
            else nativeState.actions['1'][field] = replacement
            nativeState.remove('delAct')
        }
        when:
        def result = script._nmDeleteRow(42L, 'action', '2')
        then:
        result.deleted && result.partial && !result.success && !result.rowsPreserved
        where:
        field     | replacement
        'method'  | 'getNotify'
        'cond'    | '9'
        'rule'    | '9'
        'wait'    | ['9']
        'delay'   | 10
        'modes'   | ['Away']
        'label'   | 'Changed'
        'indent'  | '  '
        'unknown' | null
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

    def 'predicate editing and rejection retain the correct native group after the last ordinary condition is deleted'() {
        given:
        nativeState.capabsfalse = ['1': 'Switch A on', '2': 'Switch B off', '3': 'Unused switch']
        nativeState.predCapabs = [1, 2]
        nativeState.isPredicate = false
        settings.put('rCapab_1', [type: 'enum', value: 'Switch'])
        settings.put('state_1', [type: 'enum', value: 'on'])
        nativeRule.removeSetting = { String name -> settings.remove(name) }
        nativeRule.appButtonHandler = { String name -> assert name == 'cancelCapab'; nativeState.cancelCapab = true }
        nativeRule.selectConditions = { Map params ->
            nativeState.isPredicate = params.pred
            if (nativeState.deleteCon != null) {
                assert params.pred == false
                nativeState.capabsfalse.remove(nativeState.deleteCon.toString())
                nativeState.remove('deleteCon')
                nativeState.capabDone = true
                return nativeForm([])
            }
            boolean hasConditions = nativeState.capabsfalse.keySet().any { key ->
                nativeState.predCapabs.contains(key.toInteger()) == params.pred
            }
            if (!hasConditions || nativeState.editCond != '1') return nativeForm([])
            assert params.pred == true
            if (nativeState.cancelCapab == true) {
                nativeState.remove('cancelCapab')
                nativeState.remove('editCond')
                nativeState.capabDone = true
                return nativeForm([])
            }
            // The native input renderer consumes the prior operation's Done flag.
            nativeState.remove('capabDone')
            if (nativeState.hasAll == true) {
                nativeState.capabsfalse['1'] = reject ? 'BROKEN' : compiledLabel
                // Native string membership checks can append a String beside an Integer ID.
                nativeState.predCapabs = nativeState.predCapabs + ['1']
                nativeState.capabDone = true
                nativeState.remove('editCond')
            }
            nativeForm([[name: 'rCapab_1', type: 'enum', options: ['Switch'], submitOnChange: true],
                [name: 'rDev_1', type: 'capability.switch', multiple: true],
                [name: 'state_1', type: 'enum', options: ['on', 'off']]])
        }
        List fields = [[name: 'rDev_#', type: 'capability.switch', value: ['7']],
            [name: 'state_#', type: 'enum', value: 'off']]
        when:
        def deleted = script._nmDeleteRow(42L, 'condition', '3')
        def edited = script._nmUpsertCondition(42L, false, [id: '1', capability: 'Switch', fields: fields])
        then:
        deleted.success
        edited.success == !reject
        !edited.partial
        nativeState.capabsfalse == ['1': reject ? 'Switch A on' : compiledLabel, '2': 'Switch B off']
        nativeState.predCapabs == [1, 2]
        settings.state_1.value == (reject ? 'on' : 'off')
        if (reject) {
            assert edited.rolledBack
            assert edited.compiledLabel == 'BROKEN'
            assert nativeState.isPredicate == false
            assert !settings.containsKey('rDev_1')
        } else assert settings.rDev_1.value == ['7']
        where:
        reject | compiledLabel
        false  | 'Switch A off'
        true   | 'Switch A off'
        // A device that has no current value yet: RM renders it as null inside its value span.
        false  | "Switch A(<span style='color:black'>null</span>) is off"
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

    def 'periodic trigger deletion lets native cleanup own schedule fields while verifying surviving configuration'() {
        given:
        nativeState.capabstrue = ['1': 'Periodic Schedule', '2': [label: 'Surviving trigger', nested: [value: 'retain']]]
        settings.tCapab1 = [type: 'enum', value: 'Periodic Schedule']
        settings.everyNMinutesC1 = [type: 'number', value: 5]
        settings.cronStr1 = [type: 'text', value: '0 */5 * ? * *']
        settings.unrelated = [type: 'text', value: 'retain']
        nativeRule.selectTriggers = { ->
            nativeState.capabstrue.remove('1')
            ['tCapab1', 'everyNMinutesC1', 'cronStr1'].each { settings.remove(it) }
            nativeState.remove('deleteCon')
            if (damage == 'addition') settings.unexpected = [type: 'text', value: 'new']
            if (damage == 'value') settings.unrelated.value = 'changed'
            if (damage == 'type') settings.unrelated.type = 'enum'
            if (damage == 'row') nativeState.capabstrue['2'].nested.value = 'corrupted'
            if (damage == 'reference') nativeState.eval['0'] = ['2']
        }
        when:
        def result = script._nmDeleteRow(42L, 'trigger', '1')
        then:
        result.deleted && result.callbackConsumed
        result.success == (damage == 'none')
        result.partial == (damage != 'none')
        result.removedSettings as Set == ['tCapab1', 'everyNMinutesC1', 'cronStr1'] as Set
        !result.containsKey('remainingSettings')
        result.remainingSettingCount == settings.size()
        if (damage == 'none') {
            assert result.settingsVerified && result.rowsPreserved && result.referencesPreserved
            assert settings == [unrelated: [type: 'text', value: 'retain']]
        }
        where:
        damage << ['none', 'addition', 'value', 'type', 'row', 'reference']
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

    def 'Button Rule action order refreshes its action editor without calling its primary page'() {
        given:
        nativeType = 'Button Rule-5.1'
        nativeState.actions = ['1': [method: 'getLogMsg', indent: ''], '2': [method: 'getLogMsg', indent: '']]
        nativeState.actionList = ['1', '2']
        List pages = []
        nativeRule.selectActions = { -> throw new AssertionError('Action edit must use selectActionsX') }
        nativeRule.selectActionsX = { -> pages << 'selectActionsX' }
        nativeRule.markIfs = { -> }
        when:
        def result = script._nmOrderActions(42L, ['2', '1'])
        then:
        result.success && result.orderVerified && result.actionsPreserved
        pages == ['selectActionsX']
        nativeState.actionList == ['2', '1']
        nativeState.keep == [unknown: true]
    }

    def 'an action order that fails structure verification restores the original order'() {
        given:
        nativeState.actions = ['1': [method: 'getRepeat', indent: ''], '2': [method: 'getEndRepeat', indent: '']]
        nativeState.actionList = ['1', '2']
        int marks = 0
        nativeRule.selectActions = { Map params -> }
        nativeRule.markIfs = { -> marks++; nativeState.inRepeat = nativeState.actionList.collect { it.toString() } != ['1', '2'] }
        when:
        def result = script._nmOrderActions(42L, ['2', '1'])
        then:
        !result.success
        result.rolledBack
        !result.partial
        result.structuralIssues == ['A repeat block remains open']
        marks == 2
        nativeState.actionList == ['1', '2']
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
        buttons == ['actionCancel']
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
        buttons == ['actionCancel']
        renders == 5
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
        !result.success && !result.partial && result.rolledBack
        result.error.contains('actType.1')
        !writes.any { it instanceof List }
        !settings.containsKey('logmsg.1')
        settings.get('actType.1').value == 'messageActs'
        buttons == ['actionCancel']
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

    def 'model numeric inputs use shared numeric and native range checks'() {
        when:
        def result = script._nmValidateInputs(nativeForm([[name: 'amount', type: type, multiple: false] + bounds]),
            [[name: 'amount', type: type, value: value]])
        then:
        result.success == accepted
        if (!accepted) assert result.error.contains('amount')
        where:
        type      | bounds                       | value | accepted
        'number'  | [:]                          | 1.5   | true
        'number'  | [range: '0..10']             | -1    | false
        'number'  | [range: '0..10']             | 11    | false
        'number'  | [range: '0..10']             | 10    | true
        'decimal' | [min: 1, max: 2]             | 1.5   | true
        'decimal' | [range: '*..2']              | 2.1   | false
        'number'  | [range: '0..10', min: -2]    | -1    | true
        'number'  | [:]                          | ''    | false
    }

    def 'model device values must belong to native capability choices and respect excluded devices'() {
        given:
        List queries = []
        script.metaClass.hubInternalGet = { String path, Map query ->
            queries << [path: path, query: query]
            [[id: 7, label: 'Allowed'], [id: 8, label: 'Excluded']]
        }
        when:
        def result = script._nmValidateInputs(nativeForm([[name: 'device', type: 'capability.switch', multiple: false, ignoreDevices: ['8']]]),
            [[name: 'device', type: 'capability.switch', value: deviceId]])
        then:
        result.success == accepted
        queries == [[path: '/device/listJson', query: [capability: 'capability.switch']]]
        if (!accepted) assert result.error.contains('device')
        where:
        deviceId | accepted
        7        | true
        8        | false
        999      | false
    }

    def 'rejected new trigger cancels its draft without removing the successful batch prefix'() {
        given:
        nativeState.capabstrue = ['1': 'Successful prefix']
        settings.tCapab1 = [type: 'enum', value: 'Switch']
        Map originalSettings = [:] + settings
        List removed = []
        nativeRule.removeSetting = { String name -> removed << name; settings.remove(name) }
        nativeRule.appButtonHandler = { String button ->
            assert button == 'cancelCapab'
            nativeState.cancelCapab = true
        }
        nativeRule.selectTriggers = { ->
            nativeState.runtimeEvents = (nativeState.runtimeEvents ?: 0) + 1
            if (nativeState.cancelCapab) {
                nativeState.remove('cancelCapab')
                nativeState.remove('moreCond')
                nativeState.capabsTrueUnused = 'native-owned'
                nativeState.capabstrue.remove('4')
                settings.remove('tCapab4')
                nativeState.ndx = 5
                nativeState.capabDone = true
                return nativeForm([])
            }
            nativeState.capabstrue.put('4', [:])
            if (settings.containsKey('tCapab4')) {
                settings.generated4 = [type: 'number', value: '1']
                if (renderThrows) throw new IllegalStateException('native render refused')
                return nativeForm([[name: 'time4', type: 'time', multiple: false]])
            }
            nativeForm([[name: 'tCapab4', type: 'enum', multiple: false,
                         options: ['Certain Time (and optional date)'], submitOnChange: true]])
        }
        when:
        def result = script._nmUpsertCondition(42L, true, [capability: 'Certain Time (and optional date)',
            fields: [[name: 'time#', type: 'enum', value: '17:30']]])
        then:
        !result.success && !result.partial && result.rolledBack
        result.error.contains(renderThrows ? 'native render refused' : 'time4')
        settings == originalSettings
        nativeState.capabstrue == ['1': 'Successful prefix']
        nativeState.ndx == 4
        !nativeState.containsKey('hasAll')
        !nativeState.containsKey('moreCond')
        !nativeState.containsKey('capabDone')
        nativeState.runtimeEvents == 3
        nativeState.capabsTrueUnused == 'native-owned'
        nativeState.keep == [unknown: true]
        removed.contains('generated4')
        where:
        renderThrows << [false, true]
    }

    def 'rejected existing trigger restores an earlier staged typed value and closes the editor'() {
        given:
        nativeState.capabstrue = ['2': 'Timer original', '3': 'Unrelated trigger']
        nativeState.time2 = 'old native derived time'
        settings.tCapab2 = [type: 'enum', value: 'Certain Time (and optional date)']
        settings.time2 = [type: 'time', value: '2026-10-20T17:30:00.000-0400']
        settings.unknown = [type: 'text', value: 'keep']
        Map originalSettings = [:] + settings
        nativeRule.removeSetting = { String name -> settings.remove(name) }
        nativeRule.appButtonHandler = { String button -> nativeState.cancelCapab = true }
        nativeRule.selectTriggers = { ->
            if (nativeState.cancelCapab) {
                nativeState.remove('cancelCapab')
                nativeState.remove('editCond')
                nativeState.capabDone = true
                return nativeForm([])
            }
            nativeState.capabstrue.put('2', [:])
            nativeState.time2 = settings.time2.value
            nativeState.runtimeCounter = (nativeState.runtimeCounter ?: 0) + 1
            nativeForm([[name: 'time2', type: 'time', multiple: false, submitOnChange: true]])
        }
        when:
        def result = script._nmUpsertCondition(42L, true, [id: '2', capability: 'Certain Time (and optional date)', fields: [
            [name: 'time#', type: 'time', value: '2026-10-20T18:30:00.000-0400'],
            [name: 'tstate#', type: 'enum', value: 'on']]])
        then:
        !result.success && !result.partial && result.rolledBack
        result.error.contains('tstate2')
        writes.findAll { it instanceof List }.collectMany { it }.any {
            it.name == 'time2' && it.value == '2026-10-20T18:30:00.000-0400'
        }
        settings == originalSettings
        nativeState.capabstrue == ['2': 'Timer original', '3': 'Unrelated trigger']
        nativeState.time2 == 'old native derived time'
        nativeState.runtimeCounter == 2
        !nativeState.containsKey('editCond')
        !nativeState.containsKey('moreCond')
        !nativeState.containsKey('hasAll')
        !nativeState.containsKey('capabDone')
    }

    def 'native cancellation that fails setting cleanup remains an explicit partial refusal'() {
        given:
        nativeState.capabstrue = [:]
        nativeRule.removeSetting = { String name -> null }
        nativeRule.appButtonHandler = { String button -> nativeState.cancelCapab = true }
        nativeRule.selectTriggers = { ->
            if (nativeState.cancelCapab) {
                nativeState.remove('cancelCapab')
                nativeState.capabstrue.remove('4')
                return nativeForm([])
            }
            nativeState.capabstrue.put('4', [:])
            nativeForm(settings.containsKey('tCapab4') ? [[name: 'time4', type: 'time', multiple: false]] :
                [[name: 'tCapab4', type: 'enum', multiple: false, options: ['Certain Time'], submitOnChange: true]])
        }
        when:
        def result = script._nmUpsertCondition(42L, true, [capability: 'Certain Time', fields: [[name: 'time#', type: 'enum', value: '17:30']]])
        then:
        !result.success && result.partial && !result.rolledBack
        result.error.contains('time4')
        result.cleanupError.contains('could not be verified')
        result.verificationNeeded
        settings.containsKey('tCapab4')
        nativeState.capabstrue == [:]
    }


    def 'new action rejection restores selectors and compiler scratch while retaining earlier actions and runtime events'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg', indent: '']]
        nativeState.actionList = ['1']
        nativeState.actNdx = 2
        nativeState.ruleNdx = 1
        nativeState.firstR = ['0': false, '9': false]
        nativeState.waitDone = ['.9': ['7': false]]
        settings.put('logmsg.1', [type: 'textarea', value: 'successful prefix'])
        Map originalSettings = [:] + settings
        nativeRule.removeSetting = { String name -> if (!ignoreRemoval) settings.remove(name) }
        List buttons = []
        nativeRule.appButtonHandler = { String name -> buttons << name; nativeState.actionCancel = true }
        nativeRule.doActPage = { ->
            if (nativeState.actionCancel) {
                nativeState.remove('actionCancel')
                nativeState.actionDone = true
                settings.remove('actType.2')
                nativeState.firstR.put('9', true)
                nativeState.waitDone.get('.9').put('7', true)
                nativeState.runtimeEvent = 'arrived during cancellation'
                return nativeForm([])
            }
            nativeState.capabsfalse.put('6', '')
            nativeState.ndx = 7
            settings.generated2 = [type: 'number', value: '1']
            if (settings.containsKey('actType.2')) {
                if (renderThrows) throw new IllegalStateException('native action render failed')
                return nativeForm([[name: 'actSubType.2', type: 'enum', multiple: false,
                                    options: ['getSetVariable'], submitOnChange: true]])
            }
            nativeForm([[name: 'actType.2', type: 'enum', multiple: false,
                         options: ['modeActs'], submitOnChange: true]])
        }
        when:
        def result = script._nmUpsertAction(42L, [type: 'modeActs', subtype: 'getReturnValue', fields: []])
        then:
        !result.success
        result.partial == ignoreRemoval
        result.rolledBack == !ignoreRemoval
        result.error.contains(renderThrows ? 'native action render failed' : 'actSubType.2')
        buttons == ['actionCancel']
        nativeState.actions == ['1': [method: 'getLogMsg', indent: '']]
        nativeState.actionList == ['1']
        nativeState.capabsfalse == ['1': 'Switch A on', '2': 'Switch B off']
        nativeState.ndx == 4
        nativeState.actNdx == 2
        nativeState.ruleNdx == 1
        nativeState.eval == ['0': ['1']]
        nativeState.firstR == ['0': false, '9': true]
        nativeState.waitDone == ['.9': ['7': true]]
        nativeState.runtimeEvent == 'arrived during cancellation'
        !nativeState.containsKey('doActN')
        !nativeState.containsKey('actionDone')
        !nativeState.containsKey('actionCancel')
        if (ignoreRemoval) {
            assert settings.generated2.value == '1'
            assert result.cleanupError
        } else assert settings == originalSettings
        where:
        ignoreRemoval | renderThrows
        false         | false
        false         | true
        true          | false
    }

    def 'rejected existing action restores staged typed value and deep compiled row without restoring runtime state'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg', indent: '', label: 'old']]
        nativeState.actionList = ['1']
        settings.put('actType.1', [type: 'enum', value: 'messageActs'])
        settings.put('actSubType.1', [type: 'enum', value: 'getLogMsg'])
        settings.put('logmsg.1', [type: 'text', value: 'old'])
        Map originalSettings = [:] + settings
        nativeRule.removeSetting = { String name -> settings.remove(name) }
        List buttons = []
        nativeRule.appButtonHandler = { String name -> buttons << name; nativeState.actionCancel = true }
        nativeRule.doActPage = { ->
            nativeState.runtimeEvents = (nativeState.runtimeEvents ?: 0) + 1
            if (nativeState.actionCancel) {
                nativeState.remove('actionCancel')
                nativeState.actionDone = true
                return nativeForm([])
            }
            nativeState.actions.get('1').label = settings.get('logmsg.1').value
            nativeForm([[name: 'logmsg.1', type: 'text', multiple: false, submitOnChange: true]])
        }
        when:
        def result = script._nmUpsertAction(42L, [id: '1', type: 'messageActs', subtype: 'getLogMsg', fields: [
            [name: 'logmsg.#', type: 'text', value: 'new'],
            [name: 'noSuchField.#', type: 'enum', value: 'on']]])
        then:
        !result.success && !result.partial && result.rolledBack
        result.error.contains('noSuchField.1')
        settings == originalSettings
        nativeState.actions == ['1': [method: 'getLogMsg', indent: '', label: 'old']]
        nativeState.actionList == ['1']
        nativeState.runtimeEvents == 3
        nativeState.keep == [unknown: true]
        !nativeState.containsKey('doActN')
        !nativeState.containsKey('actionDone')
        buttons == ['actionCancel']
    }


    def 'an edited action with empty native label rolls back its original target and recomputes native health'() {
        given:
        nativeState.actions = ['1': [method: 'getRuleActions', indent: '']]
        nativeState.actionList = ['1']
        nativeState.broken = false
        settings.put('actType.1', [type: 'enum', value: 'rulesActs'])
        settings.put('actSubType.1', [type: 'enum', value: 'getRuleActions'])
        settings.put('ruleAct.1', [type: 'enum', multiple: true, value: ['8']])
        Map original = [:] + settings
        int rowChecks = 0
        int pageValidations = 0
        nativeRule.removeSetting = { String name -> settings.remove(name) }
        script.metaClass._nrWriteSettings = { Long id, List rows ->
            rows.each { setting -> settings.put(setting.name, [type: setting.type, multiple: true, value: setting.value]) }
            [success: true]
        }
        nativeRule.doActPage = { ->
            if (nativeState.actionCancel) nativeState.remove('actionCancel')
            nativeForm([[name: 'ruleAct.1', type: 'enum', multiple: true, options: ['8'], submitOnChange: true]])
        }
        nativeRule.appButtonHandler = { String name ->
            if (name == 'actionCancel') nativeState.actionCancel = true
        }
        nativeRule.thisActionLabel = { Object actionId ->
            rowChecks++
            nativeState.broken = settings.get('ruleAct.1').value.isEmpty()
            nativeState.nestedLabel = ['temporary label scope']
            nativeState.actLabelIndent = 'changed by label render'
            nativeState.broken ? "<span style='color:red'>**Broken Action**</span>" : 'Run Actions: target'
        }
        nativeRule.broken = { ->
            pageValidations++
            nativeState.broken = settings.get('ruleAct.1').value.isEmpty() || unhealthyAfterRestore
            nativeState.broken
        }
        when:
        def result = script._nmUpsertAction(42L, [id: '1', type: 'rulesActs', subtype: 'getRuleActions',
            fields: [[name: 'ruleAct.#', type: 'enum', value: []]]])
        then:
        !result.success
        result.error.contains('label')
        result.partial == unhealthyAfterRestore
        result.rolledBack == !unhealthyAfterRestore
        settings == original
        nativeState.actionList == ['1']
        nativeState.actions.'1'.method == 'getRuleActions'
        nativeState.broken == unhealthyAfterRestore
        !nativeState.containsKey('nestedLabel')
        !nativeState.containsKey('actLabelIndent')
        rowChecks == 1
        pageValidations == 1
        if (unhealthyAfterRestore) assert result.cleanupError.contains('health was not restored')
        where:
        unhealthyAfterRestore << [false, true]
    }

    def 'rejected dependent fields recover stale broken health through native validation rather than a state flag patch'() {
        given:
        nativeState.actions = ['1': [method: 'getLogMsg', indent: '']]
        nativeState.actionList = ['1']
        nativeState.broken = false
        settings.put('actType.1', [type: 'enum', value: 'messageActs'])
        settings.put('actSubType.1', [type: 'enum', value: 'getLogMsg'])
        settings.put('logmsg.1', [type: 'text', value: 'original'])
        Map original = [:] + settings
        int validations = 0
        nativeRule.removeSetting = { String name -> settings.remove(name) }
        nativeRule.appButtonHandler = { String name -> nativeState.actionCancel = true }
        nativeRule.doActPage = { ->
            if (nativeState.actionCancel) nativeState.remove('actionCancel')
            else nativeState.broken = true
            nativeForm([[name: 'logmsg.1', type: 'text', multiple: false, submitOnChange: true]])
        }
        nativeRule.broken = { ->
            validations++
            assert settings == original
            assert nativeState.broken == true
            nativeState.broken = false
            false
        }
        when:
        def result = script._nmUpsertAction(42L, [id: '1', type: 'messageActs', subtype: 'getLogMsg', fields: [
            [name: 'logmsg.#', type: 'text', value: 'staged'],
            [name: 'hidden.#', type: 'number', value: 40]]])
        then:
        !result.success && !result.partial && result.rolledBack
        result.error.contains('hidden.1')
        settings == original
        !nativeState.broken
        validations == 1
        !writes.any { it instanceof Map && it.containsKey('broken') }
    }

    def 'cleanup health uses fresh native readback even when the wrapper state snapshot remains stale'() {
        given:
        nativeState.broken = true
        List validations = []
        nativeRule.actionLabelText = { -> validations << 'labels'; 'Original valid action' }
        nativeRule.broken = { -> validations << 'broken'; false }
        script.metaClass.hubInternalGet = { String path ->
            assert path == '/app/ruleBuilderJson/42'
            validations << 'readback'
            [broken: false]
        }
        when:
        script._nmRefreshHealth(42L, [broken: false])
        then:
        validations == ['labels', 'broken', 'readback']
        nativeState.broken
        writes.empty
    }

    def 'successful row label validation preserves formatting context and avoids a complete native page render'() {
        given:
        List stack = ['existing block']
        nativeState.nestedLabel = stack
        nativeState.actLabelIndent = '\t'
        nativeState.inIf = true
        nativeRule.doActPage = { -> authorForm([[name: 'logmsg.1', type: 'text', multiple: false]]) }
        nativeRule.appButtonHandler = { String name ->
            nativeState.actions = ['1': [method: 'getLogMsg']]
            nativeState.actionList = ['1']
        }
        nativeRule.thisActionLabel = { Object actionId ->
            nativeState.nestedLabel.pop()
            nativeState.inIf = false
            nativeState.actLabelIndent = ''
            'Log: valid'
        }
        nativeRule.selectActions = { Map params -> assert false: 'No complete page render should be needed for a valid row' }
        nativeRule.broken = { -> assert false: 'No whole-rule validation should be needed for a valid row' }
        nativeRule.actionLabelText = { -> assert false: 'Only the edited row should be rendered on success' }
        when:
        def result = script._nmUpsertAction(42L, [type: 'messageActs', subtype: 'getLogMsg', fields: [[name: 'logmsg.#', type: 'text', value: 'valid']]])
        then:
        result.success
        nativeState.nestedLabel == ['existing block']
        nativeState.actLabelIndent == '\t'
        nativeState.inIf
    }


}
