package server

import groovy.json.JsonOutput

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Durable-shape contract for terminal MCP request-state records.
 *
 * Active records retain their continuation inputs. Once a result is terminal,
 * terminalResult is the replay source and the working aggregate is redundant.
 */
class MrtrTerminalStorageSpec extends ToolSpecBase {

    @Shared private TestChildApp lifecycleApp = new TestChildApp(id: 1L, label: 'MCP')

    private static class FailOnceAtomicState extends LinkedHashMap {
        int failuresRemaining
        int mrtrWrites
        Object put(Object key, Object value) {
            if (key?.toString() == 'mrtrRequests') {
                mrtrWrites++
                if (failuresRemaining > 0) {
                    failuresRemaining--
                    throw new IllegalStateException('injected state write failure')
                }
            }
            return super.put(key, value)
        }
    }

    def setupSpec() {
        appExecutor.getApp() >> lifecycleApp
    }

    def setup() {
        lifecycleApp.settingsStore.clear()
        settingsMap.enableWrite = true
    }

    private Map modernCall(String toolName, Map args, String requestState) {
        int id = ++mcpDriver.lastSentId
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': toolName
        ])
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: id, method: 'tools/call',
            params: [name: toolName, arguments: args, requestState: requestState]
        ])
        script.handleMcpRequest()
        return mcpDriver.parseResponseJson() as Map
    }

    private Map claimedRecord(String outerTool, String leafTool, Map args,
                              String claimId, int generation, Map extra = [:]) {
        long at = script.now() as Long
        Map rec = [
            schemaVersion: 1,
            status: 'active',
            outerTool: outerTool,
            leafTool: leafTool,
            argDigest: script._mrtrBinding(outerTool, leafTool, args).argDigest,
            startedAt: at - 1000L,
            updatedAt: at,
            expiresAt: at + 60000L,
            rounds: 1,
            generation: generation,
            claimId: claimId,
            claimedGeneration: generation,
            claimedAt: at
        ]
        rec.putAll(extra)
        return rec
    }

    private void installRecord(String stateId, Map rec) {
        atomicStateMap.mrtrRequests = [(stateId): rec]
        script._writeStateCacheInvalidate()
    }

    def "successful terminal publication removes the working aggregate and replays after a cache clear"() {
        given: 'a claimed multi-rule request whose final result already contains every slice outcome'
        String stateId = 'mrtr-terminal-success-0001'
        Map args = [ruleId: [11, 12], action: 'stop']
        Map workingAggregate = [
            kind: 'call_rule',
            results: [[ruleId: 11, success: true], [ruleId: 12, success: true]],
            ruleIds: [11, 12],
            anyPartial: false
        ]
        Map result = [
            success: true,
            results: [[ruleId: 11, success: true], [ruleId: 12, success: true]],
            ruleIds: [11, 12],
            mrtr: [continued: true, rounds: 2, startedAt: script.now() - 1000L]
        ]
        Map rec = claimedRecord('hub_call_rule', 'hub_call_rule', args,
            'claim-terminal-success', 2, [aggregate: workingAggregate])
        installRecord(stateId, rec)

        when:
        boolean stored = script._mrtrStoreTerminal(stateId, rec,
            [claimId: 'claim-terminal-success', generation: 2], result, false)
        Map terminal = (atomicStateMap.mrtrRequests as Map)[stateId] as Map

        then: 'terminalResult is the sole durable copy of the assembled outcome'
        stored
        terminal.status == 'terminal'
        !terminal.containsKey('aggregate')
        terminal.terminalResult == result

        when: 'both class-live terminal evidence and the persisted-state cache are cleared'
        (scriptStaticField('MRTR_TERMINAL_EVIDENCE') as Map).clear()
        script._writeStateCacheInvalidate()
        Map replay = modernCall('hub_call_rule', args, stateId)

        then: 'the public requestState path replays the complete durable result without leaf execution'
        replay.result.resultType == 'complete'
        !replay.result.isError
        mcpDriver.parseInner(replay) == result
    }

    def "terminal publication keeps an error's embedded aggregate while removing the record aggregate"() {
        given:
        String stateId = 'mrtr-terminal-error-00001'
        Map args = [ruleId: [21, 22], action: 'start']
        Map completed = [
            kind: 'call_rule',
            results: [[ruleId: 21, success: true]],
            ruleIds: [21, 22]
        ]
        Map failure = [
            success: false,
            isError: true,
            error: 'The final worker failed after earlier committed work.',
            aggregate: completed,
            note: 'Inspect the aggregate before retrying.'
        ]
        Map rec = claimedRecord('hub_call_rule', 'hub_call_rule', args,
            'claim-terminal-error', 3, [
                aggregate: completed,
                nextArguments: [ruleId: [22], action: 'start'],
                checkpoint: [phase: 'second-slice']
            ])
        installRecord(stateId, rec)

        when:
        boolean stored = script._mrtrStoreTerminal(stateId, rec,
            [claimId: 'claim-terminal-error', generation: 3], failure, true)
        Map terminal = (atomicStateMap.mrtrRequests as Map)[stateId] as Map

        then:
        stored
        !terminal.containsKey('aggregate')
        !terminal.containsKey('nextArguments')
        !terminal.containsKey('checkpoint')
        terminal.terminalIsError == true
        terminal.terminalResult == failure
        terminal.terminalResult.aggregate == completed
    }

    def "continuation cap keeps its merged outcome inside terminalResult without a second record copy"() {
        given: 'a bulk edit at the final owner slice with prior actions already banked'
        String stateId = 'mrtr-terminal-capped-0001'
        Map originalArgs = [appId: 77, addActions: [[deviceId: 1], [deviceId: 2], [deviceId: 3]], confirm: true]
        Map prior = [
            kind: 'bulk_edit',
            triggers: [],
            actions: [[deviceId: 1, success: true]],
            anyPartial: true
        ]
        Map rec = claimedRecord('hub_set_rule', 'hub_set_rule', originalArgs,
            'claim-terminal-capped', 4, [
                rounds: (script._mrtrMaxContinuationSlices() as Integer) - 1,
                aggregate: prior,
                nextArguments: [appId: 77, addActions: [[deviceId: 2], [deviceId: 3]], confirm: true],
                checkpoint: [page: 'actions']
            ])
        installRecord(stateId, rec)
        Map slice = [
            success: true,
            status: 'in_progress',
            triggers: [],
            actions: [[deviceId: 2, success: true]],
            addActionsRemaining: [[deviceId: 3]],
            partial: true,
            resume: [operation: 'addActions']
        ]

        when:
        Map outcome = script._mrtrCommitSlice(stateId, rec,
            [claimId: 'claim-terminal-capped', generation: 4], originalArgs, slice) as Map
        Map terminal = (atomicStateMap.mrtrRequests as Map)[stateId] as Map

        then: 'the cap response retains committed outcomes and exact remaining work'
        outcome.outcome == 'terminal'
        outcome.isError == true
        outcome.result.status == 'continuation_limit'
        outcome.result.aggregate.actions*.deviceId == [1, 2]
        outcome.result.addActionsRemaining*.deviceId == [3]

        and: 'only active-only fields and the redundant record aggregate are discarded'
        !terminal.containsKey('aggregate')
        !terminal.containsKey('nextArguments')
        !terminal.containsKey('checkpoint')
        terminal.terminalResult == outcome.result
        terminal.outerTool == 'hub_set_rule'
        terminal.leafTool == 'hub_set_rule'
        terminal.argDigest == script._mrtrBinding('hub_set_rule', 'hub_set_rule', originalArgs).argDigest
    }

    def "loading a legacy terminal compacts only that record and saves the representative bytes"() {
        given: 'one legacy terminal duplicates a sizeable result while an active peer still needs its continuation state'
        long at = script.now() as Long
        String terminalId = 'mrtr-legacy-terminal-0001'
        Map terminalArgs = [ruleId: [31, 32], action: 'stop']
        Map ledger = [
            kind: 'call_rule',
            results: (1..12).collect { int id ->
                [ruleId: id, success: true, message: "representative-result-${id}-" + ('x' * 48)]
            },
            ruleIds: (1..12).toList(),
            anyPartial: false
        ]
        Map terminalResult = [
            success: true,
            results: ledger.results,
            ruleIds: ledger.ruleIds,
            mrtr: [continued: true, rounds: 4, startedAt: at - 4000L]
        ]
        Map legacyTerminal = [
            schemaVersion: 1, status: 'terminal',
            outerTool: 'hub_call_rule', leafTool: 'hub_call_rule',
            argDigest: script._mrtrBinding('hub_call_rule', 'hub_call_rule', terminalArgs).argDigest,
            startedAt: at - 4000L, updatedAt: at - 1000L, finishedAt: at - 1000L,
            expiresAt: at + 60000L, rounds: 3, generation: 3,
            aggregate: ledger, terminalResult: terminalResult, terminalIsError: false
        ]
        Map active = [
            schemaVersion: 1, status: 'active',
            outerTool: 'hub_set_rule', leafTool: 'hub_set_rule', argDigest: 'active-binding',
            startedAt: at - 2000L, updatedAt: at, expiresAt: at + 60000L,
            rounds: 2, generation: 2, claimId: 'active-claim', claimedGeneration: 2,
            aggregate: [kind: 'bulk_edit', actions: [[deviceId: 9, success: true]]],
            nextArguments: [appId: 99, addActions: [[deviceId: 10]], confirm: true],
            checkpoint: [phase: 'actions', cursor: 2]
        ]
        Map activeBefore = script._mrtrCopyMap(active) as Map
        atomicStateMap.mrtrRequests = [(terminalId): legacyTerminal, active: active]
        int legacyBytes = JsonOutput.toJson(atomicStateMap.mrtrRequests).getBytes('UTF-8').length
        int redundantPropertyBytes = JsonOutput.toJson(ledger).getBytes('UTF-8').length + 13
        script._writeStateCacheInvalidate()

        when: 'the legacy requestState is safely loaded through the public replay path'
        Map replay = modernCall('hub_call_rule', terminalArgs, terminalId)
        Map durable = atomicStateMap.mrtrRequests as Map
        int compactBytes = JsonOutput.toJson(durable).getBytes('UTF-8').length

        then: 'replay is unchanged and the original request binding remains durable'
        replay.result.resultType == 'complete'
        mcpDriver.parseInner(replay) == terminalResult
        durable[terminalId].outerTool == legacyTerminal.outerTool
        durable[terminalId].leafTool == legacyTerminal.leafTool
        durable[terminalId].argDigest == legacyTerminal.argDigest

        and: 'only the safely terminal legacy aggregate is removed'
        !durable[terminalId].containsKey('aggregate')
        durable.active == activeBefore

        and: 'the compact UTF-8 payload saves exactly the duplicate property bytes'
        legacyBytes - compactBytes == redundantPropertyBytes
    }

    def "a failed legacy compaction never blocks replay and retries after backoff"() {
        given:
        long at = script.now() as Long
        String stateId = 'mrtr-legacy-retry-0000001'
        Map args = [ruleId: [41, 42], action: 'stop']
        Map result = [success: true, results: [[ruleId: 41, success: true]], ruleIds: [41, 42]]
        Map legacy = [
            schemaVersion: 1, status: 'terminal', outerTool: 'hub_call_rule', leafTool: 'hub_call_rule',
            argDigest: script._mrtrBinding('hub_call_rule', 'hub_call_rule', args).argDigest,
            startedAt: at, updatedAt: at, finishedAt: at, expiresAt: at + 120000L,
            rounds: 1, generation: 1, aggregate: [kind: 'call_rule', results: result.results],
            terminalResult: result, terminalIsError: false
        ]
        def backing = new FailOnceAtomicState()
        backing['mrtrRequests'] = [(stateId): legacy]
        backing.@mrtrWrites = 0
        backing.@failuresRemaining = 1
        def peer = newCompiledScriptInstance([app: lifecycleApp, state: stateMap, atomicState: backing])
        peer._writeStateCacheInvalidate()
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28'])
        def replay = { int id ->
            mcpDriver.decodeToolCallResponse(peer.handleToolsCall([jsonrpc: '2.0', id: id,
                method: 'tools/call', params: [name: 'hub_call_rule', arguments: args, requestState: stateId]]) as Map)
        }

        when: 'the first opportunistic compacting write fails'
        Map first = replay(1) as Map

        then: 'the durable legacy record remains usable and replay still succeeds'
        first.result.resultType == 'complete'
        mcpDriver.parseInner(first) == result
        backing['mrtrRequests'][stateId].containsKey('aggregate')
        backing.@mrtrWrites == 1

        when: 'the same terminal state is replayed again'
        Map second = replay(2) as Map

        then: 'replay remains available without retrying persistence immediately'
        second.result.resultType == 'complete'
        mcpDriver.parseInner(second) == result
        backing['mrtrRequests'][stateId].containsKey('aggregate')
        backing.@mrtrWrites == 1

        when: 'the retry backoff passes while the terminal is still valid'
        NOW_OVERRIDE.set({ at + 60000L })
        Map third = replay(3) as Map

        then:
        third.result.resultType == 'complete'
        mcpDriver.parseInner(third) == result
        !backing['mrtrRequests'][stateId].containsKey('aggregate')
        backing.@mrtrWrites == 2
    }
}
