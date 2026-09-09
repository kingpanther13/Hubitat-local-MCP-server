package server

import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.PermissiveLog
import support.ToolSpecBase

class MrtrCleanupSpec extends ToolSpecBase {
    @Shared private TestChildApp cleanupApp = new TestChildApp(id: 1L, label: 'MCP')

    private static class FailingState extends LinkedHashMap {
        int writes
        int failWrite
        Object put(Object key, Object value) {
            if (key == 'mrtrRequests' && ++writes == failWrite) {
                throw new IllegalStateException('injected MRTR persistence failure')
            }
            return super.put(key, value)
        }
    }

    def setupSpec() { appExecutor.getApp() >> cleanupApp }

    private Map terminal(long expiry, boolean legacy = false) {
        Map rec = [status: 'terminal', leafTool: 'hub_call_rule', outerTool: 'hub_call_rule',
            expiresAt: expiry, updatedAt: 1L,
            terminalResult: [success: false, aggregate: [results: [[success: true]]]],
            terminalIsError: true]
        if (legacy) rec.put('aggregate', [results: [[success: true]]])
        return rec
    }

    private List jobs() { runInCalls.findAll { it[1] == 'runMrtrCleanup' } }

    private def failingPeer(Map records, int failWrite) {
        def backing = new FailingState()
        backing.put('mrtrRequests', records)
        backing.@writes = 0
        backing.@failWrite = failWrite
        def peer = newCompiledScriptInstance([app: cleanupApp, state: stateMap, atomicState: backing])
        peer._writeStateCacheInvalidate()
        return [peer, backing]
    }

    def 'stored records arm one expiry job and earlier read expiry moves it forward'() {
        given:
        long at = script.now()

        when:
        script._mrtrPutLocked('write', terminal(at + 600000L))
        script._mrtrPutLocked('later', terminal(at + 610000L))
        Map read = terminal(at + 30000L)
        read.leafTool = 'hub_get_jobs'
        read.terminalResult = [__slowReadReplay: true, tool: 'hub_get_jobs']
        script._mrtrPutLocked('read', read)

        then:
        jobs()*.getAt(0) == [600, 30]
        jobs().every { it[2].overwrite == true }
        atomicStateMap.mrtrRequests.read.terminalResult.__slowReadReplay

        when: 'only the scheduled callback runs after the read expiry'
        NOW_OVERRIDE.set({ at + 30000L })
        script.runMrtrCleanup()

        then:
        atomicStateMap.mrtrRequests.keySet() == ['write', 'later'] as Set
        jobs().last()[0] == 570

        when: 'the remaining expiries pass with no requests'
        NOW_OVERRIDE.set({ at + 610000L })
        script.runMrtrCleanup()
        int scheduled = jobs().size()
        script._mrtrEnsureCleanupScheduled()

        then:
        atomicStateMap.mrtrRequests.isEmpty()
        jobs().size() == scheduled
    }

    def 'ping bootstrap and lifecycle reset rearm durable records without repeated scheduling'() {
        given:
        atomicStateMap.mrtrRequests = [old: terminal(script.now() - 1L)]
        script._writeStateCacheInvalidate()

        when:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'ping'])
        script.handleMcpRequest()
        20.times { script._mrtrEnsureCleanupScheduled() }

        then:
        mcpDriver.parseResponseJson().result == [:]
        jobs().size() == 1
        jobs().first()[0] == 1
        atomicStateMap.mrtrRequests.containsKey('old')

        when: 'initialize has unscheduled the prior job'
        script._mrtrEnsureCleanupScheduled(true)

        then:
        jobs().size() == 2

        when: 'a code reload loses static scheduling metadata'
        (scriptStaticField('MRTR_CLEANUP_SCHEDULES') as Map).clear()
        script._writeStateCacheInvalidate()
        script._mrtrEnsureCleanupScheduled()

        then:
        jobs().size() == 3
    }

    def 'an empty cold store stays idle without creating durable empty state'() {
        when:
        20.times { script._mrtrEnsureCleanupScheduled() }

        then:
        jobs().isEmpty()
        !atomicStateMap.containsKey('mrtrRequests')
    }

    def 'expired live workers retain checkpoints and work items until their owner stops'() {
        given:
        long at = script.now()
        Map active = [status: 'active', leafTool: 'hub_clone_native_app', expiresAt: at - 1L,
            claimId: 'live', checkpoint: [clonerAppId: 77]]
        atomicStateMap.mrtrRequests = [active: active]
        script._writeStateCacheInvalidate()
        (scriptStaticField('LIVE_WRITE_EXECUTIONS') as Set).add('live')
        (scriptStaticField('MRTR_WORK_ITEMS') as Map).put('live', [stateId: 'active', started: true])
        List paths = []
        script.metaClass.hubInternalGetRaw = { String path, Map params = null, Integer timeout = 30 ->
            paths << path
            [status: 302, data: '']
        }

        when:
        script.runMrtrCleanup()

        then:
        atomicStateMap.mrtrRequests.active == active
        (scriptStaticField('MRTR_WORK_ITEMS') as Map).containsKey('live')
        paths.isEmpty()
        jobs().last()[0] == 60

        when:
        (scriptStaticField('LIVE_WRITE_EXECUTIONS') as Set).remove('live')
        NOW_OVERRIDE.set({ at + 60000L })
        script.runMrtrCleanup()

        then:
        atomicStateMap.mrtrRequests.isEmpty()
        (scriptStaticField('MRTR_WORK_ITEMS') as Map).isEmpty()
        paths == ['/installedapp/forcedelete/77/quiet']
    }

    def 'scheduler failure backs off and ordinary requests recover without failing publication'() {
        given:
        long at = script.now()
        int attempts = 0
        List warnings = []
        script.metaClass.mcpLog = { level, category, message -> warnings << [level, category, message] }
        RUN_IN_OVERRIDE.set({ List call ->
            attempts++
            throw new IllegalStateException('scheduler unavailable')
        })

        when:
        script._mrtrPutLocked('saved', terminal(at + 600000L))
        20.times { script._mrtrEnsureCleanupScheduled() }

        then:
        attempts == 1
        atomicStateMap.mrtrRequests.containsKey('saved')
        warnings.size() == 1
        warnings.first()[0..1] == ['warn', 'mrtr']

        when:
        NOW_OVERRIDE.set({ at + 60000L })
        RUN_IN_OVERRIDE.set(null)
        script._mrtrEnsureCleanupScheduled()

        then:
        jobs().size() == 1
        jobs().last()[0] == 540
    }

    def 'an accepted callback rearms survivors after a failed replacement during backoff'() {
        given:
        long at = script.now()
        script._mrtrPutLocked('soon', terminal(at + 30000L))
        script._mrtrPutLocked('later', terminal(at + 600000L))
        NOW_OVERRIDE.set({ at + 10000L })
        RUN_IN_OVERRIDE.set({ List call -> throw new IllegalStateException('replacement rejected') })
        script._mrtrPutLocked('earlier', terminal(at + 20000L))

        when: 'the original accepted job fires while requests are backing off'
        NOW_OVERRIDE.set({ at + 30000L })
        RUN_IN_OVERRIDE.set(null)
        script.runMrtrCleanup()

        then:
        atomicStateMap.mrtrRequests.keySet() == ['later'] as Set
        jobs().last()[0] == 570

        when: 'the rearmed callback runs without intervening request traffic'
        NOW_OVERRIDE.set({ at + 600000L })
        script.runMrtrCleanup()

        then:
        atomicStateMap.mrtrRequests.isEmpty()
    }

    def 'a missed expiry callback is rearmed by read traffic after its grace interval'() {
        given:
        long at = script.now()
        script._mrtrPutLocked('saved', terminal(at + 1000L))

        when:
        NOW_OVERRIDE.set({ at + 62000L })
        script._mrtrEnsureCleanupScheduled()

        then:
        jobs().size() == 2
        jobs().last()[0] == 1
    }

    def 'mixed sweep commits eviction before an optional compaction failure and preserves replay'() {
        given:
        long at = script.now()
        Map legacy = terminal(at + 600000L, true)
        String stateId = 'mrtr-mixed-cleanup-00000001'
        Map binding = script._mrtrBinding('hub_call_rule', 'hub_call_rule', [ruleId: [1, 2], action: 'stop'])
        legacy.argDigest = binding.argDigest
        def (peer, backing) = failingPeer([(stateId): legacy, expired: terminal(at - 1L)], 2)

        when:
        Map claim = peer._mrtrClaim(stateId, 'hub_call_rule', 'hub_call_rule', binding)

        then:
        claim.outcome == 'terminal'
        claim.record.terminalResult == legacy.terminalResult
        backing.get('mrtrRequests').keySet() == [stateId] as Set
        backing.get('mrtrRequests').get(stateId).containsKey('aggregate')
        peer._writeStateMapLocked('mrtrRequests') == backing.get('mrtrRequests')
        backing.@writes == 2

        when: 'another replay during backoff does not retry the failed compaction'
        peer._mrtrClaim(stateId, 'hub_call_rule', 'hub_call_rule', binding)

        then:
        backing.@writes == 2
    }

    @Unroll
    def 'required eviction failure preserves cache and helper ownership with legacy=#legacy'() {
        given:
        long at = script.now()
        Map active = [status: 'active', leafTool: 'hub_clone_native_app', expiresAt: at - 1L,
            claimId: 'stopped', checkpoint: [clonerAppId: 77]]
        Map records = [keep: terminal(at + 600000L, legacy), expired: active]
        def (peer, backing) = failingPeer(records, 1)
        (scriptStaticField('MRTR_WORK_ITEMS') as Map).put('stopped', [stateId: 'expired', started: false])

        when:
        peer._mrtrSweepLocked()

        then:
        thrown(IllegalStateException)
        peer._writeStateMapLocked('mrtrRequests') == records
        backing.get('mrtrRequests') == records
        (scriptStaticField('MRTR_WORK_ITEMS') as Map).containsKey('stopped')

        when: 'retry returns helper cleanup only after durable eviction'
        List cleanup = peer._mrtrSweepLocked()

        then:
        cleanup == [active]
        backing.get('mrtrRequests').keySet() == ['keep'] as Set
        !(scriptStaticField('MRTR_WORK_ITEMS') as Map).containsKey('stopped')

        where:
        legacy << [false, true]
    }

    @Unroll
    def '#operation survives unavailable storage even when MCP logging is cold'() {
        given:
        def nativeLog = new PermissiveLog()
        def peer = newCompiledScriptInstance(app: cleanupApp, state: stateMap, log: nativeLog,
            atomicState: { throw new IllegalStateException('storage unavailable') })
        (scriptStaticField('DEBUG_LOG_BUFFERS') as Map).clear()
        peer._writeStateCacheInvalidate()

        when:
        peer."$operation"()

        then:
        noExceptionThrown()
        nativeLog.messages.any { it.startsWith('warn:') && it.contains('storage unavailable') }
        if (operation == 'runMrtrCleanup') assert jobs().last()[0] == 60

        where:
        operation << ['_cleanupRetiredToolState', '_mrtrEnsureCleanupScheduled', 'runMrtrCleanup']
    }

    def 'background persistence failure requeues a bounded retry and later removes expired records'() {
        given:
        long at = script.now()
        def (peer, backing) = failingPeer([expired: terminal(at - 1L)], 1)

        when:
        peer.runMrtrCleanup()

        then:
        backing.get('mrtrRequests').containsKey('expired')
        jobs().last()[0] == 60

        when:
        NOW_OVERRIDE.set({ at + 60000L })
        peer.runMrtrCleanup()

        then:
        backing.get('mrtrRequests').isEmpty()
        backing.@writes == 2
    }
}
