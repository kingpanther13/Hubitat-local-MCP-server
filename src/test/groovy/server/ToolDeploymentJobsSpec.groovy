package server

import support.ToolSpecBase

/**
 * Spec for the `deployment` argument surface in libraries/mcp-deploy-jobs-lib.groovy,
 * routed from toolSetRule / toolSetNativeApp (libraries/mcp-native-rules-lib.groovy).
 *
 * There are no dedicated deployment tools: hub_set_rule and hub_set_native_app both
 * route args.deployment to the one shared engine. Covered here:
 *   - routing contract: self-contained (rejects combination with other args), shared
 *     across both tools, op enum validated
 *   - op="status" is a pure read (no confirm required) listing job records
 *   - op="create" validates the whole manifest BEFORE persisting anything
 *     (fail-before-side-effect is what makes -32602 token release safe)
 *   - op="delete" removes only finished (completed/cancelled) job RECORDS and
 *     surfaces the rollback handles one last time
 *
 * Job records are seeded straight into atomicStateMap.deployJobs — the engine
 * reads/writes atomicState.deployJobs (whole-map) and per-entry via
 * atomicState.updateMapValue (TestAtomicState implements both).
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

    def "dispatch envelope: hub_set_rule deployment status flows through executeTool"() {
        given:
        settingsMap.enableRead = true
        seedJob("dj-wire", "completed")

        when:
        def result = script.executeTool("hub_set_rule", [deployment: [op: "status"]])

        then:
        result.success == true
        result.jobs*.jobId == ["dj-wire"]
    }
}
