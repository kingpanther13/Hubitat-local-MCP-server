library(name: "McpNativeDocumentsLib", namespace: "mcp", author: "kingpanther13", description: "Native Rule Machine document tools.")

private Long _ndId(Object id) {
    if (id == null || !id.toString().isLong() || id.toString().toLong() <= 0L) throw new IllegalArgumentException('A positive native application ID is required')
    id.toString().toLong()
}

private Object _ndJson(Object value) {
    value instanceof Map || value instanceof List ? value : new groovy.json.JsonSlurper().parseText(value?.toString() ?: '{}')
}

private Boolean _ndNativeFlag(Map health, String name) {
    if (!health.containsKey(name)) return false
    health.get(name) instanceof Boolean ? health.get(name) : null
}

private List _ndSettings(Long id) {
    def response = _ndJson(hubInternalGet("/installedapp/settings/${id}"))
    if (!(response instanceof Map) || !(response.settings instanceof List)) throw new IllegalStateException('Native settings are unavailable')
    response.settings.collect { row ->
        if (!(row instanceof Map) || !(row.name instanceof String)) throw new IllegalStateException('Unexpected native setting row')
        def setting = [name: row.name, type: row.type, multiple: row.multiple, value: row.type == 'password' ? null : row.value, device_ids: row.deviceList instanceof Map ? row.deviceList.keySet().collect { it.toString() }.sort() : null]
        if (row.type == 'password') setting.redacted = true
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
            else if (action.method in ['getElse', 'getElseIf', 'getElseIfThen']) {
                if (!blocks || blocks.last() != 'if') issues << 'Native ELSE has no matching IF'
            } else if (action.method == 'getEndIf') {
                if (!blocks || blocks.last() != 'if') issues << 'Native END-IF has no matching IF'
                else blocks.remove(blocks.size() - 1)
            }
        }
    }
    if (blocks) issues << 'Native IF block has no END-IF'
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
    def rendered = _nrPage(id, page).result
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
    boolean paged = args.containsKey('offset') || args.containsKey('limit')
    if (paged && sections.size() != 1) throw new IllegalArgumentException('Paged reads require exactly one section')
    def offset = args.containsKey('offset') ? args.offset : 0
    def limit = args.containsKey('limit') ? args.limit : 50
    if (paged && (!(offset instanceof Number) || !offset.toString().isLong() || offset < 0 || offset > 1000000 ||
        !(limit instanceof Number) || !limit.toString().isLong() || limit < 1 || limit > 100)) throw new IllegalArgumentException('Read offset must be an integer from 0 to 1000000 and limit from 1 to 100')
    boolean requiredEnabled = _nrHandle(id).getSetting('useST') == true
    Map nativeState = _nrState(id)
    def structuralIssues = _ndStructuralIssues(nativeState, requiredEnabled)
    def document = [rule_id: id, validation: [structuralIssues: structuralIssues, uiChecked: false]]
    try {
        def health = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
        if (health instanceof Map) document.validation.structuralIssues = _ndStructuralIssues(nativeState + health, requiredEnabled)
        document.nativeHealth = health instanceof Map && health.broken instanceof Boolean ? [available: true, broken: health.broken, stopped: _ndNativeFlag(health, 'stopped'), paused: _ndNativeFlag(health, 'paused')] : [available: false]
    } catch (Exception failure) { document.nativeHealth = [available: false, error: failure.message?.toString()] }
    if ('actions' in sections) document.actions = (nativeState.actionList instanceof List ? nativeState.actionList : []).collect { actionId ->
        [id: actionId.toString(), value: nativeState.actions instanceof Map ? _nmEntry(nativeState.actions, actionId.toString()) : null]
    }
    if ('conditions' in sections) document.conditions = nativeState.capabsfalse instanceof Map ? nativeState.capabsfalse : [:]
    if ('triggers' in sections) document.triggers = nativeState.capabstrue instanceof Map ? nativeState.capabstrue : [:]
    if ('expressions' in sections) document.expressions = nativeState.eval instanceof Map ? nativeState.eval : [:]
    if ('variables' in sections) document.variables = _nrHandle(id).getAllLocalVars()
    if ('settings' in sections) document.settings = _ndSettings(id)
    if (paged) {
        String section = sections[0]
        def complete = document.get(section)
        List rows = complete instanceof Map ? complete.entrySet().toList().sort { a, b -> a.key.toString() <=> b.key.toString() } :
            (section == 'settings' ? complete.sort { a, b -> a.name <=> b.name } : complete)
        int total = rows.size()
        int start = Math.min(offset.toInteger(), total)
        int end = Math.min(start + limit.toInteger(), total)
        List selected = rows.subList(start, end)
        document[section] = complete instanceof Map ? selected.collectEntries { entry -> [(entry.key): entry.value] } : selected
        document.page = [offset: offset, limit: limit, total: total, next_offset: end < total ? end : null,
            revision: _mrtrSha256(groovy.json.JsonOutput.toJson(_mrtrCanonicalArgs(complete)))]
    }
    document
}

private List _ndChanges(Object changes, boolean allowEmpty = false) {
    if (!(changes instanceof List) || (!allowEmpty && changes.isEmpty()) || changes.size() > 100) throw new IllegalArgumentException(allowEmpty ? 'changes must contain between 0 and 100 changes' : 'changes must contain between 1 and 100 changes')
    changes.each { change ->
        if (!(change instanceof Map) || change.keySet().any { !(it in ['part', 'value']) } || !(change.value instanceof Map)) throw new IllegalArgumentException('Each change requires part and value')
        def value = change.value
        def keys = [condition: ['id', 'capability', 'fields'], trigger: ['id', 'capability', 'fields'],
                    action: ['id', 'type', 'subtype', 'fields', 'expression', 'events', 'condition'], expression: ['id', 'tokens'],
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
                _nrValidateScalar(row, row.value)
            }
        }
        if (change.part in ['condition', 'trigger']) {
            if (!(value.capability instanceof String) || !value.capability.trim()) throw new IllegalArgumentException('Native capability is required')
        }
        if (change.part == 'action') {
            if (!(value.type instanceof String) || !value.type.trim() || !(value.subtype instanceof String) || !value.subtype.trim() || (value.subtype.toLowerCase().contains('wait') && !(value.subtype in ['getWaitEvents', 'getWaitRule']))) throw new IllegalArgumentException('A supported native action type and subtype are required')
            if (value.containsKey('expression') && (!(value.expression instanceof List) || !value.expression || !(value.subtype in ['getIfThen', 'getElseIf', 'getWaitRule']))) throw new IllegalArgumentException('Invalid action expression')
            if (!value.containsKey('id') && value.subtype in ['getIfThen', 'getElseIf', 'getWaitRule'] && !value.containsKey('expression')) throw new IllegalArgumentException('New expression actions require expression tokens')
            if (value.containsKey('condition')) {
                _nmId(value.condition)
                if (value.containsKey('expression') || value.type in ['condActs', 'repeatActs', 'delayActs']) throw new IllegalArgumentException('A simple condition requires an ordinary native action')
            }
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
    Map projectedActions = before.actions instanceof Map ? before.actions.collectEntries { key, value -> [(key.toString()): value] } : [:]
    List projectedOrder = new ArrayList(actionOrder)
    int newAction = 0
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
        if (change.part in ['action', 'terminator']) {
            String actionId = value.containsKey('id') ? value.id.toString() : "new:${newAction++}".toString()
            if (!value.containsKey('id')) projectedOrder << actionId
            String method = change.part == 'terminator' ? [endIf: 'getEndIf', else: 'getElse', endRep: 'getEndRepeat'].get(value.subtype) : value.subtype
            projectedActions.put(actionId, [method: method])
        }
        if (change.part == 'order' && !allocatedActions) {
            List requested = value.ids.collect { it.toString() }
            if (requested.sort(false) != actionOrder.sort(false)) throw new IllegalArgumentException('Action order must be an exact permutation of existing action IDs')
            actionOrder = requested
            projectedOrder = new ArrayList(requested)
        }
        if (change.part == 'delete' && value.part == 'action') projectedOrder = projectedOrder.findAll { it != value.id.toString() }
        if (change.part == 'delete' && value.part == 'action' && !allocatedActions) {
            if (!actionOrder.contains(value.id.toString())) throw new IllegalArgumentException('Native action does not exist')
            actionOrder = actionOrder.findAll { it != value.id.toString() }
        }
        if (change.part == 'condition' && !value.containsKey('id')) allocatedConditions = true
        if (change.part == 'action' && value.containsKey('condition')) {
            List tokens = [value.condition.toString()]
            if (!allocatedConditions) _nmTokens(tokens, conditions)
            pendingExpressions << tokens
        }
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
    List blockIssues = _ndBlockIssues(projectedOrder, projectedActions)
    if (blockIssues) throw new IllegalArgumentException(blockIssues.join('; '))
}

private Map _ndApply(Long id, List changes, boolean created) {
    def outcomes = []
    boolean started = created
    try {
        def beforeHealth = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
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
        ['selectTriggers', pages.actions, pages.primary].unique().each { page -> _ndRender(id, page) }
        def health = _ndJson(hubInternalGet("/app/ruleBuilderJson/${id}"))
        Map renderedState = _nrState(id) + (health instanceof Map ? health : [:])
        def structuralIssues = _ndStructuralIssues(renderedState, _nrHandle(id).getSetting('useST') == true)
        structuralIssues.addAll(_nmValidateStructure(id, renderedState).structuralIssues ?: [])
        if (structuralIssues) throw new IllegalStateException(structuralIssues.join('; '))
        if (!(health instanceof Map) || health.broken != false) return [success: false, partial: true, rule_id: id, created: created, results: outcomes, broken: health instanceof Map ? health.broken : null, error: 'Native rule health is broken or unverified']
        def done = hubInternalPostFormRaw('/installedapp/update/json', 'id=' + id.toString() + '&_action_update=Done&_cancellable=true')
        def doneStatus = done instanceof Map ? done.get('status') : null
        if (doneStatus != null && doneStatus.toString().isInteger() && doneStatus.toInteger() >= 400) throw new IllegalStateException('Native Done was rejected')
        def doneBody = done instanceof Map ? done.get('data') : null
        if (doneBody instanceof Map || (doneBody != null && doneBody.toString().trim())) _nfJson(doneBody)
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
        def requiredTokens = persistedState.eval instanceof Map ? _nmEntry(persistedState.eval, '0') : null
        // Native Done evaluates expression 0 into stPrimed before removing trigger subscriptions.
        boolean suppressed = !stopped && !paused && _nrHandle(id).getSetting('useST') == true &&
            persistedState.hasPredicate == true && requiredTokens instanceof List && !requiredTokens.isEmpty() &&
            persistedState.stPrimed == false
        if (actualTriggers && !stopped && !paused && !suppressed && subscriptions.empty && jobs.empty) throw new IllegalStateException('Native triggers have no active subscriptions or scheduled jobs')
        if (created && getAppByAppId(id)?.installed != true) throw new IllegalStateException('Native rule is not installed after Done')
        [success: true, rule_id: id, created: created, results: outcomes, broken: false, stopped: stopped, paused: paused, eventSubscriptions: subscriptions, scheduledJobs: jobs, verification: 'Native persistence, compilation and activation checks passed; verify intended device behavior independently.', document: toolGetRuleDocument([rule_id: id, sections: ['actions', 'conditions', 'triggers', 'expressions', 'variables']])] +
            (suppressed ? [subscriptionSettle: 'SUPPRESSED: Required Expression is false'] : [:])
    } catch (Exception failure) {
        [success: false, partial: started, rule_id: id, created: created, results: outcomes, remaining: changes.drop(outcomes.size()), error: failure.message?.toString()]
    }
}

Map toolUpdateRuleDocument(Map args) {
    Long id = _ndId(args.rule_id)
    List changes = _ndChanges(args.changes)
    requireDestructiveConfirm(args.confirm)
    _requireUnprotectedAppMutation(id, 'edit native rule document')
    String appKey = _nfKey(id)
    if (NATIVE_FORM_BUSY.putIfAbsent(appKey, true) != null) throw new IllegalStateException('This rule already has an active native editing operation')
    boolean attempted = false
    try {
        _nrHandle(id)
        if (getAppByAppId(id)?.disabled == true) throw new IllegalArgumentException('Enable the native rule before editing its document')
        try { _ndPreflight(id, changes) }
        catch (IllegalArgumentException failure) { return [success: false, partial: false, rule_id: id, error: failure.message?.toString()] }
        Map backup
        try { backup = _ndEditBackup(id, 'pre-document-update') }
        catch (Exception failure) { return [success: false, partial: false, rule_id: id, error: "Pre-edit backup failed; no document changes were applied: ${failure.message}".toString()] }
        attempted = true
        _ndApply(id, changes, false) + [backup: backup]
    } finally {
        if (attempted) NATIVE_FORM_CONTEXTS.entrySet().findAll { entry -> entry.value.app_id?.toString() == id.toString() }.each { entry -> NATIVE_FORM_CONTEXTS.remove(entry.key, entry.value) }
        NATIVE_FORM_BUSY.remove(appKey)
    }
}

private Map _ndEditBackup(Long id, String reason) {
    if (settings.backupBeforeRuleEdits == false) return [skipped: true, reason: 'Automatic pre-edit backups are disabled']
    Map backup = _rmBackupRuleSnapshot(id.toString().toInteger(), reason, true)
    if (!(backup?.backupKey instanceof String) || !backup.backupKey) throw new IllegalStateException('No recovery handle was returned')
    backup
}

Map toolCreateRuleDocument(Map args) {
    Long parentId = _ndId(args.parent_id)
    def requestedName = args.get('name')
    if (!(requestedName instanceof String) || !requestedName.trim() || requestedName.size() > 200) throw new IllegalArgumentException('A rule name is required (maximum 200 characters)')
    String name = requestedName.trim()
    List changes = _ndChanges(args.changes, true)
    requireDestructiveConfirm(args.confirm)
    _requireUnprotectedAppMutation(parentId, 'create native rule child')
    def parent = getAppByAppId(parentId)
    if (parent?.appType?.namespace != 'hubitat' || parent?.appType?.name != 'Rule Machine') throw new IllegalArgumentException('parent_id must identify native Rule Machine')
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
        _ndPreflight(id, changes)
        _ndApply(id, changes, true)
    } catch (Exception failure) {
        [success: false, partial: true, rule_id: verifiedChild ? id : null, created: verifiedChild, error: failure.message?.toString()]
    }
}

boolean _isRuleReadCall(String toolName, args) {
    toolName == 'hub_set_rule' && args instanceof Map && (!args.containsKey('operation') || args.operation == 'read' || args.confirm != true)
}

private Map _ndOperationSchema(String operation) {
    def changes = [type: 'array', minItems: operation == 'create' ? 0 : 1, maxItems: 100,
        items: [type: 'object', additionalProperties: false, properties: [part: [type: 'string', enum: ['condition', 'trigger', 'action', 'expression', 'required', 'variable', 'terminator', 'delete', 'order']], value: [type: 'object']], required: ['part', 'value']]]
    Map properties = [:]
    List required = []
    if (operation == 'read') {
        properties.sections = [type: 'array', items: [type: 'string', enum: ['actions', 'conditions', 'triggers', 'expressions', 'variables', 'settings']]]
        properties.offset = [type: 'integer', minimum: 0, maximum: 1000000]
        properties.limit = [type: 'integer', minimum: 1, maximum: 100]
    } else if (operation == 'create') {
        properties = [parent_id: [type: 'integer', minimum: 1], name: [type: 'string', minLength: 1, maxLength: 200], changes: changes]
        required = ['parent_id', 'name', 'changes']
    } else if (operation == 'update') {
        properties.changes = changes
        required = ['changes']
    } else if (operation == 'form') {
        properties = [steps: [type: 'array', minItems: 1, maxItems: 16, items: [type: 'object', additionalProperties: false,
            properties: [kind: [type: 'string', enum: ['open', 'submit']], page: [type: 'string'], expect_page: [type: 'string'], fields: [type: 'object'], activate: [type: 'string']], required: ['kind']]],
            options_for: [type: 'array', maxItems: 32, items: [type: 'string']]]
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
        if (schema.additionalProperties == false && value.keySet().any { !schema.properties.containsKey(it) }) throw new IllegalArgumentException("Unknown ${path} property")
        if (complete && schema.required?.any { !value.containsKey(it) }) throw new IllegalArgumentException("Missing required ${path} property")
        value.each { key, item -> if (schema.properties?.containsKey(key)) _ndValidateSchema(item, schema.properties.get(key), "${path}.${key}".toString(), complete) }
    } else if (schema.type == 'array') {
        if ((schema.minItems != null && value.size() < schema.minItems) || (schema.maxItems != null && value.size() > schema.maxItems)) throw new IllegalArgumentException("Invalid ${path} length")
        value.each { item -> _ndValidateSchema(item, schema.items, path, complete) }
    } else if (schema.type == 'string') {
        if ((schema.minLength != null && value.trim().size() < schema.minLength) || (schema.maxLength != null && value.size() > schema.maxLength)) throw new IllegalArgumentException("Invalid ${path} length")
    } else if (schema.minimum != null && value < schema.minimum) throw new IllegalArgumentException("Invalid ${path}")
}

Map toolSetRule(Map request) {
    if (request.keySet().any { !(it in ['operation', 'rule_id', 'args', 'confirm', 'bestPracticeKey']) }) throw new IllegalArgumentException('Unknown hub_set_rule property')
    if (request.containsKey('confirm') && !(request.confirm instanceof Boolean)) throw new IllegalArgumentException('confirm must be boolean')
    if (request.containsKey('rule_id')) _ndValidateSchema(request.rule_id, [type: 'integer', minimum: 1], 'rule_id', true)
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
    if (operation == 'create' && request.containsKey('rule_id')) throw new IllegalArgumentException('create uses args.parent_id, not rule_id')
    if (!execute) {
        Map properties = [operation: [type: 'string', enum: [operation]], args: schema, confirm: [type: 'boolean'], bestPracticeKey: [type: 'string']]
        List required = ['operation', 'args', 'confirm']
        if (operation != 'create') { properties.rule_id = [type: 'integer', minimum: 1]; required << 'rule_id' }
        return [operation: operation, confirm_required: true, inputSchema: [type: 'object', additionalProperties: false, properties: properties, required: required],
            usage: 'Supply these arguments with confirm=true and a recent backup to execute. See set_rule_reference.']
    }
    if (operation != 'create' && !request.containsKey('rule_id')) throw new IllegalArgumentException('rule_id is required')
    if (operation == 'read') return toolGetRuleDocument(arguments + [rule_id: request.rule_id])
    if (operation == 'create') return toolCreateRuleDocument(arguments + [confirm: true])
    if (operation == 'update') return toolUpdateRuleDocument(arguments + [rule_id: request.rule_id, confirm: true])
    Long id = _ndId(request.rule_id)
    def target = getAppByAppId(id)
    if (target?.id?.toString() != id.toString() || target?.appType?.namespace != 'hubitat' || !(target?.appType?.name in ['Rule-5.1', 'Button Rule-5.1', 'Rule Machine'])) throw new IllegalArgumentException('rule_id must identify native Rule-5.1, Button Rule-5.1 or Rule Machine')
    toolCallAppForm(arguments + [app_id: request.rule_id, confirm: true])
}

def _getAllToolDefinitions_partNativeDocuments() {
    return [[
      name: 'hub_set_rule', description: 'Read, create or update native Rule Machine rules, or use their native forms. Omit operation for help; select a write operation without confirm for its schema. Writes require confirm=true and a recent backup; partial changes can remain. Guide: set_rule_reference.',
      inputSchema: [type: 'object', additionalProperties: false, properties: [operation: [type: 'string', enum: ['read', 'create', 'update', 'form']], rule_id: [type: 'integer', minimum: 1], args: [type: 'object'], confirm: [type: 'boolean'], bestPracticeKey: [type: 'string']]]]]
}

def _readOnlyToolNames_partNativeDocuments() { [] }

def _toolDisplayMeta_partNativeDocuments() {
    [hub_set_rule: [title: 'Set Native Rule', summary: 'Read, create and update native rules or use their native editor forms.']]
}

String _ndGuide() {
    return '''## Set Rule Reference

Call hub_set_rule without operation for help, or choose a write operation without confirm to inspect its schema without changing the rule. Top-level rule_id identifies the target; args cannot override it or confirm. When mandatory guide acknowledgment is enabled, pass its key as top-level bestPracticeKey alongside operation, rule_id, args and confirm.

Read hub_set_rule(operation:"read",rule_id,args:{sections:[...]}) before editing an existing native rule. Reads use native state and typed settings without rendering its editor. Sections: actions, conditions, triggers, expressions, variables, settings. Action IDs and order are native IDs, preserved for in-place edits; omitted parts and unknown settings remain native-owned.

For large rules, read one section with args:{sections:["settings"],offset:0,limit:50}. Paging requires exactly one section; offset is 0..1000000 and limit is 1..100 (defaults: 0 and 50). The section keeps its usual map or list shape. page returns offset, limit, total, next_offset (null at the end) and revision, a hash of the complete section. Follow next_offset until null; restart if revision changes between pages. Actions retain native order, settings sort by name, and map sections sort by string key. Omit offset and limit for an unpaged read.

Writes require confirm=true and a recent hub backup. Automatic pre-edit recovery is enabled by default: an update captures one recovery point for its whole changes batch, and a rule form captures one before its first render and reuses it through that workflow. Recovery metadata is returned as backup, including after partial failure; capture failure stops the requested edit. Rule Machine parent creation/navigation has no existing child configuration to recover; opening the child starts its own protected workflow. hub_set_rule(operation:"update",rule_id,args:{changes:[...]},confirm:true) applies changes in order. hub_set_rule(operation:"create",args:{parent_id,name,changes:[...]},confirm:true) creates a Rule-5.1 child under native Rule Machine. A failed creation can leave a native child: inspect returned rule_id and parent before retrying. A partial update can leave preceding changes committed; inspect before resubmitting.

Each change is {part,value}. Supported parts:
- condition or trigger: {id?,capability,fields:[{name,type,value}]}.
- action: {id?,type,subtype,fields:[{name,type,value}],expression?,events?,condition?}. condition is an existing native condition ID for a Simple Conditional Action.
- expression: {id,tokens}; required: {tokens}.
- getWaitEvents actions accept events:[{capability,fields}] (1 through 16 native event rows); an edit preserves the event count. Duration fields remain native typed fields.
- getWaitRule actions accept expression tokens, with optional native timeout/duration fields. New IF, ELSE-IF and Wait for Expression actions require expression tokens.
- variable: {operation:create|set|delete,name,type?,value?}. Create types: integer,bigdecimal,boolean,string,datetime. DateTime uses native ISO text, e.g. 2026-01-01T12:30:00.000-0500; native date-only/time-only sentinels are accepted.
- terminator: {subtype:endIf|else|endRep}.
- delete: {part:action|condition|trigger,id}; referenced conditions must be detached first.
- order: {ids:[native action IDs]}; a complete exact permutation is required.

Typed fields use exact native names containing # for the allocated ID, for example logmsg.#. Expressions use existing native condition IDs and (, ), NOT, AND, OR, XOR. Omit an id to append; provide an existing native id to edit. Field names and capability/action variants require verified native contracts; not every capability-specific variant is supported. Discover native options through operation:"form" when a document contract is unavailable. Unknown fields cannot be assumed to compile correctly merely because a setting persists. A successful result establishes native model checks, typed readback, native editor diagnostics, expression-reference checks and nonbroken health with activation status; independently verify device behavior and native UI editing for the intended automation.

Example changes: [{part:"action",value:{type:"messageActs",subtype:"getLogMsg",fields:[{name:"logmsg.#",type:"textarea",value:"Document action"}]}}]. Writes render the native trigger/action/main pages, check health, submit native Done, and read subscriptions plus a fresh document. These pages can mutate native state and are never part of document reads.

## Native Rule Forms

Use hub_set_rule(operation:"form",rule_id,args:{steps:[{kind:"open"}]},confirm:true) to operate an enabled Rule-5.1 or Button Rule-5.1 rule, or its Rule Machine parent. Select operation:"form" without confirmation to inspect the schema. Discover native action variants, input names and option values through the returned forms rather than guessing a static catalogue. Even open renders execute app code and can change state or cause side effects. Start with steps:[{kind:"open"}]; an optional page names a registered native page. Follow returned controls for page parameters and breadcrumbs. A create_child control creates the native child type exposed by its parent, returns created_app_id and closes the parent context. Open that child separately to configure it; creation alone does not prove configuration is complete. App removal uses the dedicated deletion tool.

Submit with {kind:"submit",expect_page:"last returned page",fields:{"native field name":value},activate:"returned control_id"}. Omit activate to save the supplied fields and refresh the current page. Control IDs distinguish native buttons sharing a name, such as editing and deleting the same variable. Native enum keys, device IDs, booleans and single/multiple cardinality are checked before the request. Only supplied settings are posted; native page code can still change its own settings. Defaults are shown but are not automatically submitted; supply a required default explicitly before navigating. Use args.options_for:["device field name"] to expand device choices on the final returned page.

Batches contain 1 through 16 ordered steps. Each step uses the form returned by the preceding step, so dependent fields require another submit after they appear. A button handler runs before that step's fields are saved, as in the native browser; submit fields in a preceding step when the handler needs them. A button without submitOnChange reloads and requires a separate field submission.

The execution context expires after five minutes and is lost on app reload. It retains no field values, messages or option labels; limits are 24 KiB of serialized descriptors per app, 96 KiB total, and eight caller workflows. Opening another app replaces only that caller's previous context; reopen the previous app before submitting it again. Other callers' contexts are retained. expect_page guards the latest tool context, not concurrent edits in another browser. Avoid concurrent editing. Errors can leave completed operations or an uncertain final operation; inspect the app and reopen instead of replaying buttons. Terminal navigation closes the context without following redirects. A verified local native link or script navigation to the same app or its direct child returns navigated_app_id and navigated_page with closed:true; open that target separately. No script is evaluated. App-authored JavaScript, custom HTML inputs, uploads, external links and unsupported browser controls are reported in unsupported; they are never silently executed. Success means the native form operation returned, not that the app's intended runtime behavior was independently verified.'''
}
