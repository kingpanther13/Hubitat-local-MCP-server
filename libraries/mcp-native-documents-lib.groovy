library(name: "McpNativeDocumentsLib", namespace: "mcp", author: "kingpanther13", description: "Native Rule Machine document tools.")

@groovy.transform.Field static final List ND_EXPRESSION_SUBTYPES = ['getIfThen', 'getElseIf', 'getWaitRule']
@groovy.transform.Field static final Map ND_TERMINATOR_METHODS = [endIf: 'getEndIf', 'else': 'getElse', endRep: 'getEndRepeat']
@groovy.transform.Field static final Map ND_CHANGE_KEYS = [condition: ['id', 'capability', 'fields'], trigger: ['id', 'capability', 'fields'],
    action: ['id', 'type', 'subtype', 'fields', 'expression', 'events', 'condition'], expression: ['id', 'tokens'],
    variable: ['operation', 'name', 'type', 'value'], required: ['tokens'], delete: ['part', 'id'], order: ['ids'], terminator: ['subtype']]

private Long _ndId(Object id) {
    Integer exact = _exactIntId(id)
    if (exact == null) throw new IllegalArgumentException("A positive native application ID is required, got: ${id}.".toString())
    exact as Long
}

private Object _ndJson(Object value) {
    value instanceof Map || value instanceof List ? value : new groovy.json.JsonSlurper().parseText(value?.toString() ?: '{}')
}

private Object _ndRuleHealth(Long id) { _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}")) }

// A never-written lifecycle flag is false; one stored in any other form than a flag is malformed (null).
private Boolean _ndNativeFlag(Map health, String name) {
    if (!health.containsKey(name)) return false
    _appStateFlag(health.get(name))
}

private List _ndSettings(Long id) {
    def response = _ndJson(hubInternalGet("/installedapp/settings/${id}"))
    if (!(response instanceof Map) || !(response.settings instanceof List)) throw new IllegalStateException('Native settings are unavailable')
    if (response.settings.any { !(it instanceof Map) || !(it.name instanceof String) }) throw new IllegalStateException('Unexpected native setting row')
    // A lock-code action's parameters, the PIN among them, are masked like a credential, and an HTTP URL's secret query
    // parameters within their values.
    Map lock = _ruleLockParams(response.settings.collectEntries { [(it.name): it.value] })
    response.settings.collect { row ->
        boolean secret = _redactsCredentials() && (row.type == 'password' || _appConfigIsSecretSetting(row.name, row.type, row.value) || row.name in lock.names)
        def setting = [name: row.name, type: row.type, multiple: row.multiple, value: secret ? null : _ruleSecretsMasked(row.value, lock), deviceIds: row.deviceList instanceof Map ? row.deviceList.keySet().collect { it.toString() }.sort() : null]
        if (secret) setting.redacted = true
        // A {name, value} row: the token pass keys on map keys, so it never sees a setting named accessToken.
        else if (_redactsAccessTokens() && row.name ==~ /(?i)access_?token/ && row.value instanceof CharSequence) setting.value = _accessTokenRedactedMarker()
        setting
    }
}

private List _ndBlockIssues(List order, Map actions) {
    def issues = []
    def blocks = []
    order.each { id ->
        def action = _nmEntry(actions, id.toString())
        if (!(action instanceof Map)) issues << 'Native action order references a missing action'
        else {
            if (action.method == 'getIfThen') blocks << 'if'
            else if (action.method in ['getRepeat', 'getWhile', 'getUntil', 'getRepeatWhile']) blocks << 'repeat'
            else if (action.method in ['getElse', 'getElseIf']) {
                if (!blocks || blocks.last() != 'if') issues << 'Native ELSE has no matching IF'
            } else if (action.method == 'getEndIf') {
                if (!blocks || blocks.last() != 'if') issues << 'Native END-IF has no matching IF'
                else blocks.remove(blocks.size() - 1)
            } else if (action.method == 'getEndRepeat') {
                if (!blocks || blocks.last() != 'repeat') issues << 'Native END-REP has no matching REPEAT'
                else blocks.remove(blocks.size() - 1)
            }
        }
    }
    if (blocks.contains('if')) issues << 'Native IF block has no END-IF'
    if (blocks.contains('repeat')) issues << 'Native REPEAT block has no END-REP'
    issues
}

private List _ndStructuralIssues(Map nativeState, boolean requiredEnabled) {
    def actions = nativeState.actions instanceof Map ? nativeState.actions : [:]
    def order = nativeState.actionList instanceof List ? nativeState.actionList : []
    def conditions = nativeState.capabsfalse instanceof Map ? nativeState.capabsfalse : [:]
    def expressions = nativeState.eval instanceof Map ? nativeState.eval : [:]
    def expressionIds = []
    def issues = _ndBlockIssues(order, actions)
    if (nativeState.inIf == true) issues << 'Native editor has an unfinished IF block'
    order.each { id ->
        def action = _nmEntry(actions, id.toString())
        if (action instanceof Map && action.rule != null) expressionIds << action.rule.toString()
    }
    if (requiredEnabled) expressionIds << '0'
    expressionIds.unique().each { id ->
        def tokens = _nmEntry(expressions, id)
        try {
            if (!(tokens instanceof List)) throw new IllegalArgumentException('Missing native expression')
            _nmTokens(tokens, conditions)
        } catch (Exception failure) { issues << "Native expression ${id}: ${failure.message}".toString() }
    }
    issues
}

private List _ndPageIssues(Object node) {
    def issues = []
    if (node instanceof List) node.each { child -> issues.addAll(_ndPageIssues(child)) }
    else if (node instanceof Map) {
        // A broken row is read from the rule's state (_ndRuleHealth), not from the rendered page.
        if (node.error || node.errors) issues << 'Native editor returned an error'
        ['sections', 'body', 'children'].each { key -> if (node.get(key) != null) issues.addAll(_ndPageIssues(node.get(key))) }
    }
    issues
}

private Map _ndRender(Long id, String page) {
    def rendered = _nrPage(id, page).result
    if (!(rendered instanceof Map) || rendered.name != page || !(rendered.sections instanceof List) || rendered.sections.isEmpty()) throw new IllegalStateException('Native editor page is unavailable or unexpected')
    def issues = _ndPageIssues(rendered)
    if (issues) throw new IllegalStateException(issues.join('; '))
    rendered
}

// The document read's section, cursor and limit arguments, checked before anything is read.
private Map _ndDocumentArgs(Map args, List allowed, String toolName) {
    def sections = args.sections == null ? allowed : args.sections
    if (!(sections instanceof List) || sections.any { !(it in allowed) }) throw new IllegalArgumentException('Unknown document section')
    boolean paged = args.containsKey('cursor') || args.containsKey('limit')
    if (paged && sections.size() != 1) throw new IllegalArgumentException('Paged reads require exactly one section')
    def cursor = args.containsKey('cursor') ? args.cursor : ''
    def limit = args.containsKey('limit') ? args.limit : 50
    if (paged && (!(cursor instanceof String) || !(limit instanceof Number) || !limit.toString().isLong() || limit < 1 || limit > 100))
        throw new IllegalArgumentException('Read cursor must be a string and limit an integer from 1 to 100')
    if (paged) _parseListCursor(cursor, Integer.MAX_VALUE, toolName)
    [sections: sections, paged: paged, cursor: cursor, limit: limit]
}

// A rule's local variables as name/type/value rows, credential-named values hidden as settings are.
private List _ndLocalVariables(Long id) {
    def all = _nrHandle(id).getAllLocalVars()
    // A rule with no locals answers an empty map, so a null is a failed read, not an empty list.
    if (all == null) throw new IllegalStateException("Rule ${id}'s local variables could not be read")
    if (!(all instanceof Map)) throw new IllegalStateException("Rule ${id} returned its local variables in an unexpected shape")
    all.collect { name, row -> _rmPublicLocalVariable(name?.toString(), row instanceof Map ? [type: row.type?.toString(), value: row.value] : [value: row]) }.sort { it.name }
}

// A document's size as tools/call sends it: the JSON-within-text envelope, measured before it is sent.
private int _ndEnvelopeBytes(Map document) {
    groovy.json.JsonOutput.toJson(jsonRpcResult(0, [content: [[type: 'text', text: groovy.json.JsonOutput.toJson(document)]]])).getBytes('UTF-8').length
}

// localVariables:true is hub_get_rule's read: the local variables ride as their own list instead of the variables
// section, and page as a section of their own. Health comes first and always returns; a read that would pass the
// response cap keeps the parts that fit whole, pages the first that does not, and lists the others that do not fit
// in omittedSections.
// health: the health a caller already holds (a write's read-back, whose own checks stand for it); false leaves it out.
Map toolGetRuleDocument(Map args, Object health = null) {
    Long id = _ndId(args.ruleId)
    boolean withLocals = args.localVariables == true
    String toolName = withLocals ? 'hub_get_rule' : 'hub_set_rule'
    List allowed = withLocals ? ['actions', 'conditions', 'triggers', 'expressions', 'settings', 'localVariables'] : ['actions', 'conditions', 'triggers', 'expressions', 'variables', 'settings']
    Map read = _ndDocumentArgs(args, allowed, toolName)
    List sections = read.sections
    def cursor = read.cursor
    def limit = read.limit
    _nrHandle(id)
    try {
        def document = health == false ? [ruleId: id] : [health: health ?: _rmCheckRuleHealth(id as Integer), ruleId: id]
        // Only these sections come from native state, so a read of health, settings or the locals alone never reads it.
        Map nativeState = sections.any { it in ['actions', 'conditions', 'triggers', 'expressions'] } ? _nrState(id) : null
        // hub_get_rule returns the locals with every read except a page of another section.
        String key = withLocals ? 'localVariables' : 'variables'
        boolean readLocals = withLocals ? !(read.paged && sections[0] != key) : key in sections
        if (readLocals) {
            // The health is already read, so a failed locals read is reported beside it rather than losing it.
            try {
                List locals = _ndLocalVariables(id)
                document.put(key, withLocals ? locals : locals.collectEntries { row -> [(row.name): row.findAll { k, v -> k != 'name' }] })
            } catch (Exception localsFailure) {
                mcpLog('warn', 'rm-native', "${toolName}: rule ${id}'s local variables could not be read (${localsFailure.message})")
                document.put(key, null)
                document.localVariablesError = (localsFailure.message ?: localsFailure.toString()).toString()
                // A page of the locals that did not come back must not read as their end.
                if (read.paged && sections[0] == key) return [success: false, health: document.health, ruleId: id, localVariablesError: document.localVariablesError, cursor: cursor,
                    error: "Rule ${id}'s local variables could not be read: ${document.localVariablesError}".toString(),
                    note: "Nothing was read past this point; retry with the same cursor. See hub_get_tool_guide(section='builtin_app_tools_rules')."]
            }
        }
        if ('actions' in sections) document.actions = (nativeState.actionList instanceof List ? nativeState.actionList : []).collect { actionId ->
            [id: actionId.toString(), value: nativeState.actions instanceof Map ? _nmEntry(nativeState.actions, actionId.toString()) : null]
        }
        if ('conditions' in sections) document.conditions = nativeState.capabsfalse instanceof Map ? nativeState.capabsfalse : [:]
        if ('triggers' in sections) document.triggers = nativeState.capabstrue instanceof Map ? nativeState.capabstrue : [:]
        if ('expressions' in sections) document.expressions = nativeState.eval instanceof Map ? nativeState.eval : [:]
        if ('settings' in sections) {
            // A requested section that did not come back is a failed read, as on the Basic Rule path; the health stays.
            try { document.settings = _ndSettings(id) }
            catch (Exception settingsFailure) {
                String why = (settingsFailure.message ?: settingsFailure.toString()).toString()
                return [success: false, health: document.health, ruleId: id, settings: null, settingsError: why, error: "Rule ${id} could not be read: ${why}".toString(),
                    note: "The read does not open the rule's page or change its configuration, so retrying once is safe. If it fails again, check that the hub answers with hub_get_info. See hub_get_tool_guide(section='builtin_app_tools_rules')."]
            }
        }
        // Match the tools/call JSON-within-text envelope; reserve 1 KiB for its actual request ID and response metadata.
        int budget = _responseSizeLimitBytes() - 1024
        String section = read.paged ? sections[0] : null
        // Held in locals: a compound condition makes Groovy 2.4 compile the whole if statement twice.
        boolean overCap = !read.paged && _ndEnvelopeBytes(document) > budget
        Map parts = [:]
        if (overCap) {
            // Put back each part that still fits, in order; the first that does not is paged below.
            for (part in new ArrayList(document.keySet())) {
                def value = document.get(part)
                boolean listed = value instanceof Collection || value instanceof Map
                if (listed && part != 'health') parts.put(part, document.remove(part))
            }
            List omitted = []
            for (part in parts.keySet()) {
                document.put(part, parts.get(part))
                if (_ndEnvelopeBytes(document) > budget) { document.remove(part); omitted << part }
            }
            // No part is left over only when there is none, so the health alone is over the cap: the size guard answers.
            if (omitted) {
                section = omitted.remove(0)
                document.put(section, parts.get(section))
                document.pagedSection = section
                document.omittedSections = omitted
                document.note = 'The whole rule does not fit in one response: read on with the same tool, naming only pagedSection in sections and passing nextCursor as cursor, then each of omittedSections from cursor "".'
            }
        }
        // A section whose read failed (the locals) is reported by its error, so there is nothing to page.
        boolean paging = section != null && document.get(section) != null
        if (!paging) return document
        // The page is cut inside a closure, whose class carries its call sites: the server class sits at the JVM's
        // constant-pool limit.
        Closure cut = { unused ->
            def complete = document.get(section)
            List rows = complete instanceof Map ? complete.entrySet().toList().sort { a, b -> a.key.toString() <=> b.key.toString() } :
                (section == 'settings' ? complete.sort { a, b -> a.name <=> b.name } : complete)
            int total = rows.size()
            int start = _parseListCursor(cursor, total, toolName)
            int end = Math.min(start + limit.toInteger(), total)
            document.page = [offset: start, limit: limit, total: total,
                revision: _mrtrSha256(groovy.json.JsonOutput.toJson(_mrtrCanonicalArgs(complete)))]
            while (true) {
                List selected = rows.subList(start, end)
                document.put(section, complete instanceof Map ? selected.collectEntries { entry -> [(entry.key): entry.value] } : selected)
                document.nextCursor = end < total ? end.toString() : null
                if (_ndEnvelopeBytes(document) <= budget) break
                if (end - start > 1) { end = start + (end - start).intdiv(2); continue }
                // One row does not fit beside the parts kept whole: leave them out one at a time, in order, and try again.
                def kept = parts.keySet().find { it != section && document.containsKey(it) }
                // A row too large to send beside the health alone: the read fails, but the health still comes back.
                if (kept == null) return [success: false, health: document.health, ruleId: id, section: section,
                    error: 'A native document row or its health exceeds the response size limit; it cannot be returned without truncation',
                    note: "Nothing was cut. Read the other sections one at a time; the rule's page in the Hubitat UI shows this row."]
                document.remove(kept)
                document.omittedSections = document.omittedSections + [kept]
            }
            document
        }
        cut.call(null)
    } catch (IllegalArgumentException refused) {
        throw refused
    } catch (Exception failure) {
        mcpLogError('rm-native', "${toolName} read failed for rule ${id}: ${failure.message}".toString(), failure)
        [success: false, ruleId: id, error: "Rule ${id} could not be read: ${failure.message ?: failure.toString()}".toString(),
         note: "The read does not open the rule's page or change its configuration, so retrying once is safe. If it fails again, check that the hub answers with hub_get_info. See hub_get_tool_guide(section='builtin_app_tools_rules').".toString()]
    }
}

private List _ndChanges(Object changes, boolean allowEmpty = false) {
    if (!(changes instanceof List) || (!allowEmpty && changes.isEmpty()) || changes.size() > 100) throw new IllegalArgumentException(allowEmpty ? 'changes must contain between 0 and 100 changes' : 'changes must contain between 1 and 100 changes')
    changes.eachWithIndex { change, index ->
        // Every check here depends only on the arguments: a refusal names its row and fires before any write.
        try { _ndValidateChange(change) }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("changes[${index}]: ${failure.message}".toString()) }
    }
    changes
}

// The typed field rows of one native row: valid native settings whose names carry the row's #
// placeholder, none repeated once it is filled in, and none naming the row's own selector inputs.
private void _ndValidateFields(Object fields, List selectors, String selectorMessage) {
    if (!(fields instanceof List)) throw new IllegalArgumentException('Native typed fields are required')
    fields.each { row ->
        if (!(row instanceof Map) || !(row.name instanceof String) || !row.name.contains('#')) throw new IllegalArgumentException('Invalid native typed field: its name must contain #')
        _nrCheckSettingRow(row)
        _nrValidateScalar(row, row.value)
    }
    List names = fields.collect { it.name.replace('#', '1') }
    if (names.unique(false).size() != names.size()) throw new IllegalArgumentException('Native inputs must not be repeated')
    if (names.any { it in selectors }) throw new IllegalArgumentException(selectorMessage)
}

private void _ndValidateChange(Object change) {
    // The checks run in a closure, whose class carries their call sites: the server class sits at the JVM's constant-pool limit.
    Closure checks = { wrapped ->
        if (!(change instanceof Map) || change.keySet().any { !(it in ['part', 'value']) } || !(change.value instanceof Map)) throw new IllegalArgumentException('Each change requires part and value')
        def value = change.value
        if (!ND_CHANGE_KEYS.containsKey(change.part) || value.keySet().any { !(it in ND_CHANGE_KEYS.get(change.part)) }) throw new IllegalArgumentException('Unknown change part or field')
        if (value.containsKey('id') && (value.id == null || !value.id.toString().isInteger() || value.id.toString().toInteger() < (change.part == 'expression' ? 0 : 1))) throw new IllegalArgumentException('Invalid native row ID')
        if (change.part in ['condition', 'trigger']) {
            if (!(value.capability instanceof String) || !value.capability.trim()) throw new IllegalArgumentException('Native capability is required')
            _ndValidateFields(value.get('fields'), ['tCapab1', 'rCapab_1'], 'Capability selector must not be repeated in fields')
        }
        if (change.part == 'action') {
            if (!(value.type instanceof String) || !value.type.trim() || !(value.subtype instanceof String) || !value.subtype.trim() || (value.subtype.toLowerCase().contains('wait') && !(value.subtype in ['getWaitEvents', 'getWaitRule']))) throw new IllegalArgumentException('A supported native action type and subtype are required')
            _ndValidateFields(value.get('fields'), ['actType.1', 'actSubType.1'], 'Action selectors must not be repeated')
            if (value.containsKey('expression') && (!(value.expression instanceof List) || !value.expression || !(value.subtype in ND_EXPRESSION_SUBTYPES))) throw new IllegalArgumentException('Invalid action expression')
            if (!value.containsKey('id') && value.subtype in ND_EXPRESSION_SUBTYPES && !value.containsKey('expression')) throw new IllegalArgumentException('New expression actions require expression tokens')
            if (value.containsKey('condition')) {
                _nmId(value.condition)
                if (value.containsKey('expression') || value.type in ['condActs', 'repeatActs', 'delayActs']) throw new IllegalArgumentException('A simple condition requires an ordinary native action')
            }
            if (value.subtype == 'getWaitEvents') {
                if (!(value.events instanceof List) || !value.events || value.events.size() > 16) throw new IllegalArgumentException('Wait events require between 1 and 16 events')
                value.events.eachWithIndex { event, index ->
                    try {
                        if (!(event instanceof Map) || event.keySet().any { !(it in ['capability', 'fields']) } || !(event.capability instanceof String) || !event.capability.trim()) throw new IllegalArgumentException('Wait events require a capability and typed fields')
                        _ndValidateFields(event.get('fields'), ['tCapab1'], 'Wait fields must not repeat selectors or native inputs')
                    } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("events[${index}]: ${failure.message}".toString()) }
                }
            } else if (value.containsKey('events')) throw new IllegalArgumentException('Only wait-event actions accept events')
        }
        if (change.part == 'expression' && (!value.containsKey('id') || !(value.tokens instanceof List) || !value.tokens)) throw new IllegalArgumentException('Expression ID and tokens are required')
        // Expression 0 is the Required Expression, whose conditions Rule Machine also keeps in predCapabs; only a required
        // change rebinds them, so an expression change there would leave the two disagreeing.
        if (change.part == 'expression' && value.id.toString() == '0') throw new IllegalArgumentException("Expression 0 is the Required Expression: change it with {part:'required', value:{tokens}}, which also updates the conditions it uses")
        if (change.part == 'delete' && (!(value.part in ['action', 'condition', 'trigger']) || !value.containsKey('id'))) throw new IllegalArgumentException('Deletion requires a native collection and ID')
        if (change.part == 'order') {
            if (!(value.ids instanceof List) || value.ids.any { it == null || !it.toString().isInteger() || it.toString().toInteger() <= 0 } || value.ids.collect { it.toString() }.unique(false).size() != value.ids.size()) throw new IllegalArgumentException('Ordering requires unique positive native action IDs')
        }
        if (change.part == 'required' && (!(value.tokens instanceof List) || !value.tokens)) throw new IllegalArgumentException('Required expression tokens are required')
        if (change.part == 'terminator' && !(value.subtype in ND_TERMINATOR_METHODS.keySet())) throw new IllegalArgumentException('Unsupported terminator')
        def tokens = change.part in ['expression', 'required'] ? value.tokens : (change.part == 'action' ? value.expression : null)
        if (tokens instanceof List) {
            def operands = [:]
            tokens.each { token -> if (token != null && token.toString().isInteger() && token.toString().toInteger() > 0) operands.put(token.toString(), 'condition') }
            _nmTokens(tokens, operands)
        }
        if (change.part == 'variable') {
            if (!(value.operation in ['create', 'set', 'delete']) || !(value.name instanceof String) || !(value.name ==~ /[A-Za-z_][A-Za-z0-9_ ]{0,79}/) || value.name != value.name.trim()) throw new IllegalArgumentException('Invalid native variable change')
            if (value.operation in ['create', 'set'] && !value.containsKey('value')) throw new IllegalArgumentException('Variable value is required')
            // The stored type governs set and delete; a type there would be silently ignored.
            if (value.operation != 'create' && value.containsKey('type')) throw new IllegalArgumentException('Variable type is accepted only when creating a local variable')
            if (value.operation == 'create') _nmLocalValue(value.type?.toString(), value.value)
        }
    }
    checks.call(null)
}

// Whether a batch may name this row of one native collection: true for an existing row, false when
// the id may name a row the batch appended (its id is assigned only by the write), and a refusal
// with `missing` for an id the batch already deleted or that never existed.
private boolean _ndKnownRow(Map rows, String rowId, String missing) {
    if (rowId in rows.deleted) throw new IllegalArgumentException(missing)
    if (rowId in rows.ids) return true
    if (rowId in rows.predicted) return false
    throw new IllegalArgumentException(missing)
}

// Rule Machine gives a new trigger or condition the rule's ndx and a new action or terminator its
// actNdx, then advances that counter; a new rule starts both at 1. The batch may name the row by it.
private String _ndPredictAppend(Map rows, Map counters, String part) {
    String family = part == 'action' ? 'action' : 'condition'
    List taken = family == 'action' ? [rows.action] : [rows.condition, rows.trigger]
    int next = _ndCounter(counters.get(family), taken.any { !it.ids.isEmpty() })
    counters.put(family, next + 1)
    String predicted = next.toString()
    if (taken.any { predicted in it.ids }) throw new IllegalArgumentException("Rule Machine's next ${family} id ${predicted} is already in use; read the rule again before appending")
    rows.get(part).predicted << predicted
    rows.get(part).added++
    predicted
}

// A rule with no rows in a family may not carry its counter yet; one that has rows must.
private int _ndCounter(Object value, boolean hasRows) {
    if (value != null && value.toString().isInteger() && value.toString().toInteger() > 0) return value.toString().toInteger()
    if (hasRows) throw new IllegalArgumentException("The rule's row counter is unreadable; read the rule again before appending")
    return 1
}

private void _ndDropRow(Map rows, String rowId, String missing) {
    if (_ndKnownRow(rows, rowId, missing)) rows.ids.remove(rowId)
    else { rows.predicted.remove(rowId); rows.added-- }
    rows.deleted << rowId
}

// marked: _rmMarkedRows as the update's health check read it (the conditions in use, and those whose device is gone).
private void _ndPreflight(Long id, List changes, Map marked = null) {
    List inUse = marked?.inUse
    // A condition the batch gives a device again stops counting as gone for its later changes; the update checks it
    // right after that write and stops the batch there if the repair did not take.
    List emptied = (marked?.emptied ?: []).collect { it.toString() }
    Map before = id == null ? [:] : _nrState(id)
    Map conditions = before.capabsfalse instanceof Map ? [:] + before.capabsfalse : [:]
    Map triggers = before.capabstrue instanceof Map ? [:] + before.capabstrue : [:]
    Map expressions = before.eval instanceof Map ? before.eval.collectEntries { key, value -> [(key.toString()): value] } : [:]
    List actionOrder = before.actionList instanceof List ? before.actionList.collect { it.toString() } : []
    Map projectedActions = before.actions instanceof Map ? before.actions.collectEntries { key, value -> [(key.toString()): value] } : [:]
    List projectedOrder = new ArrayList(actionOrder)
    Map rows = [action: [ids: (actionOrder + projectedActions.keySet()) as Set, added: 0, deleted: [] as Set, predicted: [] as Set],
                condition: [ids: conditions.keySet().collect { it.toString() } as Set, added: 0, deleted: [] as Set, predicted: [] as Set],
                trigger: [ids: triggers.keySet().collect { it.toString() } as Set, added: 0, deleted: [] as Set, predicted: [] as Set]]
    // Read when the batch first appends to a family, so a counter it never uses cannot refuse it.
    Map counters = [condition: before.ndx, action: before.actNdx]
    // A rule's first Required Expression advances ndx by one (live); later ones leave it.
    boolean hasRequired = expressions.get('0') instanceof List && !expressions.get('0').isEmpty()
    boolean appendedActions = false
    Map locals = id == null ? [:] : null
    // For a batch that deletes a condition: the references the batch projects (its actions' conditions and expressions,
    // the Required Expression while it is on, ticked Conditional Triggers), and the ones its earlier changes removed.
    boolean deletes = changes.any { it.part == 'delete' && it.value?.part == 'condition' }
    Map condTrigs = null
    boolean requiredOn = false
    Set freed = [] as Set
    Closure references = { unused ->
        List names = requiredOn && expressions.get('0') instanceof List ? expressions.get('0')*.toString() : []
        projectedOrder.each { actionId ->
            def action = projectedActions.get(actionId)
            def tokens = action instanceof Map ? (action.containsKey('tokens') ? action.tokens : expressions.get(action.rule?.toString())) : null
            if (action instanceof Map && action.cond != null) names << action.cond.toString()
            if (tokens instanceof List) names.addAll(tokens*.toString())
        }
        names + condTrigs.values().findAll { it.ticked && it.cond != null }*.cond
    }
    changes.eachWithIndex { change, index ->
        try {
            Map value = change.value
            if (deletes && condTrigs == null) {
                condTrigs = [:]
                Map inventory = id == null ? [:] : _nmSettingsInventory(id)
                triggers.keySet().each { trigger -> condTrigs.put(trigger.toString(), [ticked: inventory.get('isCondTrig.' + trigger)?.value?.toString() == 'true', cond: inventory.get('condTrig.' + trigger)?.value?.toString()]) }
                requiredOn = id != null && _nrHandle(id).getSetting('useST') == true
            }
            List was = deletes ? references(null) : []
            if (change.part in ['condition', 'trigger'] && value.containsKey('id')) _ndKnownRow(rows.get(change.part), value.id.toString(), 'Native row does not exist')
            if (change.part == 'action' && value.containsKey('id') && _ndKnownRow(rows.action, value.id.toString(), 'Native action does not exist')) {
                def prior = projectedActions.get(value.id.toString())
                if (!(prior instanceof Map)) throw new IllegalArgumentException('Native action does not exist')
                if (value.subtype == 'getWaitEvents' && (!(prior.wait instanceof List) || prior.wait.size() != value.events.size())) throw new IllegalArgumentException('Editing waits currently requires the existing event count')
            }
            // Every condition an expression or a simple conditional action names must be in the batch's view.
            List named = change.part in ['expression', 'required'] ? value.tokens :
                (change.part == 'action' ? (value.expression instanceof List ? value.expression : []) + (value.containsKey('condition') ? [value.condition] : []) : [])
            named.findAll { it != null && it.toString().isInteger() }.each { token -> _ndKnownRow(rows.condition, token.toString(), 'Expression references an unknown condition') }
            // Rule Machine marks a condition whose device is gone once an expression names it, which would fail the write after it lands.
            def gone = change.part in ['expression', 'required'] ? named.find { it?.toString() in emptied } : null
            if (gone != null) throw new IllegalArgumentException("Expression names condition ${gone}, whose device is gone, so Rule Machine would mark it broken; repair or delete that condition first".toString())
            // Only the condition's own device picker repairs it; a relative device (relDevice_#) leaves it gone.
            if (change.part == 'condition' && value.containsKey('id') && value.get('fields')?.any { it instanceof Map && it.name == 'rDev_#' && it.type?.toString()?.startsWith('capability') && it.value }) emptied.remove(value.id.toString())
            if (change.part == 'variable') {
                if (locals == null) {
                    def fresh = _nrHandle(id).getAllLocalVars()
                    if (!(fresh instanceof Map)) throw new IllegalStateException('Native local variables are unavailable')
                    locals = [:] + fresh
                }
                if (value.operation == 'create') {
                    if (locals.containsKey(value.name)) throw new IllegalArgumentException('Local variable already exists')
                    locals.put(value.name, [type: value.type, value: value.value])
                } else {
                    if (!(locals.get(value.name) instanceof Map)) throw new IllegalArgumentException('Local variable does not exist')
                    if (value.operation == 'set') {
                        _nmLocalValue(locals.get(value.name).type?.toString(), value.value)
                        locals.put(value.name, locals.get(value.name) + [value: value.value])
                    } else locals.remove(value.name)
                }
            }
            String appended = change.part in ['condition', 'trigger'] && !value.containsKey('id') ? _ndPredictAppend(rows, counters, change.part) : null
            if (change.part in ['action', 'terminator']) {
                boolean newRow = !value.containsKey('id')
                String actionId = newRow ? _ndPredictAppend(rows, counters, 'action') : value.id.toString()
                if (newRow) appendedActions = true
                if (newRow) projectedOrder << actionId
                String method = change.part == 'terminator' ? ND_TERMINATOR_METHODS.get(value.subtype) : value.subtype
                def prior = projectedActions.get(actionId)
                // Merge over the prior record so a second edit of the same existing row still sees its wait events and condition.
                Map record = (prior instanceof Map ? [:] + prior : [:]) + [method: method]
                if (change.part == 'action' && value.containsKey('condition')) record.cond = value.condition.toString()
                if (change.part == 'action' && value.expression instanceof List) record.tokens = value.expression
                projectedActions.put(actionId, record)
            }
            if (change.part == 'order') {
                if (appendedActions) throw new IllegalArgumentException('Action order cannot follow new actions in the same batch; add the actions first and order them in a later call')
                List requested = value.ids.collect { it.toString() }
                if (requested.sort(false) != actionOrder.sort(false)) throw new IllegalArgumentException('Action order must be an exact permutation of existing action IDs')
                actionOrder = requested
                projectedOrder = new ArrayList(requested)
            }
            if (change.part == 'delete' && value.part == 'action') {
                String target = value.id.toString()
                _ndDropRow(rows.action, target, 'Native action does not exist')
                projectedOrder = projectedOrder.findAll { it != target }
                projectedActions.remove(target)
                actionOrder = actionOrder.findAll { it != target }
            }
            if (change.part in ['expression', 'required']) {
                String evalId = change.part == 'required' ? '0' : value.id.toString()
                expressions.put(evalId, value.tokens)
                if (change.part == 'required') requiredOn = true
                // The newest write to an eval entry wins: an action edited earlier in the batch reads its expression from here.
                projectedActions.each { key, action ->
                    if (action instanceof Map && action.containsKey('tokens') && action.rule?.toString() == evalId) projectedActions.put(key, action.findAll { it.key != 'tokens' })
                }
            }
            if (change.part == 'trigger' && deletes) {
                // A trigger the batch appends is keyed by the id it will take, so a later change can name it.
                String trigger = value.containsKey('id') ? value.id.toString() : appended
                Map binding = condTrigs.get(trigger) ?: [ticked: false, cond: null]
                value.get('fields').each { field ->
                    String name = field?.name?.toString()
                    if (name?.startsWith('isCondTrig')) binding.ticked = field.value?.toString() == 'true'
                    if (name?.startsWith('condTrig')) binding.cond = field.value?.toString()
                }
                condTrigs.put(trigger, binding)
            }
            if (change.part == 'delete' && value.part == 'trigger' && deletes) condTrigs.remove(value.id.toString())
            if (change.part == 'required' && !hasRequired) { counters.put('condition', _ndCounter(counters.get('condition'), !(rows.condition.ids.isEmpty() && rows.trigger.ids.isEmpty())) + 1); hasRequired = true }
            if (change.part == 'delete' && value.part == 'condition') {
                String target = value.id.toString()
                _ndKnownRow(rows.condition, target, 'Native row does not exist')
                // Refused before anything is written while the batch's projected rows still reference the condition, or
                // Rule Machine lists it in use (state.inUseConds) and no earlier change in the batch removed a reference
                // to it. The delete itself checks again as things stand then.
                if (target in condTrigs.values().findAll { it.ticked }*.cond) throw new IllegalArgumentException('Referenced trigger conditions must be detached before deletion')
                boolean held = target in references(null) || (inUse != null && target in inUse && !(target in freed))
                if (held) throw new IllegalArgumentException('Referenced native conditions must be detached before deletion')
                _ndDropRow(rows.condition, target, 'Native row does not exist')
            }
            if (deletes) freed.addAll(was - references(null))
            if (change.part == 'delete' && value.part == 'trigger') _ndDropRow(rows.trigger, value.id.toString(), 'Native row does not exist')
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("changes[${index}]: ${failure.message}".toString())
        }
    }
    List blockIssues = _ndBlockIssues(projectedOrder, projectedActions)
    if (blockIssues) throw new IllegalArgumentException(blockIssues.join('; '))
}

// The rows Rule Machine marks right now, from the rule's live state and settings, so a write is compared with itself.
private Map _ndMarkedNow(Long id) {
    Map state = _nrState(id)
    List settings = _nmSettingsInventory(id).collect { name, row -> [name: name, type: row.type?.toString(), value: row.value, deviceIds: row.deviceList instanceof Map ? row.deviceList.keySet().toList() : []] }
    _rmMarkedRows(_nrHandle(id), state, settings)
}

// before: the rows Rule Machine marked before an update (_ndMarkedNow); a write fails only for a row it newly marks.
private Map _ndApply(Long id, List changes, boolean created, Map before = null) {
    // The body runs in a closure, whose class carries its call sites: the server class sits at the JVM's constant-pool limit.
    return { wrapped ->
    def outcomes = []
    boolean started = created
    // 0 while the changes run, 1 once every change is written, 2 once Done was pressed: the failure note says which.
    int stage = 0
    try {
        def beforeHealth = _ndRuleHealth(id)
        if (!(beforeHealth instanceof Map) || !(beforeHealth.broken instanceof Boolean)) throw new IllegalStateException('Native rule health is unavailable before changes')
        if (_ndNativeFlag(beforeHealth, 'stopped') == null || _ndNativeFlag(beforeHealth, 'paused') == null) throw new IllegalStateException('Native rule lifecycle state is malformed')
        boolean wasStopped = _ndNativeFlag(beforeHealth, 'stopped')
        boolean wasPaused = _ndNativeFlag(beforeHealth, 'paused')
        Map pages = _nrRulePages(id)
        Map beforeState = _nrState(id)
        if (pages.actions == 'selectActionsX' && beforeState.actNdx == null && !beforeState.actions && !beforeState.actionList) {
            started = true
            _ndRender(id, pages.actions)
        }
        for (change in changes) {
            def value = change.value
            Map outcome
            // A change refuses its arguments before it writes anything, so a refusal leaves started as it was.
            boolean wrote = started
            started = true
            try {
                switch (change.part) {
                    case 'condition': outcome = _nmUpsertCondition(id, false, value); break
                    case 'trigger': outcome = _nmUpsertCondition(id, true, value); break
                    case 'action': outcome = _nmUpsertAction(id, value); break
                    case 'expression': outcome = _nmSetExpression(id, value.id, value.tokens); break
                    case 'delete': outcome = _nmDeleteRow(id, value.part, value.id); break
                    case 'order': outcome = _nmOrderActions(id, value.ids); break
                    case 'required': outcome = _nmSetRequired(id, value.tokens); break
                    case 'variable': outcome = _nmLocal(id, value.operation, value.name, value.value, value.type); break
                    case 'terminator': outcome = _nmAddTerminator(id, value.subtype); break
                }
            } catch (IllegalArgumentException refused) { started = wrote; throw refused }
            // A repair of a condition whose device was gone must take before a later change names it.
            if (outcome?.success == true && change.part == 'condition' && value.containsKey('id') && value.id.toString() in (before?.emptied ?: [])*.toString()
                    && value.id.toString() in (_ndMarkedNow(id).emptied ?: [])*.toString())
                outcome = outcome + [success: false, partial: true, error: "Condition ${value.id} still has no device after the change, so the repair did not take".toString()]
            outcomes << [part: change.part, result: outcome]
            if (outcome?.success != true) {
                boolean landed = created || outcomes.size() > 1 || outcome?.partial == true
                return [success: false, partial: landed, ruleId: id, created: created, results: outcomes, remaining: changes.drop(outcomes.size()),
                    error: outcome?.error?.toString() ?: "Native ${change.part} change did not verify".toString(), note: _ndFailureNote(landed, created)]
            }
        }
        stage = 1
        ['selectTriggers', pages.actions, pages.primary].unique().each { page -> _ndRender(id, page) }
        def health = _ndRuleHealth(id)
        Map renderedState = _nrState(id) + (health instanceof Map ? health : [:])
        def structuralIssues = _ndStructuralIssues(renderedState, _nrHandle(id).getSetting('useST') == true)
        structuralIssues.addAll(_nmValidateStructure(id, renderedState).structuralIssues ?: [])
        // A simple conditional action whose condition renders nothing raises no broken flag; Rule Machine's labels mark
        // it, so a row they mark that was not marked before the write fails it, while one already marked may stay or be repaired.
        // A restore rebuild (no baseline, not created) is verified against its recovery state instead.
        Map marks = before ?: (created ? [rows: [], marks: [:]] : null)
        List added = []
        if (marks != null) {
            Map after = _ndMarkedNow(id)
            added = after.rows.findAll { row -> row.id != null && !marks.rows.any { it.kind == row.kind && it.id == row.id } }.collect { "${it.kind} ${it.id}".toString() }
            List grown = []
            after.marks.each { kind, count -> if (count > (marks.marks.get(kind) ?: 0)) { grown << kind; added << "${kind} (${count} marked, ${marks.marks.get(kind) ?: 0} before)".toString() } }
            // An id-less row is compared by kind and text too, so repairing one and adding another of the same kind shows.
            // A row loses its id once nothing uses it (an action moved off a marked condition, C-7), so an id-less row of
            // a kind that lost an id'd row is that row, not a new one.
            List was = marks.rows.findAll { it.id == null }.collect { "${it.kind} ('${it.text}')".toString() }
            List lost = marks.rows.findAll { row -> row.id != null && !after.rows.any { it.kind == row.kind && it.id == row.id } }*.kind
            after.rows.findAll { it.id == null && !(it.kind in grown) }.each { row -> String key = "${row.kind} ('${row.text}')".toString(); if (!was.remove(key) && !lost.remove(row.kind)) added << key }
        }
        if (added) structuralIssues << "The write adds rows Rule Machine marks broken: ${added.join('; ')}".toString()
        if (structuralIssues) throw new IllegalStateException(structuralIssues.join('; '))
        // The verdict is the rule's own broken(), not the stored flag. Editing an action gated on a condition whose
        // device was deleted turns broken() true, though the rule's name gains no *BROKEN* marker; broken() decides.
        def flag = _nrHandle(id).broken()
        boolean healthy = health instanceof Map && flag == false
        String unhealthy = flag == true ? "The change was written, but Rule Machine's own broken() check now calls rule ${id} broken, so Done was not pressed".toString() : 'Native rule health is broken or unverified'
        if (!healthy) return [success: false, partial: true, ruleId: id, created: created, results: outcomes, broken: flag, error: unhealthy, note: _ndFailureNote(true, created, 1)]
        // Set before the POST: one that throws in transport may still have landed.
        stage = 2
        String failed = null
        // A Done that is rejected, throws, or answers an unreadable body may still have landed and restarted the rule, so
        // its error is kept and the lifecycle below is still put back. Done is never sent twice.
        try {
            def done = hubInternalPostFormRaw('/installedapp/update/json', 'id=' + id.toString() + '&_action_update=Done&_cancellable=true')
            def doneStatus = done instanceof Map ? done.get('status') : null
            failed = doneStatus != null && doneStatus.toString().isInteger() && doneStatus.toInteger() >= 400 ? 'Native Done was rejected' : null
            def doneBody = done instanceof Map ? done.get('data') : null
            if (failed == null && (doneBody instanceof Map || (doneBody != null && doneBody.toString().trim()))) _nfJson(doneBody)
        } catch (Exception doneError) { failed = 'Native Done failed: ' + (doneError.message ?: doneError.toString()) }
        boolean doneFailed = failed != null
        // A stopped or paused rule that Done restarted is put back before any check below can fail. Its flags are
        // trusted only beside a readable broken flag: a partial read would have a button pressed on a guess.
        def finalHealth = null
        boolean readable = false
        boolean pressed = false
        String unread = null
        try {
            finalHealth = _ndRuleHealth(id)
            readable = finalHealth instanceof Map && finalHealth.broken instanceof Boolean
            if (!created && readable) {
                Boolean nowStopped = _ndNativeFlag(finalHealth, 'stopped')
                Boolean nowPaused = _ndNativeFlag(finalHealth, 'paused')
                if (nowStopped != null && nowStopped != wasStopped) { pressed = true; _nrHandle(id).appButtonHandler('stopRule') }
                if (nowPaused != null && nowPaused != wasPaused) { pressed = true; _nrHandle(id).appButtonHandler('pausRule') }
                finalHealth = _ndRuleHealth(id)
                readable = finalHealth instanceof Map && finalHealth.broken instanceof Boolean
            }
        } catch (Exception restoreError) {
            mcpLogError('rm-native', "Reading or restoring rule ${id}'s stopped and paused state after Done failed: ${restoreError.message}".toString(), restoreError)
            readable = false
            unread = " (${restoreError.message ?: restoreError.toString()}${pressed ? '; a stop or pause button press was already sent' : ''})".toString()
        }
        // Null: present but not a flag, which proves nothing either way (an absent flag reads false).
        Boolean finalStopped = readable ? _ndNativeFlag(finalHealth, 'stopped') : null
        Boolean finalPaused = readable ? _ndNativeFlag(finalHealth, 'paused') : null
        boolean stopped = finalStopped == true
        boolean paused = finalPaused == true
        boolean lifecycleKept = finalStopped != null && finalPaused != null && wasStopped == stopped && wasPaused == paused
        if (!created && (doneFailed || unread != null)) failed = (failed ?: 'Native Done was sent') + (lifecycleKept ? "; the rule's stopped and paused state was verified unchanged" : "; the rule's stopped and paused state could not be verified unchanged: check it with hub_get_rule" + (unread ?: ''))
        else if (unread != null) failed = (failed ?: 'Native rule health failed after Done') + unread
        if (failed == null && !(readable && _nrHandle(id).broken() == false)) failed = 'Native rule health failed after Done'
        if (failed == null && (finalStopped == null || finalPaused == null)) failed = 'Native stopped or paused state could not be read after Done'
        if (failed == null && !created && !lifecycleKept) failed = 'Native stopped or paused state was not preserved'
        if (failed != null) throw new IllegalStateException(failed)
        def status = _ndJson(hubInternalGet("/installedapp/statusJson/${id}"))
        if (!(status instanceof Map) || !status.containsKey('eventSubscriptions') || !status.containsKey('scheduledJobs')) throw new IllegalStateException('Native activation status is unavailable')
        def subscriptions = status.get('eventSubscriptions')
        def jobs = status.get('scheduledJobs')
        if (subscriptions == null || jobs == null) {
            def installed = status.installedApp
            if (!(installed instanceof Map) || installed.id?.toString() != id.toString() || installed.installed != true) throw new IllegalStateException('Native activation status cannot verify an installed rule')
            if (subscriptions == null) subscriptions = []
            if (jobs == null) jobs = []
        }
        if (!(subscriptions instanceof List) || !(jobs instanceof List)) throw new IllegalStateException('Native activation status is malformed')
        if (stopped && (jobs || subscriptions)) throw new IllegalStateException('Stopped native rule retained jobs or subscriptions')
        def persistedState = _nrState(id)
        def actualTriggers = persistedState.capabstrue instanceof Map ? persistedState.capabstrue.findAll { key, value -> key.toString().isInteger() && key.toString().toInteger() > 0 && value?.toString()?.trim() } : [:]
        def requiredTokens = persistedState.eval instanceof Map ? _nmEntry(persistedState.eval, '0') : null
        // Native Done evaluates expression 0 into stPrimed before removing trigger subscriptions.
        boolean suppressed = !stopped && !paused && _nrHandle(id).getSetting('useST') == true &&
            persistedState.hasPredicate == true && requiredTokens instanceof List && !requiredTokens.isEmpty() &&
            persistedState.stPrimed == false
        if (actualTriggers && !stopped && !paused && !suppressed && subscriptions.empty && jobs.empty) throw new IllegalStateException('Native triggers have no active subscriptions or scheduled jobs')
        if (created && getAppByAppId(id)?.installed != true) throw new IllegalStateException('Native rule is not installed after Done')
        // The same masking as hub_get_app_config's App Status lists: an app may pass credentials as subscription or job data.
        Map lists = _appStatusLists(subscriptions, jobs)
        Map written = [success: true, ruleId: id, created: created, results: outcomes, broken: false, stopped: stopped, paused: paused, eventSubscriptions: lists.eventSubscriptions, scheduledJobs: lists.scheduledJobs, verification: 'Native persistence, compilation and activation checks passed; verify intended device behavior independently.']
        if (suppressed) written.subscriptionSettle = 'SUPPRESSED: Required Expression is false'
        // An oversized result is replaced whole by the size guard, which would drop this receipt and its backup key
        // (added after this returns, so room is kept for it): long lists are cut to counts first.
        int budget = _responseSizeLimitBytes() - 3072
        if (_ndEnvelopeBytes(written) > budget) {
            written.remove('eventSubscriptions')
            written.remove('scheduledJobs')
            written.eventSubscriptionCount = subscriptions.size()
            written.scheduledJobCount = jobs.size()
            written.results = outcomes.collect { [part: it.part, success: it.result?.success] }
            written.trimmed = "The receipt is too large to return whole: results keep each change's part and success, and the activation lists are counts. hub_get_app_config(appId: ${id}, includeStatus: true) lists the subscriptions and jobs.".toString()
        }
        // The write is verified by now, so a failed read-back, returned or thrown, must not read as a failed write that a caller would re-send.
        Map document
        try { document = toolGetRuleDocument([ruleId: id, sections: ['actions', 'conditions', 'triggers', 'expressions', 'variables']], false) }
        catch (Exception readBack) { document = [success: false, error: readBack.message ?: readBack.toString()] }
        if (document.success == false) {
            written.documentReadError = document.error
            written.note = "The changes were written and verified; only reading the rule back failed. Do not send them again: read the rule with hub_set_rule(operation:'read') or hub_get_rule. See hub_get_tool_guide(section='set_rule_reference')."
        } else if (document.pagedSection != null || _ndEnvelopeBytes(written + [document: document]) > budget) {
            // A read-back that came back paged is left out the same way: its note would have the caller send the write again.
            written.documentOmitted = true
            written.note = "The changes were written and verified; the rule's document is too large to return with them. Do not send them again: read it one section at a time with hub_set_rule(operation:'read', args:{sections:['actions'], cursor:'', limit:50}) and follow nextCursor. See hub_get_tool_guide(section='set_rule_reference')."
        } else written.document = document
        written
    } catch (Exception failure) {
        mcpLogError('rm-native', "hub_set_rule ${created ? 'create' : 'update'} failed for rule ${id}: ${failure.message}".toString(), failure)
        [success: false, partial: started, ruleId: id, created: created, results: outcomes, remaining: changes.drop(outcomes.size()), error: failure.message?.toString(), note: _ndFailureNote(started, created, stage)]
    }
    }.call(null)
}

// What the caller should do after a failed batch: the hub log carries the detail, the response the recovery. stage is
// _ndApply's: 1 when every change was written before a check failed, 2 when Done was pressed too.
private String _ndFailureNote(boolean landed, boolean created, int stage = 0) {
    String written = stage == 2 ? "Every change was written and Done was sent, but it may or may not have taken, or a check after it failed. Do not send the changes again: read the rule with hub_get_rule before anything else." :
        stage == 1 ? "Every change was written, but a check after the write failed, so Done was not pressed. Do not send the changes again: read the rule with hub_get_rule, then repair it or restore the backup." :
        landed ? "Changes before the failure may have been written; read the rule with hub_set_rule(operation:'read') and resubmit only what is missing." : 'No change was written.'
    String recovery = created ? ' The new rule exists; delete it with hub_delete_native_app if it is not wanted.' : " An update's response carries backup.backupKey for hub_restore_backup when pre-edit backups are on."
    (written + recovery + " See hub_get_tool_guide(section='set_rule_reference').").toString()
}

Map toolUpdateRuleDocument(Map args) {
    Long id = _ndId(args.ruleId)
    List changes = _ndChanges(args.changes)
    requireDestructiveConfirm(args.confirm)
    _requireUnprotectedAppMutation(id, 'edit native rule document')
    String appKey = _nfKey(id)
    if (NATIVE_FORM_BUSY.putIfAbsent(appKey, true) != null) throw new IllegalStateException('This rule already has an active native editing operation')
    boolean attempted = false
    try {
        _nrHandle(id)
        if (getAppByAppId(id)?.disabled == true) throw new IllegalArgumentException('Enable the native rule before editing its document')
        // A rule that is already broken is refused here, not reported broken after the write; hub_get_rule reports the
        // same health, so the read and the refusal agree. Only broken() refuses: a row Rule Machine's page marks
        // without calling the rule broken is editable in the Hubitat UI, so the write is checked for new marks instead.
        // The health check's marked rows and conditions in use serve the batch check and the write's baseline, so the
        // rule's labels and settings are read once before the write.
        Map marked = [:]
        Map beforeHealth = _rmCheckRuleHealth(id as Integer, marked)
        if (beforeHealth.broken == true) {
            String rows = beforeHealth.brokenRows ? ': ' + beforeHealth.brokenRows.collect { "${it.kind} ${it.id}" + (it.text ? " ('${it.text}')" : '') }.unique().join('; ') : ''
            return [success: false, partial: false, ruleId: id, broken: true, brokenRows: beforeHealth.brokenRows, checkErrors: beforeHealth.checkErrors,
                error: "Rule ${id} is already broken${rows}; no change was written.".toString(),
                note: "Repair the broken rows first: open the rule with hub_set_rule(operation:'form') or in the Hubitat UI and replace or remove what refers to the missing device or variable, then retry. hub_get_rule lists the broken rows; see hub_get_tool_guide(section='set_rule_reference').".toString()]
        }
        // disabledActions stays null only when the rule's compiled state went unread or is malformed; the writes below
        // verify against that same state, so a rule whose state cannot be read is not edited either. Unread marked
        // rows would let a newly marked row go unnoticed.
        boolean unread = beforeHealth.broken == null || beforeHealth.disabledActions == null || beforeHealth.brokenRows == null || !marked
        if (unread) {
            String why = beforeHealth.broken == null ? "broken flag could not be read before the write, so whether it is already broken is unknown" :
                beforeHealth.disabledActions == null ? "compiled state could not be read before the write, so it is not known to be intact" :
                "marked rows could not be read before the write, so a newly marked row would go unnoticed"
            return [success: false, partial: false, ruleId: id, broken: beforeHealth.broken, checkErrors: beforeHealth.checkErrors,
                error: "Rule ${id}'s ${why}; no change was written.".toString(),
                note: "checkErrors says why. Check that the hub answers with hub_get_info, then read the rule with hub_get_rule: send the update once its health reads broken:false and lists disabledActions and brokenRows. If it stays unreadable, check the rule in the Hubitat UI or with hub_set_rule(operation:'form') before editing it. See hub_get_tool_guide(section='set_rule_reference')."]
        }
        try { _ndPreflight(id, changes, marked) }
        catch (Exception failure) { return [success: false, partial: false, ruleId: id, error: failure.message?.toString()] }
        Map backup
        try { backup = _ndEditBackup(id, 'pre-document-update') }
        catch (Exception failure) { return [success: false, partial: false, ruleId: id, error: "Pre-edit backup failed; no document changes were applied: ${failure.message}".toString()] }
        attempted = true
        Map written = _ndApply(id, changes, false, marked) + [backup: backup]
        if (marked.rows) written.brokenRowsBefore = marked.rows
        written
    } finally {
        if (attempted) NATIVE_FORM_CONTEXTS.entrySet().findAll { entry -> entry.value.app_id?.toString() == id.toString() }.each { entry -> NATIVE_FORM_CONTEXTS.remove(entry.key, entry.value) }
        NATIVE_FORM_BUSY.remove(appKey)
    }
}

private Map _ndEditBackup(Long id, String reason) {
    if (_effectiveBackupSetting('backupBeforeRuleEdits') == 'false') return [skipped: true, reason: 'Automatic pre-edit backups are disabled']
    Map backup = _rmBackupRuleSnapshot(id.toString().toInteger(), reason, true)
    if (!(backup?.backupKey instanceof String) || !backup.backupKey) throw new IllegalStateException('No recovery handle was returned')
    backup
}

Map toolCreateRuleDocument(Map args) {
    Long parentId = _ndId(args.parentId)
    def requestedName = args.get('name')
    if (!(requestedName instanceof String) || !requestedName.trim() || requestedName.size() > 200) throw new IllegalArgumentException('A rule name is required (maximum 200 characters)')
    String name = requestedName.trim()
    List changes = _ndChanges(args.changes, true)
    requireDestructiveConfirm(args.confirm)
    _requireUnprotectedAppMutation(parentId, 'create native rule child')
    def parent = getAppByAppId(parentId)
    if (parent?.appType?.namespace != 'hubitat' || parent?.appType?.name != 'Rule Machine') throw new IllegalArgumentException('parentId must identify native Rule Machine: hub_list_apps(scope:"instances") lists its app id')
    try { _ndPreflight(null, changes) }
    catch (IllegalArgumentException failure) { return [success: false, partial: false, error: failure.message?.toString()] }
    Long id = null
    boolean verifiedChild = false
    try {
        def response = hubInternalGetRaw("/installedapp/createchild/hubitat/Rule-5.1/parent/${parentId}")
        id = _nrCreatedAppId(response)
        if (id == null) return [success: false, partial: true, error: 'Native child creation did not return its rule ID; inspect the parent before retrying']
        def child = getAppByAppId(id)
        if (child?.parentAppId?.toString() != parentId.toString() || child?.appType?.namespace != 'hubitat' || child?.appType?.name != 'Rule-5.1') throw new IllegalStateException('Created child did not match the requested native parent and rule type')
        verifiedChild = true
        _nrHandle(id).initState()
        _nrHandle(id).updateSetting('origLabel', [type: 'text', value: name])
        _nrHandle(id).updateLabel(name)
        _ndApply(id, changes, true)
    } catch (Exception failure) {
        mcpLogError('rm-native', "hub_set_rule create failed for rule ${id} under parent ${parentId}: ${failure.message}".toString(), failure)
        [success: false, partial: true, ruleId: verifiedChild ? id : null, created: verifiedChild, error: failure.message?.toString(), note: verifiedChild ? _ndFailureNote(true, true) : 'No rule was created.']
    }
}

boolean _isRuleReadCall(String toolName, args) {
    toolName == 'hub_set_rule' && args instanceof Map && (!args.containsKey('operation') || args.operation == 'read' || args.confirm != true)
}

// hub_set_native_app's help and unconfirmed schema replies write nothing, so they need no write worker,
// write slot or best-practice key; they are still a write tool's calls, gated by the Write master.
boolean _isNativeAppSchemaOnlyCall(args) {
    args instanceof Map && (!args.containsKey('operation') || args.confirm != true)
}

private Map _ndOperationSchema(String operation, String toolName = 'hub_set_rule') {
    def changes = [type: 'array', minItems: operation == 'create' ? 0 : 1, maxItems: 100,
        items: [type: 'object', additionalProperties: false, properties: [part: [type: 'string', enum: ['condition', 'trigger', 'action', 'expression', 'required', 'variable', 'terminator', 'delete', 'order']], value: [type: 'object']], required: ['part', 'value']]]
    Map properties = [:]
    List required = []
    if (operation == 'read') {
        properties.sections = [type: 'array', items: [type: 'string', enum: ['actions', 'conditions', 'triggers', 'expressions', 'variables', 'settings']]]
        properties.cursor = [type: 'string']
        properties.limit = [type: 'integer', minimum: 1, maximum: 100]
    } else if (operation == 'create') {
        properties = [parentId: [type: 'integer', minimum: 1, description: 'The installed Rule Machine app id (the parent of every rule): hub_list_apps(scope:"instances") lists it as "Rule Machine".'], name: [type: 'string', minLength: 1, maxLength: 200], changes: changes]
        required = ['parentId', 'name', 'changes']
    } else if (operation == 'update') {
        properties.changes = changes
        required = ['changes']
    } else if (operation == 'form') {
        Map names = _nfKeyNames(toolName)
        properties = [steps: [type: 'array', minItems: 1, maxItems: 16, items: [type: 'object', additionalProperties: false,
            properties: [kind: [type: 'string', enum: ['open', 'submit']], page: [type: 'string'], from: [type: 'string'], (names.expect): [type: 'string'], fields: [type: 'object'], activate: [type: 'string']], required: ['kind']]],
            (names.options): [type: 'array', maxItems: 32, items: [type: 'string']]]
        required = ['steps']
    } else throw new IllegalArgumentException('Unknown rule operation; use read, create, update or form')
    [type: 'object', additionalProperties: false, properties: properties, required: required]
}

private void _ndValidateSchema(Object value, Map schema, String path, boolean complete) {
    boolean valid = schema.type == 'object' ? value instanceof Map : schema.type == 'array' ? value instanceof List :
        schema.type == 'string' ? value instanceof String : schema.type == 'boolean' ? value instanceof Boolean :
        schema.type == 'integer' ? value instanceof Number && value.toString().isLong() : true
    if (!valid || (schema.enum != null && !(value in schema.enum))) throw new IllegalArgumentException("Invalid ${path}")
    if (schema.type == 'object') {
        def unknown = schema.additionalProperties == false ? value.keySet().find { !schema.properties.containsKey(it) } : null
        if (unknown != null) throw new IllegalArgumentException("Unknown ${path} property: ${unknown}".toString())
        if (complete && schema.required?.any { !value.containsKey(it) }) throw new IllegalArgumentException("Missing required ${path} property")
        value.each { key, item -> if (schema.properties?.containsKey(key)) _ndValidateSchema(item, schema.properties.get(key), "${path}.${key}".toString(), complete) }
    } else if (schema.type == 'array') {
        if ((schema.minItems != null && value.size() < schema.minItems) || (schema.maxItems != null && value.size() > schema.maxItems)) throw new IllegalArgumentException("Invalid ${path} length")
        value.each { item -> _ndValidateSchema(item, schema.items, path, complete) }
    } else if (schema.type == 'string') {
        if ((schema.minLength != null && value.trim().size() < schema.minLength) || (schema.maxLength != null && value.size() > schema.maxLength)) throw new IllegalArgumentException("Invalid ${path} length")
    } else if (schema.minimum != null) {
        if (value < schema.minimum) throw new IllegalArgumentException("Invalid ${path}")
    }
}

Map toolSetRule(Map request) {
    if (request.containsKey('confirm') && !(request.confirm instanceof Boolean)) throw new IllegalArgumentException('confirm must be boolean')
    // The rule tools share one id check, so a numeric string or an id past the int range reads as it does in hub_call_rule.
    if (request.containsKey('ruleId')) _ndId(request.ruleId)
    if (request.containsKey('args') && !(request.args instanceof Map)) throw new IllegalArgumentException('args must be an object')
    if (!request.containsKey('operation')) {
        if (request.args) throw new IllegalArgumentException('Choose an operation before supplying args')
        return [operations: ['read', 'create', 'update', 'form'], usage: 'Choose operation for its schema. Writes return schema until confirm=true. See set_rule_reference.']
    }
    if (!(request.operation instanceof String)) throw new IllegalArgumentException('operation must be a string')
    String operation = request.operation
    Map schema = _ndOperationSchema(operation)
    Map arguments = request.args ?: [:]
    boolean execute = operation == 'read' || request.confirm == true
    _ndValidateSchema(arguments, schema, 'args', execute)
    if (operation == 'create' && request.containsKey('ruleId')) throw new IllegalArgumentException('create uses args.parentId, not ruleId')
    if (!execute) {
        Map properties = [operation: [type: 'string', enum: [operation]], args: schema, confirm: [type: 'boolean'], bestPracticeKey: [type: 'string']]
        List required = ['operation', 'args', 'confirm']
        if (operation != 'create') { properties.ruleId = [type: 'integer', minimum: 1]; required << 'ruleId' }
        return [operation: operation, confirmRequired: true, inputSchema: [type: 'object', additionalProperties: false, properties: properties, required: required],
            usage: 'Supply these arguments with confirm=true and a recent backup to execute. See set_rule_reference.']
    }
    if (operation != 'create' && !request.containsKey('ruleId')) throw new IllegalArgumentException('ruleId is required')
    if (operation == 'read') return toolGetRuleDocument(arguments + [ruleId: request.ruleId])
    if (operation == 'create') return toolCreateRuleDocument(arguments + [confirm: true])
    if (operation == 'update') return toolUpdateRuleDocument(arguments + [ruleId: request.ruleId, confirm: true])
    Long id = _ndId(request.ruleId)
    def target = getAppByAppId(id)
    boolean rmParent = target?.appType?.namespace == 'hubitat' && target?.appType?.name == 'Rule Machine'
    if (target?.id?.toString() != id.toString() || !rmParent) _nrRequireEditableRule(target, id)
    _ndCamelForm(toolCallAppForm(arguments + [ruleId: request.ruleId, confirm: true], 'hub_set_rule'))
}

// hub_set_rule spells its keys in camelCase; the form code it shares with hub_set_native_app writes snake_case,
// so the result, its completed steps, fields and controls are renamed here. Values (input names, kinds) stay.
private Map _ndCamelForm(Map result) {
    Closure camel = { Map row -> row.collectEntries { k, v -> [(k.toString().replaceAll(/_([a-z])/) { all, c -> c.toUpperCase() }): v] } }
    Map renamed = camel(result)
    ['completed', 'fields', 'controls'].each { key ->
        if (renamed.get(key) instanceof List) renamed.put(key, renamed.get(key).collect { it instanceof Map ? camel(it) : it })
    }
    renamed
}

def _getAllToolDefinitions_partNativeDocuments() {
    return [[
      name: 'hub_set_rule', description: 'Read, create or update native Rule Machine rules, or use their native forms. Omit operation for help; select a write operation without confirm for its schema. Writes require confirm=true and a recent backup; partial changes can remain. Guide: set_rule_reference.',
      inputSchema: [type: 'object', additionalProperties: false, properties: [operation: [type: 'string', enum: ['read', 'create', 'update', 'form']], ruleId: [type: 'integer', minimum: 1], args: [type: 'object'], confirm: [type: 'boolean'], bestPracticeKey: [type: 'string']]]]]
}

def _readOnlyToolNames_partNativeDocuments() { [] }

def _toolDisplayMeta_partNativeDocuments() {
    [hub_set_rule: [title: 'Set Native Rule', summary: 'Read, create and update native rules or use their native editor forms.']]
}

String _ndGuide() {
    return '''## Set Rule Reference

Call hub_set_rule without operation for help, or choose a write operation without confirm to inspect its schema without changing the rule. Top-level ruleId identifies the target; args cannot override it or confirm. When mandatory guide acknowledgment is enabled, pass its key as top-level bestPracticeKey alongside operation, ruleId, args and confirm.

Read hub_set_rule(operation:"read",ruleId,args:{sections:[...]}) before editing an existing native rule; hub_get_rule returns the same document from the read-only tools, with the local variables as a localVariables list in place of the variables section. A read does not open the rule's page: it returns health, the same report hub_get_rule returns (broken from the rule's own check, brokenRows for the rows its page marks, paused, stopped, privateBoolean, structuralIssues; see builtin_app_tools_rules). The sections come from native state and typed settings. Sections: actions, conditions, triggers, expressions, variables, settings. Action IDs and order are native IDs, preserved for in-place edits; omitted parts and unknown settings remain native-owned.

For large rules, read one section with args:{sections:["settings"],cursor:"",limit:50}. Paging requires exactly one section; limit is 1..100 (default 50). The section keeps its usual map or list shape. Pass the returned top-level nextCursor into args.cursor until nextCursor is null. page returns offset, limit, total and revision, a hash of the complete section; restart if revision changes. Pages may contain fewer than limit rows to fit the response size cap; a row too large to send beside the health alone returns success:false with the health, never truncated. Actions retain native order, settings sort by name, and map sections sort by string key. Omit cursor and limit for an unpaged read; one too large for the response cap returns health and the parts that fit whole, pages the first part that does not (pagedSection, nextCursor) and lists the other parts that do not fit in omittedSections.

Writes require confirm=true and a recent hub backup. Automatic pre-edit recovery is enabled by default: an update captures one recovery point for its whole changes batch, and a rule form captures one before its first render and reuses it through that workflow. Recovery metadata is returned as backup, including after partial failure; capture failure stops the requested edit. Rule Machine parent creation/navigation has no existing child configuration to recover; opening the child starts its own protected workflow. hub_set_rule(operation:"update",ruleId,args:{changes:[...]},confirm:true) applies changes in order. hub_set_rule(operation:"create",args:{parentId,name,changes:[...]},confirm:true) creates a Rule-5.1 child under native Rule Machine; parentId is the installed Rule Machine app's id, which hub_list_apps(scope:"instances") lists. A failed creation can leave a native child: inspect returned ruleId and parent before retrying. A partial update can leave preceding changes committed; inspect before resubmitting. An update first reads the same health and, before any backup or write, refuses a rule that is already broken (its own broken() check), naming each marked row by kind and native id (trigger 2), or a rule whose broken flag, compiled state or marked rows cannot be read, with checkErrors saying why: repair broken rows with operation:"form" or in the Hubitat UI. A rule whose page marks rows without being broken (a condition whose device was deleted) is updated: brokenRowsBefore lists the rows marked before the write, and the write fails only if it adds a newly marked row, so it can also repair them. An action change whose action comes back marked or with no label is refused and rolled back, even when it was marked before: its results entry reads {part:"action",result:{success:false,rolledBack:true}}. An action left gated on a marked condition turns the rule's own broken() check true, so move the action to another condition, or repair or delete that condition, first. If that rollback does not verify (result.rolledBack:false in that entry, and the update reads partial:true), read the rule and repair it, or restore the pre-edit backup if one was returned. After a check that follows the writes fails, every change is on the rule: do not send them again, read the rule and repair it or restore the backup.

Each change is {part,value}. Supported parts:
- condition or trigger: {id?,capability,fields:[{name,type,value}]}.
- action: {id?,type,subtype,fields:[{name,type,value}],expression?,events?,condition?}. condition is an existing native condition ID for a Simple Conditional Action.
- expression: {id,tokens}, id 1 or more; required: {tokens} sets the Required Expression (expression 0, which an expression change refuses).
- getWaitEvents actions accept events:[{capability,fields}] (1 through 16 native event rows); an edit preserves the event count. Duration fields remain native typed fields.
- getWaitRule actions accept expression tokens, with optional native timeout/duration fields. New IF, ELSE-IF and Wait for Expression actions require expression tokens.
- variable: {operation:create|set|delete,name,type?,value?}. Create types: integer,bigdecimal,boolean,string,datetime. DateTime uses native ISO text, e.g. 2026-01-01T12:30:00.000-0500; native date-only/time-only sentinels are accepted.
- terminator: {subtype:endIf|else|endRep}.
- delete: {part:action|condition|trigger,id}; a condition still in use (Rule Machine lists it, a ticked Conditional Trigger, the Required Expression, an IF or wait expression or a conditional action uses it) must be detached first. Detaching and deleting can share a batch.
- order: {ids:[native action IDs]}; a complete exact permutation is required.

Typed fields use exact native names containing # for the allocated ID, for example logmsg.#. Expressions use existing native condition IDs and (, ), NOT, AND, OR, XOR. Omit an id to append; provide an existing native id to edit. Field names and capability/action variants require verified native contracts; not every capability-specific variant is supported. Discover native options through operation:"form" when a document contract is unavailable. Unknown fields cannot be assumed to compile correctly merely because a setting persists. A successful result establishes native model checks, typed readback, native editor diagnostics, expression-reference checks and nonbroken health with activation status; independently verify device behavior and native UI editing for the intended automation.

Example changes: [{part:"action",value:{type:"messageActs",subtype:"getLogMsg",fields:[{name:"logmsg.#",type:"textarea",value:"Document action"}]}}]. Writes render the native trigger/action/main pages, check health, submit native Done, and read subscriptions plus a fresh document (without health: the write's own checks stand for it); a document too large to send with the result is left out (documentOmitted:true), so read it by section, and a receipt still too large keeps each change's part and success and counts the subscriptions and jobs (trimmed says so). These pages can mutate native state; a document read renders none of them.

## Native Rule Forms

hub_set_rule(operation:"form",ruleId,args:{steps:[{kind:"open"}]},confirm:true) operates an enabled Rule-5.1 or Button Rule-5.1 rule, or its Rule Machine parent; hub_set_native_app(operation:"form",app_id,...) runs the same steps on any other classic app, spelling expectPage and optionsFor as expect_page and options_for, and its result keys in snake_case (control_id, created_app_id, navigated_app_id, reopen_required). Read action variants, input names and option values from the returned forms instead of guessing them. Even open renders execute app code and can change state or cause side effects. An open's optional page names a registered native page, and from names the page that links to it, so a sub-page opened directly offers its Done back to that parent. Follow returned controls for page parameters and breadcrumbs. A create_child control creates the parent's native child type, returns createdAppId and closes the parent context; open the child separately to configure it. Apps are deleted with hub_delete_native_app.

Submit with {kind:"submit",expectPage:"last returned page",fields:{"native field name":value},activate:"returned controlId"}; without activate it saves the fields and refreshes the page. Control IDs tell apart native buttons that share a name, such as editing and deleting the same variable. With Hide passwords and credentials on (the default), form results mask password-typed fields and fields whose names look like credentials (API keys, tokens, passwords, PINs, lock codes) with the same marker hub_get_app_config uses; with it off they read as stored, except for a per-client token. A page with both a next page and a done page renders its Done as the _action_done control, which the hub answers by saving the page and rendering that same page again. An app without pages also shows the hub's default fields, label (the app's name) and mode[mode] (a list of mode IDs from its options), and a page's own label and mode elements are the same two fields; the hub does not always keep the label posted with mode[mode], so after such a submit the label is checked and posted again on its own when the hub put back an older one (labelRestored). Native enum keys, device IDs, booleans and single/multiple cardinality are checked before the request. A file-text input takes the file's text, not a path. Each submit posts the supplied settings and, as the browser does, the shown default of every other input with no stored value; a password's default is never posted, and page code can still change its own settings. args.optionsFor:["device field name"] expands device choices on the final returned page.

A batch holds 1 through 16 steps, each using the form the step before it returned, so dependent fields require another submit after they appear. A button handler runs before its step's fields are saved, as in the browser: submit the fields it needs in an earlier step. A button without submitOnChange reloads and needs a separate field submission.

The context expires after five minutes or an app reload and keeps no field values, messages or option labels; limits are 24 KiB of descriptors per app, 96 KiB in all, and eight caller workflows. Opening another app replaces only that caller's context; reopen the previous app before submitting it again. expectPage guards the caller's latest context, not edits in a browser, so avoid editing concurrently. After an error, completed operations stay and the last can be uncertain: inspect the app and reopen instead of replaying buttons. Terminal navigation closes the context without following redirects; a local link or script navigation to the same app or its direct child returns navigatedAppId and navigatedPage with closed:true, to open separately. No script is evaluated: app JavaScript, custom HTML inputs, external links and other unsupported browser controls are listed in unsupported. Success means the native form operation returned, not that the app's intended runtime behavior was independently verified.'''
}
