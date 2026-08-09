library(name: "McpDeployJobsLib", namespace: "mcp", author: "kingpanther13", description: "Durable multi-app deployment jobs: staged clone/import/edit ops with on-hub checkpoints, a validation gate, commit cutover, and cancel rollback")

def _deployOpTypes() { ["cloneApp", "importApp", "buttonRule", "addActions", "modifyAction", "pause", "resume", "setDisabled"] }
def _deployCreateOpTypes() { ["cloneApp", "importApp", "buttonRule"] }
def _deployActivePhases() { ["staging", "committing"] }
def _deployMaxJobs() { 8 }
def _deployMaxManifestOps() { 50 }
def _deployMaxManifestChars() { 64000 }
def _deployLeaseMs() { 90000L }
def _deployWorkerBudgetMs() { 45000L }
def _deployMaxWorkerFailures() { 3 }

def _deployJobs() {
    return (atomicState.deployJobs instanceof Map) ? atomicState.deployJobs : [:]
}

private Map _deployLoadJob(Object jobIdRaw) {
    if (jobIdRaw == null) throw new IllegalArgumentException("jobId is required for this operation. List jobs via deployment:{op:'status'}.")
    def jobId = jobIdRaw.toString()
    def job = _deployJobs()[jobId]
    if (!(job instanceof Map)) throw new IllegalArgumentException("Deployment job '${jobId}' not found. List jobs via deployment:{op:'status'}.")
    return job
}

private void _deploySaveJob(Map job) {
    // The checkpoint primitive: one atomicState map-entry write per save, so a
    // killed request/worker thread loses at most the op in flight, never the job.
    job.updatedAt = now()
    if (!(atomicState.deployJobs instanceof Map)) atomicState.deployJobs = [:]
    try {
        atomicState.updateMapValue("deployJobs", job.jobId.toString(), job)
    } catch (MissingMethodException e) {
        // Whole-map fallback for older firmware / test harnesses without
        // updateMapValue -- the same pattern _opTokenPut uses.
        def jobs = [:]
        if (atomicState.deployJobs instanceof Map) jobs.putAll(atomicState.deployJobs)
        jobs[job.jobId.toString()] = job
        atomicState.deployJobs = jobs
    }
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
        if (t == "setDisabled") _deployRequireDisabledFlag((op.args instanceof Map) ? op.args.disabled : null, "${listName}[${i}] (setDisabled)")
        if (op.alias != null) {
            if (!(t in _deployCreateOpTypes())) throw new IllegalArgumentException("${listName}[${i}].alias is only valid on ${_deployCreateOpTypes().join('/')} ops (it names the created app's id for later ops)")
            def a = op.alias.toString()
            if (knownAliases.contains(a)) throw new IllegalArgumentException("${listName}[${i}].alias '${a}' is declared more than once")
        }
        // An op's own args are checked BEFORE its alias joins the known set, so a
        // self-reference ({alias:'x'} inside the op declaring 'x') fails here rather
        // than mid-job, where the alias has no recorded appId yet.
        _deployCheckAliasRefs(op.args, knownAliases, "${listName}[${i}].args")
        if (op.alias != null) knownAliases << op.alias.toString()
    }
}

private boolean _deployRequireDisabledFlag(Object raw, String where) {
    // No default: an absent flag used to mean "disable", so a caller who meant
    // "enable" and mistyped the key silently disabled the app mid-cutover.
    if (raw instanceof Boolean) return raw
    def s = raw?.toString()
    if (s == "true") return true
    if (s == "false") return false
    throw new IllegalArgumentException("${where} requires args.disabled as an explicit boolean -- true disables the app, false enables it (got: ${raw == null ? 'absent' : raw}). See hub_get_tool_guide(section='deployment_jobs').")
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
            return [result: toolSetAppDisabled([appId: a.appId, disabled: _deployRequireDisabledFlag(a.disabled, "setDisabled op")])]
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
    ["success", "newAppId", "buttonRuleId", "paused", "disabled", "partial", "adopted",
     "status", "settingsSkipped", "updateRuleFailed", "subscriptionsNotLive"].each { k ->
        if (res.containsKey(k)) keep[k] = res[k]
    }
    if (res.backup instanceof Map && res.backup.backupKey) keep.backupKey = res.backup.backupKey
    if (res.backupKey) keep.backupKey = res.backupKey
    if (res.repairHints instanceof List) {
        keep.repairHints = ((List) res.repairHints).take(5).collect { it?.toString()?.take(120) }
    }
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
        // Label match is the ONLY adoption evidence. A lone unmatched new child is just as
        // likely somebody else's concurrently-created app, and adopting it would put a
        // stranger on createdAppIds for cancel to delete; returning null hands the op to
        // the interrupted gate instead, which is refusable but never destructive.
        if (recon.expectLabel) {
            def byLabel = cands.findAll { it.label?.toString() == recon.expectLabel.toString() }
            if (byLabel.size() == 1) return byLabel[0].id.toString() as Integer
        }
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
    if (results.isEmpty()) {
        // An empty createdAppIds means nothing was health-checked; ok:true here would read
        // as a passed gate instead of a gate with nothing to check.
        job.validation.note = "no created apps to health-check; per-op success gates were the only validation"
    }
    if (allOk) {
        job.phase = "ready_for_commit"
        _deployAppendHistory(job, results.isEmpty() ? "staging complete: no created apps to health-check" : "staging validated: ${results.size()} created app(s) healthy")
    } else {
        job.phase = "failed"
        job.error = "validation failed: created app(s) ${results.findAll { !it.ok }.collect { it.appId }} are unhealthy. Inspect via hub_get_rule_health / hub_get_app_config, fix, then op='resume' (re-validates) or op='cancel'."
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
            // if the create committed); re-run convergent ops; gate the ambiguous
            // ones behind an explicit retryInFlight approval -- an unreconcilable
            // create (a re-run can leave a duplicate app cancel never deletes) and
            // addActions (a re-run can duplicate actions).
            if (opType in _deployCreateOpTypes()) {
                def adopted = _deployReconcileCreateOp(entry)
                if (adopted != null) {
                    entry.status = "done"
                    entry.result = [success: true, newAppId: adopted, adopted: true]
                    entry.finishedAt = now()
                    entry.remove("recon")
                    _deployRecordCreated(job, op, adopted)
                    statusList[idx] = entry
                    if (staging) { job.opStatus = statusList } else { job.commitStatus = statusList }
                    _deployAppendHistory(job, "op ${idx} (${opType}) adopted app ${adopted} after interrupted slice")
                    _deploySaveJob(job)
                    processed++
                    continue
                }
                if (entry.retryApproved != true) {
                    entry.status = "failed"
                    entry.interrupted = true
                    entry.error = "interrupted mid-create and the created app could not be identified; a re-run may create a duplicate"
                    statusList[idx] = entry
                    if (staging) { job.opStatus = statusList } else { job.commitStatus = statusList }
                    job.phase = "failed"
                    job.error = "op ${idx} (${opType}) was interrupted mid-create and could not be reconciled. Verify via hub_get_app_config / hub_list_rules whether the app was created (delete a stray copy manually), then op='resume' with retryInFlight=true to re-run it, or op='cancel'."
                    job.sliceLeaseUntil = null
                    _deploySaveJob(job)
                    break
                }
            } else if (opType == "addActions" && entry.retryApproved != true) {
                entry.status = "failed"
                entry.interrupted = true
                entry.error = "interrupted mid-write; a re-run may duplicate actions"
                statusList[idx] = entry
                if (staging) { job.opStatus = statusList } else { job.commitStatus = statusList }
                job.phase = "failed"
                job.error = "op ${idx} (addActions) was interrupted mid-write. Verify via hub_get_app_config(appId) whether the actions landed, then op='resume' with retryInFlight=true to re-run it, or op='cancel'."
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
            // A create can COMMIT and still report failure -- a clone whose stageDisabled leg
            // failed returns success:false WITH newAppId. Record the id anyway: unrecorded, cancel
            // strands a live enabled app and resume blindly re-runs the create as a duplicate.
            def committedId = null
            if (execError == null && (opType in _deployCreateOpTypes()) && (res instanceof Map) && outcome?.newAppIdKey != null) {
                committedId = ((Map) res)[outcome.newAppIdKey]
            }
            if (committedId != null) _deployRecordCreated(job, op, committedId)
            if (res instanceof Map) entry.result = _deployTrimResult(res)
            job.phase = "failed"
            if (committedId != null) {
                entry.interrupted = true
                job.error = "op ${idx} (${opType}) failed AFTER the app was created: app ${committedId} EXISTS. op='cancel' now deletes it; op='resume' with retryInFlight=true re-runs the create and MAY DUPLICATE it -- verify via hub_get_app_config(${committedId}) first. Original failure: ${entry.error}"
            } else {
                job.error = "op ${idx} (${opType}) failed: ${entry.error}"
            }
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
        // A worker armed by ANOTHER job must not advance a job whose caller opted out of
        // on-hub continuation; background:false means op='resume' is the only driver.
        if (job.background == false) return
        if (job.sliceLeaseUntil != null && now() < (job.sliceLeaseUntil as Long)) return
        job.sliceLeaseUntil = now() + _deployLeaseMs()
        _deploySaveJob(job)
        try {
            _deployRunSlice(job, now(), null, _deployWorkerBudgetMs())
            job.workerFailStreak = 0
        } catch (Exception e) {
            mcpLogError("deploy", "worker slice for job ${jid} threw", e)
            // A throw leaves the job ACTIVE, so the re-arm below would retry it every
            // 15s forever. Bound the streak: after 3 consecutive throwing slices the
            // job goes terminal, which is what lets the re-arm condition end.
            int streak = ((job.workerFailStreak ?: 0) as Integer) + 1
            job.workerFailStreak = streak
            _deployAppendHistory(job, "worker slice threw: ${(e.message ?: e.toString()).toString().take(120)} (streak ${streak})")
            if (streak >= _deployMaxWorkerFailures()) {
                job.phase = "failed"
                job.error = "worker slice threw repeatedly: ${(e.message ?: e.toString()).toString().take(200)}"
                _deployAppendHistory(job, "worker abandoned after ${streak} throwing slices")
            }
        } finally {
            job.sliceLeaseUntil = null
            _deploySaveJob(job)
        }
    }
    def stillActive = _deployJobs().any { k, v -> (v instanceof Map) && (v.phase?.toString() in _deployActivePhases()) && v.background != false }
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
        throw new IllegalArgumentException("op='create' requires ops: a non-empty array of {op, args, alias?} objects. See hub_get_tool_guide(section='deployment_jobs').")
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
    // Bound the manifest BEFORE the prune below writes anything: the whole job record is
    // rewritten to atomicState on every checkpoint, so an unbounded manifest makes each of
    // those saves unbounded too.
    int totalOps = ((List) args.ops).size() + commitOps.size()
    if (totalOps > _deployMaxManifestOps()) {
        throw new IllegalArgumentException("Deployment manifest declares ${totalOps} ops (ops + commitOps); the cap is ${_deployMaxManifestOps()}. Split the migration across smaller jobs.")
    }
    int manifestChars
    try {
        manifestChars = groovy.json.JsonOutput.toJson([ops: args.ops, commitOps: commitOps]).length()
    } catch (Exception serErr) {
        throw new IllegalArgumentException("ops/commitOps could not be serialized (${serErr.message}); every op must be plain JSON values.")
    }
    if (manifestChars > _deployMaxManifestChars()) {
        throw new IllegalArgumentException("Deployment manifest serializes to ${manifestChars} characters; the cap is ${_deployMaxManifestChars()}. Split the migration across smaller jobs.")
    }
    def jobs = _deployJobs()
    if (jobs.size() >= _deployMaxJobs()) {
        // A FAILED job is NOT evictable: its createdAppIds/backupKeys are the only rollback
        // handles for whatever it half-built, and evicting the record loses them. Cancelling
        // it deletes those apps and makes it evictable.
        def evictable = jobs.findAll { k, v -> !(v instanceof Map) || (v.phase?.toString() in ["completed", "cancelled"]) }
        if (jobs.size() - evictable.size() >= _deployMaxJobs()) {
            throw new IllegalArgumentException("Too many active deployment jobs (${_deployMaxJobs()} max). Commit or cancel one first -- including any FAILED job, whose created apps and backup keys it holds until you cancel it (deployment:{op:'status'} lists them).")
        }
        def pruned = [:]
        jobs.each { k, v -> if (!evictable.containsKey(k)) pruned[k] = v }
        evictable.sort { a, b -> ((a.value?.updatedAt ?: 0) as Long) <=> ((b.value?.updatedAt ?: 0) as Long) }
            .drop([(evictable.size() - (_deployMaxJobs() - pruned.size() - 1)), 0].max())
            .each { k, v -> pruned[k] = v }
        atomicState.deployJobs = pruned
    }
    // Random suffix, not the bare clock: two creates landing in the same millisecond
    // would otherwise share a jobId and the second would overwrite the first.
    def jobId = "dj-" + Long.toString(now(), 16) + "-" + java.util.UUID.randomUUID().toString()
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
    boolean workerArmed = false
    if (job.phase == "staging") {
        workerArmed = _deployScheduleWorker(job)
        def slice = _deployInlineSlice(job, args.__reqT0, maxOps)
        if (slice?.scheduled == true) workerArmed = true
    }
    def created = _deployJobStatus(_deployLoadJob(jobId), true)
    created.workerScheduled = workerArmed
    return created
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
        st.note = "Job is validated and ready_for_commit -- call op='commit' to run the cutover ops."
        return st
    }
    if (phase in _deployActivePhases() && job.sliceLeaseUntil != null && now() < (job.sliceLeaseUntil as Long)) {
        def st = _deployJobStatus(job, true)
        st.note = "An on-hub worker slice is currently active for this job; poll deployment:{op:'status', jobId:'${job.jobId}'}."
        return st
    }
    if (phase == "failed") {
        boolean retryInFlight = (args.retryInFlight == true)
        // Read BEFORE the reset loop rewrites failed/in_flight entries to pending:
        // afterwards nothing distinguishes a job that died on its first commit op
        // from one that never started committing.
        boolean commitStarted = ((job.commitStatus ?: []) as List).any { it?.status != "pending" }
        ["opStatus", "commitStatus"].each { listName ->
            def statusList = (job[listName] ?: []) as List
            statusList.eachWithIndex { entry, i ->
                if (!(entry instanceof Map)) return
                if (entry.status == "failed" || entry.status == "in_flight") {
                    if (entry.interrupted == true && !retryInFlight) {
                        throw new IllegalArgumentException("Job '${job.jobId}' op ${i} was interrupted mid-write (${((List) job[listName == 'opStatus' ? 'ops' : 'commitOps'])[i]?.op}). Verify its target via hub_get_app_config, then resume with retryInFlight=true to re-run it, or op='cancel'.")
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
    boolean workerArmed = _deployScheduleWorker(job)
    def slice = _deployInlineSlice(job, args.__reqT0, maxOps)
    def resumed = _deployJobStatus(_deployLoadJob(job.jobId), true)
    resumed.workerScheduled = (workerArmed || slice?.scheduled == true)
    return resumed
}

private Map _deployOpCommit(Map args) {
    def job = _deployLoadJob(args?.jobId)
    def phase = job.phase?.toString()
    if (phase != "ready_for_commit") {
        throw new IllegalArgumentException("Job '${job.jobId}' is in phase '${phase}' -- commit requires ready_for_commit (staging complete + validation passed). ${phase == 'failed' ? "Fix and op='resume' first." : (phase in _deployActivePhases() ? "Staging is still running; poll op='status'." : '')}")
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
    boolean workerArmed = _deployScheduleWorker(job)
    def slice = _deployInlineSlice(job, args.__reqT0, maxOps)
    def committed = _deployJobStatus(_deployLoadJob(job.jobId), true)
    committed.workerScheduled = (workerArmed || slice?.scheduled == true)
    return committed
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
    // A live worker slice holds a snapshot of this job and saves it whole; letting
    // cancel write the terminal state underneath it would be undone on the slice's
    // next save, resurrecting a job whose apps were already deleted.
    if (phase in _deployActivePhases() && job.sliceLeaseUntil != null && now() < (job.sliceLeaseUntil as Long)) {
        throw new IllegalArgumentException("Job '${job.jobId}' has an on-hub worker slice active right now -- wait for the slice to finish (poll deployment:{op:'status', jobId:'${job.jobId}'}), then cancel.")
    }
    def cancelState = (job.cancel instanceof Map) ? job.cancel : [deleted: [], failures: []]
    def targets = ((job.createdAppIds ?: []) as List).reverse()
    // Hold the lease across the whole delete loop: a worker firing between two deletes
    // would otherwise take the job and re-save its own snapshot over this one.
    job.sliceLeaseUntil = now() + _deployLeaseMs()
    _deploySaveJob(job)
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
        st.success = false
        st.error = "Rollback incomplete: ${cancelState.failures.size()} of ${targets.size()} created app(s) could not be deleted."
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
    boolean background = (job.background != false)
    def out = [
        success: (phase != "failed"),
        jobId: job.jobId,
        name: job.name,
        phase: phase,
        background: background,
        progress: _deployProgress(job),
        aliases: job.aliases ?: [:],
        createdAppIds: job.createdAppIds ?: [],
        backupKeys: job.backupKeys ?: [],
        createdAt: job.createdAt,
        updatedAt: job.updatedAt
    ]
    if (job.validation != null) out.validation = job.validation
    // Carry the rollback residue on EVERY status read, not just the cancel response:
    // otherwise the list of apps that could not be deleted dies with that one response
    // and the operator has nothing left to clean up from.
    if (job.cancel instanceof Map) {
        out.cancel = job.cancel
        def cancelFailures = (job.cancel.failures ?: []) as List
        if (cancelFailures) {
            out.success = false
            out.error = "Rollback incomplete: ${cancelFailures.size()} created app(s) could not be deleted -- remove them via hub_delete_native_app."
        }
    }
    // jobError stays for compatibility; error is the runtime-error contract's field.
    if (job.error) out.jobError = job.error
    if (phase == "failed" && job.error) out.error = job.error
    if (includeOps) {
        out.ops = _deployZipOps((job.ops ?: []) as List, (job.opStatus ?: []) as List)
        out.commitOps = _deployZipOps((job.commitOps ?: []) as List, (job.commitStatus ?: []) as List)
    }
    switch (phase) {
        case "draft": out.note = "Draft -- op='resume' starts staging."; break
        case "staging":
            out.note = background
                ? "Staging in progress on-hub; poll deployment:{op:'status', jobId} -- the job advances without further calls (op='resume' also drives a slice)."
                : "Staging advances only on op='resume' (background=false disabled the on-hub worker); poll deployment:{op:'status', jobId} between resumes."
            break
        case "ready_for_commit":
            out.note = (job.validation?.results instanceof List && ((List) job.validation.results).isEmpty())
                ? "Staging complete (no created apps to validate) -- op='commit' runs the cutover ops."
                : "Staging validated -- op='commit' runs the cutover ops."
            break
        case "committing": out.note = "Commit in progress on-hub; poll deployment:{op:'status', jobId}."; break
        case "completed": out.note = "Completed. Rollback handles: backupKeys (hub_restore_backup) + createdAppIds. op='delete' removes the finished record."; break
        case "failed": out.note = "Failed -- see jobError. op='resume' retries; op='cancel' rolls back created apps."; break
        case "cancelled": out.note = "Cancelled -- created apps deleted (see cancel.deleted). op='delete' removes the finished record."; break
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


def _deployHandleArgument(Map deployment, Boolean confirm, Object reqT0) {
    // The deployment ARGUMENT surface (no dedicated tools): hub_set_rule and
    // hub_set_native_app both route here when args.deployment is present, so a
    // staged multi-app migration rides the tools the client already has.
    // op="status" is the read mode (guide:true precedent -- makes NO change and
    // needs no confirm); create/resume/commit/cancel are the write ops.
    def op = deployment?.op?.toString()
    if (!(op in ["create", "resume", "commit", "cancel", "delete", "status"])) {
        throw new IllegalArgumentException("deployment.op must be one of: create, resume, commit, cancel, delete, status (got: ${op}). See hub_get_tool_guide(section='deployment_jobs').")
    }
    if (op == "status") {
        if (deployment.jobId != null) {
            return _deployJobStatus(_deployLoadJob(deployment.jobId), deployment.includeOps != false)
        }
        def jobs = _deployJobs()
        def summaries = jobs.collect { k, v ->
            (v instanceof Map) ? [jobId: v.jobId, name: v.name, phase: v.phase, progress: _deployProgress(v), createdAt: v.createdAt, updatedAt: v.updatedAt] : null
        }.findAll { it != null }.sort { -(it.updatedAt ?: 0) }
        return [success: true, jobs: summaries, total: summaries.size()]
    }
    requireDestructiveConfirm(confirm)
    def opArgs = [:]
    opArgs.putAll(deployment)
    opArgs.remove("op")
    opArgs.__reqT0 = reqT0
    switch (op) {
        case "create": return _deployOpCreate(opArgs)
        case "resume": return _deployOpResume(opArgs)
        case "commit": return _deployOpCommit(opArgs)
        case "cancel": return _deployOpCancel(opArgs)
        case "delete": return _deployOpDelete(opArgs)
    }
}

private Map _deployOpDelete(Map args) {
    def job = _deployLoadJob(args?.jobId)
    def phase = job.phase?.toString()
    // Belt-and-braces with the terminal-phase check below: a live worker slice
    // re-saves its whole job snapshot, which would resurrect a record deleted here.
    if (phase in _deployActivePhases() && job.sliceLeaseUntil != null && now() < (job.sliceLeaseUntil as Long)) {
        throw new IllegalArgumentException("Job '${job.jobId}' has an on-hub worker slice active right now -- wait for the slice to finish (poll deployment:{op:'status', jobId:'${job.jobId}'}), then delete the finished record.")
    }
    if (!(phase in ["completed", "cancelled"])) {
        def steer = (phase in _deployActivePhases()) ? "Wait for the running slice, then op='commit' or op='cancel' first." : "Finish it first: op='cancel' rolls back created apps (or op='commit' from ready_for_commit)."
        throw new IllegalArgumentException("Job '${job.jobId}' is in phase '${phase}' -- delete removes only finished job records (completed/cancelled). ${steer}")
    }
    def jobs = _deployJobs()
    def pruned = [:]
    jobs.each { k, v -> if (k.toString() != job.jobId.toString()) pruned[k] = v }
    atomicState.deployJobs = pruned
    return [success: true, jobId: job.jobId, name: job.name, deleted: true,
            backupKeys: job.backupKeys ?: [], createdAppIds: job.createdAppIds ?: [],
            note: "Job record removed. Its apps and backups are untouched -- backupKeys still work with hub_restore_backup."]
}

def _deployRouteFromTool(Map args) {
    // Shared guard for hub_set_rule / hub_set_native_app: deployment is a
    // self-contained call -- combining it with an edit/create in the same call
    // is ambiguous (which runs first?) and is rejected fail-loud.
    if (!(args.deployment instanceof Map)) {
        throw new IllegalArgumentException("deployment must be an object {op: create|resume|commit|cancel|delete|status, ...}. See hub_get_tool_guide(section='deployment_jobs').")
    }
    def others = args.keySet().findAll { !(it in ["deployment", "confirm", "bestPracticeKey", "__reqT0"]) }
    if (others) {
        throw new IllegalArgumentException("deployment is a self-contained call and cannot be combined with other arguments (got: ${others.sort().join(', ')}). Issue the deployment call alone.")
    }
    return _deployHandleArgument((Map) args.deployment, args.confirm as Boolean, args.__reqT0)
}

def _getAllToolDefinitions_partDeployJobs() {
    // No dedicated tools: the deployment surface is the `deployment` argument on
    // hub_set_rule / hub_set_native_app (defs live in McpNativeRulesLib). The
    // empty part keeps the library on the standard aggregation rails so a future
    // def added here ships without main-file archaeology.
    return []
}

def _toolDisplayMeta_partDeployJobs() {
    return [:]
}
