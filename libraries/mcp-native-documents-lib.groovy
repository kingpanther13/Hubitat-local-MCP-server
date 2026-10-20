library(name: "McpNativeDocumentsLib", namespace: "mcp", author: "kingpanther13", description: "Native Rule Machine document tools.")

private Long _ndId(Object id) {
    if (id == null || !id.toString().isLong() || id.toString().toLong() <= 0L) throw new IllegalArgumentException('A positive native application ID is required')
    id.toString().toLong()
}

private Object _ndJson(Object value) {
    value instanceof Map || value instanceof List ? value : new groovy.json.JsonSlurper().parseText(value?.toString() ?: '{}')
}

private List _ndSettings(Long id) {
    def response = _ndJson(hubInternalGet("/installedapp/settings/${id}"))
    if (!(response instanceof Map) || !(response.settings instanceof List)) throw new IllegalStateException('Native settings are unavailable')
    response.settings.collect { row ->
        if (!(row instanceof Map) || !(row.name instanceof String)) throw new IllegalStateException('Unexpected native setting row')
        [name: row.name, type: row.type, multiple: row.multiple, value: row.value, device_ids: row.deviceList instanceof Map ? row.deviceList.keySet().collect { it.toString() }.sort() : null]
    }
}

private List _ndStructuralIssues(Map nativeState) {
    def issues = []
    def actions = nativeState.actions instanceof Map ? nativeState.actions : [:]
    def conditions = nativeState.capabsfalse instanceof Map ? nativeState.capabsfalse : [:]
    def expressions = nativeState.eval instanceof Map ? nativeState.eval : [:]
    def expressionIds = []
    def blocks = []
    if (nativeState.inIf == true) issues << 'Native editor has an unfinished IF block'
    (nativeState.actionList instanceof List ? nativeState.actionList : []).each { id ->
        def action = _nmEntry(actions, id.toString())
        if (!(action instanceof Map)) issues << 'Native action order references a missing action'
        else {
            if (action.rule != null) expressionIds << action.rule.toString()
            if (action.method == 'getIfThen') blocks << 'if'
            else if (action.method in ['getElse', 'getElseIf', 'getElseIfThen']) {
                if (!blocks || blocks.last() != 'if') issues << 'Native ELSE has no matching IF'
            } else if (action.method == 'getEndIf') {
                if (!blocks || blocks.last() != 'if') issues << 'Native END-IF has no matching IF'
                else blocks.remove(blocks.size() - 1)
            }
        }
    }
    if (blocks) issues << 'Native IF block has no END-IF'
    if (nativeState.hasPredicate == true) expressionIds << '0'
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
        if ((node.type ?: node.element) in ['paragraph', 'href']) {
            def description = node.description?.toString() ?: ''
            if (description =~ /(?is)<span\b[^>]*color\s*:\s*red[^>]*>[^<]*\*\*Broken [^*]+\*\*/) issues << 'Native editor reports a broken condition, trigger or action'
        }
        if (node.error || node.errors) issues << 'Native editor returned an error'
        ['sections', 'body', 'children'].each { key -> if (node.get(key) != null) issues.addAll(_ndPageIssues(node.get(key))) }
    }
    issues
}

private Map _ndRender(Long id, String page) {
    def rendered
    switch (page) {
        case 'selectTriggers': rendered = _nrHandle(id).selectTriggers(); break
        case 'selectActions': rendered = _nrHandle(id).selectActions([:]); break
        case 'mainPage': rendered = _nrHandle(id).mainPage(); break
    }
    if (!(rendered instanceof Map) || rendered.name != page || !(rendered.sections instanceof List) || rendered.sections.isEmpty()) throw new IllegalStateException('Native editor page is unavailable or unexpected')
    def issues = _ndPageIssues(rendered)
    if (issues) throw new IllegalStateException(issues.join('; '))
    rendered
}

Map toolGetRuleDocument(Map args) {
    Long id = _ndId(args.rule_id)
    def allowed = ['actions', 'conditions', 'triggers', 'expressions', 'variables', 'settings']
    def sections = args.sections == null ? allowed : args.sections
    if (!(sections instanceof List) || sections.any { !(it in allowed) }) throw new IllegalArgumentException('Unknown document section')
    _nrHandle(id)
    Map nativeState = _nrState(id)
    def structuralIssues = _ndStructuralIssues(nativeState)
    def document = [rule_id: id, validation: [structuralIssues: structuralIssues, uiChecked: false]]
    try {
        def health = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
        if (health instanceof Map) document.validation.structuralIssues = _ndStructuralIssues(nativeState + health)
        document.nativeHealth = health instanceof Map && health.broken instanceof Boolean ? [available: true, broken: health.broken, stopped: health.stopped == true, paused: health.paused == true] : [available: false]
    } catch (Exception failure) { document.nativeHealth = [available: false, error: failure.message?.toString()] }
    if ('actions' in sections) document.actions = (nativeState.actionList instanceof List ? nativeState.actionList : []).collect { actionId ->
        [id: actionId.toString(), value: nativeState.actions instanceof Map ? _nmEntry(nativeState.actions, actionId.toString()) : null]
    }
    if ('conditions' in sections) document.conditions = nativeState.capabsfalse instanceof Map ? nativeState.capabsfalse : [:]
    if ('triggers' in sections) document.triggers = nativeState.capabstrue instanceof Map ? nativeState.capabstrue : [:]
    if ('expressions' in sections) document.expressions = nativeState.eval instanceof Map ? nativeState.eval : [:]
    if ('variables' in sections) document.variables = _nrHandle(id).getAllLocalVars()
    if ('settings' in sections) document.settings = _ndSettings(id)
    document
}

private List _ndChanges(Object changes) {
    if (!(changes instanceof List) || !changes || changes.size() > 100) throw new IllegalArgumentException('changes must contain between 1 and 100 changes')
    changes.each { change ->
        if (!(change instanceof Map) || change.keySet().any { !(it in ['part', 'value']) } || !(change.value instanceof Map)) throw new IllegalArgumentException('Each change requires part and value')
        def value = change.value
        def keys = [condition: ['id', 'capability', 'fields'], trigger: ['id', 'capability', 'fields'],
                    action: ['id', 'type', 'subtype', 'fields', 'expression', 'events'], expression: ['id', 'tokens'],
                    variable: ['operation', 'name', 'type', 'value'], required: ['tokens'], delete: ['part', 'id'], order: ['ids'], terminator: ['subtype']]
        if (!keys.containsKey(change.part) || value.keySet().any { !(it in keys.get(change.part)) }) throw new IllegalArgumentException('Unknown change part or field')
        if (value.containsKey('id') && (value.id == null || !value.id.toString().isInteger() || value.id.toString().toInteger() < (change.part == 'expression' ? 0 : 1))) throw new IllegalArgumentException('Invalid native row ID')
        if (change.part in ['condition', 'trigger', 'action']) {
            if (!(value.get('fields') instanceof List)) throw new IllegalArgumentException('Native typed fields are required')
            value.get('fields').each { row ->
                if (!(row instanceof Map) || row.keySet().any { !(it in ['name', 'type', 'value']) } || !(row.name instanceof String) || !row.name.contains('#') || !(row.type instanceof String) || !row.containsKey('value') || row.value == null) throw new IllegalArgumentException('Invalid native typed field')
                boolean device = row.type == 'capability' || row.type.startsWith('capability.')
                if (!(row.type in ['text', 'textarea', 'password', 'enum', 'mode', 'bool', 'number', 'decimal', 'time']) && !device) throw new IllegalArgumentException('Unsupported typed field')
                def values = row.value instanceof List ? row.value : [row.value]
                if (row.value instanceof List && !(device || row.type in ['enum', 'mode'])) throw new IllegalArgumentException('Unsupported list field')
                if (values.any { !(it instanceof String || it instanceof Number || it instanceof Boolean) }) throw new IllegalArgumentException('Typed fields require scalar values')
                if (device && values.any { !it.toString().isLong() || it.toString().toLong() <= 0L }) throw new IllegalArgumentException('Typed device fields require positive IDs')

            }
        }
        if (change.part in ['condition', 'trigger']) {
            if (!(value.capability instanceof String) || !value.capability.trim()) throw new IllegalArgumentException('Native capability is required')
        }
        if (change.part == 'action') {
            if (!(value.type instanceof String) || !value.type.trim() || !(value.subtype instanceof String) || !value.subtype.trim() || (value.subtype.toLowerCase().contains('wait') && value.subtype != 'getWaitEvents')) throw new IllegalArgumentException('A supported native action type and subtype are required')
            if (value.containsKey('expression') && (!(value.expression instanceof List) || !value.expression || !(value.subtype in ['getIfThen', 'getElseIf']))) throw new IllegalArgumentException('Invalid action expression')
        }
        if (change.part == 'action' && value.subtype == 'getWaitEvents') {
            if (!(value.events instanceof List) || !value.events || value.events.size() > 16) throw new IllegalArgumentException('Wait events require between 1 and 16 events')
            if (value.events.any { !(it instanceof Map) || it.keySet().any { key -> !(key in ['capability', 'fields']) } }) throw new IllegalArgumentException('Wait events require capability and fields only')
            _ndChanges(value.events.collect { event -> [part: 'condition', value: event] })
        } else if (value.containsKey('events')) throw new IllegalArgumentException('Only wait-event actions accept events')
        if (change.part == 'expression' && (!value.containsKey('id') || !(value.tokens instanceof List) || !value.tokens)) throw new IllegalArgumentException('Expression ID and tokens are required')
        if (change.part == 'delete' && (!(value.part in ['action', 'condition', 'trigger']) || !value.containsKey('id'))) throw new IllegalArgumentException('Deletion requires a native collection and ID')
        if (change.part == 'order') {
            if (!(value.ids instanceof List) || value.ids.any { it == null || !it.toString().isInteger() || it.toString().toInteger() <= 0 } || value.ids.collect { it.toString() }.unique(false).size() != value.ids.size()) throw new IllegalArgumentException('Ordering requires unique positive native action IDs')
        }
        if (change.part == 'required' && (!(value.tokens instanceof List) || !value.tokens)) throw new IllegalArgumentException('Required expression tokens are required')
        if (change.part == 'terminator' && !(value.subtype in ['endIf', 'else', 'endRep'])) throw new IllegalArgumentException('Unsupported terminator')
        def tokens = change.part in ['expression', 'required'] ? value.tokens : (change.part == 'action' ? value.expression : null)
        if (tokens instanceof List) {
            def operands = [:]
            tokens.each { token -> if (token != null && token.toString().isInteger() && token.toString().toInteger() > 0) operands.put(token.toString(), 'condition') }
            _nmTokens(tokens, operands)
        }
        if (change.part == 'variable') {
            if (!(value.operation in ['create', 'set', 'delete']) || !(value.name instanceof String) || !(value.name ==~ /[A-Za-z_][A-Za-z0-9_ ]{0,79}/) || value.name != value.name.trim()) throw new IllegalArgumentException('Invalid native variable change')
            if (value.operation in ['create', 'set'] && !value.containsKey('value')) throw new IllegalArgumentException('Variable value is required')
            if (value.operation == 'create') _nmLocalValue(value.type?.toString(), value.value)
        }
    }
    changes
}

private void _ndPreflight(Long id, List changes) {
    Map before = _nrState(id)
    Map conditions = before.capabsfalse instanceof Map ? [:] + before.capabsfalse : [:]
    Map expressions = before.eval instanceof Map ? [:] + before.eval : [:]
    boolean allocatedConditions = false
    def pendingExpressions = []
    List actionOrder = before.actionList instanceof List ? before.actionList.collect { it.toString() } : []
    boolean allocatedActions = false
    Map locals = null
    changes.each { change ->
        Map value = change.value
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
        if ((change.part == 'action' && !value.containsKey('id')) || change.part == 'terminator') allocatedActions = true
        if (change.part == 'order' && !allocatedActions) {
            List requested = value.ids.collect { it.toString() }
            if (requested.sort(false) != actionOrder.sort(false)) throw new IllegalArgumentException('Action order must be an exact permutation of existing action IDs')
            actionOrder = requested
        }
        if (change.part == 'delete' && value.part == 'action' && !allocatedActions) {
            if (!actionOrder.contains(value.id.toString())) throw new IllegalArgumentException('Native action does not exist')
            actionOrder = actionOrder.findAll { it != value.id.toString() }
        }
        if (change.part == 'condition' && !value.containsKey('id')) allocatedConditions = true
        if (change.part in ['expression', 'required'] || (change.part == 'action' && value.expression instanceof List)) {
            List tokens = change.part == 'action' ? value.expression : value.tokens
            if (!allocatedConditions) _nmTokens(tokens, conditions)
            if (change.part == 'action') pendingExpressions << tokens
            else {
                String expressionId = change.part == 'required' ? '0' : value.id.toString()
                expressions.keySet().findAll { it.toString() == expressionId }.each { expressions.remove(it) }
                expressions.put(expressionId, tokens)
            }
        }
        if (change.part == 'delete' && value.part == 'condition') {
            String target = value.id.toString()
            boolean referenced = (expressions.values().toList() + pendingExpressions).any { tokens -> tokens instanceof List && tokens.any { it?.toString() == target } }
            referenced = referenced || (before.predCapabs instanceof List && before.predCapabs.any { it?.toString() == target })
            referenced = referenced || (before.actions instanceof Map && before.actions.values().any { it instanceof Map && it.cond?.toString() == target })
            if (referenced) throw new IllegalArgumentException('Referenced native conditions must be detached before deletion')
            conditions.keySet().findAll { it.toString() == target }.each { conditions.remove(it) }
        }
    }
}

private Map _ndApply(Long id, List changes, boolean created) {
    def outcomes = []
    boolean started = created
    try {
        def beforeHealth = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
        if (!(beforeHealth instanceof Map) || !(beforeHealth.broken instanceof Boolean)) throw new IllegalStateException('Native rule health is unavailable before changes')
        boolean wasStopped = beforeHealth.stopped == true
        boolean wasPaused = beforeHealth.paused == true
        for (change in changes) {
            def value = change.value
            started = true
            Map outcome
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
            outcomes << [part: change.part, result: outcome]
            if (outcome?.success != true) return [success: false, partial: created || outcomes.size() > 1 || outcome?.partial == true, rule_id: id, created: created, results: outcomes, remaining: changes.drop(outcomes.size())]
        }
        _ndRender(id, 'selectTriggers')
        _ndRender(id, 'selectActions')
        _ndRender(id, 'mainPage')
        def health = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
        Map renderedState = _nrState(id) + (health instanceof Map ? health : [:])
        def structuralIssues = _ndStructuralIssues(renderedState)
        structuralIssues.addAll(_nmValidateStructure(id, renderedState).structuralIssues ?: [])
        if (structuralIssues) throw new IllegalStateException(structuralIssues.join('; '))
        if (!(health instanceof Map) || health.broken != false) return [success: false, partial: true, rule_id: id, created: created, results: outcomes, broken: health instanceof Map ? health.broken : null, error: 'Native rule health is broken or unverified']
        def done = hubInternalPostFormRaw('/installedapp/update/json', 'id=' + id.toString() + '&_action_update=Done&_cancellable=true')
        def doneStatus = done instanceof Map ? done.get('status') : null
        if (doneStatus != null && doneStatus.toString().isInteger() && doneStatus.toInteger() >= 400) throw new IllegalStateException('Native Done was rejected')
        def finalHealth = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
        if (!(finalHealth instanceof Map) || finalHealth.broken != false) throw new IllegalStateException('Native rule health failed after Done')
        boolean stopped = finalHealth.stopped == true
        boolean paused = finalHealth.paused == true
        if (!created) {
            if (wasStopped != stopped) _nrHandle(id).appButtonHandler('stopRule')
            if (wasPaused != paused) _nrHandle(id).appButtonHandler('pausRule')
            finalHealth = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
            stopped = finalHealth instanceof Map && finalHealth.stopped == true
            paused = finalHealth instanceof Map && finalHealth.paused == true
            if (!(finalHealth instanceof Map) || finalHealth.broken != false || wasStopped != stopped || wasPaused != paused) throw new IllegalStateException('Native stopped or paused state was not preserved')
        }
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
        if (actualTriggers && !stopped && !paused && subscriptions.empty) throw new IllegalStateException('Native triggers have no active subscriptions')
        if (created && getAppByAppId(id)?.installed != true) throw new IllegalStateException('Native rule is not installed after Done')
        [success: true, rule_id: id, created: created, results: outcomes, broken: false, stopped: stopped, paused: paused, eventSubscriptions: subscriptions, verification: 'Native persistence, compilation and activation checks passed; verify intended device behavior independently.', document: toolGetRuleDocument([rule_id: id, sections: ['actions', 'conditions', 'triggers', 'expressions', 'variables']])]
    } catch (Exception failure) {
        [success: false, partial: started, rule_id: id, created: created, results: outcomes, remaining: changes.drop(outcomes.size()), error: failure.message?.toString()]
    }
}

Map toolUpdateRuleDocument(Map args) {
    Long id = _ndId(args.rule_id)
    List changes = _ndChanges(args.changes)
    requireDestructiveConfirm(args.confirm)
    _nrHandle(id)
    if (getAppByAppId(id)?.disabled == true) throw new IllegalArgumentException('Enable the native rule before editing its document')
    try { _ndPreflight(id, changes) }
    catch (IllegalArgumentException failure) { return [success: false, partial: false, rule_id: id, error: failure.message?.toString()] }
    _ndApply(id, changes, false)
}

Map toolCreateRuleDocument(Map args) {
    Long parentId = _ndId(args.parent_id)
    def requestedName = args.get('name')
    if (!(requestedName instanceof String) || !requestedName.trim() || requestedName.size() > 200) throw new IllegalArgumentException('A rule name is required (maximum 200 characters)')
    String name = requestedName.trim()
    List changes = _ndChanges(args.changes)
    requireDestructiveConfirm(args.confirm)
    def parent = getAppByAppId(parentId)
    if (parent?.appType?.namespace != 'hubitat' || parent?.appType?.name != 'Rule Machine') throw new IllegalArgumentException('parent_id must identify native Rule Machine')
    Long id = null
    boolean verifiedChild = false
    try {
        def response = hubInternalGetRaw("/installedapp/createchild/hubitat/Rule-5.1/parent/${parentId}")
        String location = response?.location?.toString()?.trim()?.replaceFirst(/(?i)^Location:\s*/, '')
        location = location?.replaceFirst(/(?i)^https?:\/\/(?:127\.0\.0\.1|localhost|\[::1\])(?::[0-9]+)?(?=\/)/, '')
        def match = location =~ /^\/installedapp\/configure\/(\d+)(?:\?.*)?$/
        if (!match.find()) return [success: false, partial: true, error: 'Native child creation did not return its rule ID; inspect the parent before retrying']
        id = _ndId(match.group(1))
        def child = getAppByAppId(id)
        if (child?.parentAppId?.toString() != parentId.toString() || child?.appType?.namespace != 'hubitat' || child?.appType?.name != 'Rule-5.1') throw new IllegalStateException('Created child did not match the requested native parent and rule type')
        verifiedChild = true
        _nrHandle(id).initState()
        _nrHandle(id).updateSetting('origLabel', [type: 'text', value: name])
        _nrHandle(id).updateLabel(name)
        _ndPreflight(id, changes)
        _ndApply(id, changes, true)
    } catch (Exception failure) {
        [success: false, partial: true, rule_id: verifiedChild ? id : null, created: verifiedChild, error: failure.message?.toString()]
    }
}

def _getAllToolDefinitions_partNativeDocuments() {
    def changes = [type: 'array', minItems: 1, maxItems: 100, items: [type: 'object', additionalProperties: false, properties: [part: [type: 'string', enum: ['condition', 'trigger', 'action', 'expression', 'required', 'variable', 'terminator', 'delete', 'order']], value: [type: 'object']], required: ['part', 'value']]]
    def warning = ' confirm=true and recent hub backup required. Preflight: read native_rule_documents guide/document; verify IDs and fields. Partial changes can remain.'
    return [
        [
            name: 'hub_get_rule_document', description: 'Read native rule structure, typed settings and current local variables; no editor renders.', inputSchema: [type: 'object', additionalProperties: false, properties: [rule_id: [type: 'integer', minimum: 1], sections: [type: 'array', items: [type: 'string', enum: ['actions', 'conditions', 'triggers', 'expressions', 'variables', 'settings']]]], required: ['rule_id']]],
        [
            name: 'hub_update_rule_document', description: 'Update a native rule with ordered {part,value} changes.' + warning, inputSchema: [type: 'object', additionalProperties: false, properties: [rule_id: [type: 'integer', minimum: 1], changes: changes, confirm: [type: 'boolean']], required: ['rule_id', 'changes', 'confirm']]],
        [
            name: 'hub_create_rule_document', description: 'Create/install a native rule with ordered {part,value} changes.' + warning, inputSchema: [type: 'object', additionalProperties: false, properties: [parent_id: [type: 'integer', minimum: 1], name: [type: 'string', minLength: 1, maxLength: 200], changes: changes, confirm: [type: 'boolean']], required: ['parent_id', 'name', 'changes', 'confirm']]]
    ]
}

def _readOnlyToolNames_partNativeDocuments() {
    return ["hub_get_rule_document"]
}
def _toolDisplayMeta_partNativeDocuments() {
    return [hub_get_rule_document: [title: 'Get Native Rule Document', summary: 'Read native rule actions, expressions, conditions, triggers, variables and typed settings.'],
     hub_update_rule_document: [title: 'Update Native Rule Document', summary: 'Compile ordered changes into an existing native Rule Machine rule.'],
     hub_create_rule_document: [title: 'Create Native Rule Document', summary: 'Create and install a native Rule Machine rule from ordered changes.']]
}

String _ndGuide() {
    return '''## Native Rule Documents

Read hub_get_rule_document(rule_id, sections?) before editing an existing native rule. Reads use native state and typed settings without rendering its editor. Sections: actions, conditions, triggers, expressions, variables, settings. Action IDs and order are native IDs, preserved for in-place edits; omitted parts and unknown settings remain native-owned.

Writes require confirm=true and a recent hub backup. hub_update_rule_document(rule_id, changes, confirm) applies changes in order. hub_create_rule_document(parent_id, name, changes, confirm) creates a Rule-5.1 child under native Rule Machine. A failed creation can leave a native child: inspect returned rule_id and parent before retrying. A partial update can leave preceding changes committed; inspect before resubmitting.

Each change is {part,value}. Supported parts:
- condition or trigger: {id?,capability,fields:[{name,type,value}]}.
- action: {id?,type,subtype,fields:[{name,type,value}],expression?,events?}.
- expression: {id,tokens}; required: {tokens}.
- getWaitEvents actions accept events:[{capability,fields}] (1 through 16 native event rows); an edit preserves the event count. Duration fields remain native typed fields.
- variable: {operation:create|set|delete,name,type?,value?}. Create types: integer,bigdecimal,boolean,string,datetime.
- terminator: {subtype:endIf|else|endRep}.
- delete: {part:action|condition|trigger,id}; referenced conditions must be detached first.
- order: {ids:[native action IDs]}; a complete exact permutation is required.

Typed fields use exact native names containing # for the allocated ID, for example logmsg.#. Expressions use existing native condition IDs and (, ), NOT, AND, OR, XOR. Omit an id to append; provide an existing native id to edit. Field names and capability/action variants require verified native contracts; wait expressions and every capability-specific variant are not yet supported. Unknown fields cannot be assumed to compile correctly merely because a setting persists. A successful result establishes native model checks, typed readback, native editor diagnostics, expression-reference checks and nonbroken health with activation status; independently verify device behavior and native UI editing for the intended automation.

Example changes: [{part:"action",value:{type:"messageActs",subtype:"getLogMsg",fields:[{name:"logmsg.#",type:"textarea",value:"Document action"}]}}]. Writes render the native trigger/action/main pages, check health, submit native Done, and read subscriptions plus a fresh document. These pages can mutate native state and are never part of document reads.'''
}
