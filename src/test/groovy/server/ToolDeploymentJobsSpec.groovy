package server

import groovy.json.JsonOutput
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

        then: "the job is terminal but the envelope does NOT claim success"
        result.phase == "cancelled"
        result.cancel.failures.size() == 2
        result.success == false
        result.error.contains("could not be deleted")
        result.note.contains("hub_delete_native_app")
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
