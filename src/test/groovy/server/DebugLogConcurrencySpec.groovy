package server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class DebugLogConcurrencySpec extends ToolSpecBase {
    @Shared private TestChildApp loggingApp = new TestChildApp(id: 402L)

    def setupSpec() {
        appExecutor.getApp() >> loggingApp
    }

    def "concurrent executions retain every admitted entry within the ring capacity"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        script.initDebugLogs()
        def first = newCompiledScriptInstance()
        def second = newCompiledScriptInstance()
        def start = new CountDownLatch(1)
        def done = new CountDownLatch(2)
        def failures = Collections.synchronizedList([])
        def workers = [first, second].withIndex().collect { peer, index ->
            new Thread({
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError('start timed out')
                    (1..40).each { n -> peer.mcpLog('info', 'concurrency', "${index}:${n}") }
                } catch (Throwable e) {
                    failures << e
                } finally {
                    done.countDown()
                }
            } as Runnable)
        }

        when:
        workers.each { it.start() }
        start.countDown()
        boolean finished = done.await(10, TimeUnit.SECONDS)

        then:
        finished
        failures.empty
        def entries = script.getDebugLogEntries()
        entries.size() == 80
        entries*.message.toSet() == (0..1).collectMany { i -> (1..40).collect { n -> "${i}:${n}".toString() } }.toSet()

        cleanup:
        start.countDown()
        workers.each { it.join(1000) }
    }
}
