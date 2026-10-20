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
    List result = fields.collect { row ->
        if (!(row instanceof Map) || !(row.name instanceof String) || !row.name.contains('#') || !(row.type instanceof String) || !row.containsKey('value') || row.keySet().any { !(it in ['name', 'type', 'value']) }) {
            throw new IllegalArgumentException('Fields require native name templates containing #, type and value')
        }
        boolean device = row.type == 'capability' || row.type.startsWith('capability.')
        if (!(row.type in ['text', 'textarea', 'password', 'enum', 'mode', 'bool', 'number', 'decimal', 'time']) && !device) throw new IllegalArgumentException('Unsupported native setting type')
        if (row.value == null) throw new IllegalArgumentException('Null native setting values are unsupported')
        if (row.value instanceof List) {
            if (!device && !(row.type in ['enum', 'mode'])) throw new IllegalArgumentException('Setting lists require enum, mode or capability type')
            if (row.value.any { !(it instanceof String || it instanceof Number || it instanceof Boolean) }) throw new IllegalArgumentException('Setting lists require scalar values')
        } else if (!(row.value instanceof String || row.value instanceof Number || row.value instanceof Boolean)) throw new IllegalArgumentException('Native settings require scalar values')
        if (device) {
            List ids = row.value instanceof List ? row.value : [row.value]
            if (ids.any { !it.toString().isLong() || it.toString().toLong() <= 0L }) throw new IllegalArgumentException('Capability settings require positive device IDs')
        }
        [name: row.name.replace('#', id), type: row.type, value: row.value]
    }
    if (result.collect { it.name }.unique().size() != result.size()) throw new IllegalArgumentException('Native inputs must not be repeated')
    result
}

private void _nmCollectInputs(Object node, Map inputs) {
    if (node instanceof Map) {
        if (node.element == 'input' && node.name instanceof String && node.type instanceof String && !inputs.containsKey(node.name)) inputs.put(node.name, node)
        ['sections', 'input', 'body', 'children'].each { key ->
            def child = node.get(key)
            if (child instanceof Map || child instanceof List) _nmCollectInputs(child, inputs)
        }
    } else if (node instanceof List) node.each { child -> if (child instanceof Map || child instanceof List) _nmCollectInputs(child, inputs) }
}

Map _nmValidateInputs(Object page, List fields) {
    Map inputs = [:]
    if (page instanceof Map) _nmCollectInputs(page, inputs)
    List errors = []
    fields.each { row ->
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
    [success: errors.isEmpty(), error: errors ? errors.get(0).error : null, fieldErrors: errors, verifiedFields: fields.findAll { row -> !errors.any { it.field == row.name } }.collect { it.name }]
}

private Map _nmWriteExposed(Long ruleId, List fields, List selectors, Object page, Closure render) {
    if (fields.collect { it.name }.unique().size() != fields.size()) throw new IllegalArgumentException('Native inputs must not be repeated')
    List pending = new ArrayList(fields)
    while (pending) {
        Map inputs = [:]
        if (page instanceof Map) _nmCollectInputs(page, inputs)
        List selectorRows = pending.findAll { it.name in selectors }
        if (selectorRows) {
            Map saved = _nrSettings(ruleId, selectorRows.collect { it.name })
            for (Object row in selectorRows) {
                if (!inputs.containsKey(row.name)) {
                    def prior = saved.get(row.name)
                    if (prior?.type == row.type && _nrComparableSetting(row.type, prior.value) == _nrComparableSetting(row.type, row.value)) pending.remove(row)
                }
            }
            selectorRows = pending.findAll { it.name in selectors }
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

Map _nmSetRequired(Long ruleId, List tokens) {
    Map before = _nrState(ruleId)
    _nmTokens(tokens, before.capabsfalse instanceof Map ? before.capabsfalse : [:])
    Map enabled = _nrWriteSettings(ruleId, [[name: 'useST', type: 'bool', value: true]])
    if (enabled.success != true) return [success: false, partial: enabled.partial ?: false, settings: enabled]
    _nrHandle(ruleId).STPage([:])
    Map expression = _nmSetExpression(ruleId, '0', tokens)
    if (expression.success != true) return [success: false, partial: true, expression: expression]
    List operands = tokens.findAll { it.toString().isInteger() }.collect { it.toString().toInteger() }.unique()
    Map fresh = _nrState(ruleId)
    Map first = fresh.firstR instanceof Map ? [:] + fresh.firstR : [:]
    first.put('0', false)
    _nrStatePatch(ruleId, [predCapabs: operands, firstR: first, hasRule: true])
    _nrHandle(ruleId).STPage([:])
    _nrStatePatch(ruleId, [doneST: true])
    _nrHandle(ruleId).STPage([:])
    Map pages = _nrRulePages(ruleId)
    [pages.primary, pages.actions].unique().each { page -> _nrPage(ruleId, page) }
    Map after = _nrState(ruleId)
    Map settings = _nrSettings(ruleId, ['useST'])
    boolean predicate = after.hasPredicate == true && _nmEntry(after.eval instanceof Map ? after.eval : [:], '0') == tokens
    boolean bound = after.predCapabs instanceof List && after.predCapabs.collect { it.toString() }.sort() == operands.collect { it.toString() }.sort()
    boolean persisted = settings.get('useST')?.type == 'bool' && _nrComparableSetting('bool', settings.get('useST')?.value) == 'true'
    [success: predicate && bound && persisted, partial: !(predicate && bound && persisted), expressionId: '0', tokens: tokens, predicateVerified: predicate && bound, settingsVerified: persisted, verificationNeeded: true]
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
    [success: verified, deleted: operation == 'delete' && verified, variable: after, verificationNeeded: true]
}

private void _nmRestoreSettings(Long ruleId, Map savedSettings) {
    Map current = _nmSettingsInventory(ruleId)
    current.keySet().findAll { !savedSettings.containsKey(it) }.each { name -> _nrHandle(ruleId).removeSetting(name) }
    List restore = []
    savedSettings.each { name, original ->
        if (current.get(name) != original) {
            if (current.get(name)?.type != original.type) _nrHandle(ruleId).removeSetting(name)
            def value = original.value
            if (original.type == 'capability' || original.type?.startsWith('capability.')) {
                List devices = original.deviceList instanceof Map ? original.deviceList.keySet().collect { it.toString() } : []
                value = original.multiple == true ? devices : (devices ? devices.get(0) : null)
            } else if (original.multiple == true && value instanceof String) value = new groovy.json.JsonSlurper().parseText(value)
            if (value == null) throw new IllegalStateException("Cannot restore empty native setting '${name}'")
            restore << [name: name, type: original.type, value: value]
        }
    }
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
    Map format = before.findAll { key, value -> key in keys }
    if (format.nestedLabel instanceof List) format.put('nestedLabel', new ArrayList(format.nestedLabel))
    def label
    try { label = _nrHandle(ruleId).thisActionLabel(id) }
    finally { _nrStatePatch(ruleId, format, keys.findAll { !format.containsKey(it) }) }
    label instanceof String && label.trim() && !(label =~ /(?is)<span\b[^>]*color\s*:\s*red[^>]*>[^<]*\*\*Broken [^*]+\*\*/)
}

private Map _nmRejectEdit(Long ruleId, String id, Map baseline, Map savedSettings, List keys, Map entries, Closure cancel, Map failure) {
    String cleanupError = null
    boolean verified = false
    try {
        boolean consumed = cancel.call() == true
        _nmRestoreSettings(ruleId, savedSettings)
        Map current = _nrState(ruleId)
        Map values = baseline.findAll { key, value -> key in keys }
        List remove = keys.findAll { !baseline.containsKey(it) }
        entries.each { name, targets ->
            Map original = baseline.get(name) instanceof Map ? baseline.get(name) : [:]
            Map restored = current.get(name) instanceof Map ? [:] + current.get(name) : [:]
            targets.each { target ->
                restored.keySet().findAll { it.toString() == target }.each { restored.remove(it) }
                def priorKey = original.keySet().find { it.toString() == target }
                if (priorKey != null) restored.put(priorKey, original.get(priorKey))
            }
            if (restored || baseline.containsKey(name)) values.put(name, restored)
            else remove << name
        }
        _nrStatePatch(ruleId, values, remove)
        _nmRefreshHealth(ruleId, baseline)
        Map fresh = _nrState(ruleId)
        verified = consumed && _nmSettingsInventory(ruleId) == savedSettings && keys.every { key -> fresh.containsKey(key) == baseline.containsKey(key) && fresh.get(key) == baseline.get(key) } && entries.every { name, targets ->
            Map actual = fresh.get(name) instanceof Map ? fresh.get(name) : [:]
            Map original = baseline.get(name) instanceof Map ? baseline.get(name) : [:]
            targets.every { target -> actual.keySet().any { it.toString() == target } == original.keySet().any { it.toString() == target } && _nmEntry(actual, target) == _nmEntry(original, target) }
        }
        if (!verified) cleanupError = 'Native row cancellation or restoration could not be verified'
    } catch (Exception cleanupFailure) { cleanupError = cleanupFailure.message ?: 'Native row cleanup failed' }
    failure + [success: false, id: id, error: failure.error ?: cleanupError ?: 'Native row authoring failed', fieldErrors: failure.fieldErrors ?: [], partial: !verified, rolledBack: verified,
     cleanupError: cleanupError, verificationNeeded: !verified]
}

private Map _nmRejectCondition(Long ruleId, boolean trigger, boolean editing, String id, Map baseline, Map savedSettings, List fields, Closure render, Map failure) {
    String collection = trigger ? 'capabstrue' : 'capabsfalse'
    List keys = ([collection, 'ndx', 'hasAll', 'moreCond', 'editCond', 'capabDone', 'cancelCapab', 'updatingTrig', 'hasConditions', "hhmmss${id}".toString()] + fields.collect { it.name }).unique()
    Closure cancel = { unused ->
        Map context = [hasAll: false, capabDone: false]
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
    if (current.eval instanceof Map) expressionIds.addAll(current.eval.keySet().findAll { key -> !(baseline.eval instanceof Map) || !baseline.eval.containsKey(key) }.collect { it.toString() })
    List keys = (['actions', 'actionList', 'capabsfalse', 'capabstrue', 'eval', 'predCapabs', 'isPredicate', 'hasPredicate', 'ndx', 'actNdx', 'ruleNdx', 'waitCondNdx',
                 'doActN', 'editAct', 'editIf', 'editToken', 'action', 'actionDone', 'actionCancel', 'hasDevice', 'hasCondition', 'hasCompleteRule',
                 'hasAll', 'hasRule', 'capabDone', 'capabActDone', 'inSimpleCond', 'simpleCond', 'inIf', 'inRepeat', 'hasWhileRule', 'needRuleButtons',
                 'needWaitButtons', 'needWaitDone', 'needWaitCancel', 'anotherWait', 'doneWaitEvents', 'moreCond', 'editCond'] + fields.collect { it.name }).unique()
    Closure cancel = { unused ->
        _nrHandle(ruleId).appButtonHandler('actionCancel')
        render.call()
        _nrState(ruleId).actionCancel != true
    }
    _nmRejectEdit(ruleId, id, baseline, savedSettings, keys,
        [firstR: expressionIds.unique(), waitNdx: [".${id}".toString()], waitDone: [".${id}".toString()]], cancel, failure)
}

Map _nmUpsertCondition(Long ruleId, boolean trigger, Map row) {
    if (row == null || row.keySet().any { !(it in ['id', 'capability', 'fields']) } || !(row.capability instanceof String) || !row.capability.trim()) throw new IllegalArgumentException('A capability and typed fields are required')
    Map before = _nrState(ruleId)
    String collection = trigger ? 'capabstrue' : 'capabsfalse'
    Map prior = before.get(collection) instanceof Map ? [:] + before.get(collection) : [:]
    boolean editing = row.containsKey('id')
    String id = _nmId(editing ? row.id : before.ndx)
    if (editing && !_nmEntry(prior, id)?.toString()?.trim()) throw new IllegalArgumentException('Native row does not exist')
    if (!editing && prior.keySet().any { it.toString() == id }) throw new IllegalArgumentException('Native allocation slot is already occupied')
    List fields = _nmFields(row.get('fields') as List, id)
    String selector = trigger ? "tCapab${id}".toString() : "rCapab_${id}".toString()
    if (fields.any { it.name == selector }) throw new IllegalArgumentException('Capability selector must not be repeated in fields')
    List settings = [[name: selector, type: 'enum', value: row.capability]] + fields
    Map savedSettings = _nmSettingsInventory(ruleId)
    Map baseline = [:] + before
    if (before.containsKey(collection)) baseline.put(collection, prior)
    Closure render = { unused -> trigger ? _nrHandle(ruleId).selectTriggers() : _nrHandle(ruleId).selectConditions([pred: false]) }
    try {
        Map context = [hasAll: false, capabDone: false]
        if (editing) context.editCond = id
        else { context.moreCond = true; context.ndx = id.toInteger() }
        _nrStatePatch(ruleId, context, editing ? ['moreCond'] : ['editCond'])
        Map inputValidation = _nmWriteExposed(ruleId, settings, [selector], render.call(), render)
        if (inputValidation.success != true) return _nmRejectCondition(ruleId, trigger, editing, id, baseline, savedSettings, settings, render, inputValidation)
        _nrStatePatch(ruleId, [hasAll: true])
        render.call()
        Map after = _nrState(ruleId)
        def label = _nmEntry(after.get(collection) instanceof Map ? after.get(collection) : [:], id)
        Map echoed = _nrSettings(ruleId, settings.collect { it.name })
        boolean persisted = settings.every { echoed.get(it.name)?.type?.toString() == it.type && _nrComparableSetting(it.type, echoed.get(it.name)?.value) == _nrComparableSetting(it.type, it.value) }
        boolean compiled = label instanceof String && label.trim() && !label.contains('null') && !label.contains('BROKEN')
        if (!(compiled && persisted)) return _nmRejectCondition(ruleId, trigger, editing, id, baseline, savedSettings, settings, render,
            [error: compiled ? 'Native input persistence failed' : 'Native row did not compile a valid label'])
        [success: true, id: id, label: label, partial: false, settingsVerified: true]
    } catch (Exception failure) {
        _nmRejectCondition(ruleId, trigger, editing, id, baseline, savedSettings, settings, render, [error: failure.message ?: 'Native row authoring failed'])
    }
}

Map _nmUpsertAction(Long ruleId, Map row) {
    if (row == null || row.keySet().any { !(it in ['id', 'type', 'subtype', 'fields', 'expression', 'events', 'condition']) } || !(row.type instanceof String) || !row.type.trim() || !(row.subtype instanceof String) || !row.subtype.trim()) throw new IllegalArgumentException('Action type, subtype and typed fields are required')
    boolean waitEvents = row.subtype == 'getWaitEvents'
    boolean waitRule = row.subtype == 'getWaitRule'
    if (row.subtype.toString().toLowerCase().contains('wait') && !waitEvents && !waitRule) throw new IllegalArgumentException('Unsupported native wait action')
    if (waitEvents) {
        if (!(row.events instanceof List) || row.events.empty || row.events.size() > 16) throw new IllegalArgumentException('Wait actions require between one and sixteen events')
        row.events.each { event ->
            if (!(event instanceof Map) || event.keySet().any { !(it in ['capability', 'fields']) } || !(event.capability instanceof String) || !event.capability.trim()) throw new IllegalArgumentException('Wait events require a capability and typed fields')
            List preview = _nmFields(event.get('fields') as List, '-1')
            if (preview.any { it.name == 'tCapab-1' } || preview.collect { it.name }.unique().size() != preview.size()) throw new IllegalArgumentException('Wait fields must not repeat selectors or native inputs')
        }
    } else if (row.containsKey('events')) throw new IllegalArgumentException('Only Wait for Events accepts event rows')
    Map before = _nrState(ruleId)
    List priorOrder = before.actionList instanceof List ? before.actionList.collect { it.toString() } : []
    boolean editing = row.containsKey('id')
    String id = _nmId(editing ? row.id : before.actNdx)
    def prior = _nmEntry(before.actions instanceof Map ? before.actions : [:], id)
    if (editing && !(prior instanceof Map)) throw new IllegalArgumentException('Native action does not exist')
    if (!editing && prior != null) throw new IllegalArgumentException('Native action allocation slot is already occupied')
    if (waitEvents && editing && (!(prior.wait instanceof List) || prior.wait.size() != row.events.size())) throw new IllegalArgumentException('Editing waits currently requires the existing event count')
    if (!editing && row.subtype in ['getIfThen', 'getElseIf', 'getWaitRule'] && !row.containsKey('expression')) throw new IllegalArgumentException('New expression actions require expression tokens')
    String expectedExpressionId = null
    if (row.containsKey('expression')) {
        if (!(row.subtype in ['getIfThen', 'getElseIf', 'getWaitRule'])) throw new IllegalArgumentException('Unsupported native expression action')
        _nmTokens(row.expression as List, before.capabsfalse instanceof Map ? before.capabsfalse : [:])
    }
    String conditionId = row.containsKey('condition') ? _nmId(row.condition) : (editing && prior.cond ? prior.cond.toString() : null)
    if (row.containsKey('condition')) {
        _nmTokens([conditionId], before.capabsfalse instanceof Map ? before.capabsfalse : [:])
        if (row.containsKey('expression') || row.type in ['condActs', 'repeatActs', 'delayActs']) throw new IllegalArgumentException('A simple condition requires an ordinary native action')
    }
    List fields = _nmFields(row.get('fields') as List, id)
    if (fields.any { it.name in ["actType.${id}".toString(), "actSubType.${id}".toString()] }) throw new IllegalArgumentException('Action selectors must not be repeated')
    List settings = [[name: "actType.${id}".toString(), type: 'enum', value: row.type], [name: "actSubType.${id}".toString(), type: 'enum', value: row.subtype]] + fields
    String editToken = 'N'
    if (editing) {
        boolean expressionAction = prior.method in ['getIfThen', 'getElseIfThen', 'getWhile', 'getUntil', 'getWaitRule']
        editToken = "${id}.${conditionId ?: 0}.${expressionAction}".toString()
    }
    Map savedSettings = _nmSettingsInventory(ruleId)
    Map baseline = [:] + before
    ['actions', 'actionList', 'capabsfalse', 'capabstrue', 'eval', 'predCapabs', 'firstR', 'waitNdx', 'waitDone'].each { key ->
        if (before.get(key) instanceof Map || before.get(key) instanceof List) baseline.put(key, new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(before.get(key))))
    }
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
            Map selected = _nmWriteExposed(ruleId, conditional, conditional.take(2).collect { it.name }, render.call(), render)
            if (selected.success != true) return reject.call(selected)
            _nrHandle(ruleId).appButtonHandler('hasAll')
            render.call()
        }
        Map inputValidation = _nmWriteExposed(ruleId, waitRule ? settings.take(2) : settings, settings.take(2).collect { it.name }, render.call(), render)
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
        boolean listed = after.actionList instanceof List && after.actionList.any { it.toString() == id }
        List expectedOrder = editing ? priorOrder : priorOrder + [id]
        boolean ordered = after.actionList instanceof List && after.actionList.collect { it.toString() } == expectedOrder
        boolean compiled = action instanceof Map && action.method == row.subtype
        boolean expressionVerified = expectedExpressionId == null || (action instanceof Map && action.rule?.toString() == expectedExpressionId && _nmEntry(after.eval instanceof Map ? after.eval : [:], expectedExpressionId) == row.expression)
        boolean waitsVerified = waitIds == null || (action instanceof Map && action.wait instanceof List && action.wait.collect { it.toString() } == waitIds)
        boolean conditionVerified = conditionId == null || (action instanceof Map && action.cond?.toString() == conditionId)
        Map echoed = _nrSettings(ruleId, settings.collect { it.name })
        boolean persisted = settings.every { echoed.get(it.name)?.type?.toString() == it.type && _nrComparableSetting(it.type, echoed.get(it.name)?.value) == _nrComparableSetting(it.type, it.value) }
        if (!(listed && ordered && compiled && persisted && expressionVerified && waitsVerified && conditionVerified)) return reject.call([error: 'Native action compilation or input verification failed', settingsVerified: persisted, expressionVerified: expressionVerified, waitsVerified: waitsVerified, conditionVerified: conditionVerified])
        if (!_nmActionLabel(ruleId, id)) return reject.call([error: 'Native action label is missing or broken'])
        [success: true, id: id, action: action, partial: false, settingsVerified: true, expressionVerified: true, waitsVerified: true, conditionVerified: true, verificationNeeded: true]
    } catch (Exception failure) { reject.call([error: failure.message ?: 'Native action authoring failed']) }
}

private Map _nmCompileWait(Long ruleId, String actionId, List events, boolean editing) {
    String key = ".${actionId}".toString()
    List settings = []
    List eventIds = []
    for (int position = 0; position < events.size(); position++) {
        Map fresh = _nrState(ruleId)
        def allocated = fresh.waitNdx instanceof Map ? fresh.waitNdx.get(key) : null
        if (!(allocated instanceof List) || allocated.size() <= position) return [success: false, error: 'Native wait event allocation failed']
        String eventId = _nmId(allocated.get(position))
        if (eventIds.contains(eventId)) return [success: false, error: 'Native wait event identity was repeated']
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
    parts.size() == 3 && parts.every { it.isBigDecimal() } && parts.collect { new BigDecimal(it) } == expected
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
    decoded.settings.each { row ->
        if (!(row instanceof Map) || !(row.name instanceof String) || inventory.containsKey(row.name)) throw new IllegalStateException('Native setting inventory contains invalid names')
        inventory.put(row.name, [type: row.type, value: row.value, multiple: row.multiple, deviceList: row.deviceList])
    }
    inventory
}

Map _nmDeleteRow(Long ruleId, String part, Object rowId) {
    if (!(part in ['action', 'condition', 'trigger'])) throw new IllegalArgumentException('Unsupported native row collection')
    String id = _nmId(rowId)
    Map before = _nrState(ruleId)
    String collection = part == 'action' ? 'actions' : (part == 'trigger' ? 'capabstrue' : 'capabsfalse')
    Map rows = before.get(collection) instanceof Map ? [:] + before.get(collection) : [:]
    if (!rows.keySet().any { it.toString() == id }) throw new IllegalArgumentException('Native row does not exist')
    if (part == 'condition') {
        boolean referenced = before.eval instanceof Map && before.eval.values().any { it instanceof List && it.any { token -> token?.toString() == id } }
        referenced = referenced || (before.predCapabs instanceof List && before.predCapabs.any { it.toString() == id })
        referenced = referenced || (before.actions instanceof Map && before.actions.values().any { it instanceof Map && it.cond?.toString() == id })
        if (referenced) throw new IllegalArgumentException('Referenced native conditions must be detached before deletion')
    }
    List oldOrder = before.actionList instanceof List ? before.actionList.collect { it.toString() } : []
    Map settingsBefore = _nmSettingsInventory(ruleId)
    if (part == 'condition' && settingsBefore.any { name, setting -> name.startsWith('condTrig.') && setting.value?.toString() == id }) throw new IllegalArgumentException('Referenced trigger conditions must be detached before deletion')
    List removedNames = []
    if (part == 'action') {
        removedNames = settingsBefore.keySet().findAll { it.endsWith(".${id}".toString()) }.toList()
        def deletedAction = _nmEntry(rows, id)
        if (deletedAction instanceof Map && deletedAction.wait instanceof List) {
            List prefixes = ['tCapab', 'tDev', 'ReltDev', 'reltDevice', 'xVarT', 'tstate', 'istVar', 'istDev', 'AlltDev', 'SHours', 'SMins', 'SSecs', 'stays', 'ButtontDev', 'tCustomAttr', 'tCustomAttrType', 'not', 'hhmmss', 'modesX', 'elapseHor', 'elapseMin', 'elapseSec', 'elapseTime']
            removedNames.addAll(settingsBefore.keySet().findAll { name -> deletedAction.wait.any { eventId -> prefixes.any { name == "${it}-${eventId}".toString() } } })
        }
    } else {
        List prefixes = part == 'trigger' ? ['tCapab', 'tDev', 'ReltDev', 'reltDevice', 'xVarT', 'tstate', 'istVar', 'istDev', 'AlltDev', 'SHours', 'SMins', 'SSecs', 'stays', 'ButtontDev', 'tCustomAttr', 'tCustomAttrType'] : ['rCapab_', 'rDev_', 'RelrDev_', 'relDevice_', 'xVarR_', 'state_', 'isVar_', 'isDev_', 'AllrDev_', 'SHours_', 'SMins_', 'SSecs_', 'stays_', 'rCustomAttr_', 'rCustomAttrType_']
        prefixes.addAll(['not', 'hhmmss', 'atTime', 'atSunriseOffset', 'atSunsetOffset', 'time', 'date', 'atVarTime', 'atDate', 'modesX', 'elapseHor', 'elapseMin', 'elapseSec', 'elapseTime',
                         'startDateType', 'endDateType', 'monthStart', 'dayStart', 'timeStart', 'useVarStart', 'varStart', 'monthEnd', 'dayEnd', 'timeEnd', 'useVarEnd', 'varEnd',
                         'atOrBetween', 'starting', 'startingA', 'startSunriseOffset', 'startSunsetOffset', 'startConTime', 'startVarTime', 'ending', 'endingA', 'endSunriseOffset', 'endSunsetOffset', 'endConTime', 'endVarTime', 'whichPeriod', 'xVar_', 'stateSince_'])
        removedNames = settingsBefore.keySet().findAll { name -> prefixes.any { name == "${it}${id}".toString() } || (part == 'trigger' && name in ["isCondTrig.${id}".toString(), "condTrig.${id}".toString()]) }.toList()
        String selector = part == 'trigger' ? "tCapab${id}".toString() : "rCapab_${id}".toString()
        if (settingsBefore.get(selector)?.value == 'Days of Week Schedule') removedNames.addAll(settingsBefore.keySet().findAll { it.endsWith("-${id}".toString()) })
    }
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
    boolean deleted = !remaining.keySet().any { it.toString() == id }
    List expectedRows = rows.keySet().collect { it.toString() }.findAll { it != id }.sort()
    boolean preserved = remaining.keySet().collect { it.toString() }.sort() == expectedRows
    List expectedOrder = part == 'action' ? oldOrder.findAll { it != id } : oldOrder
    boolean ordered = (after.actionList instanceof List ? after.actionList.collect { it.toString() } : []) == expectedOrder
    boolean cleaned = removedNames.every { !settingsAfter.containsKey(it) }
    boolean callbackConsumed = after.get(part == 'action' ? 'delAct' : 'deleteCon') == null
    Map expectedSettings = [:] + settingsBefore
    removedNames.each { expectedSettings.remove(it) }
    boolean settingsPreserved = settingsAfter == expectedSettings
    [success: deleted && preserved && ordered && cleaned && settingsPreserved && callbackConsumed, partial: !(deleted && preserved && ordered && cleaned && settingsPreserved && callbackConsumed), deleted: deleted, part: part, id: id,
     settingsVerified: cleaned && settingsPreserved, callbackConsumed: callbackConsumed, removedSettings: removedNames, remainingSettings: settingsAfter, verificationNeeded: true]
}

Map _nmOrderActions(Long ruleId, List ids) {
    if (ids == null) throw new IllegalArgumentException('A complete action order is required')
    List requested = ids.collect { _nmId(it) }
    Map before = _nrState(ruleId)
    if (!(before.actionList instanceof List) || !(before.actions instanceof Map)) throw new IllegalStateException('Native action order is unavailable')
    List original = before.actionList.collect { it.toString() }
    List originalKeys = before.actions.keySet().collect { it.toString() }.sort()
    if (requested.unique(false).size() != requested.size() || original.unique(false).size() != original.size() || requested.sort(false) != original.sort(false)) throw new IllegalArgumentException('Action order must be an exact permutation of existing action IDs')
    if (requested.any { !(_nmEntry(before.actions, it) instanceof Map) }) throw new IllegalArgumentException('Action order references an unavailable native action')
    Map contracts = [:]
    original.each { id ->
        Map action = _nmEntry(before.actions, id)
        contracts.put(id, [method: action.method, cond: action.cond, rule: action.rule, wait: action.wait instanceof List ? new ArrayList(action.wait) : action.wait])
    }
    List ordered = requested.collect { id -> before.actionList.find { it.toString() == id } }
    _nrStatePatch(ruleId, [actionList: ordered])
    _nrPage(ruleId, _nrRulePages(ruleId).actions)
    _nrHandle(ruleId).markIfs()
    Map after = _nrState(ruleId)
    boolean verified = after.actionList instanceof List && after.actionList.collect { it.toString() } == requested
    boolean retained = after.actions instanceof Map && after.actions.keySet().collect { it.toString() }.sort() == originalKeys && contracts.every { id, contract ->
        def action = _nmEntry(after.actions, id)
        action instanceof Map && action.method == contract.method && action.cond == contract.cond && action.rule == contract.rule && action.wait == contract.wait
    }
    Map structure = _nmValidateStructure(ruleId, after)
    [success: verified && retained && structure.success, partial: !(verified && retained && structure.success), orderVerified: verified, actionsPreserved: retained, ids: requested, structuralIssues: structure.structuralIssues, verificationNeeded: true]
}
