package server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import support.TestChildApp
import support.ToolSpecBase

class DebugLogConcurrencySpec extends ToolSpecBase {
    def "concurrent executions retain every admitted entry within the ring capacity"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        script.metaClass.getApp = { -> new TestChildApp(id: 402L) }
        script.initDebugLogs()
        def first = newCompiledScriptInstance()
        def second = newCompiledScriptInstance()
        [first, second].each { peer -> peer.metaClass.getApp = { -> new TestChildApp(id: 402L) } }
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
