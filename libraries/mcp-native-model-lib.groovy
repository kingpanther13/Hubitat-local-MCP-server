library(name: "McpNativeModelLib", namespace: "mcp", author: "kingpanther13", description: "Native rule semantic compiler.")

private String _nmId(Object value, boolean allowZero = false) {
    if (value == null || !value.toString().isInteger() || value.toString().toInteger() < (allowZero ? 0 : 1)) throw new IllegalArgumentException('A positive native row ID is required')
    value.toString()
}

private Object _nmEntry(Map values, String id) {
    values?.find { key, value -> key.toString() == id }?.value
}

private List _nmFields(List fields, String id) {
    if (fields == null) throw new IllegalArgumentException('Typed fields are required')
    fields.collect { row ->
        if (!(row instanceof Map) || !(row.name instanceof String) || !row.name.contains('#') || !(row.type instanceof String) || !row.containsKey('value') || row.keySet().any { !(it in ['name', 'type', 'value']) }) {
            throw new IllegalArgumentException('Fields require native name templates containing #, type and value')
        }
        [name: row.name.replace('#', id), type: row.type, value: row.value]
    }
}

private void _nmTokens(List tokens, Map conditions) {
    if (!tokens) throw new IllegalArgumentException('An expression must contain an operand')
    boolean operand = true
    int depth = 0
    tokens.each { token ->
        String text = token?.toString()
        if (operand) {
            if (text == '(') depth++
            else if (text == 'NOT') { /* A unary operator still needs an operand. */ }
            else {
                if (text == null || !text.isInteger() || text.toInteger() <= 0 || !_nmEntry(conditions, text)?.toString()?.trim()) throw new IllegalArgumentException('Expression references an unknown condition')
                operand = false
            }
        } else if (text == ')') {
            if (--depth < 0) throw new IllegalArgumentException('Expression parentheses are misordered')
        } else if (text in ['AND', 'OR', 'XOR']) operand = true
        else throw new IllegalArgumentException('Expression requires a binary operator')
    }
    if (operand || depth != 0) throw new IllegalArgumentException('Expression is incomplete or parentheses are unmatched')
}

Map _nmSetExpression(Long ruleId, Object expressionId, List tokens) {
    String id = _nmId(expressionId, true)
    Map before = _nrState(ruleId)
    _nmTokens(tokens, before.capabsfalse instanceof Map ? before.capabsfalse : [:])
    Map expressions = before.eval instanceof Map ? [:] + before.eval : [:]
    Object priorKey = expressions.keySet().find { it.toString() == id }
    boolean existed = priorKey != null
    Object original = existed && expressions.get(priorKey) instanceof List ? new ArrayList(expressions.get(priorKey)) : expressions.get(priorKey)
    Object key = existed ? priorKey : id
    expressions.put(key, new ArrayList(tokens))
    _nrStatePatch(ruleId, [eval: expressions])
    String error = null
    boolean verified = false
    try {
        def nativeError = _nrHandle(ruleId).checkTokens(id)
        error = nativeError?.toString()
        Map after = _nrState(ruleId)
        verified = nativeError instanceof String && nativeError == '' && _nmEntry(after.eval instanceof Map ? after.eval : [:], id) == tokens
        if (!verified && !error) error = 'Native expression did not persist'
    } catch (Exception failure) { error = failure.message ?: 'Native expression validation failed' }
    if (!verified) {
        Map fresh = _nrState(ruleId)
        Map rollback = fresh.eval instanceof Map ? [:] + fresh.eval : [:]
        rollback.keySet().findAll { it.toString() == id }.each { rollback.remove(it) }
        if (existed) rollback.put(priorKey, original)
        _nrStatePatch(ruleId, [eval: rollback])
        Map restored = _nrState(ruleId)
        boolean kept = existed ? _nmEntry(restored.eval instanceof Map ? restored.eval : [:], id) == original : !(restored.eval instanceof Map && restored.eval.keySet().any { it.toString() == id })
        return [success: false, expressionId: id, error: error, originalPreserved: kept, partial: !kept]
    }
    [success: true, expressionId: id, tokens: tokens]
}

private void _nmLocalValue(String type, Object value) {
    boolean valid = false
    switch (type) {
        case 'integer': valid = value instanceof Number && value.toString().isBigDecimal() && new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0; break
        case 'bigdecimal': valid = value instanceof Number && value.toString().isBigDecimal(); break
        case 'boolean': valid = value instanceof Boolean; break
        case 'string': valid = value instanceof String; break
        case 'datetime': valid = value instanceof Number && value.toString().isLong(); break
        default: throw new IllegalArgumentException('Unsupported native local variable type')
    }
    if (!valid) throw new IllegalArgumentException('Local variable value does not match its native type')
}

Map _nmLocal(Long ruleId, String operation, String name = null, Object value = null, String type = null) {
    if (!(operation in ['list', 'get', 'create', 'set', 'delete'])) throw new IllegalArgumentException('Unsupported local operation')
    if (operation != 'list' && (!(name instanceof String) || !(name ==~ /[A-Za-z_][A-Za-z0-9_ ]{0,79}/) || name != name.trim())) throw new IllegalArgumentException('A valid local variable name is required')
    def handle = _nrHandle(ruleId)
    def allBefore = handle.getAllLocalVars()
    if (operation == 'list') return [success: allBefore instanceof Map, localVariables: allBefore]
    if (!(allBefore instanceof Map)) throw new IllegalStateException('Native local variables are unavailable')
    def before = handle.getLocalVar(name)
    if (allBefore.containsKey(name) != (before instanceof Map)) throw new IllegalStateException('Native local variable reads disagree')
    if (operation == 'get') return [success: before instanceof Map, variable: before]
    if (operation == 'create') {
        if (before != null) throw new IllegalArgumentException('Local variable already exists')
        _nmLocalValue(type, value)
        handle.createLocalVar(name, value, type)
    } else {
        if (!(before instanceof Map)) throw new IllegalArgumentException('Local variable does not exist')
        if (operation == 'set') { _nmLocalValue(before.type?.toString(), value); handle.setLocalVar(name, value) }
        else handle.deleteLocalVar(name)
    }
    def after = _nrHandle(ruleId).getLocalVar(name)
    def allAfter = _nrHandle(ruleId).getAllLocalVars()
    boolean verified = allAfter instanceof Map && (operation == 'delete' ? after == null && !allAfter.containsKey(name) : after instanceof Map && allAfter.get(name) == after && after.name == name && after.value == value && after.type == (operation == 'create' ? type : before.type))
    [success: verified, deleted: operation == 'delete' && verified, variable: after, verificationNeeded: true]
}

Map _nmUpsertCondition(Long ruleId, boolean trigger, Map row) {
    if (row == null || row.keySet().any { !(it in ['id', 'capability', 'fields']) } || !(row.capability instanceof String) || !row.capability.trim()) throw new IllegalArgumentException('A capability and typed fields are required')
    Map before = _nrState(ruleId)
    String collection = trigger ? 'capabstrue' : 'capabsfalse'
    Map prior = before.get(collection) instanceof Map ? before.get(collection) : [:]
    boolean editing = row.containsKey('id')
    String id = _nmId(editing ? row.id : before.ndx)
    if (editing && !_nmEntry(prior, id)?.toString()?.trim()) throw new IllegalArgumentException('Native row does not exist')
    List fields = _nmFields(row.fields as List, id)
    String selector = trigger ? "tCapab${id}".toString() : "rCapab_${id}".toString()
    if (fields.any { it.name == selector }) throw new IllegalArgumentException('Capability selector must not be repeated in fields')
    List settings = [[name: selector, type: 'enum', value: row.capability]] + fields
    Map written = _nrWriteSettings(ruleId, settings)
    if (written.success != true) return [success: false, id: id, partial: written.partial ?: false, settings: written]
    Map context = [hasAll: false]
    if (editing) context.editCond = id
    else { context.moreCond = true; context.ndx = id.toInteger() }
    _nrStatePatch(ruleId, context, editing ? ['moreCond'] : ['editCond'])
    if (trigger) _nrHandle(ruleId).selectTriggers()
    else _nrHandle(ruleId).selectConditions([:])
    _nrStatePatch(ruleId, [hasAll: true])
    if (trigger) _nrHandle(ruleId).selectTriggers()
    else _nrHandle(ruleId).selectConditions([:])
    Map after = _nrState(ruleId)
    def label = _nmEntry(after.get(collection) instanceof Map ? after.get(collection) : [:], id)
    Map echoed = _nrSettings(ruleId, settings.collect { it.name })
    boolean persisted = settings.every { echoed.get(it.name)?.type?.toString() == it.type && _nrComparableSetting(it.type, echoed.get(it.name)?.value) == _nrComparableSetting(it.type, it.value) }
    boolean compiled = label instanceof String && label.trim() && !label.contains('null') && !label.contains('BROKEN')
    [success: compiled && persisted, id: id, label: label, partial: !(compiled && persisted), settingsVerified: persisted, error: compiled ? (persisted ? null : 'Native input persistence failed') : 'Native row did not compile a valid label']
}

Map _nmUpsertAction(Long ruleId, Map row) {
    if (row == null || row.keySet().any { !(it in ['id', 'type', 'subtype', 'fields', 'expression']) } || !(row.type instanceof String) || !row.type.trim() || !(row.subtype instanceof String) || !row.subtype.trim()) throw new IllegalArgumentException('Action type, subtype and typed fields are required')
    if (row.subtype.toString().toLowerCase().contains('wait')) throw new IllegalArgumentException('Complex waits require a verified native sequence')
    Map before = _nrState(ruleId)
    List priorOrder = before.actionList instanceof List ? before.actionList.collect { it.toString() } : []
    boolean editing = row.containsKey('id')
    String id = _nmId(editing ? row.id : before.actNdx)
    def prior = _nmEntry(before.actions instanceof Map ? before.actions : [:], id)
    if (editing && !(prior instanceof Map)) throw new IllegalArgumentException('Native action does not exist')
    if (!editing && row.subtype in ['getIfThen', 'getElseIf'] && !row.containsKey('expression')) throw new IllegalArgumentException('New conditional actions require expression tokens')
    String expectedExpressionId = null
    if (row.containsKey('expression')) {
        if (!(row.subtype in ['getIfThen', 'getElseIf'])) throw new IllegalArgumentException('Expression actions currently support IF and ELSE-IF only')
        _nmTokens(row.expression as List, before.capabsfalse instanceof Map ? before.capabsfalse : [:])
    }
    List fields = _nmFields(row.fields as List, id)
    if (fields.any { it.name in ["actType.${id}".toString(), "actSubType.${id}".toString()] }) throw new IllegalArgumentException('Action selectors must not be repeated')
    List settings = [[name: "actType.${id}".toString(), type: 'enum', value: row.type], [name: "actSubType.${id}".toString(), type: 'enum', value: row.subtype]] + fields
    Map written = _nrWriteSettings(ruleId, settings)
    if (written.success != true) return [success: false, id: id, partial: written.partial ?: false, settings: written]
    String editToken = 'N'
    if (editing) {
        boolean expressionAction = prior.method in ['getIfThen', 'getElseIfThen', 'getWhile', 'getUntil']
        editToken = "${id}.${prior.cond ?: 0}.${expressionAction}".toString()
    }
    _nrStatePatch(ruleId, [doActN: editToken])
    _nrHandle(ruleId).doActPage()
    if (row.containsKey('expression')) {
        String expressionId = _nmId(editing && prior.rule != null ? prior.rule : _nrState(ruleId).ruleNdx, true)
        expectedExpressionId = expressionId
        Map expression = _nmSetExpression(ruleId, expressionId, row.expression as List)
        if (expression.success != true) return [success: false, id: id, partial: true, expression: expression]
        _nrHandle(ruleId).appButtonHandler('hasRule')
        _nrHandle(ruleId).doActPage()
    }
    _nrHandle(ruleId).appButtonHandler('actionDone')
    _nrHandle(ruleId).doActPage()
    Map after = _nrState(ruleId)
    def action = _nmEntry(after.actions instanceof Map ? after.actions : [:], id)
    boolean listed = after.actionList instanceof List && after.actionList.any { it.toString() == id }
    List expectedOrder = editing ? priorOrder : priorOrder + [id]
    boolean ordered = after.actionList instanceof List && after.actionList.collect { it.toString() } == expectedOrder
    boolean compiled = action instanceof Map && action.method == row.subtype
    boolean expressionVerified = expectedExpressionId == null || (action instanceof Map && action.rule?.toString() == expectedExpressionId && _nmEntry(after.eval instanceof Map ? after.eval : [:], expectedExpressionId) == row.expression)
    Map echoed = _nrSettings(ruleId, settings.collect { it.name })
    boolean persisted = settings.every { echoed.get(it.name)?.type?.toString() == it.type && _nrComparableSetting(it.type, echoed.get(it.name)?.value) == _nrComparableSetting(it.type, it.value) }
    [success: listed && ordered && compiled && persisted && expressionVerified, id: id, action: action, partial: !(listed && ordered && compiled && persisted && expressionVerified), settingsVerified: persisted, expressionVerified: expressionVerified, verificationNeeded: true]
}

Map _nmAddTerminator(Long ruleId, String subtype) {
    if (!(subtype in ['endIf', 'else', 'endRep'])) throw new IllegalArgumentException('Unsupported structural action')
    Map before = _nrState(ruleId)
    String id = _nmId(before.actNdx)
    List priorOrder = before.actionList instanceof List ? before.actionList.collect { it.toString() } : []
    _nrHandle(ruleId).addAction(subtype)
    Map after = _nrState(ruleId)
    def action = _nmEntry(after.actions instanceof Map ? after.actions : [:], id)
    Map expected = [endIf: 'getEndIf', 'else': 'getElse', endRep: 'getEndRepeat']
    boolean verified = action instanceof Map && action.method == expected.get(subtype) && after.actionList instanceof List && after.actionList.collect { it.toString() } == priorOrder + [id]
    [success: verified, id: id, action: action, partial: !verified, verificationNeeded: true]
}
