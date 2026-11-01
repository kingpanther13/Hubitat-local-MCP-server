package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * The per-app load limiter guard. The hub refuses an app's calls once its busy time since boot
 * passes uptime x the excessive-load threshold (stock 33 %), enforced after 15 minutes of uptime;
 * /hub/advanced/resetAppRuntimeStats zeroes every app's counter. Covers the class-memory estimate
 * and its early reset, the refused-call retry in executeTool, and hub_delete_logs(mode='appRuntimeStats').
 */
class LoadLimiterGuardSpec extends ToolSpecBase {

    static final String RESET = '/hub/advanced/resetAppRuntimeStats'
    static final String THRESHOLD = '/hub/advanced/getExcessiveLoadThreshold'

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        script.LOAD_GUARD.clear()
        sharedLocation.hub = new TestHub(uptime: 10000G)
    }

    private void busy(long ms) { script.LOAD_GUARD.busyMs = ms }

    private List handlers() { runInCalls.collect { it[1] } }

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

    // ---- the guard ----

    def 'below 80 % of the limit only the one seed job is scheduled'() {
        given: 'limit = 10000 s x 33 % = 3,300,000 ms; 80 % = 2,640,000 ms'
        busy(2_639_999L)

        when:
        2.times { script._loadGuardBeforeRequest() }

        then:
        handlers() == ['_seedLoadGuardJob']
    }

    def 'at 80 % of the limit one reset job is scheduled, and a second request does not schedule another'() {
        given:
        busy(2_640_000L)

        when:
        2.times { script._loadGuardBeforeRequest() }

        then:
        handlers() == ['_seedLoadGuardJob', '_resetAppRuntimeStatsJob']
        runInCalls.every { it[0] == 0 }
    }

    def 'under 15 minutes of uptime nothing is reset, however high the estimate'() {
        given:
        sharedLocation.hub = new TestHub(uptime: 899G)
        busy(10_000_000L)

        when:
        script._loadGuardBeforeRequest()

        then:
        handlers() == ['_seedLoadGuardJob']
    }

    def 'uptime going down (a reboot) zeroes the estimate'() {
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

    def 'a successful reset job zeroes the estimate, frees the latch and logs one warning'() {
        given:
        script._loadGuardBeforeRequest()
        busy(2_700_000L)
        script.LOAD_GUARD.resetPending = true
        hubGet.register(RESET) { p -> 'ok' }
        def logs = captureLogs()

        when:
        script._resetAppRuntimeStatsJob()

        then:
        script._loadGuardBusyMs() == 0L
        !script.LOAD_GUARD.containsKey('resetPending')
        logs.findAll { it.level == 'warn' }*.message == [
            "The hub's app runtime statistics were reset before the per-app load limiter tripped: busy 2700000 ms of 3300000 ms"]
    }

    def 'a refused reset frees the latch, logs once, and waits before scheduling another'() {
        given:
        busy(2_700_000L)
        hubGet.register(RESET) { p -> 'denied' }
        def logs = captureLogs()

        when:
        script._loadGuardBeforeRequest()
        script._resetAppRuntimeStatsJob()
        script._resetAppRuntimeStatsJob()
        runInCalls.clear()
        script._loadGuardBeforeRequest()

        then:
        !script.LOAD_GUARD.containsKey('resetPending')
        script._loadGuardBusyMs() == 2_700_000L
        logs.count { it.level == 'warn' && it.message.contains('did not reset') } == 1
        runInCalls.isEmpty()
    }

    @Unroll
    def 'the threshold is the hub value when readable, else 33: #label'() {
        given:
        if (answer != null) hubGet.register(THRESHOLD) { p -> answer }

        expect:
        script._loadGuardThresholdPct() == expected
        script._loadGuardLimitMs(10000L) == 10000L * expected * 10L

        where:
        label           | answer  || expected
        'readable 50'   | '50'    || 50
        'unreadable'    | null    || 33
        'out of range'  | '0'     || 33
        'not a number'  | 'oops'  || 33
    }

    def 'the seed job reads the threshold and seeds the estimate from this app App Stats row, keeping what accrued'() {
        given:
        hubGet.register(THRESHOLD) { p -> '40' }
        logsJsonWithOwnTotal { 5000L }

        when:
        busy(100L)
        script._seedLoadGuardJob()

        then:
        script.LOAD_GUARD.thresholdPct == 40
        script._loadGuardBusyMs() == 5000L

        when: 'more accrued since the read than the hub had counted'
        busy(9000L)
        script._seedLoadGuardJob()

        then:
        script._loadGuardBusyMs() == 9000L
    }

    def 'every Logs page fetch raises the estimate to this app App Stats total, never lowers it'() {
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

    // ---- refused-call retry in executeTool ----

    def 'a read refused by the limiter (thrown) is reset and repeated once'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
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
        hubGet.calls.count { it.path == RESET } == 1
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
    }

    def 'two refusals return the error with the reset instruction appended'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        script.metaClass.toolGetHsmStatus = { -> [success: false, error: 'App 1 generates excessive hub load'] }

        when:
        def result = script.executeTool('hub_get_hsm_status', [:])

        then:
        result.success == false
        result.error == "App 1 generates excessive hub load ${script.LOAD_LIMITER_TWICE_HINT}"
        result.error.contains("hub_manage_logs(tool='hub_delete_logs', args={mode:'appRuntimeStats', confirm:true})")
    }

    def 'two thrown refusals rethrow with the reset instruction appended'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        script.metaClass.toolGetHsmStatus = { -> throw new RuntimeException('App 1 generates excessive hub load') }

        when:
        script.executeTool('hub_get_hsm_status', [:])

        then:
        def e = thrown(RuntimeException)
        e.message == "App 1 generates excessive hub load ${script.LOAD_LIMITER_TWICE_HINT}"
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
        !hubGet.calls.any { it.path == RESET }
    }

    def 'a refused write that is not retry-safe is reset but not repeated'() {
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
        hubGet.calls.count { it.path == RESET } == 1
        result.error.contains('not repeated automatically')
    }

    // ---- hub_delete_logs(mode='appRuntimeStats') ----

    def 'appRuntimeStats needs confirm=true and resets nothing without it'() {
        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats'])

        then:
        result.success == false
        result.error.contains('confirm=true')
        !hubGet.calls.any { it.path == RESET }
    }

    def 'appRuntimeStats resets, reads this app row before and after, and verifies the drop'() {
        given:
        hubGet.register(RESET) { p -> 'ok' }
        long total = 5000L
        logsJsonWithOwnTotal { total }
        hubGet.register(RESET) { p -> total = 0L; 'ok' }
        busy(4000L)

        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats', confirm: true])

        then:
        result.success == true
        result.before == [totalMs: 5000L, count: 40L]
        result.after == [totalMs: 0L, count: 40L]
        result.verified == true
        result.uptimeSeconds == 10000L
        result.thresholdPct == 33
        result.limitMs == 3_300_000L
        !result.containsKey('note')
        script._loadGuardBusyMs() == 0L
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

    def 'appRuntimeStats reports a refused reset as an error'() {
        given:
        hubGet.register(RESET) { p -> 'denied' }
        logsJsonWithOwnTotal { 5000L }

        when:
        def result = script.toolDeleteLogs([mode: 'appRuntimeStats', confirm: true])

        then:
        result.success == false
        result.error.contains('denied')
        result.verified == false
    }
}
