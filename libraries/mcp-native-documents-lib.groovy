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
        def action = actions.find { key, value -> key.toString() == id.toString() }?.value
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
        def tokens = expressions.find { key, value -> key.toString() == id }?.value
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
    def document = [rule_id: id, validation: [structuralIssues: _ndStructuralIssues(nativeState)]]
    if ('actions' in sections) document.actions = (nativeState.actionList instanceof List ? nativeState.actionList : []).collect { actionId ->
        [id: actionId.toString(), value: nativeState.actions instanceof Map ? nativeState.actions.find { k, v -> k.toString() == actionId.toString() }?.value : null]
    }
    if ('conditions' in sections) document.conditions = nativeState.capabsfalse instanceof Map ? nativeState.capabsfalse : [:]
    if ('triggers' in sections) document.triggers = nativeState.capabstrue instanceof Map ? nativeState.capabstrue : [:]
    if ('expressions' in sections) document.expressions = nativeState.eval instanceof Map ? nativeState.eval : [:]
    if ('variables' in sections) document.variables = nativeState.allLocalVars instanceof Map ? nativeState.allLocalVars : [:]
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
                    variable: ['operation', 'name', 'type', 'value'], required: ['tokens'], terminator: ['subtype']]
        if (!keys.containsKey(change.part) || value.keySet().any { !(it in keys.get(change.part)) }) throw new IllegalArgumentException('Unknown change part or field')
        if (value.containsKey('id') && (value.id == null || !value.id.toString().isInteger() || value.id.toString().toInteger() < (change.part == 'expression' ? 0 : 1))) throw new IllegalArgumentException('Invalid native row ID')
        if (change.part in ['condition', 'trigger', 'action']) {
            if (!(value.fields instanceof List)) throw new IllegalArgumentException('Native typed fields are required')
            value.fields.each { row ->
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
            _ndChanges(value.events.collect { event -> [part: 'condition', value: event] })
        } else if (value.containsKey('events')) throw new IllegalArgumentException('Only wait-event actions accept events')
        if (change.part == 'expression' && (!value.containsKey('id') || !(value.tokens instanceof List) || !value.tokens)) throw new IllegalArgumentException('Expression ID and tokens are required')
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

private Map _ndApply(Long id, List changes, boolean created) {
    def outcomes = []
    boolean started = created
    try {
        def beforeHealth = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
        if (!(beforeHealth instanceof Map) || !(beforeHealth.broken instanceof Boolean)) throw new IllegalStateException('Native rule health is unavailable before changes')
        for (change in changes) {
            def value = change.value
            started = true
            Map outcome
            switch (change.part) {
                case 'condition': outcome = _nmUpsertCondition(id, false, value); break
                case 'trigger': outcome = _nmUpsertCondition(id, true, value); break
                case 'action': outcome = _nmUpsertAction(id, value); break
                case 'expression': outcome = _nmSetExpression(id, value.id, value.tokens); break
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
        def structuralIssues = _ndStructuralIssues(_nrState(id))
        if (structuralIssues) throw new IllegalStateException(structuralIssues.join('; '))
        def health = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
        if (!(health instanceof Map) || health.broken != false) return [success: false, partial: true, rule_id: id, created: created, results: outcomes, broken: health instanceof Map ? health.broken : null, error: 'Native rule health is broken or unverified']
        def done = hubInternalPostFormRaw('/installedapp/update/json', 'id=' + id.toString() + '&_action_update=Done&_cancellable=true')
        if (done instanceof Map && done.status != null && done.status.toString().isInteger() && done.status.toInteger() >= 400) throw new IllegalStateException('Native Done was rejected')
        def finalHealth = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
        if (!(finalHealth instanceof Map) || finalHealth.broken != false) throw new IllegalStateException('Native rule health failed after Done')
        if (!created) {
            if ((beforeHealth.stopped == true) != (finalHealth.stopped == true)) _nrHandle(id).appButtonHandler('stopRule')
            if ((beforeHealth.paused == true) != (finalHealth.paused == true)) _nrHandle(id).appButtonHandler('pausRule')
            finalHealth = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
            if (!(finalHealth instanceof Map) || finalHealth.broken != false || (beforeHealth.stopped == true) != (finalHealth.stopped == true) || (beforeHealth.paused == true) != (finalHealth.paused == true)) throw new IllegalStateException('Native stopped or paused state was not preserved')
        }
        def status = _ndJson(hubInternalGet("/installedapp/statusJson/${id}"))
        if (!(status instanceof Map) || !(status.eventSubscriptions instanceof List)) throw new IllegalStateException('Native activation status is unavailable')
        if (finalHealth.stopped == true && (!(status.scheduledJobs instanceof List) || status.scheduledJobs || status.eventSubscriptions)) throw new IllegalStateException('Stopped native rule retained jobs or subscriptions')
        def persistedState = _nrState(id)
        def actualTriggers = persistedState.capabstrue instanceof Map ? persistedState.capabstrue.findAll { key, value -> key.toString().isInteger() && key.toString().toInteger() > 0 && value?.toString()?.trim() } : [:]
        if (actualTriggers && finalHealth.stopped != true && finalHealth.paused != true && status.eventSubscriptions.empty) throw new IllegalStateException('Native triggers have no active subscriptions')
        if (created && getAppByAppId(id)?.installed != true) throw new IllegalStateException('Native rule is not installed after Done')
        [success: true, rule_id: id, created: created, results: outcomes, broken: false, stopped: finalHealth.stopped == true, paused: finalHealth.paused == true, eventSubscriptions: status.eventSubscriptions, verification: 'Native persistence, compilation and activation checks passed; verify intended device behavior independently.', document: toolGetRuleDocument([rule_id: id, sections: ['actions', 'conditions', 'triggers', 'expressions', 'variables']])]
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
    _ndApply(id, changes, false)
}

Map toolCreateRuleDocument(Map args) {
    Long parentId = _ndId(args.parent_id)
    if (!(args.name instanceof String) || !args.name.trim() || args.name.size() > 200) throw new IllegalArgumentException('A rule name is required (maximum 200 characters)')
    List changes = _ndChanges(args.changes)
    requireDestructiveConfirm(args.confirm)
    def parent = getAppByAppId(parentId)
    if (parent?.appType?.namespace != 'hubitat' || parent?.appType?.name != 'Rule Machine') throw new IllegalArgumentException('parent_id must identify native Rule Machine')
    Long id = null
    try {
        def response = hubInternalGetRaw("/installedapp/createchild/hubitat/Rule-5.1/parent/${parentId}")
        def match = response?.location?.toString() =~ /^\/installedapp\/configure\/(\d+)(?:\?.*)?$/
        if (!match.find()) return [success: false, partial: true, error: 'Native child creation did not return its rule ID; inspect the parent before retrying']
        id = _ndId(match.group(1))
        _nrHandle(id).initState()
        _nrHandle(id).updateSetting('origLabel', [type: 'text', value: args.name.trim()])
        _nrHandle(id).updateLabel(args.name.trim())
        _ndApply(id, changes, true)
    } catch (Exception failure) {
        [success: false, partial: true, rule_id: id, created: id != null, error: failure.message?.toString()]
    }
}

def _getAllToolDefinitions_partNativeDocuments() {
    def changes = [type: 'array', minItems: 1, maxItems: 100, description: 'Ordered changes. Each has part and value: condition/trigger {id?,capability,fields}; action {id?,type,subtype,fields,expression?,events?}; expression {id,tokens}; required {tokens}; variable {operation:create|set|delete,name,type?,value?}; terminator {subtype:endIf|else|endRep}. Typed fields use {name:native name containing # for allocated ID,type,value}. Wait events support up to 16 verified native event rows; wait expressions remain unsupported.', items: [type: 'object', additionalProperties: false, properties: [part: [type: 'string', enum: ['condition', 'trigger', 'action', 'expression', 'required', 'variable', 'terminator']], value: [type: 'object']], required: ['part', 'value']]]
    def warning = ' Requires confirm=true and a hub backup within 24 hours. Before writing: read the native document, verify target IDs and fields, and preserve a recovery backup. Stops at first failure; partial changes can remain. Native UI retains the same rule ID. Verify resulting settings and runtime behavior before relying on the rule. Wait expressions and all capability-specific variants are not yet supported.'
    [[name: 'hub_get_rule_document', description: 'Read native Rule Machine state, ordered actions, conditions, triggers, expressions, local variables and typed settings without rendering editor pages.', inputSchema: [type: 'object', additionalProperties: false, properties: [rule_id: [type: 'integer', minimum: 1], sections: [type: 'array', items: [type: 'string', enum: ['actions', 'conditions', 'triggers', 'expressions', 'variables', 'settings']]]], required: ['rule_id']]],
     [name: 'hub_update_rule_document', description: 'Apply ordered semantic changes to an existing native Rule Machine rule and finalize through its native compiler.' + warning, inputSchema: [type: 'object', additionalProperties: false, properties: [rule_id: [type: 'integer', minimum: 1], changes: changes, confirm: [type: 'boolean']], required: ['rule_id', 'changes', 'confirm']]],
     [name: 'hub_create_rule_document', description: 'Create and install a native Rule Machine child with ordered semantic changes.' + warning, inputSchema: [type: 'object', additionalProperties: false, properties: [parent_id: [type: 'integer', minimum: 1], name: [type: 'string', minLength: 1, maxLength: 200], changes: changes, confirm: [type: 'boolean']], required: ['parent_id', 'name', 'changes', 'confirm']]]]
}

def _readOnlyToolNames_partNativeDocuments() { ['hub_get_rule_document'] }
def _toolDisplayMeta_partNativeDocuments() {
    [hub_get_rule_document: [title: 'Get Native Rule Document', summary: 'Read native rule actions, expressions, conditions, triggers, variables and typed settings.'],
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

Typed fields use exact native names containing # for the allocated ID, for example logmsg.#. Expressions use existing native condition IDs and (, ), NOT, AND, OR, XOR. Omit an id to append; provide an existing native id to edit. Field names and capability/action variants require verified native contracts; wait expressions and every capability-specific variant are not yet supported. Unknown fields cannot be assumed to compile correctly merely because a setting persists. A successful result establishes native model checks, typed readback, native editor diagnostics, expression-reference checks and nonbroken health with activation status; independently verify device behavior and native UI editing for the intended automation.

Example changes: [{part:"action",value:{type:"messageActs",subtype:"getLogMsg",fields:[{name:"logmsg.#",type:"textarea",value:"Document action"}]}}]. Writes render the native trigger/action/main pages, check health, submit native Done, and read subscriptions plus a fresh document. These pages can mutate native state and are never part of document reads.'''
}
