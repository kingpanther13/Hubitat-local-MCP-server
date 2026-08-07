library(name: "McpDeployJobsLib", namespace: "mcp", author: "kingpanther13", description: "Durable multi-app deployment jobs: staged clone/import/edit ops with on-hub checkpoints, a validation gate, commit cutover, and cancel rollback (issue #376)")

def _deployOpTypes() { ["cloneApp", "importApp", "buttonRule", "addActions", "modifyAction", "pause", "resume", "setDisabled"] }
def _deployCreateOpTypes() { ["cloneApp", "importApp", "buttonRule"] }
def _deployActivePhases() { ["staging", "committing"] }
def _deployMaxJobs() { 8 }
def _deployLeaseMs() { 90000L }
def _deployWorkerBudgetMs() { 45000L }

def _deployJobs() {
    return (atomicState.deployJobs instanceof Map) ? atomicState.deployJobs : [:]
}

private Map _deployLoadJob(Object jobIdRaw) {
    if (jobIdRaw == null) throw new IllegalArgumentException("jobId is required for this operation. List jobs via hub_get_deployment.")
    def jobId = jobIdRaw.toString()
    def job = _deployJobs()[jobId]
    if (!(job instanceof Map)) throw new IllegalArgumentException("Deployment job '${jobId}' not found. List jobs via hub_get_deployment.")
    return job
}

private void _deploySaveJob(Map job) {
    // The checkpoint primitive: one atomicState map-entry write per save, so a
    // killed request/worker thread loses at most the op in flight, never the job.
    job.updatedAt = now()
    if (!(atomicState.deployJobs instanceof Map)) atomicState.deployJobs = [:]
    atomicState.updateMapValue("deployJobs", job.jobId.toString(), job)
}

private void _deployAppendHistory(Map job, String msg) {
    def h = (job.history instanceof List) ? job.history : []
    h << [at: now(), msg: msg?.toString()?.take(160)]
    if (h.size() > 30) h = h[-30..-1]
    job.history = h
}

private void _deployValidateOps(List ops, Set knownAliases, String listName) {
    ops.eachWithIndex { op, i ->
        if (!(op instanceof Map)) throw new IllegalArgumentException("${listName}[${i}] must be an object {op, args, alias?}")
        def t = op.op?.toString()
        if (!(t in _deployOpTypes())) throw new IllegalArgumentException("${listName}[${i}].op must be one of ${_deployOpTypes().join(', ')} (got: ${t}). See hub_get_tool_guide(section='deployment_jobs').")
        if (op.args != null && !(op.args instanceof Map)) throw new IllegalArgumentException("${listName}[${i}].args must be an object")
        if (op.alias != null) {
            if (!(t in _deployCreateOpTypes())) throw new IllegalArgumentException("${listName}[${i}].alias is only valid on ${_deployCreateOpTypes().join('/')} ops (it names the created app's id for later ops)")
            def a = op.alias.toString()
            if (knownAliases.contains(a)) throw new IllegalArgumentException("${listName}[${i}].alias '${a}' is declared more than once")
            knownAliases << a
        }
        _deployCheckAliasRefs(op.args, knownAliases, "${listName}[${i}].args")
    }
}

private void _deployCheckAliasRefs(Object node, Set knownAliases, String path) {
    if (node instanceof Map) {
        if (node.size() == 1 && node.containsKey("alias")) {
            def a = node.alias?.toString()
            if (!knownAliases.contains(a)) throw new IllegalArgumentException("${path} references {alias: '${a}'} but no earlier op declares alias '${a}'")
            return
        }
        node.each { k, v -> _deployCheckAliasRefs(v, knownAliases, "${path}.${k}") }
    } else if (node instanceof List) {
        node.eachWithIndex { v, i -> _deployCheckAliasRefs(v, knownAliases, "${path}[${i}]") }
    }
}

private Object _deployResolveAliases(Object node, Map aliases) {
    if (node instanceof Map) {
        if (node.size() == 1 && node.containsKey("alias")) {
            def a = node.alias?.toString()
            def resolved = aliases[a]
            if (resolved == null) throw new IllegalStateException("alias '${a}' has no recorded appId yet (its declaring op has not completed)")
            return resolved
        }
        def out = [:]
        node.each { k, v -> out[k] = _deployResolveAliases(v, aliases) }
        return out
    }
    if (node instanceof List) return node.collect { _deployResolveAliases(it, aliases) }
    return node
}

private Map _deployExecuteOp(Map job, Map op) {
    def raw = (op.args instanceof Map) ? op.args : [:]
    def a = (Map) _deployResolveAliases(raw, (Map) (job.aliases ?: [:]))
    switch (op.op?.toString()) {
        case "cloneApp":
            return [result: toolCloneNativeApp(a + [confirm: true]), newAppIdKey: "newAppId"]
        case "importApp":
            return [result: toolImportNativeApp(a + [confirm: true]), newAppIdKey: "newAppId"]
        case "buttonRule":
            return [result: toolSetNativeApp([buttonRule: a, confirm: true]), newAppIdKey: "buttonRuleId"]
        case "addActions":
            if (a.appId == null) throw new IllegalStateException("addActions op requires args.appId")
            if (!(a.actions instanceof List)) throw new IllegalStateException("addActions op requires args.actions (array of action specs)")
            return [result: toolSetRule([appId: a.appId, addActions: a.actions, confirm: true])]
        case "modifyAction":
            if (a.appId == null || a.index == null || !(a.mods instanceof Map)) {
                throw new IllegalStateException("modifyAction op requires args.appId, args.index, args.mods (object)")
            }
            return [result: toolSetRule([appId: a.appId, modifyAction: [index: a.index, mods: a.mods], confirm: true])]
        case "pause":
            return [result: toolSetRulePaused([ruleId: (a.ruleIds != null ? a.ruleIds : a.ruleId), paused: true])]
        case "resume":
            return [result: toolSetRulePaused([ruleId: (a.ruleIds != null ? a.ruleIds : a.ruleId), paused: false])]
        case "setDisabled":
            if (a.appId == null) throw new IllegalStateException("setDisabled op requires args.appId")
            return [result: toolSetAppDisabled([appId: a.appId, disabled: (a.disabled != false)])]
    }
    throw new IllegalStateException("unknown op '${op.op}'")
}

private boolean _deployOpSucceeded(Object res) {
    if (!(res instanceof Map)) return false
    if (res.isError == true) return false
    if (res.containsKey("success")) return res.success != false
    return true
}

private Map _deployTrimResult(Object res) {
    if (!(res instanceof Map)) return [note: res?.toString()?.take(200)]
    def keep = [:]
    ["success", "newAppId", "buttonRuleId", "paused", "disabled", "partial", "adopted"].each { k ->
        if (res.containsKey(k)) keep[k] = res[k]
    }
    if (res.backup instanceof Map && res.backup.backupKey) keep.backupKey = res.backup.backupKey
    if (res.backupKey) keep.backupKey = res.backupKey
    if (res.error) keep.error = res.error.toString().take(300)
    if (res.note) keep.note = res.note.toString().take(200)
    return keep
}

private Map _deployPreflightCreateOp(Map op, Map aliases) {
    // Recorded WITH the in_flight checkpoint so a resume after a mid-op death can
    // reconcile (adopt the app if the create actually committed) instead of
    // guessing. parentAppId + the parent's pre-op child ids + expected label are
    // enough to identify a child that appeared because of this op.
    def t = op.op?.toString()
    if (!(t in _deployCreateOpTypes())) return null
    Integer parentId = null
    String expectLabel = null
    try {
        def a = (Map) _deployResolveAliases((op.args instanceof Map) ? op.args : [:], aliases)
        expectLabel = a.newName?.toString()
        if (t == "cloneApp") {
            def src = (a.sourceAppId != null) ? a.sourceAppId : a.appId
            def cfg = (src != null) ? _rmFetchConfigJson(normalizeRuleId(src)) : null
            parentId = (cfg?.app?.parentAppId != null) ? (cfg.app.parentAppId.toString() as Integer) : null
        } else if (t == "importApp") {
            def cfg = (a.parentHintAppId != null) ? _rmFetchConfigJson(normalizeRuleId(a.parentHintAppId)) : null
            parentId = (cfg?.app?.parentAppId != null) ? (cfg.app.parentAppId.toString() as Integer) : null
        } else if (t == "buttonRule") {
            parentId = (a.controllerId != null) ? normalizeRuleId(a.controllerId) : null
        }
    } catch (Exception e) {
        mcpLog("warn", "deploy", "preflight snapshot for ${t} op failed: ${e.message}")
        return null
    }
    if (parentId == null) return null
    def preIds = []
    try {
        preIds = ((_rmFetchConfigJson(parentId)?.childApps ?: []) as List).collect { it?.id?.toString() }.findAll { it }
    } catch (Exception e) {
        mcpLog("warn", "deploy", "preflight child snapshot of parent ${parentId} failed: ${e.message}")
    }
    return [parentAppId: parentId, preChildIds: preIds, expectLabel: expectLabel]
}

private Integer _deployReconcileCreateOp(Map statusEntry) {
    def recon = statusEntry?.recon
    if (!(recon instanceof Map) || recon.parentAppId == null) return null
    try {
        def kids = (_rmFetchConfigJson(recon.parentAppId.toString() as Integer)?.childApps ?: []) as List
        def pre = ((recon.preChildIds ?: []) as List).collect { it?.toString() } as Set
        def cands = kids.findAll { it?.id != null && !pre.contains(it.id.toString()) }
        if (recon.expectLabel) {
            def byLabel = cands.findAll { it.label?.toString() == recon.expectLabel.toString() }
            if (byLabel.size() == 1) return byLabel[0].id.toString() as Integer
        }
        if (cands.size() == 1) return cands[0].id.toString() as Integer
    } catch (Exception e) {
        mcpLog("warn", "deploy", "reconcile against parent ${recon.parentAppId} failed: ${e.message}")
    }
    return null
}

private void _deployRecordCreated(Map job, Map op, Object newId) {
    if (newId == null) return
    Integer nid = newId.toString() as Integer
    def created = (job.createdAppIds instanceof List) ? job.createdAppIds : []
    if (!created.collect { it.toString() }.contains(nid.toString())) created << nid
    job.createdAppIds = created
    if (op.alias != null) {
        def aliases = (job.aliases instanceof Map) ? job.aliases : [:]
        aliases[op.alias.toString()] = nid
        job.aliases = aliases
    }
}

private boolean _deployBudgetExceeded(Long t0, Long fixedBudgetMs) {
    if (fixedBudgetMs != null) return t0 != null && (now() - t0) >= fixedBudgetMs
    return _timeBudgetExceeded(t0)
}

private boolean _deployScheduleWorker(Map job) {
    if (job.background == false) return false
    runIn(2, "deployJobWorker")
    return true
}

private void _deployRunValidation(Map job) {
    // The mechanical gate between staging and commit: every app this job created
    // must read back healthy (hub_get_rule_health fetches live config, so this is
    // also the existence readback) before the job may become ready_for_commit.
    def results = []
    boolean allOk = true
    ((job.createdAppIds ?: []) as List).each { id ->
        def h = null
        def err = null
        try {
            h = toolCheckRuleHealth([appId: id])
        } catch (Exception e) {
            err = e.message ?: "health check threw"
        }
        boolean ok = (err == null) && (h instanceof Map) && (h.broken != true) && (h.ok != false)
        if (!ok) allOk = false
        def issues = (h?.issues instanceof List) ? h.issues.take(5).collect { it?.toString()?.take(160) } : null
        results << [appId: id, ok: ok, broken: (h instanceof Map ? h.broken : null), issues: issues, error: err]
    }
    job.validation = [ok: allOk, checkedAt: now(), results: results]
    if (allOk) {
        job.phase = "ready_for_commit"
        _deployAppendHistory(job, "staging validated: ${results.size()} created app(s) healthy")
    } else {
        job.phase = "failed"
        job.error = "validation failed: created app(s) ${results.findAll { !it.ok }.collect { it.appId }} are unhealthy. Inspect via hub_get_rule_health / hub_get_app_config, fix, then operation=resume (re-validates) or operation=cancel."
        _deployAppendHistory(job, "validation FAILED")
    }
    _deploySaveJob(job)
}

private Map _deployRunSlice(Map job, Long t0, Integer maxOps, Long fixedBudgetMs) {
    int processed = 0
    boolean scheduled = false
    while (true) {
        if (!(job.phase?.toString() in _deployActivePhases())) break
        boolean staging = job.phase == "staging"
        def opsList = ((staging ? job.ops : job.commitOps) ?: []) as List
        def statusList = ((staging ? job.opStatus : job.commitStatus) ?: []) as List
        int idx = -1
        for (int i = 0; i < statusList.size(); i++) {
            if (statusList[i]?.status != "done") { idx = i; break }
        }
        if (idx < 0) {
            if (staging) {
                _deployRunValidation(job)
            } else {
                job.phase = "completed"
                job.sliceLeaseUntil = null
                _deployAppendHistory(job, "commit complete")
                _deploySaveJob(job)
            }
            continue
        }
        if (processed > 0 && ((maxOps != null && processed >= maxOps) || _deployBudgetExceeded(t0, fixedBudgetMs))) {
            scheduled = _deployScheduleWorker(job)
            break
        }
        def op = (Map) opsList[idx]
        def entry = (Map) statusList[idx]
        String opType = op.op?.toString()
        if (entry.status == "in_flight") {
            // A previous slice died mid-op. Reconcile create-type ops (adopt the app
            // if the create committed); re-run convergent ops; gate the one genuinely
            // ambiguous op (addActions -- a re-run can duplicate actions) behind an
            // explicit retryInFlight approval.
            if (opType in _deployCreateOpTypes()) {
                def adopted = _deployReconcileCreateOp(entry)
                if (adopted != null) {
                    entry.status = "done"
                    entry.result = [success: true, newAppId: adopted, adopted: true]
                    entry.finishedAt = now()
                    entry.remove("recon")
                    _deployRecordCreated(job, op, adopted)
                    statusList[idx] = entry
                    _deployAppendHistory(job, "op ${idx} (${opType}) adopted app ${adopted} after interrupted slice")
                    _deploySaveJob(job)
                    processed++
                    continue
                }
            } else if (opType == "addActions" && entry.retryApproved != true) {
                entry.status = "failed"
                entry.interrupted = true
                entry.error = "interrupted mid-write; a re-run may duplicate actions"
                statusList[idx] = entry
                job.phase = "failed"
                job.error = "op ${idx} (addActions) was interrupted mid-write. Verify via hub_get_app_config(appId) whether the actions landed, then operation=resume with retryInFlight=true to re-run it, or operation=cancel."
                job.sliceLeaseUntil = null
                _deploySaveJob(job)
                break
            }
        }
        entry.status = "in_flight"
        entry.startedAt = now()
        def recon = _deployPreflightCreateOp(op, (Map) (job.aliases ?: [:]))
        if (recon != null) entry.recon = recon
        statusList[idx] = entry
        job.sliceLeaseUntil = now() + _deployLeaseMs()
        _deploySaveJob(job)
        def outcome = null
        def execError = null
        try {
            outcome = _deployExecuteOp(job, op)
        } catch (Exception e) {
            execError = e.message ?: e.toString()
        }
        def res = outcome?.result
        if (execError == null && _deployOpSucceeded(res)) {
            entry.status = "done"
            entry.result = _deployTrimResult(res)
            entry.finishedAt = now()
            entry.remove("recon")
            entry.remove("error")
            if (outcome.newAppIdKey != null) _deployRecordCreated(job, op, ((Map) res)[outcome.newAppIdKey])
            if (entry.result?.backupKey) {
                def keys = (job.backupKeys instanceof List) ? job.backupKeys : []
                keys << entry.result.backupKey
                job.backupKeys = keys
            }
        } else {
            entry.status = "failed"
            entry.error = (execError ?: (res instanceof Map ? (res.error ?: res.note) : null) ?: "op returned failure").toString().take(300)
            entry.remove("recon")
            job.phase = "failed"
            job.error = "op ${idx} (${opType}) failed: ${entry.error}"
            job.sliceLeaseUntil = null
        }
        statusList[idx] = entry
        if (staging) { job.opStatus = statusList } else { job.commitStatus = statusList }
        _deploySaveJob(job)
        processed++
    }
    return [processed: processed, scheduled: scheduled]
}

def deployJobWorker() {
    // On-hub continuation: fires from the hub scheduler with NO client attached,
    // advances every active job in bounded slices, and re-arms itself while any
    // job remains active. This is what makes a job survive client death.
    def ids = _deployJobs().keySet().collect { it.toString() }
    ids.each { jid ->
        def job = _deployJobs()[jid]
        if (!(job instanceof Map) || !(job.phase?.toString() in _deployActivePhases())) return
        if (job.sliceLeaseUntil != null && now() < (job.sliceLeaseUntil as Long)) return
        job.sliceLeaseUntil = now() + _deployLeaseMs()
        _deploySaveJob(job)
        try {
            _deployRunSlice(job, now(), null, _deployWorkerBudgetMs())
        } catch (Exception e) {
            mcpLogError("deploy", "worker slice for job ${jid} threw", e)
        } finally {
            job.sliceLeaseUntil = null
            _deploySaveJob(job)
        }
    }
    def stillActive = _deployJobs().any { k, v -> (v instanceof Map) && (v.phase?.toString() in _deployActivePhases()) }
    if (stillActive) runIn(15, "deployJobWorker")
}

private Map _deployInlineSlice(Map job, Object reqT0, Integer maxOps) {
    Long t0 = (reqT0 instanceof Number) ? (reqT0 as Long) : now()
    job.sliceLeaseUntil = now() + _deployLeaseMs()
    _deploySaveJob(job)
    try {
        return _deployRunSlice(job, t0, maxOps, null)
    } finally {
        job.sliceLeaseUntil = null
        _deploySaveJob(job)
    }
}

private Map _deployOpCreate(Map args) {
    if (!(args?.ops instanceof List) || ((List) args.ops).isEmpty()) {
        throw new IllegalArgumentException("operation=create requires ops: a non-empty array of {op, args, alias?} objects. See hub_get_tool_guide(section='deployment_jobs').")
    }
    def commitOps = (args.commitOps instanceof List) ? args.commitOps : []
    Integer maxOps = null
    if (args.maxOpsPerCall != null) {
        maxOps = args.maxOpsPerCall.toString() as Integer
        if (maxOps < 1) throw new IllegalArgumentException("maxOpsPerCall must be >= 1")
    }
    def knownAliases = [] as Set
    _deployValidateOps((List) args.ops, knownAliases, "ops")
    _deployValidateOps(commitOps, knownAliases, "commitOps")
    def jobs = _deployJobs()
    if (jobs.size() >= _deployMaxJobs()) {
        def terminal = jobs.findAll { k, v -> !(v instanceof Map) || !(v.phase?.toString() in (_deployActivePhases() + ["draft", "ready_for_commit"])) }
        if (jobs.size() - terminal.size() >= _deployMaxJobs()) {
            throw new IllegalArgumentException("Too many active deployment jobs (${_deployMaxJobs()} max). Commit or cancel one first (hub_get_deployment lists them).")
        }
        def pruned = [:]
        jobs.each { k, v -> if (!terminal.containsKey(k)) pruned[k] = v }
        terminal.sort { a, b -> ((a.value?.updatedAt ?: 0) as Long) <=> ((b.value?.updatedAt ?: 0) as Long) }
            .drop([(terminal.size() - (_deployMaxJobs() - pruned.size() - 1)), 0].max())
            .each { k, v -> pruned[k] = v }
        atomicState.deployJobs = pruned
    }
    def jobId = "dj-${now()}".toString()
    def job = [
        jobId: jobId,
        name: args.name?.toString() ?: jobId,
        phase: (args.draft == true) ? "draft" : "staging",
        background: (args.background != false),
        maxOpsPerCall: maxOps,
        createdAt: now(),
        ops: args.ops,
        commitOps: commitOps,
        opStatus: ((List) args.ops).collect { [status: "pending"] },
        commitStatus: commitOps.collect { [status: "pending"] },
        aliases: [:],
        createdAppIds: [],
        backupKeys: [],
        validation: null,
        error: null,
        history: []
    ]
    _deployAppendHistory(job, "created (${((List) args.ops).size()} staging op(s), ${commitOps.size()} commit op(s))")
    _deploySaveJob(job)
    mcpLog("info", "deploy", "job ${jobId} created: ${job.name}")
    if (job.phase == "staging") {
        _deployScheduleWorker(job)
        _deployInlineSlice(job, args.__reqT0, maxOps)
    }
    return _deployJobStatus(_deployLoadJob(jobId), true)
}

private Map _deployOpResume(Map args) {
    def job = _deployLoadJob(args?.jobId)
    def phase = job.phase?.toString()
    if (phase in ["completed", "cancelled"]) {
        throw new IllegalArgumentException("Job '${job.jobId}' is ${phase}; nothing to resume.")
    }
    Integer maxOps = (args.maxOpsPerCall != null) ? (args.maxOpsPerCall.toString() as Integer) : (job.maxOpsPerCall as Integer)
    if (phase == "ready_for_commit") {
        def st = _deployJobStatus(job, true)
        st.note = "Job is validated and ready_for_commit -- call operation=commit to run the cutover ops."
        return st
    }
    if (phase in _deployActivePhases() && job.sliceLeaseUntil != null && now() < (job.sliceLeaseUntil as Long)) {
        def st = _deployJobStatus(job, true)
        st.note = "An on-hub worker slice is currently active for this job; poll hub_get_deployment(jobId='${job.jobId}')."
        return st
    }
    if (phase == "failed") {
        boolean retryInFlight = (args.retryInFlight == true)
        ["opStatus", "commitStatus"].each { listName ->
            def statusList = (job[listName] ?: []) as List
            statusList.eachWithIndex { entry, i ->
                if (!(entry instanceof Map)) return
                if (entry.status == "failed" || entry.status == "in_flight") {
                    if (entry.interrupted == true && !retryInFlight) {
                        throw new IllegalArgumentException("Job '${job.jobId}' op ${i} was interrupted mid-write (${((List) job[listName == 'opStatus' ? 'ops' : 'commitOps'])[i]?.op}). Verify its target via hub_get_app_config, then resume with retryInFlight=true to re-run it, or operation=cancel.")
                    }
                    entry.status = "pending"
                    entry.remove("error")
                    entry.remove("interrupted")
                    if (retryInFlight) entry.retryApproved = true
                }
            }
            job[listName] = statusList
        }
        boolean stagingComplete = ((job.opStatus ?: []) as List).every { it?.status == "done" }
        boolean commitStarted = ((job.commitStatus ?: []) as List).any { it?.status != "pending" }
        job.phase = (stagingComplete && job.validation?.ok == true && commitStarted) ? "committing" : "staging"
        job.error = null
        _deployAppendHistory(job, "resumed after failure")
        _deploySaveJob(job)
    } else if (phase == "draft") {
        job.phase = "staging"
        _deployAppendHistory(job, "draft started")
        _deploySaveJob(job)
    } else if (phase in _deployActivePhases()) {
        // Approve any stalled in_flight addActions gate proactively when asked.
        if (args.retryInFlight == true) {
            ["opStatus", "commitStatus"].each { listName ->
                def statusList = (job[listName] ?: []) as List
                statusList.each { entry -> if (entry instanceof Map && entry.status == "in_flight") entry.retryApproved = true }
                job[listName] = statusList
            }
            _deploySaveJob(job)
        }
        _deployAppendHistory(job, "resumed (stalled slice)")
        _deploySaveJob(job)
    }
    _deployScheduleWorker(job)
    _deployInlineSlice(job, args.__reqT0, maxOps)
    return _deployJobStatus(_deployLoadJob(job.jobId), true)
}

private Map _deployOpCommit(Map args) {
    def job = _deployLoadJob(args?.jobId)
    def phase = job.phase?.toString()
    if (phase != "ready_for_commit") {
        throw new IllegalArgumentException("Job '${job.jobId}' is in phase '${phase}' -- commit requires ready_for_commit (staging complete + validation passed). ${phase == 'failed' ? 'Fix and operation=resume first.' : (phase in _deployActivePhases() ? 'Staging is still running; poll hub_get_deployment.' : '')}")
    }
    Integer maxOps = (args.maxOpsPerCall != null) ? (args.maxOpsPerCall.toString() as Integer) : (job.maxOpsPerCall as Integer)
    if (((job.commitOps ?: []) as List).isEmpty()) {
        job.phase = "completed"
        _deployAppendHistory(job, "commit: no commitOps declared -- completed")
        _deploySaveJob(job)
        return _deployJobStatus(job, true)
    }
    job.phase = "committing"
    _deployAppendHistory(job, "commit started")
    _deploySaveJob(job)
    _deployScheduleWorker(job)
    _deployInlineSlice(job, args.__reqT0, maxOps)
    return _deployJobStatus(_deployLoadJob(job.jobId), true)
}

private Map _deployOpCancel(Map args) {
    def job = _deployLoadJob(args?.jobId)
    def phase = job.phase?.toString()
    if (phase == "completed") {
        throw new IllegalArgumentException("Job '${job.jobId}' already completed -- cancel does not undo a committed cutover. Reverse it manually (pause the new set / resume the old) or delete apps individually.")
    }
    if (phase == "cancelled") {
        throw new IllegalArgumentException("Job '${job.jobId}' is already cancelled.")
    }
    def cancelState = (job.cancel instanceof Map) ? job.cancel : [deleted: [], failures: []]
    def targets = ((job.createdAppIds ?: []) as List).reverse()
    targets.each { id ->
        def idStr = id.toString()
        if (cancelState.deleted.collect { it.toString() }.contains(idStr)) return
        try {
            def res = toolDeleteNativeApp([appId: id, confirm: true])
            if (_deployOpSucceeded(res)) {
                cancelState.deleted << id
            } else {
                def msg = (res instanceof Map ? (res.error ?: res.note) : "delete failed")?.toString()
                if (msg?.toLowerCase()?.contains("not found")) {
                    cancelState.deleted << id
                } else {
                    cancelState.failures << [appId: id, error: msg?.take(200)]
                }
            }
        } catch (Exception e) {
            def msg = e.message ?: e.toString()
            if (msg?.toLowerCase()?.contains("not found")) {
                cancelState.deleted << id
            } else {
                cancelState.failures << [appId: id, error: msg?.take(200)]
            }
        }
        job.cancel = cancelState
        _deploySaveJob(job)
    }
    job.phase = "cancelled"
    job.sliceLeaseUntil = null
    _deployAppendHistory(job, "cancelled: deleted ${cancelState.deleted.size()}/${targets.size()} created app(s)")
    _deploySaveJob(job)
    def st = _deployJobStatus(job, true)
    st.cancel = cancelState
    if (cancelState.failures) {
        st.note = "Cancelled, but ${cancelState.failures.size()} created app(s) could not be deleted -- remove them via hub_delete_native_app. Deleted: ${cancelState.deleted}."
    }
    return st
}

private Map _deployProgress(Map job) {
    def ops = (job.opStatus ?: []) as List
    def commits = (job.commitStatus ?: []) as List
    return [
        stagingDone: ops.count { it?.status == "done" } as Integer,
        stagingTotal: ops.size(),
        commitDone: commits.count { it?.status == "done" } as Integer,
        commitTotal: commits.size()
    ]
}

private Map _deployJobStatus(Map job, boolean includeOps) {
    def phase = job.phase?.toString()
    def out = [
        success: true,
        jobId: job.jobId,
        name: job.name,
        phase: phase,
        progress: _deployProgress(job),
        aliases: job.aliases ?: [:],
        createdAppIds: job.createdAppIds ?: [],
        backupKeys: job.backupKeys ?: [],
        createdAt: job.createdAt,
        updatedAt: job.updatedAt
    ]
    if (job.validation != null) out.validation = job.validation
    if (job.error) out.jobError = job.error
    if (includeOps) {
        out.ops = _deployZipOps((job.ops ?: []) as List, (job.opStatus ?: []) as List)
        out.commitOps = _deployZipOps((job.commitOps ?: []) as List, (job.commitStatus ?: []) as List)
    }
    switch (phase) {
        case "draft": out.note = "Draft -- operation=resume starts staging."; break
        case "staging": out.note = "Staging in progress on-hub; poll hub_get_deployment(jobId) -- the job advances without further calls (operation=resume also drives a slice)."; break
        case "ready_for_commit": out.note = "Staging validated -- operation=commit runs the cutover ops."; break
        case "committing": out.note = "Commit in progress on-hub; poll hub_get_deployment(jobId)."; break
        case "completed": out.note = "Completed. Rollback handles: backupKeys (hub_restore_backup) + createdAppIds."; break
        case "failed": out.note = "Failed -- see jobError. operation=resume retries; operation=cancel rolls back created apps."; break
        case "cancelled": out.note = "Cancelled -- created apps deleted (see cancel.deleted)."; break
    }
    return out
}

private List _deployZipOps(List ops, List statusList) {
    def zipped = []
    ops.eachWithIndex { op, i ->
        def entry = (i < statusList.size() && statusList[i] instanceof Map) ? statusList[i] : [:]
        def row = [index: i, op: op?.op, status: entry.status ?: "pending"]
        if (op?.alias != null) row.alias = op.alias
        if (entry.result != null) row.result = entry.result
        if (entry.error != null) row.error = entry.error
        if (entry.interrupted == true) row.interrupted = true
        zipped << row
    }
    return zipped
}

def toolCallDeployment(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    def operation = args?.operation?.toString()
    if (!(operation in ["create", "resume", "commit", "cancel"])) {
        throw new IllegalArgumentException("operation must be one of: create, resume, commit, cancel (got: ${operation})")
    }
    switch (operation) {
        case "create": return _deployOpCreate((Map) args)
        case "resume": return _deployOpResume((Map) args)
        case "commit": return _deployOpCommit((Map) args)
        case "cancel": return _deployOpCancel((Map) args)
    }
}

def toolGetDeployment(args) {
    if (args?.jobId != null) {
        def job = _deployLoadJob(args.jobId)
        return _deployJobStatus(job, args.includeOps != false)
    }
    def jobs = _deployJobs()
    def summaries = jobs.collect { k, v ->
        (v instanceof Map) ? [jobId: v.jobId, name: v.name, phase: v.phase, progress: _deployProgress(v), createdAt: v.createdAt, updatedAt: v.updatedAt] : null
    }.findAll { it != null }.sort { -(it.updatedAt ?: 0) }
    return [success: true, jobs: summaries, total: summaries.size()]
}

def _getAllToolDefinitions_partDeployJobs() {
    return [
        [
            name: "hub_call_deployment",
            description: """Run a durable multi-app deployment job on-hub (staged migrations: clone/import rules staged-disabled, edit them, validate, then cut over). operation=create takes a declarative ops manifest and checkpoints to hub storage after EVERY op, so the job survives client death: it keeps advancing via the hub scheduler with no client attached -- poll hub_get_deployment, resume from any fresh session. Staging auto-validates every created app (rule health + readback) before the job becomes ready_for_commit; operation=commit then runs the declared cutover ops; operation=cancel deletes ONLY the apps the job created; operation=resume continues after a failure, disconnect, or hub restart. Requires the Write master + confirm=true (+ a recent backup).[[FLAT_TRIM]]
Ops (each {op, args, alias?}): cloneApp {sourceAppId, newName?, stageDisabled?}, importApp {jsonContent|fromFile, parentHintAppId, newName?, stageDisabled?}, buttonRule {controllerId, buttonNumber, event}, addActions {appId, actions}, modifyAction {appId, index, mods}, pause/resume {ruleId|ruleIds}, setDisabled {appId, disabled}. A create-type op may declare alias:"name"; later ops reference the created app as {"alias":"name"} wherever an appId is taken. commitOps use the same shapes and run only on operation=commit. Phases: draft > staging > ready_for_commit > committing > completed | failed | cancelled. Rollback: per-edit backupKeys (hub_restore_backup) + createdAppIds are recorded on the job.[[/FLAT_TRIM]] Full op reference + worked example: hub_get_tool_guide(section='deployment_jobs').""",
            inputSchema: [
                type: "object",
                properties: [
                    operation: [type: "string", enum: ["create", "resume", "commit", "cancel"], description: "create=start a job from ops; resume=continue (after failure/disconnect/draft); commit=run cutover ops of a ready_for_commit job; cancel=delete the apps the job created."],
                    jobId: [type: "string", description: "Job id (from create / hub_get_deployment). Required for resume/commit/cancel."],
                    name: [type: "string", description: "create: human label for the job."],
                    ops: [type: "array", items: [type: "object"], description: "create: staging ops, each {op, args, alias?}. Executed in order with a checkpoint after each.[[FLAT_TRIM]] op is one of cloneApp/importApp/buttonRule/addActions/modifyAction/pause/resume/setDisabled.[[/FLAT_TRIM]]"],
                    commitOps: [type: "array", items: [type: "object"], description: "create: cutover ops (same shapes; typically pause old set + setDisabled false / resume new set), run only on operation=commit."],
                    draft: [type: "boolean", description: "create: true = store the job without starting it (phase draft); operation=resume starts it."],
                    background: [type: "boolean", description: "create: default true = the job continues on-hub between calls via the scheduler. false = advances only while a client calls resume."],
                    maxOpsPerCall: [type: "integer", description: "Cap ops executed inside THIS call's slice (remaining ops continue on-hub / on resume). Default: run until the response-time budget."],
                    retryInFlight: [type: "boolean", description: "resume: approve re-running an op that was interrupted mid-write (an addActions re-run can duplicate actions -- verify via hub_get_app_config first)."],
                    confirm: [type: "boolean", description: "Must be true."]
                ],
                required: ["operation", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Call outcome (job-level failure is phase=failed + jobError)"],
                    jobId: [type: "string", description: "Job id for polling/resume"],
                    name: [type: "string", description: "Job label"],
                    phase: [type: "string", description: "draft|staging|ready_for_commit|committing|completed|failed|cancelled"],
                    progress: [type: "object", description: "{stagingDone, stagingTotal, commitDone, commitTotal}"],
                    aliases: [type: "object", description: "alias -> created appId"],
                    createdAppIds: [type: "array", description: "Apps this job created (cancel deletes exactly these)", items: [type: "integer"]],
                    backupKeys: [type: "array", description: "Auto-snapshot keys recorded from edit ops (hub_restore_backup)", items: [type: "string"]],
                    validation: [type: ["object", "null"], description: "Staging validation gate results ({ok, results[]})"],
                    jobError: [type: "string", description: "Present when phase=failed"],
                    ops: [type: "array", description: "Per-op checkpoint status", items: [type: "object"]],
                    commitOps: [type: "array", description: "Per-commit-op checkpoint status", items: [type: "object"]],
                    cancel: [type: "object", description: "cancel: {deleted[], failures[]}"],
                    createdAt: [type: ["integer", "null"], description: "Epoch ms"],
                    updatedAt: [type: ["integer", "null"], description: "Epoch ms"],
                    note: [type: "string", description: "Phase-appropriate next step"]
                ],
                required: ["success", "jobId", "phase"]
            ]
        ],
        [
            name: "hub_get_deployment",
            description: """Read a deployment job's live status: phase, per-op checkpoints, aliases, created apps, validation results, rollback handles. Omit jobId to list all jobs. Pure read -- pair with hub_call_deployment (jobs advance on-hub between calls, so poll this to follow progress).""",
            inputSchema: [
                type: "object",
                properties: [
                    jobId: [type: "string", description: "Job id from hub_call_deployment create. Omit to list all jobs."],
                    includeOps: [type: "boolean", description: "Include per-op checkpoint detail (default true)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Call outcome"],
                    jobId: [type: "string", description: "Present with jobId arg"],
                    name: [type: "string", description: "Job label"],
                    phase: [type: "string", description: "Job phase"],
                    progress: [type: "object", description: "{stagingDone, stagingTotal, commitDone, commitTotal}"],
                    aliases: [type: "object", description: "alias -> created appId"],
                    createdAppIds: [type: "array", description: "Apps the job created", items: [type: "integer"]],
                    backupKeys: [type: "array", description: "Auto-snapshot keys", items: [type: "string"]],
                    validation: [type: ["object", "null"], description: "Validation gate results"],
                    jobError: [type: "string", description: "Present when phase=failed"],
                    ops: [type: "array", description: "Per-op checkpoint status", items: [type: "object"]],
                    commitOps: [type: "array", description: "Per-commit-op status", items: [type: "object"]],
                    jobs: [type: "array", description: "List mode: job summaries", items: [type: "object"]],
                    total: [type: "integer", description: "List mode: job count"],
                    note: [type: "string", description: "Phase-appropriate next step"]
                ],
                required: ["success"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partDeployJobs() {
    return ["hub_get_deployment"]
}

def _toolDisplayMeta_partDeployJobs() {
    return [
        hub_call_deployment: [title: "Run Deployment Job", summary: "Run a durable staged-migration job: clone/import/edit apps with on-hub checkpoints, validate, commit, or cancel."],
        hub_get_deployment: [title: "Get Deployment Job", summary: "Read a deployment job's phase, per-op checkpoints, and rollback handles, or list all jobs."]
    ]
}
