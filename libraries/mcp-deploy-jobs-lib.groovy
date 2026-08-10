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

// True when this call's ONLY work is deployment op='status'. That call is exempt from the
// Write master like the other schema-only exemptions, but unlike them it returns HUB DATA
// -- job names, ids, aliases, created appIds, backup keys, the whole rollback surface --
// rather than static reference content, so the READ master has to govern it. Without this
// a hub with BOTH masters off still served the job records.
def _isDeploymentStatusOnlyCall(toolName, args) {
    String n = toolName?.toString()
    if (n != 'hub_set_rule' && n != 'hub_set_native_app') return false
    if (!(args instanceof Map)) return false
    def dep = args.deployment
    return (dep instanceof Map) && dep.op?.toString() == 'status'
}

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

// End-of-slice lease release. Clears the lease and persists this slice's snapshot ONLY
// while the slice still owns the lease it last wrote. A slice that overruns
// _deployLeaseMs can be superseded -- by the next worker pass, or by a cancel/resume
// whose own lease guard saw the expired lease and took the job -- and clearing blindly
// would both hand away the new owner's lease and write this slice's stale WHOLE-job
// snapshot over the newer record, resurrecting a phase the new owner had moved past.
void _deployReleaseLease(Map job) {
    Long held = (job.sliceLeaseUntil != null) ? (job.sliceLeaseUntil as Long) : null
    def fresh = _deployJobs()[job.jobId?.toString()]
    Long current = (fresh instanceof Map && fresh.sliceLeaseUntil != null) ? (fresh.sliceLeaseUntil as Long) : null
    if (current != held) {
        mcpLog("warn", "deploy", "job ${job.jobId}: slice lease changed under this slice (held ${held}, stored ${current}) -- abandoning its final save so the new owner's record stands.")
        return
    }
    job.sliceLeaseUntil = null
    _deploySaveJob(job)
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
        _deployValidateOpArgs(t, (op.args instanceof Map) ? (Map) op.args : [:], "${listName}[${i}] (${t})")
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

// Presence check for each op type's required args, run at manifest-validation time.
// Without it a manifest whose LAST op is missing an argument still stages every op
// before it -- each committing real apps -- and then throws mid-job, which is the
// expensive failure the up-front validation exists to prevent. Presence only: whether an
// appId exists, or a controllerId really is a Button Controller, needs the hub and stays
// with the tool. An {alias:"x"} placeholder is a non-null value, so it passes here and is
// resolved (and range-checked) at execution.
private void _deployValidateOpArgs(String opType, Map a, String where) {
    switch (opType) {
        case "cloneApp":
            if (a.sourceAppId == null && a.appId == null) throw new IllegalArgumentException("${where} requires args.sourceAppId (the app to clone). See hub_get_tool_guide(section='deployment_jobs').")
            break
        case "importApp":
            if (a.parentHintAppId == null) throw new IllegalArgumentException("${where} requires args.parentHintAppId (any existing rule id under the target parent -- it seeds the cloner). See hub_get_tool_guide(section='deployment_jobs').")
            if (a.jsonContent == null && a.fromFile == null) throw new IllegalArgumentException("${where} requires args.jsonContent or args.fromFile (the exported app JSON). See hub_get_tool_guide(section='deployment_jobs').")
            break
        case "buttonRule":
            if (a.controllerId == null) throw new IllegalArgumentException("${where} requires args.controllerId (the parent Button Controller-5.1 appId). See hub_get_tool_guide(section='deployment_jobs').")
            if (a.buttonNumber == null) throw new IllegalArgumentException("${where} requires args.buttonNumber (the physical button number, >= 1). See hub_get_tool_guide(section='deployment_jobs').")
            if (a.event == null) throw new IllegalArgumentException("${where} requires args.event (pushed/held/doubleTapped/released). See hub_get_tool_guide(section='deployment_jobs').")
            break
        case "addActions":
            if (a.appId == null) throw new IllegalArgumentException("${where} requires args.appId. See hub_get_tool_guide(section='deployment_jobs').")
            if (!(a.actions instanceof List)) throw new IllegalArgumentException("${where} requires args.actions as an array of RM action specs. See hub_get_tool_guide(section='deployment_jobs').")
            break
        case "modifyAction":
            if (a.appId == null) throw new IllegalArgumentException("${where} requires args.appId. See hub_get_tool_guide(section='deployment_jobs').")
            if (a.index == null) throw new IllegalArgumentException("${where} requires args.index (the action's position in the rule). See hub_get_tool_guide(section='deployment_jobs').")
            if (!(a.mods instanceof Map)) throw new IllegalArgumentException("${where} requires args.mods as an object of the fields to change. See hub_get_tool_guide(section='deployment_jobs').")
            break
        case "pause":
        case "resume":
            if (a.ruleId == null && a.ruleIds == null) throw new IllegalArgumentException("${where} requires args.ruleId (an id or an array of ids). See hub_get_tool_guide(section='deployment_jobs').")
            break
        case "setDisabled":
            if (a.appId == null) throw new IllegalArgumentException("${where} requires args.appId. See hub_get_tool_guide(section='deployment_jobs').")
            _deployRequireDisabledFlag(a.disabled, where)
            break
    }
}

// Optional boolean flag, tolerant of the stringified form a JSON-ish client sends, and
// LOUD on anything else. `flag == true` identity comparison silently reversed a
// draft:"true" / background:"false" / includeOps:"false" into its opposite -- draft:"true"
// started the migration immediately instead of parking it. Same treatment as
// _deployRequireDisabledFlag, minus its requiredness.
private boolean _deployBooleanFlag(Object raw, boolean whenAbsent, String where) {
    if (raw == null) return whenAbsent
    if (raw instanceof Boolean) return raw
    def s = raw.toString()
    if (s == "true") return true
    if (s == "false") return false
    throw new IllegalArgumentException("${where} must be a boolean true/false (got: ${raw}). See hub_get_tool_guide(section='deployment_jobs').")
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
    if (res.containsKey("success") && res.success == false) return false
    // A migration cannot count "committed but not fully built" as done. The rule tools
    // document success:true pairing with partial:true (a create whose triggers or actions
    // only partly baked), and the not-live flags mean the hub holds the change while the
    // running instance never re-subscribed. Passing either one lets the job reach the
    // validation gate -- which is STRUCTURAL and sees nothing wrong -- so the operator
    // commits, the old rules get paused, and the migrated automation never fires.
    return _deployDegradedFlags(res).isEmpty()
}

// The degradation flags _deployTrimResult already carries into the op record, filtered to
// the ones actually set, so a failure can name what tripped it.
private List _deployDegradedFlags(Object res) {
    if (!(res instanceof Map)) return []
    return ["partial", "updateRuleFailed", "subscriptionsNotLive"].findAll { ((Map) res)[it] == true }
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

// Returns false when newId is unusable. NEVER throws: this runs AFTER the app was
// created, and a NumberFormatException here is an IllegalArgumentException, which the
// dispatch layer maps to -32602 "Invalid params" -- a validation verdict for a call that
// already committed, which then RELEASES the op token so the documented same-token
// re-issue creates a second app. The AGENTS.md contract is that a validation throw fires
// before any side effect, so this reports instead.
private boolean _deployRecordCreated(Map job, Map op, Object newId) {
    if (newId == null) return false
    Integer nid
    try {
        nid = newId.toString() as Integer
    } catch (Exception e) {
        mcpLog("warn", "deploy", "job ${job.jobId}: created app id '${newId}' is not numeric (${e.message}) -- it cannot be tracked for rollback.")
        return false
    }
    def created = (job.createdAppIds instanceof List) ? job.createdAppIds : []
    if (!created.collect { it.toString() }.contains(nid.toString())) created << nid
    job.createdAppIds = created
    if (op.alias != null) {
        def aliases = (job.aliases instanceof Map) ? job.aliases : [:]
        aliases[op.alias.toString()] = nid
        job.aliases = aliases
    }
    return true
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

// staging=false runs the SAME gate at the end of the commit list, because commitOps may
// contain create-type ops too (_deployValidateOps accepts the full op set) and an app
// created at cutover otherwise reached `completed` unchecked -- a phase op='cancel'
// refuses, so a broken cutover app could be neither detected nor rolled back.
private void _deployRunValidation(Map job, boolean staging = true) {
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
            mcpLogError("deploy", "job ${job.jobId}: health check of created app ${id} threw", e)
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
    String stage = staging ? "staging" : "commit"
    if (allOk) {
        job.phase = staging ? "ready_for_commit" : "completed"
        _deployAppendHistory(job, results.isEmpty() ? "${stage} complete: no created apps to health-check" : "${stage} validated: ${results.size()} created app(s) healthy")
    } else {
        job.phase = "failed"
        job.error = staging
            ? "validation failed: created app(s) ${results.findAll { !it.ok }.collect { it.appId }} are unhealthy. Inspect via hub_get_rule_health / hub_get_app_config, fix, then op='resume' (re-validates) or op='cancel'."
            : "commit validation failed: the cutover ran, but created app(s) ${results.findAll { !it.ok }.collect { it.appId }} are unhealthy. The job is NOT completed, so rollback is still available: inspect via hub_get_rule_health / hub_get_app_config, then op='resume' (re-validates) or op='cancel' (deletes every app this job created)."
        _deployAppendHistory(job, "${stage} validation FAILED")
    }
    // Release the slice lease in the SAME save that publishes the terminal phase. The worker's
    // finally clears it too, but that is a separate save -- so a client polling in between saw
    // ready_for_commit carrying a live lease and its immediate commit was refused as "a worker
    // slice is active right now", which is the normal sequence, not a race worth blocking. The
    // guard on commit still protects the real case (a slice genuinely mid-run).
    job.sliceLeaseUntil = null
    _deploySaveJob(job)
}

// Commit-list completion. Only re-runs the health gate when the COMMIT list actually
// created something -- a cutover of pause/setDisabled ops (the common shape) creates no
// apps, and re-checking staging's apps would be hub round trips for nothing.
private void _deployRunCommitCompletion(Map job) {
    int createdBefore = 0
    try { createdBefore = (job.createdBeforeCommit != null) ? (job.createdBeforeCommit.toString() as Integer) : ((job.createdAppIds ?: []) as List).size() }
    catch (Exception ignored) { createdBefore = ((job.createdAppIds ?: []) as List).size() }
    if (((job.createdAppIds ?: []) as List).size() > createdBefore) {
        _deployRunValidation(job, false)
        // A failed commit validation must keep its lease-clearing too, or cancel (the
        // rollback the failure exists to preserve) is refused until the lease expires.
        job.sliceLeaseUntil = null
        _deploySaveJob(job)
        return
    }
    job.phase = "completed"
    job.sliceLeaseUntil = null
    _deployAppendHistory(job, "commit complete")
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
                _deployRunCommitCompletion(job)
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
            // Log the EXCEPTION OBJECT, not just its message: this is the engine's single
            // most important failure path, and message-only left an NPE as the bare string
            // "java.lang.NullPointerException" in entry.error with no stack trace anywhere.
            mcpLogError("deploy", "job ${job.jobId} op ${idx} (${opType}) threw", e)
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
            // Name the actual reason. The old fallback string "op returned failure" was a
            // reasonless verdict, and a degradation-flag failure had no message at all
            // because the tool reported success.
            def degraded = _deployDegradedFlags(res)
            def reason = execError
            if (reason == null && res instanceof Map) reason = (res.error ?: res.note)
            if (reason == null && degraded) {
                def hints = (res instanceof Map && res.repairHints instanceof List) ? ((List) res.repairHints).take(2).join(' | ') : null
                reason = "op reported ${degraded.join('/')}=true -- the change is committed but not fully built or not live, so the migrated automation may never fire.${hints ? " Repair: ${hints}" : ''}"
            }
            if (reason == null) reason = "op returned failure; tool returned ${_deployTrimResult(res)}"
            entry.error = reason.toString().take(300)
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
    try {
        def ids = _deployJobs().keySet().collect { it.toString() }
        ids.each { jid ->
            def job = _deployJobs()[jid]
            if (!(job instanceof Map) || !(job.phase?.toString() in _deployActivePhases())) return
            // A worker armed by ANOTHER job must not advance a job whose caller opted out of
            // on-hub continuation; background:false means op='resume' is the only driver.
            if (job.background == false) return
            if (job.sliceLeaseUntil != null && now() < (job.sliceLeaseUntil as Long)) return
            try {
                // The lease claim lives INSIDE the try: as a bare statement pair before it,
                // a save failure escaped the whole worker before the re-arm, leaving the job
                // stuck in an active phase with a stale lease refusing resume/cancel/delete
                // and its fail streak never incrementing -- so the 3-strike terminal path
                // could not fire either, and status kept claiming the job self-advances.
                job.sliceLeaseUntil = now() + _deployLeaseMs()
                _deploySaveJob(job)
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
                // Persist the streak itself: the lease release below legitimately declines
                // to save when another writer took the job, and without this the strike
                // would be forgotten and the job would retry forever.
                try { _deploySaveJob(job) }
                catch (Exception se) { mcpLogError("deploy", "saving the failed-slice state for job ${jid} failed", se) }
            } finally {
                try { _deployReleaseLease(job) }
                catch (Exception re) { mcpLogError("deploy", "releasing the slice lease for job ${jid} failed", re) }
            }
        }
    } catch (Exception e) {
        mcpLogError("deploy", "deployJobWorker threw outside any job slice", e)
    } finally {
        // UNCONDITIONAL. Any escape above used to skip the re-arm entirely, and an active
        // job with nothing scheduled to advance it just stalls -- forever, silently.
        try {
            def stillActive = _deployJobs().any { k, v -> (v instanceof Map) && (v.phase?.toString() in _deployActivePhases()) && v.background != false }
            if (stillActive) runIn(15, "deployJobWorker")
        } catch (Exception e) {
            // Cannot tell whether anything is still active -- re-arm anyway. One extra pass
            // costs a scheduled no-op; skipping it strands every active job.
            mcpLogError("deploy", "could not evaluate the deployment worker re-arm condition; re-arming anyway", e)
            runIn(15, "deployJobWorker")
        }
    }
}

private Map _deployInlineSlice(Map job, Object reqT0, Integer maxOps) {
    Long t0 = (reqT0 instanceof Number) ? (reqT0 as Long) : now()
    job.sliceLeaseUntil = now() + _deployLeaseMs()
    _deploySaveJob(job)
    try {
        return _deployRunSlice(job, t0, maxOps, null)
    } finally {
        _deployReleaseLease(job)
    }
}

private Map _deployOpCreate(Map args) {
    if (!(args?.ops instanceof List) || ((List) args.ops).isEmpty()) {
        throw new IllegalArgumentException("op='create' requires ops: a non-empty array of {op, args, alias?} objects. See hub_get_tool_guide(section='deployment_jobs').")
    }
    // Present-but-wrong-shape THROWS, exactly like ops above. Silently substituting []
    // built a job with no cutover ops, and op='commit' then reported success:true / "no
    // commitOps declared -- completed" while the old rules stayed live and the staged apps
    // stayed disabled: a migration that reports success and did nothing. Absent is still [].
    if (args.commitOps != null && !(args.commitOps instanceof List)) {
        throw new IllegalArgumentException("commitOps must be an array of {op, args, alias?} objects (omit it entirely for a job with no cutover ops). See hub_get_tool_guide(section='deployment_jobs').")
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
        phase: _deployBooleanFlag(args.draft, false, "draft") ? "draft" : "staging",
        background: _deployBooleanFlag(args.background, true, "background"),
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
        if (job.phase == "committing" && job.createdBeforeCommit == null) {
            job.createdBeforeCommit = ((job.createdAppIds ?: []) as List).size()
        }
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
    // The same guard resume/cancel/delete carry. The validation that produced
    // ready_for_commit runs INSIDE a worker slice, which still holds the lease until its
    // final save -- so a commit landing in that window is undone by the slice's whole-job
    // snapshot (phase reverts to ready_for_commit) leaving the job mid-cutover while
    // cancel and delete are accepted against it. No phase test here: ready_for_commit is
    // not an active phase, but the lease outlives the phase change that reached it.
    if (job.sliceLeaseUntil != null && now() < (job.sliceLeaseUntil as Long)) {
        throw new IllegalArgumentException("Job '${job.jobId}' has an on-hub worker slice active right now -- wait for the slice to finish (poll deployment:{op:'status', jobId:'${job.jobId}'}), then commit.")
    }
    Integer maxOps = (args.maxOpsPerCall != null) ? (args.maxOpsPerCall.toString() as Integer) : (job.maxOpsPerCall as Integer)
    if (((job.commitOps ?: []) as List).isEmpty()) {
        job.phase = "completed"
        _deployAppendHistory(job, "commit: no commitOps declared -- completed")
        _deploySaveJob(job)
        return _deployJobStatus(job, true)
    }
    job.phase = "committing"
    // How many apps existed before the cutover, so the completion gate can tell whether
    // the COMMIT list created any (and therefore whether it owes them a health check).
    job.createdBeforeCommit = ((job.createdAppIds ?: []) as List).size()
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
    cancelState.failures = []
    targets.each { id ->
        def idStr = id.toString()
        if (cancelState.deleted.collect { it.toString() }.contains(idStr)) return
        def msg = null
        try {
            def res = toolDeleteNativeApp([appId: id, confirm: true])
            if (_deployOpSucceeded(res)) {
                cancelState.deleted << id
                job.cancel = cancelState
                _deploySaveJob(job)
                return
            }
            msg = (res instanceof Map ? (res.error ?: res.note) : "delete failed")?.toString()
        } catch (Exception e) {
            mcpLogError("deploy", "job ${job.jobId}: rollback delete of app ${id} threw", e)
            msg = e.message ?: e.toString()
        }
        // A "not found" SUBSTRING is not proof the app is gone -- a backup read miss, a
        // parent/child lookup or an auth error phrased that way would all record a live app
        // as successfully rolled back. Confirm with a read instead; only an unreadable app
        // counts as deleted.
        if (msg?.toLowerCase()?.contains("not found") && !_deployAppStillExists(id)) {
            cancelState.deleted << id
        } else {
            cancelState.failures << [appId: id, error: msg?.take(200)]
        }
        job.cancel = cancelState
        _deploySaveJob(job)
    }
    job.sliceLeaseUntil = null
    if (cancelState.failures) {
        // Do NOT go terminal on a failed rollback. `cancelled` is refused by a second
        // op='cancel', so the documented rollback would be single-shot: an app the hub
        // refuses to delete (one with children -- the Button Controller migration shape)
        // left the operator deleting apps by hand with no way to retry the engine.
        job.error = "Rollback incomplete: ${cancelState.failures.size()} of ${targets.size()} created app(s) could not be deleted. Clear the blocker (a parent with children must lose its children first) and re-issue op='cancel' -- the apps already deleted are remembered and not retried."
        job.phase = "failed"
        _deployAppendHistory(job, "cancel INCOMPLETE: deleted ${cancelState.deleted.size()}/${targets.size()}, ${cancelState.failures.size()} still live")
        _deploySaveJob(job)
        def partialSt = _deployJobStatus(job, true)
        partialSt.cancel = cancelState
        partialSt.success = false
        partialSt.error = job.error
        partialSt.note = "Still cancellable: re-issue deployment:{op:'cancel', jobId:'${job.jobId}'} after clearing the blocker, or delete the remaining app(s) via hub_delete_native_app. Deleted so far: ${cancelState.deleted}."
        return partialSt
    }
    job.phase = "cancelled"
    _deployAppendHistory(job, "cancelled: deleted ${cancelState.deleted.size()}/${targets.size()} created app(s)")
    _deploySaveJob(job)
    def st = _deployJobStatus(job, true)
    st.cancel = cancelState
    return st
}

// Existence readback used to confirm a rollback delete actually removed the app. Errs
// toward "still exists" on any unreadable answer: recording a live app as deleted is the
// damaging direction (it reports a clean rollback and drops the app from the retry set),
// while a false "still exists" only asks the operator to retry.
private boolean _deployAppStillExists(Object appId) {
    // Deliberately NOT _rmFetchConfigJson: that helper THROWS for a missing app (empty
    // response, or a parsed body with no app object), so routing this through it made
    // "gone" and "unreadable" the same answer -- and since unreadable errs toward
    // still-present, a genuinely deleted app could never be confirmed and cancel could
    // never reach `cancelled`. Reading the endpoint directly is what lets absence be
    // proven rather than inferred from an exception message (the same "not found
    // substring" trap this readback exists to close).
    String body
    try {
        body = hubInternalGet("/installedapp/configure/json/${normalizeRuleId(appId)}")
    } catch (Exception e) {
        mcpLog("debug", "deploy", "existence readback for app ${appId} was unreadable (${e.message}); treating it as still present.")
        return true
    }
    // An empty body is how the hub answers for an app that is gone.
    if (!body?.trim()) return false
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(body)
        // Parsed but carrying no app object = gone. Anything with an app object is live.
        return ((parsed instanceof Map) && parsed.app != null)
    } catch (Exception pe) {
        mcpLog("debug", "deploy", "existence readback for app ${appId} returned an unparseable body (${pe.message}); treating it as still present.")
        return true
    }
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
            return _deployJobStatus(_deployLoadJob(deployment.jobId), _deployBooleanFlag(deployment.includeOps, true, "includeOps"))
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
