library(name: "McpNativeModelLib", namespace: "mcp", author: "kingpanther13", description: "Native rule semantic compiler.")

private String _nmId(Object value, boolean allowZero = false) {
    if (value == null || !value.toString().isInteger() || value.toString().toInteger() < (allowZero ? 0 : 1)) throw new IllegalArgumentException('A positive native row ID is required')
    value.toString()
}

private Object _nmEntry(Map values, String id) {
    (values)?.find(((this.&_bat529Cb_ba52df93_55).curry([id] as Object[])))?.value
}

private List _nmFields(List fields, String id) {
    if (fields == null) throw new IllegalArgumentException('Typed fields are required')
    List result = (fields).collect(((this.&_bat529Cb_ba52df93_105).curry([id] as Object[])))
    if ((result).collect((this.&_bat529Cb_ba52df93_3)).unique().size() != result.size()) throw new IllegalArgumentException('Native inputs must not be repeated')
    result
}

private void _nmCollectInputs(Object node, Map inputs) {
    if (node instanceof Map) {
        if (node.element == 'input' && node.name instanceof String && node.type instanceof String && !inputs.containsKey(node.name)) inputs.put(node.name, node)
        (['sections', 'input', 'body', 'children']).each(((this.&_bat529Cb_ba52df93_94).curry([inputs, node] as Object[])))
    } else if (node instanceof List) (node).each(((this.&_bat529Cb_ba52df93_83).curry([inputs] as Object[])))
}

Map _nmValidateInputs(Object page, List fields) {
    Map inputs = [:]
    if (page instanceof Map) _nmCollectInputs(page, inputs)
    List errors = []
    (fields).each(((this.&_bat529Cb_ba52df93_106).curry([errors, inputs, page] as Object[])))
    [success: errors.isEmpty(), error: errors ? errors.get(0).error : null, fieldErrors: errors, verifiedFields: ((fields).findAll(((this.&_bat529Cb_ba52df93_63).curry([errors] as Object[])))).collect((this.&_bat529Cb_ba52df93_76))]
}

private Map _nmWriteExposed(Long ruleId, List fields, List selectors, Object page, Closure render) {
    if ((fields).collect((this.&_bat529Cb_ba52df93_2)).unique().size() != fields.size()) throw new IllegalArgumentException('Native inputs must not be repeated')
    List pending = new ArrayList(fields)
    while (pending) {
        Map inputs = [:]
        if (page instanceof Map) _nmCollectInputs(page, inputs)
        List selectorRows = (pending).findAll(((this.&_bat529Cb_ba52df93_22).curry([selectors] as Object[])))
        if (selectorRows) {
            Map saved = _nrSettings(ruleId, (selectorRows).collect((this.&_bat529Cb_ba52df93_10)))
            for (Object row in selectorRows) {
                if (!inputs.containsKey(row.name)) {
                    def prior = saved.get(row.name)
                    if (prior?.type == row.type && _nrComparableSetting(row.type, prior.value) == _nrComparableSetting(row.type, row.value)) pending.remove(row)
                }
            }
            selectorRows = (pending).findAll(((this.&_bat529Cb_ba52df93_20).curry([selectors] as Object[])))
        }
        if (!pending) break
        List batch = []
        for (Object descriptor in inputs.values()) {
            def row = pending.find { it.name == descriptor.name && (!selectorRows || it.name in selectors) }
            if (row != null) {
                batch << row
                if (descriptor.submitOnChange == true || row.name in selectors) break
            }
        }
        Map validation = _nmValidateInputs(page, batch ?: pending)
        if (!batch || validation.success != true) return [success: false, partial: true, error: validation.error ?: 'Native inputs are not exposed; no authoring progress is possible', fieldErrors: validation.fieldErrors]
        Map written = _nrWriteSettings(ruleId, batch)
        if (written.success != true) return [success: false, partial: true, error: 'Native input persistence failed', settings: written]
        pending.removeAll(batch)
        page = render.call()
    }
    [success: true, page: page]
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
    Object priorKey = (expressions.keySet()).find(((this.&_bat529Cb_ba52df93_47).curry([id] as Object[])))
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
        ((rollback.keySet()).findAll(((this.&_bat529Cb_ba52df93_48).curry([id] as Object[])))).each(((this.&_bat529Cb_ba52df93_71).curry([rollback] as Object[])))
        if (existed) rollback.put(priorKey, original)
        _nrStatePatch(ruleId, [eval: rollback])
        Map restored = _nrState(ruleId)
        boolean kept = existed ? _nmEntry(restored.eval instanceof Map ? restored.eval : [:], id) == original : !(restored.eval instanceof Map && (restored.eval.keySet()).any(((this.&_bat529Cb_ba52df93_49).curry([id] as Object[]))))
        return [success: false, expressionId: id, error: error, originalPreserved: kept, partial: !kept]
    }
    [success: true, expressionId: id, tokens: tokens]
}

Map _nmSetRequired(Long ruleId, List tokens) {
    Map before = _nrState(ruleId)
    _nmTokens(tokens, before.capabsfalse instanceof Map ? before.capabsfalse : [:])
    Map enabled = _nrWriteSettings(ruleId, [[name: 'useST', type: 'bool', value: true]])
    if (enabled.success != true) return [success: false, partial: enabled.partial ?: false, settings: enabled]
    _nrHandle(ruleId).STPage([:])
    Map expression = _nmSetExpression(ruleId, '0', tokens)
    if (expression.success != true) return [success: false, partial: true, expression: expression]
    List operands = ((tokens).findAll((this.&_bat529Cb_ba52df93_32))).collect((this.&_bat529Cb_ba52df93_75)).unique()
    Map fresh = _nrState(ruleId)
    Map first = fresh.firstR instanceof Map ? [:] + fresh.firstR : [:]
    first.put('0', false)
    _nrStatePatch(ruleId, [predCapabs: operands, firstR: first, hasRule: true])
    _nrHandle(ruleId).STPage([:])
    _nrStatePatch(ruleId, [doneST: true])
    _nrHandle(ruleId).STPage([:])
    Map pages = _nrRulePages(ruleId)
    ([pages.primary, pages.actions].unique()).each(((this.&_bat529Cb_ba52df93_72).curry([ruleId] as Object[])))
    Map after = _nrState(ruleId)
    Map settings = _nrSettings(ruleId, ['useST'])
    boolean predicate = after.hasPredicate == true && _nmEntry(after.eval instanceof Map ? after.eval : [:], '0') == tokens
    boolean bound = after.predCapabs instanceof List && (after.predCapabs).collect((this.&_bat529Cb_ba52df93_26)).sort() == (operands).collect((this.&_bat529Cb_ba52df93_12)).sort()
    boolean persisted = settings.get('useST')?.type == 'bool' && _nrComparableSetting('bool', settings.get('useST')?.value) == 'true'
    boolean verified = predicate && bound && persisted
    [success: verified, partial: !verified, expressionId: '0', tokens: tokens, predicateVerified: predicate && bound, settingsVerified: persisted, verificationNeeded: !verified]
}

private boolean _nmDateTimeValue(Object value) {
    if (!(value instanceof String) || !(value ==~ /(?:\d{4}-\d{2}-\d{2})T(?:\d{2}:\d{2}:\d{2}\.\d{3})(?:Z|[+-]\d{4})/)) return false
    String normalized = value
    // RM uses these sentinels for time-only and date-only variables.
    if (normalized.startsWith('9999-99-99')) normalized = '2000-01-01' + normalized.substring(10)
    if (normalized.endsWith('T99:99:99.999-9999')) normalized = normalized.take(10) + 'T00:00:00.000+0000'
    if (normalized.endsWith('Z')) normalized = normalized.take(normalized.size() - 1) + '+0000'
    String offset = normalized.substring(normalized.size() - 5)
    if (!(offset ==~ /[+-](?:[01]\d|2[0-3])[0-5]\d/)) return false
    try {
        def zone = TimeZone.getTimeZone('GMT' + offset)
        return Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", normalized).format("yyyy-MM-dd'T'HH:mm:ss.SSSZ", zone) == normalized
    } catch (Exception ignored) { return false }
}

private void _nmLocalValue(String type, Object value) {
    boolean valid = false
    switch (type) {
        case 'integer': valid = value instanceof Number && value.toString().isBigDecimal() && new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0; break
        case 'bigdecimal': valid = value instanceof Number && value.toString().isBigDecimal(); break
        case 'boolean': valid = value instanceof Boolean; break
        case 'string': valid = value instanceof String; break
        case 'datetime': valid = _nmDateTimeValue(value); break
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
    def inventoryRow = allAfter instanceof Map ? allAfter.get(name) : null
    boolean verified = allAfter instanceof Map && (operation == 'delete' ? after == null && !allAfter.containsKey(name) : after instanceof Map && inventoryRow instanceof Map && inventoryRow.type == after.type && inventoryRow.value == after.value && after.name == name && after.value == value && after.type == (operation == 'create' ? type : before.type))
    [success: verified, partial: !verified, deleted: operation == 'delete' && verified, variable: after, verificationNeeded: !verified]
}

private void _nmRestoreSettings(Long ruleId, Map savedSettings) {
    Map current = _nmSettingsInventory(ruleId)
    ((current.keySet()).findAll(((this.&_bat529Cb_ba52df93_61).curry([savedSettings] as Object[])))).each(((this.&_bat529Cb_ba52df93_87).curry([ruleId] as Object[])))
    List restore = []
    (savedSettings).each(((this.&_bat529Cb_ba52df93_104).curry([current, restore, ruleId] as Object[])))
    if (restore && _nrWriteSettings(ruleId, restore).success != true) throw new IllegalStateException('Native setting restoration failed')
}

private void _nmRefreshHealth(Long ruleId, Map baseline) {
    def labels = _nrHandle(ruleId).actionLabelText()
    def validated = _nrHandle(ruleId).broken()
    if (!(labels instanceof String) || !(validated instanceof Boolean)) throw new IllegalStateException('Native row health could not be validated after cancellation')
    def raw = hubInternalGet("/app/ruleBuilderJson/${ruleId}")
    def health = raw instanceof Map ? raw : new groovy.json.JsonSlurper().parseText(raw?.toString() ?: '{}')
    if (!(health instanceof Map) || !(health.broken instanceof Boolean)) throw new IllegalStateException('Native row health could not be verified after cancellation')
    if (validated != (baseline.broken == true) || health.broken != validated) throw new IllegalStateException('Native row health was not restored after cancellation')
}

private boolean _nmActionLabel(Long ruleId, String id) {
    Map before = _nrState(ruleId)
    List keys = ['actLabelIndent', 'nestedLabel', 'inIf', 'inRepeat']
    Map format = (before).findAll(((this.&_bat529Cb_ba52df93_33).curry([keys] as Object[])))
    if (format.nestedLabel instanceof List) format.put('nestedLabel', new ArrayList(format.nestedLabel))
    def label
    try { label = _nrHandle(ruleId).thisActionLabel(id) }
    finally { _nrStatePatch(ruleId, format, (keys).findAll(((this.&_bat529Cb_ba52df93_21).curry([format] as Object[])))) }
    label instanceof String && label.trim() && !(label =~ /(?is)<span\b[^>]*color\s*:\s*red[^>]*>[^<]*\*\*Broken [^*]+\*\*/)
}

private Map _nmRejectEdit(Long ruleId, String id, Map baseline, Map savedSettings, List keys, Map entries, Closure cancel, Map failure) {
    String cleanupError = null
    boolean verified = false
    try {
        boolean consumed = cancel.call() == true
        _nmRestoreSettings(ruleId, savedSettings)
        Map current = _nrState(ruleId)
        Map values = (baseline).findAll(((this.&_bat529Cb_ba52df93_40).curry([keys] as Object[])))
        List remove = (keys).findAll(((this.&_bat529Cb_ba52df93_24).curry([baseline] as Object[])))
        (entries).each(((this.&_bat529Cb_ba52df93_103).curry([baseline, current, remove, values] as Object[])))
        _nrStatePatch(ruleId, values, remove)
        _nmRefreshHealth(ruleId, baseline)
        Map fresh = _nrState(ruleId)
        verified = consumed && _nmSettingsInventory(ruleId) == savedSettings && (keys).every(((this.&_bat529Cb_ba52df93_86).curry([baseline, fresh] as Object[])))&& (entries).every(((this.&_bat529Cb_ba52df93_101).curry([baseline, fresh] as Object[])))
        if (!verified) cleanupError = 'Native row cancellation or restoration could not be verified'
    } catch (Exception cleanupFailure) { cleanupError = cleanupFailure.message ?: 'Native row cleanup failed' }
    if (cleanupError) mcpLog('warn', 'rm-native', "Rule ${ruleId} row ${id}: ${cleanupError}".toString())
    failure + [success: false, id: id, error: failure.error ?: cleanupError ?: 'Native row authoring failed', fieldErrors: failure.fieldErrors ?: [], partial: !verified, rolledBack: verified,
     cleanupError: cleanupError, verificationNeeded: !verified]
}

private Map _nmRejectCondition(Long ruleId, boolean trigger, boolean editing, String id, Map baseline, Map savedSettings, List fields, Closure render, Map failure) {
    String collection = trigger ? 'capabstrue' : 'capabsfalse'
    List keys = ([collection, 'ndx', 'hasAll', 'moreCond', 'editCond', 'capabDone', 'cancelCapab', 'updatingTrig', 'hasConditions', 'isPredicate', 'predCapabs', "hhmmss${id}".toString()] + (fields).collect((this.&_bat529Cb_ba52df93_5))).unique()
    Closure cancel = { unused ->
        Map context = [hasAll: false]
        if (editing) context.editCond = id
        else { context.moreCond = true; context.ndx = id.toInteger() }
        _nrStatePatch(ruleId, context, editing ? ['moreCond'] : ['editCond'])
        _nrHandle(ruleId).appButtonHandler('cancelCapab')
        render.call()
        _nrState(ruleId).cancelCapab != true
    }
    _nmRejectEdit(ruleId, id, baseline, savedSettings, keys, [:], cancel, failure)
}

private Map _nmRejectAction(Long ruleId, String id, Map baseline, Map savedSettings, List fields, Closure render, Map failure) {
    Map current = _nrState(ruleId)
    List expressionIds = []
    def prior = _nmEntry(baseline.actions instanceof Map ? baseline.actions : [:], id)
    if (prior instanceof Map && prior.rule != null) expressionIds << prior.rule.toString()
    if (current.ruleNdx != null) expressionIds << current.ruleNdx.toString()
    if (current.eval instanceof Map) expressionIds.addAll(((current.eval.keySet()).findAll(((this.&_bat529Cb_ba52df93_84).curry([baseline] as Object[])))).collect((this.&_bat529Cb_ba52df93_89)))
    List keys = (['actions', 'actionList', 'capabsfalse', 'capabstrue', 'eval', 'predCapabs', 'isPredicate', 'hasPredicate', 'ndx', 'actNdx', 'ruleNdx', 'waitCondNdx',
                 'doActN', 'editAct', 'editIf', 'editToken', 'action', 'actionDone', 'actionCancel', 'hasDevice', 'hasCondition', 'hasCompleteRule',
                 'hasAll', 'hasRule', 'capabDone', 'capabActDone', 'inSimpleCond', 'simpleCond', 'inIf', 'inRepeat', 'hasWhileRule', 'needRuleButtons',
                 'needWaitButtons', 'needWaitDone', 'needWaitCancel', 'anotherWait', 'doneWaitEvents', 'moreCond', 'editCond'] + (fields).collect((this.&_bat529Cb_ba52df93_4))).unique()
    Closure cancel = { unused ->
        _nrHandle(ruleId).appButtonHandler('actionCancel')
        render.call()
        _nrState(ruleId).actionCancel != true
    }
    _nmRejectEdit(ruleId, id, baseline, savedSettings, keys,
        [firstR: expressionIds.unique(), waitNdx: [".${id}".toString()], waitDone: [".${id}".toString()]], cancel, failure)
}

Map _nmUpsertCondition(Long ruleId, boolean trigger, Map row) {
    if (row == null || (row.keySet()).any((this.&_bat529Cb_ba52df93_62))|| !(row.capability instanceof String) || !row.capability.trim()) throw new IllegalArgumentException('A capability and typed fields are required')
    Map before = _nrState(ruleId)
    String collection = trigger ? 'capabstrue' : 'capabsfalse'
    Map prior = before.get(collection) instanceof Map ? [:] + before.get(collection) : [:]
    boolean editing = row.containsKey('id')
    String id = _nmId(editing ? row.id : before.ndx)
    if (editing && !_nmEntry(prior, id)?.toString()?.trim()) throw new IllegalArgumentException('Native row does not exist')
    if (!editing && (prior.keySet()).any(((this.&_bat529Cb_ba52df93_25).curry([id] as Object[])))) throw new IllegalArgumentException('Native allocation slot is already occupied')
    List fields = _nmFields(row.get('fields') as List, id)
    String selector = trigger ? "tCapab${id}".toString() : "rCapab_${id}".toString()
    if ((fields).any(((this.&_bat529Cb_ba52df93_13).curry([selector] as Object[])))) throw new IllegalArgumentException('Capability selector must not be repeated in fields')
    List settings = [[name: selector, type: 'enum', value: row.capability]] + fields
    Map savedSettings = _nmSettingsInventory(ruleId)
    Map baseline = [:] + before
    if (before.containsKey(collection)) baseline.put(collection, prior)
    if (before.predCapabs instanceof List) baseline.predCapabs = new ArrayList(before.predCapabs)
    // Native condition pages only expose edits in the row's predicate group.
    boolean predicate = !trigger && editing && before.predCapabs instanceof List && (before.predCapabs).any(((this.&_bat529Cb_ba52df93_39).curry([id] as Object[])))
    Closure render = { unused -> trigger ? _nrHandle(ruleId).selectTriggers() : _nrHandle(ruleId).selectConditions([pred: predicate]) }
    try {
        Map context = [hasAll: false]
        if (editing) context.editCond = id
        else { context.moreCond = true; context.ndx = id.toInteger() }
        _nrStatePatch(ruleId, context, editing ? ['moreCond'] : ['editCond'])
        Map inputValidation = _nmWriteExposed(ruleId, settings, [selector], render.call(), render)
        if (inputValidation.success != true) return _nmRejectCondition(ruleId, trigger, editing, id, baseline, savedSettings, settings, render, inputValidation)
        _nrStatePatch(ruleId, [hasAll: true])
        render.call()
        Map after = _nrState(ruleId)
        if (predicate && after.predCapabs != baseline.predCapabs) {
            // Editing a predicate must not add a second binding with a different ID type.
            _nrStatePatch(ruleId, [predCapabs: new ArrayList(baseline.predCapabs)])
            after = _nrState(ruleId)
        }
        boolean membership = !predicate || after.predCapabs == baseline.predCapabs
        def label = _nmEntry(after.get(collection) instanceof Map ? after.get(collection) : [:], id)
        Map echoed = _nrSettings(ruleId, (settings).collect((this.&_bat529Cb_ba52df93_7)))
        boolean persisted = (settings).every(((this.&_bat529Cb_ba52df93_91).curry([echoed] as Object[])))
        // RM shows a device's current value in the label; a device with no value yet shows null there.
        String bare = label instanceof String ? label.replaceAll(/\(?<span style='color:black'>\(?[^<]*\)?<\/span>\)?/, '') : null
        boolean compiled = bare?.trim() && !bare.contains('null') && !bare.contains('BROKEN')
        if (!(compiled && persisted && membership)) return _nmRejectCondition(ruleId, trigger, editing, id, baseline, savedSettings, settings, render,
            [error: compiled ? 'Native input persistence failed' : 'Native row did not compile a valid label', compiledLabel: label?.toString()])
        [success: true, id: id, label: label, partial: false, settingsVerified: true]
    } catch (Exception failure) {
        mcpLogError('rm-native', "Native ${trigger ? 'trigger' : 'condition'} change failed for rule ${ruleId}: ${failure.message}".toString(), failure)
        _nmRejectCondition(ruleId, trigger, editing, id, baseline, savedSettings, settings, render, [error: failure.message ?: 'Native row authoring failed'])
    }
}

Map _nmUpsertAction(Long ruleId, Map row) {
    if (row == null || (row.keySet()).any((this.&_bat529Cb_ba52df93_81))|| !(row.type instanceof String) || !row.type.trim() || !(row.subtype instanceof String) || !row.subtype.trim()) throw new IllegalArgumentException('Action type, subtype and typed fields are required')
    boolean waitEvents = row.subtype == 'getWaitEvents'
    boolean waitRule = row.subtype == 'getWaitRule'
    if (row.subtype.toString().toLowerCase().contains('wait') && !waitEvents && !waitRule) throw new IllegalArgumentException('Unsupported native wait action')
    if (waitEvents) {
        if (!(row.events instanceof List) || row.events.empty || row.events.size() > 16) throw new IllegalArgumentException('Wait actions require between one and sixteen events')
        (row.events).each((this.&_bat529Cb_ba52df93_102))
    } else if (row.containsKey('events')) throw new IllegalArgumentException('Only Wait for Events accepts event rows')
    Map before = _nrState(ruleId)
    List priorOrder = before.actionList instanceof List ? (before.actionList).collect((this.&_bat529Cb_ba52df93_35)): []
    boolean editing = row.containsKey('id')
    String id = _nmId(editing ? row.id : before.actNdx)
    def prior = _nmEntry(before.actions instanceof Map ? before.actions : [:], id)
    if (editing && !(prior instanceof Map)) throw new IllegalArgumentException('Native action does not exist')
    if (!editing && prior != null) throw new IllegalArgumentException('Native action allocation slot is already occupied')
    if (waitEvents && editing && (!(prior.wait instanceof List) || prior.wait.size() != row.events.size())) throw new IllegalArgumentException('Editing waits currently requires the existing event count')
    if (!editing && row.subtype in ND_EXPRESSION_SUBTYPES && !row.containsKey('expression')) throw new IllegalArgumentException('New expression actions require expression tokens')
    String expectedExpressionId = null
    if (row.containsKey('expression')) {
        if (!(row.subtype in ND_EXPRESSION_SUBTYPES)) throw new IllegalArgumentException('Unsupported native expression action')
        _nmTokens(row.expression as List, before.capabsfalse instanceof Map ? before.capabsfalse : [:])
    }
    String conditionId = row.containsKey('condition') ? _nmId(row.condition) : (editing && prior.cond ? prior.cond.toString() : null)
    if (row.containsKey('condition')) {
        _nmTokens([conditionId], before.capabsfalse instanceof Map ? before.capabsfalse : [:])
        if (row.containsKey('expression') || row.type in ['condActs', 'repeatActs', 'delayActs']) throw new IllegalArgumentException('A simple condition requires an ordinary native action')
    }
    List fields = _nmFields(row.get('fields') as List, id)
    if ((fields).any(((this.&_bat529Cb_ba52df93_77).curry([id] as Object[])))) throw new IllegalArgumentException('Action selectors must not be repeated')
    List settings = [[name: "actType.${id}".toString(), type: 'enum', value: row.type], [name: "actSubType.${id}".toString(), type: 'enum', value: row.subtype]] + fields
    String editToken = 'N'
    if (editing) {
        boolean expressionAction = prior.method in ['getIfThen', 'getWhile', 'getUntil', 'getWaitRule']
        editToken = "${id}.${conditionId ?: 0}.${expressionAction}".toString()
    }
    Map savedSettings = _nmSettingsInventory(ruleId)
    Map baseline = [:] + before
    (['actions', 'actionList', 'capabsfalse', 'capabstrue', 'eval', 'predCapabs', 'firstR', 'waitNdx', 'waitDone']).each(((this.&_bat529Cb_ba52df93_99).curry([baseline, before] as Object[])))
    Closure render = { unused -> _nrHandle(ruleId).doActPage() }
    Closure reject = { Map failure -> _nmRejectAction(ruleId, id, baseline, savedSettings, settings, render, failure) }
    try {
        _nrStatePatch(ruleId, [doActN: editToken])
        if (row.containsKey('condition') && !editing) {
            List conditional = [
                [name: "actType.${id}".toString(), type: 'enum', value: 'condActs'],
                [name: "actSubType.${id}".toString(), type: 'enum', value: 'getCondAct'],
                [name: "useCond.${id}".toString(), type: 'bool', value: true],
                [name: "useCondNdx.${id}".toString(), type: 'enum', value: conditionId]
            ]
            Map selected = _nmWriteExposed(ruleId, conditional, (conditional.take(2)).collect((this.&_bat529Cb_ba52df93_19)), render.call(), render)
            if (selected.success != true) return reject.call(selected)
            _nrHandle(ruleId).appButtonHandler('hasAll')
            render.call()
        }
        Map inputValidation = _nmWriteExposed(ruleId, waitRule ? settings.take(2) : settings, (settings.take(2)).collect((this.&_bat529Cb_ba52df93_14)), render.call(), render)
        if (inputValidation.success != true) return reject.call(inputValidation)
        List waitIds = null
        if (waitEvents) {
            Map waits = _nmCompileWait(ruleId, id, row.events as List, editing)
            if (waits.success != true) return reject.call(waits + [waitEvents: waits])
            waitIds = waits.eventIds
            settings.addAll(waits.settings)
        }
        if (row.containsKey('expression')) {
            String expressionId = _nmId(editing && prior.rule != null ? prior.rule : _nrState(ruleId).ruleNdx, true)
            expectedExpressionId = expressionId
            Map expression = _nmSetExpression(ruleId, expressionId, row.expression as List)
            if (expression.success != true) return reject.call([error: expression.error ?: 'Native action expression failed', expression: expression])
            if (waitRule && fields) {
                Map durationInputs = _nmWriteExposed(ruleId, fields, [], render.call(), render)
                if (durationInputs.success != true) return reject.call(durationInputs)
            }
            _nrHandle(ruleId).appButtonHandler('hasRule')
            _nrHandle(ruleId).doActPage()
            if (waitRule) _nrHandle(ruleId).doActPage()
        }
        if (waitRule && fields && !row.containsKey('expression')) {
            Map durationInputs = _nmWriteExposed(ruleId, fields, [], render.call(), render)
            if (durationInputs.success != true) return reject.call(durationInputs)
        }
        // Wait for Expression commits when its expression Done is consumed.
        if (!(waitRule && row.containsKey('expression'))) {
            _nrHandle(ruleId).appButtonHandler('actionDone')
            _nrHandle(ruleId).doActPage()
        }
        if (waitEvents) _nrHandle(ruleId).selectTriggers()
        Map after = _nrState(ruleId)
        def action = _nmEntry(after.actions instanceof Map ? after.actions : [:], id)
        boolean listed = after.actionList instanceof List && (after.actionList).any(((this.&_bat529Cb_ba52df93_37).curry([id] as Object[])))
        List expectedOrder = editing ? priorOrder : priorOrder + [id]
        boolean ordered = after.actionList instanceof List && (after.actionList).collect((this.&_bat529Cb_ba52df93_29))== expectedOrder
        boolean compiled = action instanceof Map && action.method == row.subtype
        boolean expressionVerified = expectedExpressionId == null || (action instanceof Map && action.rule?.toString() == expectedExpressionId && _nmEntry(after.eval instanceof Map ? after.eval : [:], expectedExpressionId) == row.expression)
        boolean waitsVerified = waitIds == null || (action instanceof Map && action.wait instanceof List && (action.wait).collect((this.&_bat529Cb_ba52df93_18))== waitIds)
        boolean conditionVerified = conditionId == null || (action instanceof Map && action.cond?.toString() == conditionId)
        Map echoed = _nrSettings(ruleId, (settings).collect((this.&_bat529Cb_ba52df93_8)))
        boolean persisted = (settings).every(((this.&_bat529Cb_ba52df93_92).curry([echoed] as Object[])))
        if (!(listed && ordered && compiled && persisted && expressionVerified && waitsVerified && conditionVerified)) return reject.call([error: 'Native action compilation or input verification failed', settingsVerified: persisted, expressionVerified: expressionVerified, waitsVerified: waitsVerified, conditionVerified: conditionVerified])
        if (!_nmActionLabel(ruleId, id)) return reject.call([error: 'Native action label is missing or broken'])
        [success: true, id: id, action: action, partial: false, settingsVerified: true, expressionVerified: true, waitsVerified: true, conditionVerified: true]
    } catch (Exception failure) {
        mcpLogError('rm-native', "Native action change failed for rule ${ruleId}: ${failure.message}".toString(), failure)
        reject.call([error: failure.message ?: 'Native action authoring failed'])
    }
}

private Map _nmCompileWait(Long ruleId, String actionId, List events, boolean editing) {
    String key = ".${actionId}".toString()
    List settings = []
    List eventIds = []
    for (int position = 0; position < events.size(); position++) {
        Map fresh = _nrState(ruleId)
        def allocated = fresh.waitNdx instanceof Map ? fresh.waitNdx.get(key) : null
        if (!(allocated instanceof List) || allocated.size() <= position) return (_bat529EmbeddedCatalogRead(['["m",[["success",false],["error","Native wait event allocation failed"]]]']) as Map)
        String eventId = _nmId(allocated.get(position))
        if (eventIds.contains(eventId)) return (_bat529EmbeddedCatalogRead(['["m",[["success",false],["error","Native wait event identity was repeated"]]]']) as Map)
        eventIds << eventId
        Map event = events.get(position)
        List fields = [[name: "tCapab-${eventId}".toString(), type: 'enum', value: event.capability]] + _nmFields(event.get('fields') as List, "-${eventId}".toString())
        Closure render = { unused -> _nrHandle(ruleId).doActPage() }
        Map inputValidation = _nmWriteExposed(ruleId, fields, [fields.get(0).name], render.call(), render)
        settings.addAll(fields)
        if (inputValidation.success != true) return [success: false, error: inputValidation.error, fieldErrors: inputValidation.fieldErrors]
        if (!_nmWaitDuration(ruleId, eventId)) return [success: false, error: 'Native wait duration did not compile', eventId: eventId]
        if (!editing) {
            _nrHandle(ruleId).appButtonHandler('hasAll')
            _nrHandle(ruleId).doActPage()
            Map completed = _nrState(ruleId)
            def done = completed.waitDone instanceof Map ? completed.waitDone.get(key) : null
            if (!(done instanceof Map) || done.get(eventId) != true) return [success: false, error: 'Native wait event did not complete', eventId: eventId]
            if (position + 1 < events.size()) {
                _nrStatePatch(ruleId, [anotherWait: true])
                _nrHandle(ruleId).doActPage()
            }
        }
    }
    _nrStatePatch(ruleId, [doneWaitEvents: true])
    _nrHandle(ruleId).doActPage()
    [success: true, eventIds: eventIds, settings: settings]
}

private boolean _nmWaitDuration(Long ruleId, String eventId) {
    String suffix = "-${eventId}".toString()
    Map values = _nrSettings(ruleId, ["stays${suffix}".toString(), "SHours${suffix}".toString(), "SMins${suffix}".toString(), "SSecs${suffix}".toString()])
    if (_nrComparableSetting('bool', values.get("stays${suffix}".toString())?.value) != 'true') return true
    Map nativeState = _nrState(ruleId)
    List expected = []
    for (String component in ['SHours', 'SMins', 'SSecs']) {
        def value = values.get("${component}${suffix}".toString())?.value
        if (value != null && !value.toString().isBigDecimal()) return false
        BigDecimal amount = value == null ? 0G : new BigDecimal(value.toString())
        if (amount < 0G) return false
        expected << amount
        if (value != null) {
            def derived = nativeState.get("${component}${suffix}".toString())
            if (derived == null || !derived.toString().isBigDecimal() || new BigDecimal(derived.toString()) != amount) return false
        }
    }
    def formatted = nativeState.get("hhmmss${suffix}".toString())
    if (!(formatted instanceof String)) return false
    List parts = formatted.tokenize(':')
    parts.size() == 3 && (parts).every((this.&_bat529Cb_ba52df93_11))&& (parts).collect((this.&_bat529Cb_ba52df93_16))== expected
}

Map _nmAddTerminator(Long ruleId, String subtype) {
    if (!ND_TERMINATOR_METHODS.containsKey(subtype)) throw new IllegalArgumentException('Unsupported structural action')
    Map before = _nrState(ruleId)
    String id = _nmId(before.actNdx)
    List priorOrder = before.actionList instanceof List ? (before.actionList).collect((this.&_bat529Cb_ba52df93_36)): []
    _nrHandle(ruleId).addAction(subtype)
    Map after = _nrState(ruleId)
    def action = _nmEntry(after.actions instanceof Map ? after.actions : [:], id)
    boolean verified = action instanceof Map && action.method == ND_TERMINATOR_METHODS.get(subtype) && after.actionList instanceof List && (after.actionList).collect((this.&_bat529Cb_ba52df93_28))== priorOrder + [id]
    [success: verified, id: id, action: action, partial: !verified, verificationNeeded: !verified]
}

Map _nmValidateStructure(Long ruleId, Map renderedState = null) {
    if (renderedState == null) _nrPage(ruleId, _nrRulePages(ruleId).actions)
    Map fresh = renderedState == null ? _nrState(ruleId) : renderedState
    List issues = []
    if (fresh.inIf == true) issues << 'An IF block remains open'
    if (fresh.inRepeat == true) issues << 'A repeat block remains open'
    [success: issues.isEmpty(), structuralIssues: issues, nativeFlags: [inIf: fresh.inIf, inRepeat: fresh.inRepeat]]
}

private Map _nmSettingsInventory(Long ruleId) {
    def response = hubInternalGet("/installedapp/settings/${ruleId}")
    def decoded = response instanceof Map ? response : new groovy.json.JsonSlurper().parseText(response?.toString() ?: '{}')
    if (!(decoded instanceof Map) || !(decoded.settings instanceof List)) throw new IllegalStateException('Native setting inventory is unavailable')
    Map inventory = [:]
    (decoded.settings).each(((this.&_bat529Cb_ba52df93_100).curry([inventory] as Object[])))
    inventory
}

Map _nmDeleteRow(Long ruleId, String part, Object rowId) {
    if (!(part in ['action', 'condition', 'trigger'])) throw new IllegalArgumentException('Unsupported native row collection')
    String id = _nmId(rowId)
    Map before = new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(_nrState(ruleId))) as Map
    String collection = part == 'action' ? 'actions' : (part == 'trigger' ? 'capabstrue' : 'capabsfalse')
    Map rows = before.get(collection) instanceof Map ? [:] + before.get(collection) : [:]
    if (!(rows.keySet()).any(((this.&_bat529Cb_ba52df93_23).curry([id] as Object[])))) throw new IllegalArgumentException('Native row does not exist')
    if (part == 'condition') {
        // Rule Machine keeps the eval entry of a deleted IF; only the Required Expression and live actions' expressions count.
        Set liveExpressionIds = (['0'] + (before.actions instanceof Map ? ((before.actions.values()).findAll((this.&_bat529Cb_ba52df93_69))).collect((this.&_bat529Cb_ba52df93_82)): [])) as Set
        boolean referenced = before.eval instanceof Map && (before.eval).any(((this.&_bat529Cb_ba52df93_90).curry([id, liveExpressionIds] as Object[])))
        referenced = referenced || (before.predCapabs instanceof List && (before.predCapabs).any(((this.&_bat529Cb_ba52df93_38).curry([id] as Object[]))))
        referenced = referenced || (before.actions instanceof Map && (before.actions.values()).any(((this.&_bat529Cb_ba52df93_73).curry([id] as Object[]))))
        if (referenced) throw new IllegalArgumentException('Referenced native conditions must be detached before deletion')
    }
    List oldOrder = before.actionList instanceof List ? (before.actionList).collect((this.&_bat529Cb_ba52df93_34)): []
    Map settingsBefore = new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(_nmSettingsInventory(ruleId))) as Map
    if (part == 'condition' && (settingsBefore).any(((this.&_bat529Cb_ba52df93_80).curry([id] as Object[])))) throw new IllegalArgumentException('Referenced trigger conditions must be detached before deletion')
    List requiredRemoved = part == 'action' ? (settingsBefore.keySet()).findAll(((this.&_bat529Cb_ba52df93_67).curry([id] as Object[]))).toList() :
        [part == 'trigger' ? "tCapab${id}".toString() : "rCapab_${id}".toString()]
    if (part == 'action') {
        _nrStatePatch(ruleId, [delAct: id])
        _nrPage(ruleId, _nrRulePages(ruleId).actions)
    } else {
        _nrStatePatch(ruleId, [deleteCon: id.toInteger()])
        if (part == 'trigger') _nrHandle(ruleId).selectTriggers()
        else _nrHandle(ruleId).selectConditions([pred: false])
    }
    Map after = _nrState(ruleId)
    Map settingsAfter = _nmSettingsInventory(ruleId)
    Map remaining = after.get(collection) instanceof Map ? after.get(collection) : [:]
    boolean deleted = !(remaining.keySet()).any(((this.&_bat529Cb_ba52df93_42).curry([id] as Object[])))
    Map expectedRows = (rows).findAll(((this.&_bat529Cb_ba52df93_54).curry([id] as Object[])))
    Closure rowPayloads = { Map source ->
        (source).collectEntries(((this.&_bat529Cb_ba52df93_97).curry([part] as Object[])))
    }
    boolean preserved = rowPayloads.call(remaining) == rowPayloads.call(expectedRows)
    List expectedOrder = part == 'action' ? (oldOrder).findAll(((this.&_bat529Cb_ba52df93_9).curry([id] as Object[]))): oldOrder
    boolean ordered = (after.actionList instanceof List ? (after.actionList).collect((this.&_bat529Cb_ba52df93_31)): []) == expectedOrder
    boolean cleaned = (requiredRemoved).every(((this.&_bat529Cb_ba52df93_59).curry([settingsAfter] as Object[])))
    boolean callbackConsumed = after.get(part == 'action' ? 'delAct' : 'deleteCon') == null
    List removedNames = (settingsBefore.keySet()).findAll(((this.&_bat529Cb_ba52df93_65).curry([settingsAfter] as Object[]))).toList()
    boolean settingsPreserved = (settingsAfter).every(((this.&_bat529Cb_ba52df93_85).curry([settingsBefore] as Object[])))
    boolean referencesPreserved
    if (part == 'action') {
        List expressions = ['0'] + ((expectedRows.values()).findAll((this.&_bat529Cb_ba52df93_68))).collect((this.&_bat529Cb_ba52df93_79))
        referencesPreserved = (expressions.unique()).every(((this.&_bat529Cb_ba52df93_95).curry([after, before] as Object[])))&& after.predCapabs == before.predCapabs
    } else {
        referencesPreserved = (['eval', 'predCapabs', 'actions', part == 'trigger' ? 'capabsfalse' : 'capabstrue']).every(((this.&_bat529Cb_ba52df93_88).curry([after, before] as Object[])))
    }
    boolean verified = deleted && preserved && ordered && cleaned && settingsPreserved && callbackConsumed && referencesPreserved
    [success: verified, partial: !verified, deleted: deleted, part: part, id: id,
     settingsVerified: cleaned && settingsPreserved, rowsPreserved: preserved, referencesPreserved: referencesPreserved, callbackConsumed: callbackConsumed, removedSettings: removedNames, remainingSettingCount: settingsAfter.size(), verificationNeeded: !verified]
}

Map _nmOrderActions(Long ruleId, List ids) {
    if (ids == null) throw new IllegalArgumentException('A complete action order is required')
    List requested = (ids).collect((this.&_bat529Cb_ba52df93_1))
    Map before = _nrState(ruleId)
    if (!(before.actionList instanceof List) || !(before.actions instanceof Map)) throw new IllegalStateException('Native action order is unavailable')
    List original = (before.actionList).collect((this.&_bat529Cb_ba52df93_27))
    List originalActionList = new ArrayList(before.actionList)
    List originalKeys = (before.actions.keySet()).collect((this.&_bat529Cb_ba52df93_46)).sort()
    if (requested.unique(false).size() != requested.size() || original.unique(false).size() != original.size() || requested.sort(false) != original.sort(false)) throw new IllegalArgumentException('Action order must be an exact permutation of existing action IDs')
    if ((requested).any(((this.&_bat529Cb_ba52df93_64).curry([before] as Object[])))) throw new IllegalArgumentException('Action order references an unavailable native action')
    Map contracts = [:]
    (original).each(((this.&_bat529Cb_ba52df93_96).curry([before, contracts] as Object[])))
    List ordered = (requested).collect(((this.&_bat529Cb_ba52df93_70).curry([before] as Object[])))
    Closure actionsHold = { Map current ->
        current.actions instanceof Map && (current.actions.keySet()).collect((this.&_bat529Cb_ba52df93_50)).sort() == originalKeys && contracts.every { id, contract ->
            def action = _nmEntry(current.actions, id)
            action instanceof Map && action.method == contract.method && action.cond == contract.cond && action.rule == contract.rule && action.wait == contract.wait
        }
    }
    _nmWriteActionOrder(ruleId, ordered)
    Map after = _nrState(ruleId)
    boolean orderVerified = after.actionList instanceof List && (after.actionList).collect((this.&_bat529Cb_ba52df93_30))== requested
    boolean retained = actionsHold.call(after)
    Map structure = _nmValidateStructure(ruleId, after)
    Map checks = [orderVerified: orderVerified, actionsPreserved: retained, ids: requested, structuralIssues: structure.structuralIssues]
    if (orderVerified && retained && structure.success) return [success: true, partial: false] + checks + [verificationNeeded: false]
    _nmWriteActionOrder(ruleId, originalActionList)
    Map restored = _nrState(ruleId)
    boolean rolledBack = restored.actionList instanceof List && (restored.actionList).collect((this.&_bat529Cb_ba52df93_41))== original
    String outcome = rolledBack ? 'the original order was restored' : 'the original order could NOT be restored'
    [success: false, partial: !rolledBack, rolledBack: rolledBack,
     error: "Action order did not verify (order:${orderVerified}, actions preserved:${retained}, structure:${structure.structuralIssues}); ${outcome}".toString()] + checks + [verificationNeeded: true]
}

private void _nmWriteActionOrder(Long ruleId, List actionList) {
    _nrStatePatch(ruleId, [actionList: actionList])
    _nrPage(ruleId, _nrRulePages(ruleId).actions)
    _nrHandle(ruleId).markIfs()
}


def _bat529Cb_ba52df93_1(def it) { _nmId(it) 
}

def _bat529Cb_ba52df93_2(def it) { it.name 
}

def _bat529Cb_ba52df93_3(def it) { it.name 
}

def _bat529Cb_ba52df93_4(def it) { it.name 
}

def _bat529Cb_ba52df93_5(def it) { it.name 
}

def _bat529Cb_ba52df93_6(def it) { it.name 
}

def _bat529Cb_ba52df93_7(def it) { it.name 
}

def _bat529Cb_ba52df93_8(def it) { it.name 
}

def _bat529Cb_ba52df93_9(def id, def it) { it != id 
}

def _bat529Cb_ba52df93_10(def it) { it.name 
}

def _bat529Cb_ba52df93_11(def it) { it.isBigDecimal() 
}

def _bat529Cb_ba52df93_12(def it) { it.toString() 
}

def _bat529Cb_ba52df93_13(def selector, def it) { it.name == selector 
}

def _bat529Cb_ba52df93_14(def it) { it.name 
}

def _bat529Cb_ba52df93_15(def row, def it) { it.field == row.name 
}

def _bat529Cb_ba52df93_16(def it) { new BigDecimal(it) 
}

def _bat529Cb_ba52df93_17(def it) { it.name == 'tCapab-1' 
}

def _bat529Cb_ba52df93_18(def it) { it.toString() 
}

def _bat529Cb_ba52df93_19(def it) { it.name 
}

def _bat529Cb_ba52df93_20(def selectors, def it) { it.name in selectors 
}

def _bat529Cb_ba52df93_21(def format, def it) { !format.containsKey(it) 
}

def _bat529Cb_ba52df93_22(def selectors, def it) { it.name in selectors 
}

def _bat529Cb_ba52df93_23(def id, def it) { it.toString() == id 
}

def _bat529Cb_ba52df93_24(def baseline, def it) { !baseline.containsKey(it) 
}

def _bat529Cb_ba52df93_25(def id, def it) { it.toString() == id 
}

def _bat529Cb_ba52df93_26(def it) { it.toString() 
}

def _bat529Cb_ba52df93_27(def it) { it.toString() 
}

def _bat529Cb_ba52df93_28(def it) { it.toString() 
}

def _bat529Cb_ba52df93_29(def it) { it.toString() 
}

def _bat529Cb_ba52df93_30(def it) { it.toString() 
}

def _bat529Cb_ba52df93_31(def it) { it.toString() 
}

def _bat529Cb_ba52df93_32(def it) { it.toString().isInteger() 
}

def _bat529Cb_ba52df93_33(def keys, def key, def value) { key in keys 
}

def _bat529Cb_ba52df93_33(def keys, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_33(keys, *__bat529Tuple)
}

def _bat529Cb_ba52df93_34(def it) { it.toString() 
}

def _bat529Cb_ba52df93_35(def it) { it.toString() 
}

def _bat529Cb_ba52df93_36(def it) { it.toString() 
}

def _bat529Cb_ba52df93_37(def id, def it) { it.toString() == id 
}

def _bat529Cb_ba52df93_38(def id, def it) { it.toString() == id 
}

def _bat529Cb_ba52df93_39(def id, def it) { it?.toString() == id 
}

def _bat529Cb_ba52df93_40(def keys, def key, def value) { key in keys 
}

def _bat529Cb_ba52df93_40(def keys, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_40(keys, *__bat529Tuple)
}

def _bat529Cb_ba52df93_41(def it) { it.toString() 
}

def _bat529Cb_ba52df93_42(def id, def it) { it.toString() == id 
}

def _bat529Cb_ba52df93_43(def id, def it) { it.toString() == id 
}

def _bat529Cb_ba52df93_44(def id, def token) { token?.toString() == id 
}

def _bat529Cb_ba52df93_45(def target, def it) { it.toString() == target 
}

def _bat529Cb_ba52df93_46(def it) { it.toString() 
}

def _bat529Cb_ba52df93_47(def id, def it) { it.toString() == id 
}

def _bat529Cb_ba52df93_48(def id, def it) { it.toString() == id 
}

def _bat529Cb_ba52df93_49(def id, def it) { it.toString() == id 
}

def _bat529Cb_ba52df93_50(def it) { it.toString() 
}

def _bat529Cb_ba52df93_51(def target, def it) { it.toString() == target 
}

def _bat529Cb_ba52df93_52(def target, def it) { it.toString() == target 
}

def _bat529Cb_ba52df93_53(def name, def ignored) { name != 'quick' 
}

def _bat529Cb_ba52df93_53(List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_53(*__bat529Tuple)
}

def _bat529Cb_ba52df93_54(def id, def key, def value) { key.toString() != id 
}

def _bat529Cb_ba52df93_54(def id, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_54(id, *__bat529Tuple)
}

def _bat529Cb_ba52df93_55(def id, def key, def value) { key.toString() == id 
}

def _bat529Cb_ba52df93_55(def id, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_55(id, *__bat529Tuple)
}

def _bat529Cb_ba52df93_56(def target, def it) { it.toString() == target 
}

def _bat529Cb_ba52df93_57(def it) { !(it in ['name', 'type', 'value']) 
}

def _bat529Cb_ba52df93_58(def it) { it.toString() 
}

def _bat529Cb_ba52df93_59(def settingsAfter, def it) { !settingsAfter.containsKey(it) 
}

def _bat529Cb_ba52df93_60(def it) { !(it in ['capability', 'fields']) 
}

def _bat529Cb_ba52df93_61(def savedSettings, def it) { !savedSettings.containsKey(it) 
}

def _bat529Cb_ba52df93_62(def it) { !(it in ['id', 'capability', 'fields']) 
}

def _bat529Cb_ba52df93_63(def errors, def row) { !(errors).any(((this.&_bat529Cb_ba52df93_15).curry([row] as Object[])))
}

def _bat529Cb_ba52df93_64(def before, def it) { !(_nmEntry(before.actions, it) instanceof Map) 
}

def _bat529Cb_ba52df93_65(def settingsAfter, def it) { !settingsAfter.containsKey(it) 
}

def _bat529Cb_ba52df93_66(def it) { !it.toString().isLong() || it.toString().toLong() <= 0L 
}

def _bat529Cb_ba52df93_67(def id, def it) { it.endsWith(".${id}".toString()) 
}

def _bat529Cb_ba52df93_68(def it) { it instanceof Map && it.rule != null 
}

def _bat529Cb_ba52df93_69(def it) { it instanceof Map && it.rule != null 
}

def _bat529Cb_ba52df93_70(def before, def id) { (before.actionList).find(((this.&_bat529Cb_ba52df93_43).curry([id] as Object[])))
}

def _bat529Cb_ba52df93_71(def rollback, def it) { rollback.remove(it) 
}

def _bat529Cb_ba52df93_72(def ruleId, def page) { _nrPage(ruleId, page) 
}

def _bat529Cb_ba52df93_73(def id, def it) { it instanceof Map && it.cond?.toString() == id 
}

def _bat529Cb_ba52df93_74(def restored, def it) { restored.remove(it) 
}

def _bat529Cb_ba52df93_75(def it) { it.toString().toInteger() 
}

def _bat529Cb_ba52df93_76(def it) { it.name 
}

def _bat529Cb_ba52df93_77(def id, def it) { it.name in ["actType.${id}".toString(), "actSubType.${id}".toString()] 
}

def _bat529Cb_ba52df93_78(def it) { !(it instanceof String || it instanceof Number || it instanceof Boolean) 
}

def _bat529Cb_ba52df93_79(def it) { it.rule.toString() 
}

def _bat529Cb_ba52df93_80(def id, def name, def setting) { name.startsWith('condTrig.') && setting.value?.toString() == id 
}

def _bat529Cb_ba52df93_80(def id, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_80(id, *__bat529Tuple)
}

def _bat529Cb_ba52df93_81(def it) { !(it in (_bat529EmbeddedCatalogRead(['["l",["id","type","subtype","fields","expression","events","condition"]]']) as List)) 
}

def _bat529Cb_ba52df93_82(def it) { it.rule.toString() 
}

def _bat529Cb_ba52df93_83(def inputs, def child) { if (child instanceof Map || child instanceof List) _nmCollectInputs(child, inputs) 
}

def _bat529Cb_ba52df93_84(def baseline, def key) { !(baseline.eval instanceof Map) || !baseline.eval.containsKey(key) 
}

def _bat529Cb_ba52df93_85(def settingsBefore, def name, def setting) { settingsBefore.containsKey(name) && settingsBefore.get(name) == setting 
}

def _bat529Cb_ba52df93_85(def settingsBefore, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_85(settingsBefore, *__bat529Tuple)
}

def _bat529Cb_ba52df93_86(def baseline, def fresh, def key) { fresh.containsKey(key) == baseline.containsKey(key) && fresh.get(key) == baseline.get(key) 
}

def _bat529Cb_ba52df93_87(def ruleId, def name) { _nrHandle(ruleId).removeSetting(name) 
}

def _bat529Cb_ba52df93_88(def after, def before, def it) { after.get(it) == before.get(it) 
}

def _bat529Cb_ba52df93_89(def it) { it.toString() 
}

def _bat529Cb_ba52df93_90(def id, def liveExpressionIds, def key, def tokens) { key.toString() in liveExpressionIds && tokens instanceof List && (tokens).any(((this.&_bat529Cb_ba52df93_44).curry([id] as Object[])))
}

def _bat529Cb_ba52df93_90(def id, def liveExpressionIds, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_90(id, liveExpressionIds, *__bat529Tuple)
}

def _bat529Cb_ba52df93_91(def echoed, def it) { echoed.get(it.name)?.type?.toString() == it.type && _nrComparableSetting(it.type, echoed.get(it.name)?.value) == _nrComparableSetting(it.type, it.value) 
}

def _bat529Cb_ba52df93_92(def echoed, def it) { echoed.get(it.name)?.type?.toString() == it.type && _nrComparableSetting(it.type, echoed.get(it.name)?.value) == _nrComparableSetting(it.type, it.value) 
}

def _bat529Cb_ba52df93_93(def actual, def original, def target) { (actual.keySet()).any(((this.&_bat529Cb_ba52df93_45).curry([target] as Object[])))== (original.keySet()).any(((this.&_bat529Cb_ba52df93_51).curry([target] as Object[])))&& _nmEntry(actual, target) == _nmEntry(original, target) 
}

def _bat529Cb_ba52df93_94(def inputs, def node, def key) {
            def child = node.get(key)
            if (child instanceof Map || child instanceof List) _nmCollectInputs(child, inputs)
        
}

def _bat529Cb_ba52df93_95(def after, def before, def expression) {
            _nmEntry(after.eval instanceof Map ? after.eval : [:], expression) == _nmEntry(before.eval instanceof Map ? before.eval : [:], expression)
        
}

def _bat529Cb_ba52df93_96(def before, def contracts, def id) {
        Map action = _nmEntry(before.actions, id)
        contracts.put(id, [method: action.method, cond: action.cond, rule: action.rule, wait: action.wait instanceof List ? new ArrayList(action.wait) : action.wait])
    
}

def _bat529Cb_ba52df93_97(def part, def key, def value) {
            // Native rendering derives quick; all other action fields remain authoritative.
            [(key.toString()): part == 'action' && value instanceof Map ? (value).findAll((this.&_bat529Cb_ba52df93_53)): value]
        
}

def _bat529Cb_ba52df93_97(def part, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_97(part, *__bat529Tuple)
}

def _bat529Cb_ba52df93_98(def original, def restored, def target) {
                ((restored.keySet()).findAll(((this.&_bat529Cb_ba52df93_56).curry([target] as Object[])))).each(((this.&_bat529Cb_ba52df93_74).curry([restored] as Object[])))
                def priorKey = (original.keySet()).find(((this.&_bat529Cb_ba52df93_52).curry([target] as Object[])))
                if (priorKey != null) restored.put(priorKey, original.get(priorKey))
            
}

def _bat529Cb_ba52df93_99(def baseline, def before, def key) {
        if (before.get(key) instanceof Map || before.get(key) instanceof List) baseline.put(key, new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(before.get(key))))
    
}

def _bat529Cb_ba52df93_100(def inventory, def row) {
        if (!(row instanceof Map) || !(row.name instanceof String) || inventory.containsKey(row.name)) throw new IllegalStateException('Native setting inventory contains invalid names')
        inventory.put(row.name, [type: row.type, value: row.value, multiple: row.multiple, deviceList: row.deviceList])
    
}

def _bat529Cb_ba52df93_101(def baseline, def fresh, def name, def targets) {
            Map actual = fresh.get(name) instanceof Map ? fresh.get(name) : [:]
            Map original = baseline.get(name) instanceof Map ? baseline.get(name) : [:]
            (targets).every(((this.&_bat529Cb_ba52df93_93).curry([actual, original] as Object[])))
        
}

def _bat529Cb_ba52df93_101(def baseline, def fresh, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_101(baseline, fresh, *__bat529Tuple)
}

def _bat529Cb_ba52df93_102(def event) {
            if (!(event instanceof Map) || (event.keySet()).any((this.&_bat529Cb_ba52df93_60))|| !(event.capability instanceof String) || !event.capability.trim()) throw new IllegalArgumentException('Wait events require a capability and typed fields')
            List preview = _nmFields(event.get('fields') as List, '-1')
            if ((preview).any((this.&_bat529Cb_ba52df93_17))|| (preview).collect((this.&_bat529Cb_ba52df93_6)).unique().size() != preview.size()) throw new IllegalArgumentException('Wait fields must not repeat selectors or native inputs')
        
}

def _bat529Cb_ba52df93_103(def baseline, def current, def remove, def values, def name, def targets) {
            Map original = baseline.get(name) instanceof Map ? baseline.get(name) : [:]
            Map restored = current.get(name) instanceof Map ? [:] + current.get(name) : [:]
            (targets).each(((this.&_bat529Cb_ba52df93_98).curry([original, restored] as Object[])))
            if (restored || baseline.containsKey(name)) values.put(name, restored)
            else remove << name
        
}

def _bat529Cb_ba52df93_103(def baseline, def current, def remove, def values, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_103(baseline, current, remove, values, *__bat529Tuple)
}

def _bat529Cb_ba52df93_104(def current, def restore, def ruleId, def name, def original) {
        if (current.get(name) != original) {
            if (current.get(name)?.type != original.type) _nrHandle(ruleId).removeSetting(name)
            def value = original.value
            if (original.type == 'capability' || original.type?.startsWith('capability.')) {
                List devices = original.deviceList instanceof Map ? (original.deviceList.keySet()).collect((this.&_bat529Cb_ba52df93_58)): []
                value = original.multiple == true ? devices : (devices ? devices.get(0) : null)
            } else if (original.multiple == true && value instanceof String) value = new groovy.json.JsonSlurper().parseText(value)
            if (value == null) throw new IllegalStateException("Cannot restore empty native setting '${name}'")
            restore << [name: name, type: original.type, value: value]
        }
    
}

def _bat529Cb_ba52df93_104(def current, def restore, def ruleId, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_ba52df93_104(current, restore, ruleId, *__bat529Tuple)
}

def _bat529Cb_ba52df93_105(def id, def row) {
        if (!(row instanceof Map) || !(row.name instanceof String) || !row.name.contains('#') || !(row.type instanceof String) || !row.containsKey('value') || (row.keySet()).any((this.&_bat529Cb_ba52df93_57))) {
            throw new IllegalArgumentException('Fields require native name templates containing #, type and value')
        }
        boolean device = row.type == 'capability' || row.type.startsWith('capability.')
        if (!(row.type in (_bat529EmbeddedCatalogRead(['["l",["text","textarea","password","enum","mode","bool","number","decimal","time"]]']) as List)) && !device) throw new IllegalArgumentException('Unsupported native setting type')
        if (row.value == null) throw new IllegalArgumentException('Null native setting values are unsupported')
        if (row.value instanceof List) {
            if (!device && !(row.type in ['enum', 'mode'])) throw new IllegalArgumentException('Setting lists require enum, mode or capability type')
            if ((row.value).any((this.&_bat529Cb_ba52df93_78))) throw new IllegalArgumentException('Setting lists require scalar values')
        } else if (!(row.value instanceof String || row.value instanceof Number || row.value instanceof Boolean)) throw new IllegalArgumentException('Native settings require scalar values')
        if (device) {
            List ids = row.value instanceof List ? row.value : [row.value]
            if ((ids).any((this.&_bat529Cb_ba52df93_66))) throw new IllegalArgumentException('Capability settings require positive device IDs')
        }
        [name: row.name.replace('#', id), type: row.type, value: row.value]
    
}

def _bat529Cb_ba52df93_106(def errors, def inputs, def page, def row) {
        def descriptor = inputs.get(row.name)
        String reason = null
        if (!(page instanceof Map)) reason = 'native page did not return input descriptors'
        else if (!(descriptor instanceof Map)) reason = 'input is not exposed by the native page; dependent-field authoring requires an intermediate native render'
        else {
            if (descriptor.type != row.type) reason = "expected native type '${descriptor.type}', received '${row.type}'".toString()
            else if (descriptor.containsKey('multiple') && !(descriptor.multiple instanceof Boolean)) reason = 'native input cardinality metadata is unsupported'
            else if (!descriptor.containsKey('multiple') && row.value instanceof List) reason = 'native list cardinality is not exposed'
            else if (descriptor.containsKey('multiple') && descriptor.multiple != (row.value instanceof List)) reason = descriptor.multiple ? 'native input requires a list' : 'native input requires a scalar'
            else if (descriptor.required == true && (row.value == null || (row.value instanceof List && row.value.isEmpty()) || (row.value instanceof String && !row.value.trim()))) reason = 'required native input is empty'
            else if (descriptor.type in ['text', 'textarea', 'password', 'time'] && !(row.value instanceof String)) reason = 'native text input requires a string value'
            else {
                try { _nrValidateInput([:] + descriptor + [multiple: descriptor.multiple == true], row.value) }
                catch (Exception failure) { reason = failure.message ?: 'native descriptor validation failed' }
            }
        }
        if (reason != null) errors << [field: row.name, error: "Native field '${row.name}': ${reason}".toString()]
    
}
