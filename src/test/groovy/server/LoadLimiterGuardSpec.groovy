package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * The per-app load limiter guard. The hub refuses an app's calls once this app's busy time since
 * boot plus that of the app or device it calls (its own a second time when it calls itself) passes
 * uptime x the excessive-load threshold (stock 33 %), enforced after 15 minutes of uptime;
 * /hub/advanced/resetAppRuntimeStats zeroes every app's counter. Covers the class-memory estimate,
 * the check job that replaces it with the hub's own total and resets at 50 %, the refused-call retry
 * in executeTool, and hub_delete_logs(mode='appRuntimeStats').
 */
class LoadLimiterGuardSpec extends ToolSpecBase {

    static final String RESET = '/hub/advanced/resetAppRuntimeStats'
    static final String THRESHOLD = '/hub/advanced/getExcessiveLoadThreshold'
    static final long NOW = 1234567890000L
    // uptime 10000 s x 33 % = 3,300,000 ms; the check fires at 50 % = 1,650,000 ms
    static final long LIMIT = 3_300_000L
    static final long HALF = 1_650_000L

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hub = new TestHub(uptime: 10000G)
        hubGet.register(THRESHOLD) { p -> '33' }
    }

    def cleanup() {
        script.callerClient = null
        script.requestStartedAt = null
    }

    private void busy(long ms) { script.LOAD_GUARD.busyMs = ms }

    private void seeded() { script.LOAD_GUARD.seeded = true }

    private List handlers() { runInCalls.collect { it[1] } }

    private int resets() { hubGet.calls.count { it.path == RESET } }

    private List captureLogs() {
        def entries = []
        script.metaClass.mcpLog = { String level, String component, String message,
                                    String ruleId = null, Map extra = null ->
            entries << [level: level, component: component, message: message]
        }
        return entries
    }

    private void logsJsonWithOwnTotal(Closure<Long> total) {
        hubGet.register('/logs/json') { p ->
            JsonOutput.toJson([uptime: '2h', jobs: [],
                               appStats: [[id: 7, name: 'Other', count: 3, total: 900, pct: 0.1],
                                          [id: 1, name: 'MCP', count: 40, total: total.call(), pct: 2.0]]])
        }
    }

    private void logsJsonWithoutOwnRow() {
        hubGet.register('/logs/json') { p ->
            JsonOutput.toJson([uptime: '2h', jobs: [], appStats: [[id: 7, name: 'Other', count: 3, total: 900, pct: 0.1]]])
        }
    }

    // ---- per request: the estimate and the check job ----

    def 'below 50 % of the limit only the seed job is scheduled'() {
        given:
        busy(HALF - 1L)

        when:
        2.times { script._loadGuardBeforeRequest() }

        then:
        handlers() == ['_seedLoadGuardJob']
    }

    def 'at 50 % of the limit one check job is scheduled, and a second request does not schedule another'() {
        given:
        seeded()
        busy(HALF)

        when:
        2.times { script._loadGuardBeforeRequest() }

        then:
        handlers() == ['_loadGuardCheckJob']
        runInCalls.every { it[0] == 0 }
    }

    def 'under 15 minutes of uptime nothing is checked, however high the estimate'() {
        given:
        seeded()
        sharedLocation.hub = new TestHub(uptime: 899G)
        busy(10_000_000L)

        when:
        script._loadGuardBeforeRequest()

        then:
        runInCalls.isEmpty()
    }

    def 'uptime going down zeroes the estimate'() {
        given:
        script._loadGuardBeforeRequest()
        busy(1_000_000L)
        sharedLocation.hub = new TestHub(uptime: 120G)

        when:
        script._loadGuardBeforeRequest()

        then:
        script._loadGuardBusyMs() == 0L
    }

    def 'an unreadable hub uptime leaves the guard idle'() {
        given:
        sharedLocation.hub = new TestHub()
        busy(10_000_000L)

        when:
        script._loadGuardBeforeRequest()

        then:
        runInCalls.isEmpty()
    }

    def 'a scheduler that throws frees the latch, so the next request schedules again'() {
        given:
        seeded()
        busy(HALF)
        def logs = captureLogs()
        RUN_IN_OVERRIDE.set({ List args -> throw new RuntimeException('scheduler down') })

        when:
        script._loadGuardBeforeRequest()

        then:
        !script.LOAD_GUARD.containsKey('checkPending')
        logs.any { it.level == 'warn' && it.message.contains('_loadGuardCheckJob') && it.message.contains('scheduler down') }

        when:
        RUN_IN_OVERRIDE.set(null)
        script._loadGuardBeforeRequest()

        then:
        handlers() == ['_loadGuardCheckJob']
    }

    @Unroll
    def 'a check latch taken #ageMs ms ago is #verdict'() {
        given:
        seeded()
        busy(HALF)
        script.LOAD_GUARD.checkPending = NOW - ageMs

        when:
        script._loadGuardBeforeRequest()

        then:
        handlers() == expected

        where:
        ageMs   | verdict                     || expected
        1_000L  | 'held'                      || []
        61_000L | 'stale and taken over'      || ['_loadGuardCheckJob']
    }

    def 'the check job lowers the estimate to the hub total when that total is under 50 %, and resets nothing'() {
        given:
        script._loadGuardBeforeRequest()
        busy(HALF + 500_000L)
        script.LOAD_GUARD.checkPending = NOW
        logsJsonWithOwnTotal { 100_000L }
        hubGet.register(RESET) { p -> 'ok' }
        def logs = captureLogs()

        when:
        script._loadGuardCheckJob()

        then:
        script._loadGuardBusyMs() == 100_000L
        resets() == 0
        !script.LOAD_GUARD.containsKey('checkPending')
        logs.findAll { it.level == 'debug' && it.message.startsWith('load guard check:') }*.message == [
            "load guard check: hub total 100000 ms of ${LIMIT} ms limit (estimate was ${HALF + 500_000L} ms); reset: no".toString()]
    }

    def 'with a LAN time budget and a retained Logs fetch failure, the check job still reads the page inline'() {
        given:
        settingsMap.lanBudgetMs = 6000L
        script.LOGS_JSON_SNAPSHOT.fetchError = [at: NOW, message: 'an earlier background fetch failed']
        busy(HALF + 5_000L)
        script.LOAD_GUARD.checkPending = NOW
        logsJsonWithOwnTotal { 100_000L }

        when:
        script._loadGuardCheckJob()

        then:
        script._loadGuardBusyMs() == 100_000L
        !script.LOAD_GUARD.containsKey('resetFailedAt')
        resets() == 0
    }

    def 'after a check that found the hub total under 50 %, the next check waits 60 s'() {
        given:
        seeded()
        script.LOAD_GUARD.checkPending = NOW
        logsJsonWithOwnTotal { HALF - 1_000L }
        script._loadGuardCheckJob()
        runInCalls.clear()
        busy(HALF + 5_000L)

        when: 'the estimate is back over the line within the minute'
        script._loadGuardBeforeRequest()

        then:
        handlers() == []

        when: 'a minute later'
        NOW_OVERRIDE.set({ -> NOW + 60_001L })
        script._loadGuardBeforeRequest()

        then:
        handlers() == ['_loadGuardCheckJob']
    }

    def 'the check job resets when the hub total is at 50 %, zeroes the estimate and logs one warning'() {
        given:
        script._loadGuardBeforeRequest()
        busy(10L)
        script.LOAD_GUARD.checkPending = NOW
        logsJsonWithOwnTotal { HALF }
        hubGet.register(RESET) { p -> 'ok' }
        def logs = captureLogs()

        when:
        script._loadGuardCheckJob()

        then:
        resets() == 1
        script._loadGuardBusyMs() == 0L
        !script.LOAD_GUARD.containsKey('checkPending')
        logs.findAll { it.level == 'warn' }*.message == [
            "The hub's app runtime statistics were reset before the per-app load limiter tripped: busy ${HALF} ms of ${LIMIT} ms".toString()]
        logs.findAll { it.level == 'debug' && it.message.startsWith('load guard check:') }*.message == [
            "load guard check: hub total ${HALF} ms of ${LIMIT} ms limit (estimate was ${HALF} ms); reset: yes".toString()]
    }

    def 'a refused reset logs once per failure streak, backs off five minutes, and an ok reset ends the streak'() {
        given:
        seeded()
        script._loadGuardBeforeRequest()
        logsJsonWithOwnTotal { HALF }
        String answer = 'denied'
        hubGet.register(RESET) { p -> answer }
        def logs = captureLogs()

        when: 'two refused resets'
        2.times { script._loadGuardCheckJob() }
        runInCalls.clear()
        busy(HALF)
        script._loadGuardBeforeRequest()

        then: 'one warning, and no check inside the backoff'
        logs.count { it.message.contains('did not reset') } == 1
        runInCalls.isEmpty()

        when: 'the backoff has passed'
        NOW_OVERRIDE.set({ -> NOW + 300_001L })
        script._loadGuardBeforeRequest()

        then:
        handlers() == ['_loadGuardCheckJob']

        when: 'an ok reset, then another refusal'
        answer = 'ok'
        script._loadGuardCheckJob()
        answer = 'denied'
        script._loadGuardCheckJob()

        then: 'the new streak is reported again'
        logs.count { it.message.contains('did not reset') } == 2
    }

    def 'a check job that cannot read the Logs page backs off instead of resetting'() {
        given:
        script._loadGuardBeforeRequest()
        busy(HALF)
        hubGet.register('/logs/json') { p -> throw new RuntimeException('read timed out') }
        def logs = captureLogs()

        when:
        script._loadGuardCheckJob()

        then:
        resets() == 0
        script.LOAD_GUARD.resetFailedAt == NOW
        logs.any { it.level == 'warn' && it.message.contains('could not read') }
    }

    @Unroll
    def 'the threshold is the hub value when readable; an unreadable one is 33 and read again next time: #label'() {
        given:
        if (answer != null) hubGet.register(THRESHOLD) { p -> answer }
        else hubGet.register(THRESHOLD) { p -> throw new RuntimeException('no answer') }
        def logs = captureLogs()

        when:
        def first = script._loadGuardThresholdPct()
        def second = script._loadGuardThresholdPct()

        then:
        first == expected
        second == expected
        hubGet.calls.count { it.path == THRESHOLD } == reads
        logs.count { it.level == 'warn' && it.message.contains('threshold is unreadable') } == warnings

        where:
        label          | answer || expected | reads | warnings
        'readable 50'  | '50'   || 50       | 1     | 0
        'unreadable'   | null   || 33       | 2     | 2
        'out of range' | '0'    || 33       | 2     | 2
        'not a number' | 'oops' || 33       | 2     | 2
    }

    def 'the seed job reads the threshold, seeds from this app App Stats row, keeps what accrued, and is done'() {
        given:
        hubGet.register(THRESHOLD) { p -> '40' }
        logsJsonWithOwnTotal { 5000L }
        script.LOAD_GUARD.seedScheduled = NOW

        when:
        busy(100L)
        script._seedLoadGuardJob()

        then:
        script.LOAD_GUARD.thresholdPct == 40
        script._loadGuardBusyMs() == 5000L
        script.LOAD_GUARD.seeded == true
        !script.LOAD_GUARD.containsKey('seedScheduled')

        when: 'later requests'
        script._loadGuardBeforeRequest()

        then: 'no second seed'
        !handlers().contains('_seedLoadGuardJob')
    }

    def 'a seed job that cannot read the Logs page warns, frees the latch and backs off five minutes'() {
        given:
        hubGet.register('/logs/json') { p -> throw new RuntimeException('read timed out') }
        script.LOAD_GUARD.seedScheduled = NOW
        def logs = captureLogs()

        when:
        script._seedLoadGuardJob()
        script._loadGuardBeforeRequest()

        then: 'no reseed inside the backoff'
        logs.any { it.level == 'warn' && it.message.contains('could not be seeded') }
        script.LOAD_GUARD.seeded != true
        !script.LOAD_GUARD.containsKey('seedScheduled')
        script.LOAD_GUARD.seedFailedAt == NOW
        runInCalls.isEmpty()

        when: 'the backoff has passed'
        NOW_OVERRIDE.set({ -> NOW + 300_001L })
        script._loadGuardBeforeRequest()

        then:
        handlers() == ['_seedLoadGuardJob']
    }

    def 'every published Logs page fetch raises the estimate to this app App Stats total, never lowers it'() {
        given:
        long total = 7000L
        logsJsonWithOwnTotal { total }
        busy(1000L)

        when:
        script._logsJsonFetchAndPublish()

        then: 'a higher hub total raises the estimate'
        script._loadGuardBusyMs() == 7000L

        when: 'a later fetch reports less than the estimate'
        total = 500L
        script._logsJsonFetchAndPublish()

        then:
        script._loadGuardBusyMs() == 7000L
    }

    def 'a fetch fenced off by a reset (an old fetchId) does not raise the estimate'() {
        given:
        logsJsonWithOwnTotal { 9000L }
        long staleId = script._logsJsonFetchId()
        script._logsJsonInvalidate()
        busy(0L)

        when:
        script._logsJsonFetchAndPublish(staleId)

        then:
        script._loadGuardBusyMs() == 0L
    }

    def 'handleMcpRequest adds the request duration to the estimate'() {
        given:
        long clock = 1_000_000L
        NOW_OVERRIDE.set({ -> clock += 25L; clock })
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'ping'])

        when:
        script.handleMcpRequest()

        then:
        script._loadGuardBusyMs() > 0L
    }

    def 'a scheduled handler adds its wall time to the estimate and keeps its result'() {
        given:
        long clock = 1_000_000L
        NOW_OVERRIDE.set({ -> clock += 40L; clock })
        logsJsonWithOwnTotal { 0L }

        when:
        def timed = script._loadGuardTimed { 'body ran' }
        long afterTimed = script._loadGuardBusyMs()
        script.runLogsJsonFetch([:])

        then:
        timed == 'body ran'
        afterTimed > 0L
        script._loadGuardBusyMs() > afterTimed
    }

    // ---- refused-call retry in executeTool ----

    def 'a read refused by the limiter (thrown) is reset, logged and repeated once'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        def logs = captureLogs()
        int calls = 0
        script.metaClass.toolGetHsmStatus = { ->
            calls++
            if (calls == 1) throw new RuntimeException('App 1 generates excessive hub load')
            return [success: true, status: 'disarmed']
        }

        when:
        def result = script.executeTool('hub_get_hsm_status', [:])

        then:
        result == [success: true, status: 'disarmed']
        calls == 2
        resets() == 1
        logs.any { it.level == 'warn' && it.message.contains('refused hub_get_hsm_status') && it.message.contains('were reset') }
    }

    def 'a read refused by the limiter (envelope) is reset and repeated once'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        int calls = 0
        script.metaClass.toolGetHsmStatus = { ->
            calls++
            calls == 1 ? [success: false, error: 'RMUtils.sendAction failed: App 1 generates excessive hub load'] : [success: true]
        }

        when:
        def result = script.executeTool('hub_get_hsm_status', [:])

        then:
        result == [success: true]
        calls == 2
        resets() == 1
    }

    def 'a retry-safe write refused once is reset and repeated'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        int calls = 0
        script.metaClass.toolSetRulePaused = { args ->
            calls++
            calls == 1 ? [success: false, error: 'App 1 generates excessive hub load'] : [success: true, paused: true]
        }

        when:
        def result = script.executeTool('hub_set_rule_paused', [ruleId: 5, paused: true])

        then:
        result == [success: true, paused: true]
        calls == 2
        resets() == 1
    }

    def 'a read refused through its gateway is reset and repeated once'() {
        given:
        settingsMap.useGateways = true
        hubGet.register(RESET) { p -> 'ok' }
        int calls = 0
        script.metaClass.toolCheckRuleHealth = { args ->
            calls++
            if (calls == 1) throw new RuntimeException('App 1 generates excessive hub load')
            return [success: true, healthy: true]
        }

        when:
        def result = script.executeTool('hub_read_rules', [tool: 'hub_get_rule_health', args: [appId: 5]])

        then:
        result == [success: true, healthy: true]
        calls == 2
        resets() == 1
    }

    def 'two refusals return the error with the reset instruction appended'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        int calls = 0
        script.metaClass.toolGetHsmStatus = { -> calls++; [success: false, error: 'App 1 generates excessive hub load'] }

        when:
        def result = script.executeTool('hub_get_hsm_status', [:])

        then:
        calls == 2
        resets() == 1
        result.success == false
        result.error == "App 1 generates excessive hub load ${script.LOAD_LIMITER_TWICE_HINT}".toString()
        result.error.contains("hub_manage_logs(tool='hub_delete_logs', args={mode:'appRuntimeStats', confirm:true})")
    }

    def 'two thrown refusals rethrow with the reset instruction appended'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        int calls = 0
        script.metaClass.toolGetHsmStatus = { -> calls++; throw new RuntimeException('App 1 generates excessive hub load') }

        when:
        script.executeTool('hub_get_hsm_status', [:])

        then:
        def e = thrown(RuntimeException)
        e.message == "App 1 generates excessive hub load ${script.LOAD_LIMITER_TWICE_HINT}".toString()
        calls == 2
        resets() == 1
    }

    def 'an unrelated error is neither reset nor retried'() {
        given:
        int calls = 0
        script.metaClass.toolGetHsmStatus = { -> calls++; throw new RuntimeException('boom') }

        when:
        script.executeTool('hub_get_hsm_status', [:])

        then:
        def e = thrown(RuntimeException)
        e.message == 'boom'
        calls == 1
        resets() == 0
    }

    def 'a refused write that is not retry-safe is reset but not repeated (envelope)'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        int calls = 0
        script.metaClass.toolSendCommand = { a, b, c, d, e, f ->
            calls++
            [success: false, error: 'App 1 generates excessive hub load']
        }

        when:
        def result = script.executeTool('hub_call_device_command', [deviceId: '5', command: 'on'])

        then:
        calls == 1
        resets() == 1
        result.error.contains('not repeated automatically')
    }

    def 'a refused write that is not retry-safe is reset but not repeated (thrown)'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        int calls = 0
        script.metaClass.toolSendCommand = { a, b, c, d, e, f ->
            calls++
            throw new RuntimeException('App 1 generates excessive hub load')
        }

        when:
        script.executeTool('hub_call_device_command', [deviceId: '5', command: 'on'])

        then:
        def e = thrown(RuntimeException)
        e.message.startsWith('App 1 generates excessive hub load')
        e.message.contains("The hub's app runtime statistics were reset.")
        e.message.contains('not repeated automatically')
        calls == 1
        resets() == 1
    }

    def 'a reset that does not answer ok stops the retry and says so'() {
        given:
        hubGet.register(RESET) { p -> 'denied' }
        def logs = captureLogs()
        int calls = 0
        script.metaClass.toolGetHsmStatus = { -> calls++; [success: false, error: 'App 1 generates excessive hub load'] }

        when:
        def result = script.executeTool('hub_get_hsm_status', [:])

        then:
        calls == 1
        resets() == 1
        result.error.contains("Resetting the hub's app runtime statistics failed (denied).")
        !result.error.contains(script.LOAD_LIMITER_TWICE_HINT)
        logs.any { it.level == 'warn' && it.message.contains('failed (denied)') }
    }

    // ---- hub_delete_logs(mode='appRuntimeStats') ----

    def 'appRuntimeStats needs confirm=true and resets nothing without it'() {
        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats'])

        then:
        result.success == false
        result.error.contains('confirm=true')
        resets() == 0
    }

    def 'appRuntimeStats resets, reads this app row before and after, verifies the drop and logs the outcome'() {
        given:
        long total = 5000L
        logsJsonWithOwnTotal { total }
        hubGet.register(RESET) { p -> total = 0L; 'ok' }
        busy(4000L)
        def logs = captureLogs()

        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats', confirm: true])

        then:
        result.success == true
        result.before == [totalMs: 5000L, count: 40L]
        result.after == [totalMs: 0L, count: 40L]
        result.verified == true
        result.uptimeSeconds == 10000L
        result.thresholdPct == 33
        result.limitMs == LIMIT
        !result.containsKey('note')
        script._loadGuardBusyMs() == 0L
        logs.any { it.level == 'warn' && it.message.contains('were reset (hub_delete_logs)') }
    }

    def 'appRuntimeStats counts a missing row after the reset as 0'() {
        given:
        boolean wasReset = false
        hubGet.register('/logs/json') { p ->
            JsonOutput.toJson([uptime: '2h', jobs: [],
                               appStats: wasReset ? [] : [[id: 1, name: 'MCP', count: 40, total: 5000, pct: 2.0]]])
        }
        hubGet.register(RESET) { p -> wasReset = true; 'ok' }

        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats', confirm: true])

        then:
        result.success == true
        result.after == [totalMs: 0L, count: 0L]
        result.verified == true
    }

    def 'appRuntimeStats reports a reset that did not take'() {
        given:
        logsJsonWithOwnTotal { 5000L }
        hubGet.register(RESET) { p -> 'ok' }

        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error.startsWith('The reset did not take')
        result.verified == false
    }

    def 'appRuntimeStats says so when this app row cannot be read'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        hubGet.register('/logs/json') { p -> throw new RuntimeException('read timed out') }

        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats', confirm: true])

        then:
        result.success == true
        result.verified == false
        result.before == null
        result.after == null
        result.note.contains('could not be read')
    }

    def 'appRuntimeStats reports a refused reset as an error and logs it'() {
        given:
        hubGet.register(RESET) { p -> 'denied' }
        logsJsonWithOwnTotal { 5000L }
        def logs = captureLogs()

        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats', confirm: true])

        then:
        result.success == false
        result.error.contains('denied')
        result.verified == false
        logs.any { it.level == 'warn' && it.message.contains('failed (hub_delete_logs): denied') }
    }

    // ---- review round: the off switch, latch ownership, fresh threshold, missing row, time budget ----

    @Unroll
    def 'with #label nothing is scheduled, however high the estimate'() {
        given:
        settingsMap[setting] = false
        busy(HALF * 2)

        when:
        script._loadGuardBeforeRequest()

        then:
        handlers() == []

        where:
        label                       | setting
        'the automatic reset off'   | 'autoResetAppRuntimeStats'
        'the Write master off'      | 'enableWrite'
    }

    def 'a check job that runs after the automatic reset was turned off resets nothing'() {
        given:
        settingsMap.autoResetAppRuntimeStats = false
        script.LOAD_GUARD.checkPending = NOW
        logsJsonWithOwnTotal { HALF * 2 }
        hubGet.register(RESET) { p -> 'ok' }

        when:
        script._loadGuardCheckJob([stamp: NOW])

        then:
        resets() == 0
        !script.LOAD_GUARD.containsKey('checkPending')
    }

    @Unroll
    def 'a refused call is not reset or repeated when #label, and the error names the manual reset'() {
        given:
        if (setting) settingsMap[setting] = false
        if (client) {
            script.callerClient = [id: 'c1', label: 'phone']
            settingsMap['clientWrite_c1'] = false
            settingsMap['clientTools_c1'] = ['hub_get_hsm_status']
        }
        hubGet.register(RESET) { p -> 'ok' }
        int calls = 0
        script.metaClass.toolGetHsmStatus = { -> calls++; return [success: false, error: 'App 1 generates excessive hub load'] }

        when:
        def result = script.executeTool('hub_get_hsm_status', [:])

        then:
        calls == 1
        resets() == 0
        result.success == false
        result.error.contains(reason)
        result.error.contains("hub_delete_logs")

        where:
        label                              | setting                    | client || reason
        'the automatic reset is off'       | 'autoResetAppRuntimeStats' | false  || 'automatic reset is off'
        'the calling client may not write' | null                       | true   || 'this client may not write'
    }

    def 'a refused read is reset but not repeated once half the request time budget is spent'() {
        given:
        script.metaClass._isCloudRequest = { -> true }
        script.requestStartedAt = NOW - 4_000L   // relay budget 6000 ms: past half of it
        hubGet.register(RESET) { p -> 'ok' }
        int calls = 0
        script.metaClass.toolGetHsmStatus = { -> calls++; return [success: false, error: 'App 1 generates excessive hub load'] }

        when:
        def result = script.executeTool('hub_get_hsm_status', [:])

        then:
        calls == 1
        resets() == 1
        result.error.contains('not repeated within this request')
    }

    def 'a job frees only its own latch, never one a later job took over'() {
        given:
        script.LOAD_GUARD.checkPending = NOW + 61_000L   // a newer job's stamp
        logsJsonWithOwnTotal { 100L }

        when:
        script._loadGuardCheckJob([stamp: NOW])

        then:
        script.LOAD_GUARD.checkPending == NOW + 61_000L

        when:
        script._loadGuardCheckJob([stamp: NOW + 61_000L])

        then:
        !script.LOAD_GUARD.containsKey('checkPending')
    }

    def 'the scheduler hands the job its latch stamp'() {
        given:
        seeded()
        busy(HALF)

        when:
        script._loadGuardBeforeRequest()

        then:
        runInCalls.find { it[1] == '_loadGuardCheckJob' }[2] == [data: [stamp: NOW]]
        script.LOAD_GUARD.checkPending == NOW
    }

    def 'each check reads the threshold again, so a changed hub setting is used'() {
        given:
        script.LOAD_GUARD.thresholdPct = 33
        script.LOAD_GUARD.lastUptimeS = 10000L
        hubGet.register(THRESHOLD) { p -> '20' }   // 10000 s x 20 % = 2,000,000 ms; 50 % = 1,000,000 ms
        logsJsonWithOwnTotal { 1_100_000L }
        hubGet.register(RESET) { p -> 'ok' }

        when:
        script._loadGuardCheckJob()

        then:
        script.LOAD_GUARD.thresholdPct == 20
        resets() == 1
    }

    def 'a missing App Stats row counts as 0 for the seed and the check, with no backoff'() {
        given:
        logsJsonWithoutOwnRow()
        busy(HALF + 1_000L)

        when:
        script._seedLoadGuardJob()
        script._loadGuardCheckJob()

        then:
        script.LOAD_GUARD.seeded == true
        !script.LOAD_GUARD.containsKey('seedFailedAt')
        !script.LOAD_GUARD.containsKey('resetFailedAt')
        script._loadGuardBusyMs() == 0L
        resets() == 0
    }

    def 'appRuntimeStats reads before and after the reset within one observation window'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        List windows = []
        script.metaClass._logsJsonOwnAppStat = { boolean fresh, Map args ->
            windows << args?.__reqT0
            return [totalMs: windows.size() == 1 ? 5000L : 0L, count: 1L]
        }

        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats', confirm: true])

        then:
        result.verified == true
        windows.size() == 2
        windows[0] != null
        windows[0] == windows[1]
    }
}
