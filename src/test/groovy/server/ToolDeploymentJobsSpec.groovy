package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.ToolSpecBase

/**
 * No dedicated tools: hub_set_rule and hub_set_native_app both route args.deployment
 * to one shared engine. Job records seed straight into atomicStateMap.deployJobs
 * (TestAtomicState implements both whole-map and updateMapValue writes).
 */
class ToolDeploymentJobsSpec extends ToolSpecBase {

    private void enableWrite() {
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    private Map seedJob(String jobId, String phase, Map extra = [:]) {
        def job = [jobId: jobId, name: "job-${jobId}".toString(), phase: phase,
                   ops: [], opStatus: [], commitOps: [], commitStatus: [],
                   createdAt: 1000L, updatedAt: 2000L] + extra
        def jobs = (atomicStateMap.deployJobs instanceof Map) ? atomicStateMap.deployJobs : [:]
        jobs[jobId] = job
        atomicStateMap.deployJobs = jobs
        return job
    }

    // Only the deployment engine's reconcile path reads this shape: a parent app whose
    // childApps list is what the pre-op snapshot is diffed against.
    private String parentConfigJson(int parentId, List childApps) {
        JsonOutput.toJson([
            app: [id: parentId, name: "Rule-5.1", label: "Parent", trueLabel: "Parent", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null, sections: []],
            settings: [:],
            childApps: childApps
        ])
    }

    private Map storedJob(String jobId) {
        return atomicStateMap.deployJobs[jobId] as Map
    }

    private Map onlyStoredJob() {
        return (atomicStateMap.deployJobs as Map).values().first() as Map
    }

    def "deployment rejects an unknown op naming the full enum"() {
        given:
        enableWrite()

        when:
        script.toolSetRule([deployment: [op: "bogus"], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("create, resume, commit, cancel, delete, status")
    }

    def "deployment cannot be combined with other arguments"() {
        given:
        enableWrite()

        when:
        script.toolSetRule([appId: 5, deployment: [op: "status"], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("self-contained")
        e.message.contains("appId")
    }

    def "status is a pure read: lists job records without confirm"() {
        given:
        seedJob("dj-1", "completed")
        seedJob("dj-2", "staging")

        when:
        def result = script.toolSetRule([deployment: [op: "status"]])

        then:
        result.success == true
        result.total == 2
        result.jobs*.jobId.toSet() == ["dj-1", "dj-2"].toSet()
    }

    def "status routes identically from hub_set_native_app (one shared engine)"() {
        given:
        seedJob("dj-shared", "completed")

        when:
        def result = script.toolSetNativeApp([deployment: [op: "status"]])

        then:
        result.success == true
        result.jobs*.jobId == ["dj-shared"]
    }

    def "create validates the manifest before persisting any job record"() {
        given:
        enableWrite()

        when: "an unknown op type in the ops manifest"
        script.toolSetRule([deployment: [op: "create", ops: [[op: "nonsense", args: [:]]]], confirm: true])

        then:
        thrown(IllegalArgumentException)
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()

        when: "ops missing entirely"
        script.toolSetRule([deployment: [op: "create"], confirm: true])

        then:
        def e2 = thrown(IllegalArgumentException)
        e2.message.contains("op='create' requires ops")
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    def "create rejects a forward alias reference before persisting"() {
        given:
        enableWrite()

        when: "an op consumes an alias declared only by a LATER create-type op"
        script.toolSetRule([deployment: [op: "create", ops: [
            [op: "setDisabled", args: [appId: [alias: "later"], disabled: false]],
            [op: "cloneApp", alias: "later", args: [sourceAppId: 1]]
        ]], confirm: true])

        then:
        thrown(IllegalArgumentException)
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    def "create rejects a self-referencing alias before persisting"() {
        given:
        enableWrite()

        when: "a create-type op consumes the very alias it declares"
        script.toolSetRule([deployment: [op: "create", ops: [
            [op: "cloneApp", alias: "copy", args: [sourceAppId: [alias: "copy"]]]
        ]], confirm: true])

        then: "rejected at validation, naming the alias -- not left to fail mid-job"
        def e = thrown(IllegalArgumentException)
        e.message.contains("'copy'")
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    def "create rejects a duplicate alias before persisting"() {
        given:
        enableWrite()

        when: "two create ops declare the same alias"
        script.toolSetRule([deployment: [op: "create", ops: [
            [op: "cloneApp", alias: "copy", args: [sourceAppId: 1]],
            [op: "cloneApp", alias: "copy", args: [sourceAppId: 2]]
        ]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("declared more than once")
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    def "create rejects an alias declared on a non-create op before persisting"() {
        given:
        enableWrite()

        when: "a convergent op tries to name a created app"
        script.toolSetRule([deployment: [op: "create", ops: [
            [op: "pause", alias: "nope", args: [ruleId: 1]]
        ]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("alias is only valid on")
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    def "status stays readable when the Write master is off"() {
        given:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        seedJob("dj-ro", "completed")
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when: "op='status' over the real wire path"
        def readResponse = mcpDriver.callTool('hub_set_rule', [deployment: [op: "status"]])
        def readInner = mcpDriver.parseInner(readResponse)

        then: "the pure read is served"
        readResponse.error == null
        readInner.success == true
        readInner.jobs*.jobId == ["dj-ro"]

        and: "a read-shaped call on a write tool never mints an auto token"
        !readInner.containsKey("opToken")
        !(atomicStateMap.opTokens instanceof Map) || atomicStateMap.opTokens.isEmpty()

        when: "a WRITE deployment op over the same wire path"
        def writeResponse = mcpDriver.callTool('hub_set_rule', [deployment: [op: "delete", jobId: "dj-ro"], confirm: true])

        then: "the Write master blocks it and the record survives"
        writeResponse.error.code == -32602
        writeResponse.error.message.contains("Write tools are disabled")
        atomicStateMap.deployJobs.containsKey("dj-ro")
    }

    def "delete refuses a job that is not finished"() {
        given:
        enableWrite()
        seedJob("dj-active", phase)

        when:
        script.toolSetRule([deployment: [op: "delete", jobId: "dj-active"], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("finished job records")
        atomicStateMap.deployJobs.containsKey("dj-active")

        where:
        phase << ["draft", "staging", "ready_for_commit", "committing", "failed"]
    }

    def "delete removes a finished record, surfaces rollback handles, leaves siblings"() {
        given:
        enableWrite()
        seedJob("dj-done", "completed", [backupKeys: ["bk-1"], createdAppIds: [42]])
        seedJob("dj-cancelled", "cancelled")

        when:
        def result = script.toolSetRule([deployment: [op: "delete", jobId: "dj-done"], confirm: true])

        then:
        result.success == true
        result.deleted == true
        result.jobId == "dj-done"
        result.backupKeys == ["bk-1"]
        result.createdAppIds == [42]
        !atomicStateMap.deployJobs.containsKey("dj-done")
        atomicStateMap.deployJobs.containsKey("dj-cancelled")

        when: "the cancelled sibling is deletable too"
        def result2 = script.toolSetRule([deployment: [op: "delete", jobId: "dj-cancelled"], confirm: true])

        then:
        result2.deleted == true
        atomicStateMap.deployJobs.isEmpty()
    }

    def "delete of an unknown jobId steers to the status op"() {
        given:
        enableWrite()

        when:
        script.toolSetRule([deployment: [op: "delete", jobId: "dj-nope"], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("not found")
        e.message.contains("deployment:{op:'status'}")
    }

    def "hub_set_native_app drives a deployment WRITE op, not just status"() {
        given:
        enableWrite()
        seedJob("dj-native", "cancelled")

        when: "the second tool deletes a terminal record"
        def result = script.toolSetNativeApp([deployment: [op: "delete", jobId: "dj-native"], confirm: true])

        then:
        result.deleted == true
        atomicStateMap.deployJobs.isEmpty()
    }

    // ---------- dispatch envelope (the real wire path) ----------

    def "dispatch envelope: deployment status flows through the wire path and mints no write token"() {
        given:
        settingsMap.enableRead = true
        seedJob("dj-wire", "completed")

        when:
        def response = mcpDriver.callTool('hub_set_rule', [deployment: [op: "status"]])
        def inner = mcpDriver.parseInner(response)

        then: "the seeded job comes back through handleMcpRequest -> executeTool -> the engine"
        response.error == null
        response.result.isError != true
        inner.success == true
        inner.jobs*.jobId == ["dj-wire"]

        and: "no auto token is attached and no record is written"
        !inner.containsKey("opToken")
        !(atomicStateMap.opTokens instanceof Map) || atomicStateMap.opTokens.isEmpty()
    }

    def "dispatch envelope: a guide probe on hub_set_rule mints no token either"() {
        given:
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_set_rule', [guide: true])
        def inner = mcpDriver.parseInner(response)

        then:
        response.error == null
        response.result.isError != true

        and: "the probe is read-shaped, so the auto-token gate skips it"
        !inner.containsKey("opToken")
        !(atomicStateMap.opTokens instanceof Map) || atomicStateMap.opTokens.isEmpty()
    }

    // ---------- confirm / backup gate ----------

    def "a deployment write op is refused without confirm"() {
        given:
        enableWrite()

        when:
        script.toolSetRule([deployment: [op: "create", ops: [[op: "pause", args: [ruleId: 1]]]], confirm: false])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("SAFETY CHECK FAILED")
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    def "a deployment write op is refused when no recent backup exists"() {
        given: "the Write master is on and confirm is set, but nothing stamped a backup"
        settingsMap.enableWrite = true
        settingsMap.enableRead = true

        when:
        script.toolSetRule([deployment: [op: "create", ops: [[op: "pause", args: [ruleId: 1]]]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("BACKUP REQUIRED")
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    // ---------- worker slice ----------

    def "deployJobWorker abandons a job after three throwing slices and records every throw"() {
        given: "a corrupt op-status entry makes every slice throw at the same point"
        enableWrite()
        seedJob("dj-worker", "staging", [ops: [[op: "pause", args: [ruleId: 1]]], opStatus: [null], history: []])

        when:
        3.times { script.deployJobWorker() }

        then: "the streak bound turns the job terminal instead of re-arming forever"
        def job = storedJob("dj-worker")
        job.phase == "failed"
        job.error.contains("worker slice threw repeatedly")

        and: "every throw is visible in the history, not just the abandoning one"
        job.history.findAll { it.msg?.toString()?.startsWith("worker slice threw:") }.size() == 3
        job.history.any { it.msg?.toString()?.contains("(streak 1)") }
        job.history.any { it.msg?.toString()?.contains("(streak 3)") }
    }

    def "a non-throwing slice resets the worker fail streak"() {
        given:
        enableWrite()
        seedJob("dj-reset", "staging", [ops: [[op: "pause", args: [ruleId: 1]]], opStatus: [null], history: []])
        script.metaClass.toolSetRulePaused = { Map a -> [success: true] }

        when: "one throwing pass"
        script.deployJobWorker()

        then:
        storedJob("dj-reset").workerFailStreak == 1
        storedJob("dj-reset").phase == "staging"

        when: "the record is repaired and the worker runs again"
        storedJob("dj-reset").opStatus = [[status: "pending"]]
        script.deployJobWorker()

        then:
        storedJob("dj-reset").workerFailStreak == 0
        storedJob("dj-reset").phase == "ready_for_commit"
    }

    def "a non-numeric created app id is a job failure, never a -32602 validation throw"() {
        given: 'NumberFormatException IS an IllegalArgumentException, and this runs AFTER the app was created -- so it escaped as "Invalid params" for a call that already committed, and the dispatch layer then RELEASED the op token, making the documented same-token re-issue create a second app'
        enableWrite()
        script.metaClass.toolCloneNativeApp = { Map a -> [success: true, newAppId: "not-a-number"] }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", name: "badid-job", ops: [
            [op: "cloneApp", alias: "copy", args: [sourceAppId: 9]]
        ]], confirm: true])

        then: 'no throw escapes -- the job answers with a record the caller can act on'
        notThrown(IllegalArgumentException)
        result.jobId != null
    }

    def "the worker re-arms even when a job slice save blows up"() {
        given: 'the lease claim used to sit OUTSIDE the per-job try, so a save failure escaped the whole worker before the re-arm -- the job then sat in staging forever, its fail streak never incremented (so the 3-strike terminal path could not fire) and a stale lease refusing resume/cancel/delete'
        enableWrite()
        seedJob("dj-savefail", "staging", [ops: [[op: "pause", args: [ruleId: 1]]], opStatus: [[status: "pending"]], history: []])
        def scheduled = []
        script.metaClass.runIn = { Object delay, String handler -> scheduled << handler }
        script.metaClass._deploySaveJob = { Map j -> throw new RuntimeException("atomicState write refused") }

        when:
        script.deployJobWorker()

        then: 'the throw is contained and the continuation chain survives'
        notThrown(Exception)
        scheduled.contains("deployJobWorker")
    }

    def "deployJobWorker skips a background:false job armed by another job's worker"() {
        given:
        enableWrite()
        def paused = []
        script.metaClass.toolSetRulePaused = { Map a -> paused << a.ruleId; [success: true] }
        seedJob("dj-manual", "staging", [background: false,
                                         ops: [[op: "pause", args: [ruleId: 9]]],
                                         opStatus: [[status: "pending"]]])

        when:
        script.deployJobWorker()

        then: "the opt-out job is untouched -- op='resume' is its only driver"
        paused.isEmpty()
        storedJob("dj-manual").phase == "staging"
        storedJob("dj-manual").opStatus[0].status == "pending"

        when: "an explicit resume drives it"
        def result = script.toolSetRule([deployment: [op: "resume", jobId: "dj-manual"], confirm: true])

        then:
        paused == [9]
        result.phase == "ready_for_commit"
        result.background == false
    }

    // ---------- cap + prune arithmetic ----------

    def "create at the job cap evicts the oldest terminal record"() {
        given:
        enableWrite()
        script.metaClass.toolSetRulePaused = { Map a -> [success: true] }
        (1..8).each { seedJob("dj-old-${it}".toString(), "completed", [updatedAt: (1000L + it)]) }

        when:
        def result = script.toolSetRule([deployment: [op: "create", name: "fresh",
                                                      ops: [[op: "pause", args: [ruleId: 1]]]], confirm: true])

        then: "the cap holds and the OLDEST evictable record is the one that goes"
        result.phase == "ready_for_commit"
        (atomicStateMap.deployJobs as Map).size() == 8
        !atomicStateMap.deployJobs.containsKey("dj-old-1")
        atomicStateMap.deployJobs.containsKey("dj-old-8")
    }

    def "create at the cap with nothing evictable throws instead of dropping a live job"() {
        given:
        enableWrite()
        (1..8).each { seedJob("dj-live-${it}".toString(), (it % 2 == 0) ? "staging" : "draft") }

        when:
        script.toolSetRule([deployment: [op: "create", ops: [[op: "pause", args: [ruleId: 1]]]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Too many active deployment jobs")
        (atomicStateMap.deployJobs as Map).size() == 8
    }

    def "the prune keeps a FAILED job -- its createdAppIds are the only rollback handles"() {
        given:
        enableWrite()
        script.metaClass.toolSetRulePaused = { Map a -> [success: true] }
        seedJob("dj-failed", "failed", [updatedAt: 1L, createdAppIds: [77], backupKeys: ["bk-77"]])
        (1..7).each { seedJob("dj-done-${it}".toString(), "completed", [updatedAt: (1000L + it)]) }

        when:
        script.toolSetRule([deployment: [op: "create", ops: [[op: "pause", args: [ruleId: 1]]]], confirm: true])

        then: "the failed record survives even though it is the oldest of all"
        atomicStateMap.deployJobs.containsKey("dj-failed")
        storedJob("dj-failed").createdAppIds == [77]

        and: "a completed record was evicted in its place"
        !atomicStateMap.deployJobs.containsKey("dj-done-1")
        (atomicStateMap.deployJobs as Map).size() == 8
    }

    def "create bounds the manifest before anything persists"() {
        given:
        enableWrite()

        when: "more ops than the cap"
        script.toolSetRule([deployment: [op: "create",
                                         ops: (1..51).collect { [op: "pause", args: [ruleId: it]] }], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("51 ops")
        e.message.contains("the cap is 50")
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()

        when: "an op payload past the serialized-character cap"
        script.toolSetRule([deployment: [op: "create",
                                         ops: [[op: "pause", args: [ruleId: 1, filler: "x" * 70000]]]], confirm: true])

        then:
        def e2 = thrown(IllegalArgumentException)
        e2.message.contains("the cap is 64000")
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    // ---------- op execution ----------

    def "setDisabled without an explicit disabled flag is refused at create"() {
        given:
        enableWrite()

        when:
        script.toolSetRule([deployment: [op: "create", ops: [[op: "setDisabled", args: [appId: 5]]]], confirm: true])

        then: "no silent default -- an absent flag used to mean 'disable'"
        def e = thrown(IllegalArgumentException)
        e.message.contains("args.disabled as an explicit boolean")
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    def "deployment op=status is refused when the READ master is off"() {
        given: 'op=status is exempt from the WRITE master via the schema-only classifier, but unlike every other exemption it returns hub data -- job names, ids, created appIds, backup keys -- so with BOTH masters off a caller still got the whole rollback surface back. Driven over the wire, because the gate lives in executeTool'
        settingsMap.enableRead = false
        settingsMap.enableWrite = false
        seedJob("dj-readgate", "ready_for_commit", [createdAppIds: [101]])
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def response = mcpDriver.callTool('hub_set_rule', [deployment: [op: "status"]])

        then:
        response.error.code == -32602
        response.error.message.contains("Read tools are disabled")
    }

    def "deployment op=status is still served when only the WRITE master is off"() {
        given: 'the existing exemption must survive: a status poll is how a client finds a job it can no longer drive'
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        seedJob("dj-writeoff", "ready_for_commit", [createdAppIds: [101]])
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }

        when:
        def response = mcpDriver.callTool('hub_set_rule', [deployment: [op: "status"]])

        then:
        response.error == null
        mcpDriver.parseInner(response).jobs*.jobId == ["dj-writeoff"]
    }

    def "commitOps present but not a List is refused instead of silently dropped"() {
        given: 'a substituted [] built a job with no cutover ops, and op=commit then reported success and "no commitOps declared" while the old rules stayed live and the staged apps stayed disabled'
        enableWrite()

        when:
        script.toolSetRule([deployment: [op: "create",
            ops: [[op: "pause", args: [ruleId: 1]]],
            commitOps: [op: "pause", args: [ruleId: 2]]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("commitOps must be an array")

        and: 'no job record survives the refusal'
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    def "an absent commitOps is still an empty cutover, not an error"() {
        given:
        enableWrite()
        script.metaClass.toolSetRulePaused = { Map a -> [success: true] }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }

        when:
        def result = script.toolSetRule([deployment: [op: "create",
            ops: [[op: "pause", args: [ruleId: 1]]]], confirm: true])

        then:
        result.jobId != null
        result.phase != "failed"
    }

    @Unroll
    def "the draft flag accepts a stringified boolean instead of silently reversing it (draft=#value)"() {
        given: 'a client that stringifies booleans sent draft:"true" and got the migration started IMMEDIATELY -- the exact opposite of parking it'
        enableWrite()
        script.metaClass.toolSetRulePaused = { Map a -> [success: true] }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", name: "flagjob", draft: value,
            ops: [[op: "pause", args: [ruleId: 1]]]], confirm: true])

        then:
        (storedJob(result.jobId).phase == "draft") == parked

        where:
        value   | parked
        "true"  | true
        true    | true
        "false" | false
        false   | false
    }

    @Unroll
    def "the background flag accepts a stringified boolean (background=#value)"() {
        given:
        enableWrite()
        script.metaClass.toolSetRulePaused = { Map a -> [success: true] }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", name: "bgjob", background: value,
            ops: [[op: "pause", args: [ruleId: 1]]]], confirm: true])

        then:
        storedJob(result.jobId).background == expected

        where:
        value   | expected
        "false" | false
        false   | false
        "true"  | true
        true    | true
    }

    def "a non-boolean draft flag is refused rather than guessed"() {
        given:
        enableWrite()

        when:
        script.toolSetRule([deployment: [op: "create", draft: "yes",
            ops: [[op: "pause", args: [ruleId: 1]]]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("draft must be a boolean")
    }

    def "an op reporting partial success is recorded FAILED, not done"() {
        given: 'the rule tools document success:true pairing with partial:true (a create whose triggers or actions only partly baked). Treating that as done let the job reach the STRUCTURAL validation gate, which sees nothing wrong, so the operator committed and the old rules got paused in favour of automation that never fires'
        enableWrite()
        script.metaClass.toolCloneNativeApp = { Map a ->
            [success: true, newAppId: 321, partial: true,
             repairHints: ["re-run addActions for the 2 actions that did not bake"]]
        }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", name: "partial-job", ops: [
            [op: "cloneApp", alias: "copy", args: [sourceAppId: 9, newName: "Copy v2"]]
        ]], confirm: true])

        then: 'the job fails instead of validating through to ready_for_commit'
        result.phase == "failed"

        and: 'the recorded error names the flag that tripped it and carries the repair hint'
        def stored = storedJob(result.jobId)
        stored.opStatus[0].status == "failed"
        stored.opStatus[0].error.contains("partial")
        stored.opStatus[0].error.contains("re-run addActions")

        and: 'the app it DID create is still tracked, so cancel can roll it back'
        stored.createdAppIds.collect { it.toString() } == ["321"]
    }

    def "an op reporting subscriptionsNotLive is recorded FAILED too"() {
        given:
        enableWrite()
        script.metaClass.toolSetAppDisabled = { Map a -> [success: true, disabled: a.disabled, updateRuleFailed: true, subscriptionsNotLive: true] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", name: "notlive-job", ops: [
            [op: "setDisabled", args: [appId: 11, disabled: false]]
        ]], confirm: true])

        then: 'written-but-not-live is not a migration that can be committed on top of'
        result.phase == "failed"
        storedJob(result.jobId).opStatus[0].error.contains("updateRuleFailed")
    }

    def "a required op argument missing from the LAST op is rejected before the FIRST op runs"() {
        given: 'the guide promises the whole manifest is validated up front; without a per-op-type arg check a cloneApp missing sourceAppId threw mid-job, after earlier ops had already committed real apps'
        enableWrite()
        def cloned = 0
        script.metaClass.toolCloneNativeApp = { Map a -> cloned++; [success: true, newAppId: 321] }

        when:
        script.toolSetRule([deployment: [op: "create", ops: [
            [op: "cloneApp", alias: "first", args: [sourceAppId: 9, newName: "Copy v2"]],
            [op: "cloneApp", args: [newName: "Copy v3"]]
        ]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("ops[1] (cloneApp)")
        e.message.contains("args.sourceAppId")

        and: 'nothing ran and no job record was left behind'
        cloned == 0
        !(atomicStateMap.deployJobs instanceof Map) || atomicStateMap.deployJobs.isEmpty()
    }

    def "commitOps are validated up front too, not when the cutover starts"() {
        given: 'a commitOps arg error surfaces only at cutover otherwise -- after staging has created every app'
        enableWrite()

        when:
        script.toolSetRule([deployment: [op: "create",
            ops: [[op: "pause", args: [ruleId: 1]]],
            commitOps: [[op: "modifyAction", args: [appId: 5, index: 1]]]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("commitOps[0] (modifyAction)")
        e.message.contains("args.mods")
    }

    @Unroll
    def "op '#opType' missing #missing is refused at create"() {
        given:
        enableWrite()

        when:
        script.toolSetRule([deployment: [op: "create", ops: [[op: opType, args: opArgs]]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(missing)

        where:
        opType        | opArgs                                  | missing
        "importApp"   | [jsonContent: '{}']                     | "args.parentHintAppId"
        "importApp"   | [parentHintAppId: 5]                    | "args.jsonContent"
        "buttonRule"  | [buttonNumber: 1, event: "pushed"]      | "args.controllerId"
        "buttonRule"  | [controllerId: 5, event: "pushed"]      | "args.buttonNumber"
        "buttonRule"  | [controllerId: 5, buttonNumber: 1]      | "args.event"
        "addActions"  | [appId: 5]                              | "args.actions"
        "addActions"  | [actions: []]                           | "args.appId"
        "modifyAction"| [appId: 5, mods: [:]]                   | "args.index"
        "pause"       | [:]                                     | "args.ruleId"
        "resume"      | [:]                                     | "args.ruleId"
        "setDisabled" | [disabled: true]                        | "args.appId"
    }

    def "an aliased appId satisfies the up-front required-arg check"() {
        given: 'an {alias:"x"} placeholder IS the value -- the check is presence, and resolution happens at execution'
        enableWrite()
        script.metaClass.toolCloneNativeApp = { Map a -> [success: true, newAppId: 321] }
        script.metaClass.toolSetAppDisabled = { Map a -> [success: true, disabled: a.disabled] }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", name: "alias-args", ops: [
            [op: "cloneApp", alias: "copy", args: [sourceAppId: 9]],
            [op: "setDisabled", args: [appId: [alias: "copy"], disabled: false]]
        ]], confirm: true])

        then:
        result.jobId != null
        result.phase != "failed"
    }

    def "an alias resolves into a later op's args at execution time"() {
        given:
        enableWrite()
        def disabledCalls = []
        script.metaClass.toolCloneNativeApp = { Map a -> [success: true, newAppId: 321] }
        script.metaClass.toolSetAppDisabled = { Map a -> disabledCalls << a; [success: true, disabled: a.disabled] }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", name: "alias-job", ops: [
            [op: "cloneApp", alias: "copy", args: [sourceAppId: 9, newName: "Copy v2"]],
            [op: "setDisabled", args: [appId: [alias: "copy"], disabled: false]]
        ]], confirm: true])

        then: "the second op received the id the first op created"
        disabledCalls.size() == 1
        disabledCalls[0].appId == 321
        disabledCalls[0].disabled == false

        and:
        result.aliases.copy == 321
        result.createdAppIds == [321]
        result.phase == "ready_for_commit"
    }

    def "maxOpsPerCall bounds the inline slice and the worker finishes the rest"() {
        // The durability claim of this layer: a create returns after its bounded slice
        // with the job still staging, and the on-hub worker carries the remainder with
        // no client attached.
        given:
        enableWrite()
        def disabledCalls = []
        script.metaClass.toolSetAppDisabled = { Map a -> disabledCalls << a.appId; [success: true, disabled: a.disabled] }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }

        when: "three ops with a one-op-per-call bound"
        def created = script.toolSetRule([deployment: [op: "create", name: "bounded-job", maxOpsPerCall: 1, ops: [
            [op: "setDisabled", args: [appId: 11, disabled: true]],
            [op: "setDisabled", args: [appId: 12, disabled: true]],
            [op: "setDisabled", args: [appId: 13, disabled: true]]
        ]], confirm: true])

        then: "exactly one op ran; the job stays staging and hands off to the worker"
        disabledCalls == [11]
        created.phase == "staging"
        created.workerScheduled == true
        created.progress.stagingDone == 1
        created.progress.stagingTotal == 3

        when: "the worker runs the remaining slices"
        script.deployJobWorker()
        script.deployJobWorker()

        then: "every op completed and the job reached the validation gate"
        disabledCalls == [11, 12, 13]
        def st = script.toolSetRule([deployment: [op: "status", jobId: created.jobId]])
        st.phase == "ready_for_commit"
        st.progress.stagingDone == 3
    }

    def "a cancelled job keeps its rollback residue on later status reads"() {
        // The residual-cleanup list must outlive the cancel RESPONSE: without it the
        // operator has no record of which created apps are still on the hub.
        given:
        enableWrite()
        seedJob("dj-residue", "staging", [createdAppIds: [77, 78]])
        script.metaClass.toolDeleteNativeApp = { Map a ->
            a.appId == 77 ? [success: false, error: "delete refused"] : [success: true]
        }

        when:
        def cancelled = script.toolSetRule([deployment: [op: "cancel", jobId: "dj-residue"], confirm: true])

        then: "the cancel response reports the incomplete rollback"
        cancelled.success == false
        cancelled.cancel.failures*.appId == [77]

        when: "a LATER status read, after that response is gone"
        def later = script.toolSetRule([deployment: [op: "status", jobId: "dj-residue"]])

        then: "the residue and the failure verdict are still there"
        later.phase == "cancelled"
        later.success == false
        later.error.contains("Rollback incomplete")
        later.cancel.failures*.appId == [77]
        later.cancel.deleted.contains(78)
    }

    def "a create op that COMMITTED but reported failure records the app and gates the resume"() {
        given: "the clone landed, then its stageDisabled leg failed"
        enableWrite()
        script.metaClass.toolCloneNativeApp = { Map a ->
            [success: false, isError: true, newAppId: 999, error: "stageDisabled leg failed"]
        }

        when:
        def result = script.toolSetRule([deployment: [op: "create", name: "leaky", ops: [
            [op: "cloneApp", alias: "copy", args: [sourceAppId: 5, newName: "New", stageDisabled: true]]
        ]], confirm: true])

        then: "the committed id is on the job, so cancel can delete it"
        def job = onlyStoredJob()
        job.createdAppIds.collect { it.toString() } == ["999"]
        job.opStatus[0].result.newAppId == 999

        and: "the op is marked interrupted so a blind resume cannot duplicate the app"
        job.opStatus[0].interrupted == true

        and: "the failure names the surviving app and both recovery paths"
        job.phase == "failed"
        job.error.contains("999")
        job.error.contains("EXISTS")
        job.error.contains("cancel")
        job.error.contains("retryInFlight")

        and: "the envelope reports the failure rather than success:true"
        result.success == false
        result.error.contains("999")
    }

    def "a resume of an interrupted committed create is refused without retryInFlight"() {
        given:
        enableWrite()
        script.metaClass.toolCloneNativeApp = { Map a ->
            [success: false, isError: true, newAppId: 999, error: "stageDisabled leg failed"]
        }
        script.toolSetRule([deployment: [op: "create", name: "leaky", ops: [
            [op: "cloneApp", alias: "copy", args: [sourceAppId: 5, newName: "New"]]
        ]], confirm: true])
        def jobId = onlyStoredJob().jobId

        when:
        script.toolSetRule([deployment: [op: "resume", jobId: jobId], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("retryInFlight")
    }

    def "a failed op returns a failure envelope, not success:true"() {
        given:
        enableWrite()
        script.metaClass.toolSetRulePaused = { Map a -> [success: false, error: "rule 1 does not exist"] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", ops: [[op: "pause", args: [ruleId: 1]]]], confirm: true])

        then:
        result.success == false
        result.phase == "failed"
        result.error.contains("rule 1 does not exist")
        result.jobError == result.error
    }

    def "a job with no created apps reports an honest validation verdict"() {
        given:
        enableWrite()
        script.metaClass.toolSetRulePaused = { Map a -> [success: true] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", ops: [[op: "pause", args: [ruleId: 4]]]], confirm: true])

        then: "ok:true with an empty results list says so instead of implying a passed gate"
        result.phase == "ready_for_commit"
        result.validation.ok == true
        result.validation.results.isEmpty()
        result.validation.note.contains("no created apps to health-check")
        result.note.contains("no created apps to validate")
    }

    def "the status envelope reports the background flag and the worker arming"() {
        given:
        enableWrite()
        script.metaClass.toolSetRulePaused = { Map a -> [success: true] }

        when:
        def result = script.toolSetRule([deployment: [op: "create", ops: [[op: "pause", args: [ruleId: 4]]]], confirm: true])

        then:
        result.background == true
        result.workerScheduled == true

        when: "a job that opted out of the on-hub worker"
        def manual = script.toolSetRule([deployment: [op: "create", background: false,
                                                      ops: [[op: "pause", args: [ruleId: 5]]]], confirm: true])

        then:
        manual.background == false
        manual.workerScheduled == false
    }

    def "a background:false job's staging note does not promise auto-advance"() {
        given:
        enableWrite()
        seedJob("dj-note", "staging", [background: false, ops: [[op: "pause", args: [ruleId: 1]]],
                                       opStatus: [[status: "pending"]]])

        when:
        def result = script.toolSetRule([deployment: [op: "status", jobId: "dj-note"]])

        then:
        result.background == false
        result.note.contains("advances only on op='resume'")
        !result.note.contains("without further calls")
    }

    // ---------- commit ----------

    def "commit with no commitOps completes the job outright"() {
        given:
        enableWrite()
        seedJob("dj-shortcut", "ready_for_commit", [ops: [[op: "pause", args: [ruleId: 1]]],
                                                    opStatus: [[status: "done"]],
                                                    validation: [ok: true, results: []]])

        when:
        def result = script.toolSetRule([deployment: [op: "commit", jobId: "dj-shortcut"], confirm: true])

        then:
        result.phase == "completed"
        result.success == true
        storedJob("dj-shortcut").history.any { it.msg?.toString()?.contains("no commitOps declared") }
    }

    def "commit is refused while a worker slice still holds the job's lease"() {
        given: 'the window this closes: validation flips a job to ready_for_commit from INSIDE a worker slice, which keeps the lease until its final save -- a commit landing there is undone by that slice s whole-job snapshot, leaving cancel and delete accepted against a job already mid-cutover'
        enableWrite()
        seedJob("dj-commit-lease", "ready_for_commit", [
            ops: [[op: "pause", args: [ruleId: 1]]],
            opStatus: [[status: "done"]],
            commitOps: [[op: "pause", args: [ruleId: 2]]],
            commitStatus: [[status: "pending"]],
            validation: [ok: true, results: []],
            sliceLeaseUntil: 1234567890000L + 30000L
        ])
        def paused = []
        script.metaClass.toolSetRulePaused = { Map a -> paused << a.ruleId; [success: true] }

        when:
        script.toolSetRule([deployment: [op: "commit", jobId: "dj-commit-lease"], confirm: true])

        then: 'refused with the same guidance cancel/resume/delete give, and nothing committed'
        def e = thrown(IllegalArgumentException)
        e.message.contains("worker slice active")
        paused == []
        storedJob("dj-commit-lease").phase == "ready_for_commit"
    }

    def "commit proceeds once the lease has expired"() {
        given:
        enableWrite()
        seedJob("dj-commit-free", "ready_for_commit", [
            ops: [[op: "pause", args: [ruleId: 1]]],
            opStatus: [[status: "done"]],
            commitOps: [[op: "pause", args: [ruleId: 2]]],
            commitStatus: [[status: "pending"]],
            validation: [ok: true, results: []],
            sliceLeaseUntil: 1234567890000L - 1000L
        ])
        def paused = []
        script.metaClass.toolSetRulePaused = { Map a -> paused << a.ruleId; [success: true] }

        when:
        def result = script.toolSetRule([deployment: [op: "commit", jobId: "dj-commit-free"], confirm: true])

        then: 'the guard is a lease check, not a latch -- a dead slice never blocks the cutover'
        paused == [2]
        result.phase == "completed"
    }

    def "an app created by the COMMIT list is health-checked before the job completes"() {
        given: 'commitOps accepts create-type ops, but validation only ever ran when the STAGING list finished -- so a cutover-created app reached completed unchecked, and op=cancel refuses a completed job, leaving it undetectable AND unrollbackable'
        enableWrite()
        seedJob("dj-commit-val", "ready_for_commit", [
            ops: [], opStatus: [],
            commitOps: [[op: "cloneApp", alias: "late", args: [sourceAppId: 9]]],
            commitStatus: [[status: "pending"]],
            createdAppIds: [], validation: [ok: true, results: []]
        ])
        script.metaClass.toolCloneNativeApp = { Map a -> [success: true, newAppId: 777] }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: false, broken: true, issues: ["no actions"]] }

        when:
        def result = script.toolSetRule([deployment: [op: "commit", jobId: "dj-commit-val"], confirm: true])

        then: 'the job does NOT complete on a broken cutover app'
        result.phase == "failed"
        storedJob("dj-commit-val").error.contains("commit validation failed")

        and: 'rollback is still available -- the app it created is tracked and cancel is not refused'
        storedJob("dj-commit-val").createdAppIds.collect { it.toString() } == ["777"]

        when:
        def deleted = []
        script.metaClass.toolDeleteNativeApp = { Map a -> deleted << a.appId; [success: true] }
        script.toolSetRule([deployment: [op: "cancel", jobId: "dj-commit-val"], confirm: true])

        then:
        deleted.collect { it.toString() } == ["777"]
    }

    def "a healthy commit-list creation still reaches completed"() {
        given:
        enableWrite()
        seedJob("dj-commit-ok", "ready_for_commit", [
            ops: [], opStatus: [],
            commitOps: [[op: "cloneApp", alias: "late", args: [sourceAppId: 9]]],
            commitStatus: [[status: "pending"]],
            createdAppIds: [], validation: [ok: true, results: []]
        ])
        script.metaClass.toolCloneNativeApp = { Map a -> [success: true, newAppId: 778] }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }

        when:
        def result = script.toolSetRule([deployment: [op: "commit", jobId: "dj-commit-ok"], confirm: true])

        then:
        result.phase == "completed"
    }

    def "a cutover that creates NO apps completes without re-checking staging's"() {
        given: 'the common cutover shape is pause/setDisabled -- re-running the health gate over staging apps would be hub round trips for nothing'
        enableWrite()
        int healthChecks = 0
        seedJob("dj-commit-noapps", "ready_for_commit", [
            ops: [], opStatus: [],
            commitOps: [[op: "pause", args: [ruleId: 1]]],
            commitStatus: [[status: "pending"]],
            createdAppIds: [101], validation: [ok: true, results: []]
        ])
        script.metaClass.toolSetRulePaused = { Map a -> [success: true] }
        script.metaClass.toolCheckRuleHealth = { Map a -> healthChecks++; [ok: true, broken: false, issues: []] }

        when:
        def result = script.toolSetRule([deployment: [op: "commit", jobId: "dj-commit-noapps"], confirm: true])

        then:
        result.phase == "completed"
        healthChecks == 0
    }

    def "a slice whose lease was taken over abandons its final save"() {
        given: 'an overrun slice: another writer saw the expired lease, took the job and moved it on'
        enableWrite()
        seedJob("dj-lease-steal", "cancelled", [sliceLeaseUntil: 1234567890000L + 12345L])
        def staleSnapshot = [jobId: "dj-lease-steal", phase: "staging", ops: [], opStatus: [],
                             commitOps: [], commitStatus: [],
                             sliceLeaseUntil: 1234567890000L + 90000L]

        when: 'the stale slice tries to clear the lease it no longer owns'
        script._deployReleaseLease(staleSnapshot)

        then: 'the new owner s record stands, lease and all -- the stale whole-job snapshot is not written'
        storedJob("dj-lease-steal").phase == "cancelled"
        storedJob("dj-lease-steal").sliceLeaseUntil == 1234567890000L + 12345L
    }

    def "a slice that still owns its lease clears it and persists its work"() {
        given:
        enableWrite()
        seedJob("dj-lease-own", "staging", [sliceLeaseUntil: 1234567890000L + 90000L])
        def mySnapshot = [jobId: "dj-lease-own", phase: "ready_for_commit", ops: [], opStatus: [],
                          commitOps: [], commitStatus: [],
                          sliceLeaseUntil: 1234567890000L + 90000L]

        when:
        script._deployReleaseLease(mySnapshot)

        then: 'the normal path is unchanged: lease cleared, slice state saved'
        storedJob("dj-lease-own").sliceLeaseUntil == null
        storedJob("dj-lease-own").phase == "ready_for_commit"
    }

    def "resume of a job that died on its FIRST commit op resumes committing, not staging"() {
        given:
        enableWrite()
        def paused = []
        script.metaClass.toolSetRulePaused = { Map a -> paused << a.ruleId; [success: true] }
        seedJob("dj-commit-resume", "failed", [
            ops: [[op: "pause", args: [ruleId: 1]]],
            opStatus: [[status: "done"]],
            commitOps: [[op: "pause", args: [ruleId: 2]], [op: "pause", args: [ruleId: 3]]],
            commitStatus: [[status: "failed", error: "boom"], [status: "pending"]],
            validation: [ok: true, results: []]
        ])

        when:
        def result = script.toolSetRule([deployment: [op: "resume", jobId: "dj-commit-resume"], confirm: true])

        then: "the COMMIT ops ran; the already-done staging op was not re-validated into ready_for_commit"
        paused == [2, 3]
        result.phase == "completed"
        result.progress.commitDone == 2
    }

    // ---------- cancel ----------

    def "a cancel whose deletes ALL failed stays cancellable instead of going terminal"() {
        given: 'the delete loop recorded failures then went cancelled unconditionally, and a second op=cancel is refused with "already cancelled" -- so the documented rollback was single-shot. An app with children (this PR s headline Button Controller shape) is exactly what the hub refuses'
        enableWrite()
        seedJob("dj-cancel-stuck", "failed", [createdAppIds: [101, 202]])
        script.metaClass.toolDeleteNativeApp = { Map a ->
            [success: false, error: "Cannot delete app ${a.appId}: it still has child apps"]
        }

        when:
        def result = script.toolSetRule([deployment: [op: "cancel", jobId: "dj-cancel-stuck"], confirm: true])

        then: 'the attempt reports honestly and the job is NOT terminal'
        result.success == false
        result.error.contains("Rollback incomplete")
        storedJob("dj-cancel-stuck").phase == "failed"

        when: 'the operator clears the blocker and retries'
        def deleted = []
        script.metaClass.toolDeleteNativeApp = { Map a -> deleted << a.appId; [success: true] }
        def retry = script.toolSetRule([deployment: [op: "cancel", jobId: "dj-cancel-stuck"], confirm: true])

        then: 'the retry is accepted and finishes the rollback'
        deleted.collect { it.toString() }.sort() == ["101", "202"]
        retry.phase == "cancelled"
    }

    def "a PARTIALLY failed cancel remembers what it already deleted and retries only the rest"() {
        given:
        enableWrite()
        seedJob("dj-cancel-partial", "failed", [createdAppIds: [101, 202]])
        def firstPass = []
        script.metaClass.toolDeleteNativeApp = { Map a ->
            firstPass << a.appId
            (a.appId.toString() == "202") ? [success: true]
                                          : [success: false, error: "still has child apps"]
        }

        when:
        def result = script.toolSetRule([deployment: [op: "cancel", jobId: "dj-cancel-partial"], confirm: true])

        then:
        result.success == false
        storedJob("dj-cancel-partial").phase == "failed"
        storedJob("dj-cancel-partial").cancel.deleted.collect { it.toString() } == ["202"]

        when:
        def secondPass = []
        script.metaClass.toolDeleteNativeApp = { Map a -> secondPass << a.appId; [success: true] }
        def retry = script.toolSetRule([deployment: [op: "cancel", jobId: "dj-cancel-partial"], confirm: true])

        then: 'only the app that failed is retried -- the one already gone is not deleted twice'
        secondPass.collect { it.toString() } == ["101"]
        retry.phase == "cancelled"
    }

    def "a not-found delete failure is confirmed by a readback before it counts as deleted"() {
        given: 'any failure whose message merely CONTAINS "not found" -- a backup read miss, a parent lookup, an auth error phrased that way -- was recorded as a successful rollback with no readback at all, so the job reported a clean cancel while the app was still live'
        enableWrite()
        seedJob("dj-cancel-readback", "failed", [createdAppIds: [303]])
        script.metaClass.toolDeleteNativeApp = { Map a ->
            [success: false, error: "backup snapshot not found for app ${a.appId}"]
        }
        // The readback says the app is very much alive.
        script.metaClass._rmFetchConfigJson = { Object id -> [app: [id: 303, label: "Still Here"]] }

        when:
        def result = script.toolSetRule([deployment: [op: "cancel", jobId: "dj-cancel-readback"], confirm: true])

        then: 'it is a FAILURE, not a phantom success'
        result.success == false
        storedJob("dj-cancel-readback").cancel.deleted.isEmpty()
        storedJob("dj-cancel-readback").cancel.failures.size() == 1
    }

    def "a genuinely-gone app is accepted as deleted once the readback agrees"() {
        given:
        enableWrite()
        seedJob("dj-cancel-gone", "failed", [createdAppIds: [304]])
        script.metaClass.toolDeleteNativeApp = { Map a -> [success: false, error: "No rule/app with id 304 -- not found"] }
        script.metaClass._rmFetchConfigJson = { Object id -> null }

        when:
        def result = script.toolSetRule([deployment: [op: "cancel", jobId: "dj-cancel-gone"], confirm: true])

        then:
        result.phase == "cancelled"
        storedJob("dj-cancel-gone").cancel.deleted.collect { it.toString() } == ["304"]
    }

    def "cancel treats an already-gone app as deleted and rolls the rest back"() {
        given:
        enableWrite()
        seedJob("dj-cancel", "failed", [createdAppIds: [101, 202]])
        def deleteCalls = []
        script.metaClass.toolDeleteNativeApp = { Map a ->
            deleteCalls << a.appId
            (a.appId.toString() == "202") ? [success: true]
                                          : [success: false, error: "No rule/app with id 101 -- not found"]
        }
        // The "not found" verdict is now confirmed by an existence readback rather than
        // trusted from the message text; here the app really is gone.
        script.metaClass._rmFetchConfigJson = { Object id -> null }

        when:
        def result = script.toolSetRule([deployment: [op: "cancel", jobId: "dj-cancel"], confirm: true])

        then: "newest-first, and a not-found delete counts as rolled back"
        deleteCalls == [202, 101]
        result.phase == "cancelled"
        result.cancel.deleted.collect { it.toString() }.toSet() == ["101", "202"].toSet()
        result.cancel.failures.isEmpty()
        result.success == true
    }

    def "cancel with a genuine delete failure reports success:false and an error"() {
        given:
        enableWrite()
        seedJob("dj-cancel-fail", "failed", [createdAppIds: [101, 202]])
        script.metaClass.toolDeleteNativeApp = { Map a -> [success: false, error: "app is in use by a parent"] }

        when:
        def result = script.toolSetRule([deployment: [op: "cancel", jobId: "dj-cancel-fail"], confirm: true])

        then: "the envelope does NOT claim success, and the job stays cancellable rather than going terminal -- a terminal 'cancelled' would refuse the retry and strand the live apps"
        result.phase == "failed"
        result.cancel.failures.size() == 2
        result.success == false
        result.error.contains("could not be deleted")
        result.note.contains("hub_delete_native_app")
        result.note.contains("op:'cancel'")
    }

    def "cancel holds the slice lease across the whole delete loop"() {
        given:
        enableWrite()
        seedJob("dj-lease", "failed", [createdAppIds: [11, 22]])
        def leasesSeen = []
        script.metaClass.toolDeleteNativeApp = { Map a ->
            leasesSeen << storedJob("dj-lease").sliceLeaseUntil
            [success: true]
        }

        when:
        script.toolSetRule([deployment: [op: "cancel", jobId: "dj-lease"], confirm: true])

        then: "a worker firing mid-loop would see the lease and skip the job"
        leasesSeen.size() == 2
        leasesSeen.every { it != null }

        and: "the terminal save releases it"
        storedJob("dj-lease").sliceLeaseUntil == null
    }

    // ---------- interrupted-create reconciliation ----------

    def "an interrupted create adopts the app when exactly one new child matches the expected label"() {
        given:
        enableWrite()
        hubGet.register('/installedapp/configure/json/50') { params ->
            parentConfigJson(50, [[id: 1, label: "Old Child"], [id: 777, label: "New Copy"]])
        }
        script.metaClass.toolCheckRuleHealth = { Map a -> [ok: true, broken: false, issues: []] }
        seedJob("dj-adopt", "staging", [
            ops: [[op: "cloneApp", alias: "copy", args: [sourceAppId: 5, newName: "New Copy"]]],
            opStatus: [[status: "in_flight",
                        recon: [parentAppId: 50, preChildIds: ["1"], expectLabel: "New Copy"]]]
        ])

        when:
        def result = script.toolSetRule([deployment: [op: "resume", jobId: "dj-adopt"], confirm: true])

        then: "the committed app is adopted rather than the create being re-run"
        result.ops[0].status == "done"
        result.ops[0].result.adopted == true
        result.ops[0].result.newAppId == 777
        result.createdAppIds == [777]
        result.aliases.copy == 777
        result.phase == "ready_for_commit"
    }

    def "an ambiguous interrupted create is gated, not adopted"() {
        given: "two new children carry the expected label"
        enableWrite()
        hubGet.register('/installedapp/configure/json/50') { params ->
            parentConfigJson(50, [[id: 1, label: "Old Child"],
                                  [id: 777, label: "New Copy"], [id: 778, label: "New Copy"]])
        }
        seedJob("dj-ambiguous", "staging", [
            ops: [[op: "cloneApp", alias: "copy", args: [sourceAppId: 5, newName: "New Copy"]]],
            opStatus: [[status: "in_flight",
                        recon: [parentAppId: 50, preChildIds: ["1"], expectLabel: "New Copy"]]]
        ])

        when:
        def result = script.toolSetRule([deployment: [op: "resume", jobId: "dj-ambiguous"], confirm: true])

        then:
        result.phase == "failed"
        result.ops[0].interrupted == true
        result.error.contains("interrupted mid-create")
        result.createdAppIds.isEmpty()
    }

    def "a lone new child with NO expected label is never adopted"() {
        given: "the create omitted newName, so there is no label to match on"
        enableWrite()
        hubGet.register('/installedapp/configure/json/50') { params ->
            parentConfigJson(50, [[id: 1, label: "Old Child"], [id: 900, label: "Somebody Else's App"]])
        }
        seedJob("dj-stranger", "staging", [
            ops: [[op: "cloneApp", alias: "copy", args: [sourceAppId: 5]]],
            opStatus: [[status: "in_flight", recon: [parentAppId: 50, preChildIds: ["1"]]]]
        ])

        when:
        def result = script.toolSetRule([deployment: [op: "resume", jobId: "dj-stranger"], confirm: true])

        then: "adopting it would put a stranger's app on createdAppIds for cancel to delete"
        result.phase == "failed"
        result.ops[0].interrupted == true
        result.createdAppIds.isEmpty()
    }
}
