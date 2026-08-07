package server

import spock.lang.Shared
import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Deployment jobs (issue #376): hub_call_deployment / hub_get_deployment.
 *
 * Direct-call tests drive toolCallDeployment/toolGetDeployment with the
 * underlying op tools (toolCloneNativeApp, toolSetRulePaused, ...) stubbed via
 * script.metaClass, asserting the job state machine: manifest validation before
 * any side effect, per-op checkpoints in atomicState, alias resolution,
 * bounded slices + worker continuation, the validation gate, commit, cancel,
 * and the interrupted-op recovery policy. Dispatch tests prove both tools are
 * reachable through the JSON-RPC envelope under both routing modes.
 */
class ToolDeploymentJobsSpec extends ToolSpecBase {

    @Shared List runInCalls = []

    def setupSpec() {
        appExecutor.runIn(_, _) >> { a -> runInCalls << [delay: a[0], handler: a[1]] }
        appExecutor.runIn(_, _, _) >> { a -> runInCalls << [delay: a[0], handler: a[1], data: a[2]] }
    }

    def setup() {
        runInCalls.clear()
        // Destructive-confirm gate: a fresh backup stamp (now() is the harness-fixed clock).
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    private void stubOpTools(List calls, Map opts = [:]) {
        int cloneSeq = 0
        script.metaClass.toolCloneNativeApp = { Map a ->
            calls << [tool: "cloneApp", args: a]
            cloneSeq++
            if (opts.cloneFailsFirst && cloneSeq == 1) return [success: false, isError: true, error: "boom"]
            return [success: true, sourceAppId: a.sourceAppId, newAppId: (opts.newAppIdBase ?: 500) + cloneSeq, note: "cloned"]
        }
        script.metaClass.toolImportNativeApp = { Map a ->
            calls << [tool: "importApp", args: a]
            return [success: true, newAppId: 601]
        }
        script.metaClass.toolSetNativeApp = { Map a ->
            calls << [tool: "buttonRule", args: a]
            return [success: true, buttonRuleId: 701, controllerId: a.buttonRule?.controllerId]
        }
        script.metaClass.toolSetRule = { Map a ->
            calls << [tool: "setRule", args: a]
            return [success: true, appId: a.appId]
        }
        script.metaClass.toolSetRulePaused = { Map a ->
            calls << [tool: "setRulePaused", args: a]
            return [success: true, paused: a.paused]
        }
        script.metaClass.toolSetAppDisabled = { Map a ->
            calls << [tool: "setAppDisabled", args: a]
            return [success: true, disabled: a.disabled]
        }
        script.metaClass.toolDeleteNativeApp = { Map a ->
            calls << [tool: "deleteNativeApp", args: a]
            return [success: true, deleted: true]
        }
        script.metaClass.toolCheckRuleHealth = { Map a ->
            calls << [tool: "ruleHealth", args: a]
            return (opts.unhealthyAppIds ?: []).collect { it.toString() }.contains(a.appId.toString()) ?
                [ok: false, broken: true, issues: ["label carries *BROKEN*"]] :
                [ok: true, broken: false]
        }
        script.metaClass._rmFetchConfigJson = { Object id ->
            def byId = (opts.configById instanceof Map) ? opts.configById[id.toString()] : null
            return byId != null ? byId : [app: [parentAppId: 40], childApps: []]
        }
    }

    @Unroll
    def "create rejects a malformed manifest (#label) before any side effect"() {
        when:
        script.toolCallDeployment([operation: "create", confirm: true] + badArgs)

        then:
        thrown(IllegalArgumentException)
        atomicStateMap.deployJobs == null

        where:
        label                    | badArgs
        "missing ops"            | [:]
        "empty ops"              | [ops: []]
        "unknown op"             | [ops: [[op: "frobnicate", args: [:]]]]
        "duplicate alias"        | [ops: [[op: "cloneApp", alias: "a", args: [sourceAppId: 1]], [op: "cloneApp", alias: "a", args: [sourceAppId: 2]]]]
        "forward alias ref"      | [ops: [[op: "pause", args: [ruleId: [alias: "later"]]], [op: "cloneApp", alias: "later", args: [sourceAppId: 1]]]]
        "alias on non-create op" | [ops: [[op: "pause", alias: "p", args: [ruleId: 1]]]]
    }

    def "create refuses without confirm"() {
        when:
        script.toolCallDeployment([operation: "create", ops: [[op: "pause", args: [ruleId: 1]]]])

        then:
        thrown(IllegalArgumentException)
        atomicStateMap.deployJobs == null
    }

    def "create stages ops with checkpoints, resolves aliases, validates, and lands ready_for_commit"() {
        given:
        def calls = []
        stubOpTools(calls)

        when:
        def res = script.toolCallDeployment([operation: "create", name: "mig", confirm: true, ops: [
            [op: "cloneApp", alias: "newRule", args: [sourceAppId: 100, newName: "Clone A", stageDisabled: true]],
            [op: "pause", args: [ruleId: [alias: "newRule"]]]
        ]])

        then: "both ops ran, the alias resolved to the clone's new appId"
        res.success == true
        res.phase == "ready_for_commit"
        res.aliases.newRule == 501
        res.createdAppIds == [501]
        calls.find { it.tool == "cloneApp" }.args.sourceAppId == 100
        calls.find { it.tool == "cloneApp" }.args.confirm == true
        calls.find { it.tool == "setRulePaused" }.args.ruleId == 501
        calls.find { it.tool == "setRulePaused" }.args.paused == true

        and: "the validation gate health-checked the created app"
        calls.find { it.tool == "ruleHealth" }.args.appId == 501
        res.validation.ok == true
        res.validation.results[0].appId == 501

        and: "the job checkpoint is persisted in atomicState with per-op status"
        def job = atomicStateMap.deployJobs[res.jobId]
        job.phase == "ready_for_commit"
        job.opStatus.every { it.status == "done" }
        job.opStatus[0].result.newAppId == 501
    }

    def "maxOpsPerCall bounds the inline slice and schedules the on-hub worker"() {
        given:
        def calls = []
        stubOpTools(calls)

        when:
        def res = script.toolCallDeployment([operation: "create", confirm: true, maxOpsPerCall: 1, ops: [
            [op: "cloneApp", alias: "n", args: [sourceAppId: 100, newName: "C1"]],
            [op: "pause", args: [ruleId: [alias: "n"]]]
        ]])

        then: "only the first op ran in this call"
        res.phase == "staging"
        res.progress.stagingDone == 1
        res.progress.stagingTotal == 2
        atomicStateMap.deployJobs[res.jobId].opStatus[1].status == "pending"
        !calls.any { it.tool == "setRulePaused" }

        and: "the on-hub worker was scheduled to continue with no client attached"
        runInCalls.any { it.handler == "deployJobWorker" }
    }

    def "deployJobWorker continues a bounded job to ready_for_commit with no client call"() {
        given:
        def calls = []
        stubOpTools(calls)
        def res = script.toolCallDeployment([operation: "create", confirm: true, maxOpsPerCall: 1, ops: [
            [op: "cloneApp", alias: "n", args: [sourceAppId: 100, newName: "C1"]],
            [op: "pause", args: [ruleId: [alias: "n"]]]
        ]])
        assert res.phase == "staging"

        when: "the scheduled worker fires (no MCP client involved)"
        script.deployJobWorker()

        then:
        def job = atomicStateMap.deployJobs[res.jobId]
        job.phase == "ready_for_commit"
        calls.find { it.tool == "setRulePaused" }.args.ruleId == 501
        job.validation.ok == true
    }

    def "commit runs commitOps in order and completes; commit before ready_for_commit is refused"() {
        given:
        def calls = []
        stubOpTools(calls)
        def created = script.toolCallDeployment([operation: "create", confirm: true,
            ops: [[op: "cloneApp", alias: "n", args: [sourceAppId: 100, newName: "C1"]]],
            commitOps: [
                [op: "pause", args: [ruleId: [100]]],
                [op: "setDisabled", args: [appId: [alias: "n"], disabled: false]]
            ]])
        assert created.phase == "ready_for_commit"

        when:
        def committed = script.toolCallDeployment([operation: "commit", jobId: created.jobId, confirm: true])

        then:
        committed.phase == "completed"
        def commitCalls = calls.findAll { it.tool in ["setRulePaused", "setAppDisabled"] }
        commitCalls[0].tool == "setRulePaused"
        commitCalls[0].args.ruleId == [100]
        commitCalls[1].tool == "setAppDisabled"
        commitCalls[1].args.appId == 501
        commitCalls[1].args.disabled == false

        when: "commit on a completed job is refused"
        script.toolCallDeployment([operation: "commit", jobId: created.jobId, confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    def "a failed op fails the job with the op error; resume retries it to completion"() {
        given:
        def calls = []
        stubOpTools(calls, [cloneFailsFirst: true])

        when:
        def res = script.toolCallDeployment([operation: "create", confirm: true, ops: [
            [op: "cloneApp", alias: "n", args: [sourceAppId: 100, newName: "C1"]]
        ]])

        then:
        res.phase == "failed"
        res.jobError.contains("cloneApp")
        res.jobError.contains("boom")

        when:
        def resumed = script.toolCallDeployment([operation: "resume", jobId: res.jobId, confirm: true])

        then: "the retry re-ran the clone and the job validated"
        resumed.phase == "ready_for_commit"
        calls.count { it.tool == "cloneApp" } == 2
        resumed.createdAppIds == [502]
    }

    def "validation gate fails the job when a created app is unhealthy"() {
        given:
        def calls = []
        stubOpTools(calls, [unhealthyAppIds: [501]])

        when:
        def res = script.toolCallDeployment([operation: "create", confirm: true, ops: [
            [op: "cloneApp", alias: "n", args: [sourceAppId: 100, newName: "C1"]]
        ]])

        then:
        res.phase == "failed"
        res.validation.ok == false
        res.validation.results[0].broken == true
        res.jobError.toLowerCase().contains("validation failed")
    }

    def "cancel deletes only the apps the job created, newest first"() {
        given:
        def calls = []
        stubOpTools(calls)
        def created = script.toolCallDeployment([operation: "create", confirm: true, ops: [
            [op: "cloneApp", alias: "a", args: [sourceAppId: 100, newName: "C1"]],
            [op: "cloneApp", alias: "b", args: [sourceAppId: 200, newName: "C2"]]
        ]])
        assert created.createdAppIds == [501, 502]

        when:
        def cancelled = script.toolCallDeployment([operation: "cancel", jobId: created.jobId, confirm: true])

        then:
        cancelled.phase == "cancelled"
        def deletes = calls.findAll { it.tool == "deleteNativeApp" }
        deletes.collect { it.args.appId } == [502, 501]
        cancelled.cancel.deleted == [502, 501]
        !deletes.any { it.args.appId in [100, 200] }
    }

    def "an interrupted addActions op is gated: fails with guidance, refuses blind resume, re-runs with retryInFlight"() {
        given:
        def calls = []
        stubOpTools(calls)
        atomicStateMap.deployJobs = ["dj-t9": [
            jobId: "dj-t9", name: "t9", phase: "staging", background: false,
            ops: [[op: "addActions", args: [appId: 300, actions: [[capability: "log", message: "x"]]]]],
            commitOps: [], opStatus: [[status: "in_flight"]], commitStatus: [],
            aliases: [:], createdAppIds: [], backupKeys: [], history: []
        ]]

        when: "the first resume finds the op mid-flight and converts it to a gated failure"
        def res = script.toolCallDeployment([operation: "resume", jobId: "dj-t9", confirm: true])

        then:
        res.phase == "failed"
        res.jobError.contains("interrupted mid-write")
        calls.isEmpty()

        when: "resume WITHOUT retryInFlight is refused with the guidance"
        script.toolCallDeployment([operation: "resume", jobId: "dj-t9", confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("retryInFlight")

        when: "retryInFlight=true approves the re-run"
        def resumed = script.toolCallDeployment([operation: "resume", jobId: "dj-t9", confirm: true, retryInFlight: true])

        then:
        resumed.phase == "ready_for_commit"
        calls.find { it.tool == "setRule" }.args.appId == 300
    }

    def "an interrupted create-type op is adopted on resume when the app already exists"() {
        given:
        def calls = []
        stubOpTools(calls, [configById: [
            "40": [app: [parentAppId: null], childApps: [[id: 7, label: "Old"], [id: 501, label: "Clone A"]]]
        ]])
        atomicStateMap.deployJobs = ["dj-t11": [
            jobId: "dj-t11", name: "t11", phase: "staging", background: false,
            ops: [[op: "cloneApp", alias: "n", args: [sourceAppId: 100, newName: "Clone A"]]],
            commitOps: [], commitStatus: [],
            opStatus: [[status: "in_flight", recon: [parentAppId: 40, preChildIds: ["7"], expectLabel: "Clone A"]]],
            aliases: [:], createdAppIds: [], backupKeys: [], history: []
        ]]

        when:
        def res = script.toolCallDeployment([operation: "resume", jobId: "dj-t11", confirm: true])

        then: "the clone was NOT re-run -- the committed app was adopted"
        res.phase == "ready_for_commit"
        !calls.any { it.tool == "cloneApp" }
        res.createdAppIds == [501]
        res.aliases.n == 501
        atomicStateMap.deployJobs["dj-t11"].opStatus[0].result.adopted == true
    }

    def "hub_get_deployment returns job detail and lists all jobs"() {
        given:
        def calls = []
        stubOpTools(calls)
        def created = script.toolCallDeployment([operation: "create", name: "migA", confirm: true, ops: [
            [op: "pause", args: [ruleId: 300]]
        ]])

        when:
        def detail = script.toolGetDeployment([jobId: created.jobId])
        def listed = script.toolGetDeployment([:])

        then:
        detail.success == true
        detail.phase == "ready_for_commit"
        detail.ops[0].op == "pause"
        detail.ops[0].status == "done"
        listed.total == 1
        listed.jobs[0].jobId == created.jobId
        listed.jobs[0].name == "migA"
    }

    def "hub_get_deployment on an unknown job is a validation error"() {
        when:
        script.toolGetDeployment([jobId: "dj-nope"])

        then:
        thrown(IllegalArgumentException)
    }

    // ==================== dispatch-envelope (integration) ====================

    @Unroll
    def "hub_call_deployment dispatches through the envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableMandatoryBPS = false
        def calls = []
        stubOpTools(calls)

        when:
        def response = mcpDriver.callTool('hub_call_deployment', [operation: "create", confirm: true, ops: [
            [op: "pause", args: [ruleId: 300]]
        ]])

        then:
        response.jsonrpc == '2.0'
        response.id == mcpDriver.lastSentId
        response.error == null
        response.result.isError != true
        response.result.content[0].text.contains('"phase":"ready_for_commit"')
        calls.find { it.tool == "setRulePaused" }.args.ruleId == 300

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_call_deployment invalid operation maps to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableMandatoryBPS = false

        when:
        def response = mcpDriver.callTool('hub_call_deployment', [operation: "frobnicate", confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('operation')

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_get_deployment dispatches in list mode (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_get_deployment', [:])

        then:
        response.jsonrpc == '2.0'
        response.error == null
        response.result.isError != true
        response.result.content[0].text.contains('"jobs"')

        where:
        useGateways << [true, false]
    }
}
