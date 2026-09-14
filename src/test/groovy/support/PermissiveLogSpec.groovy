package support

import spock.lang.Specification

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PermissiveLogSpec extends Specification {
    def "concurrent logging retains every message without interrupting callers"() {
        given:
        def log = new PermissiveLog()
        def levels = ['error', 'warn', 'info', 'debug', 'trace']
        def pool = Executors.newFixedThreadPool(4)
        def start = new CyclicBarrier(4)

        when:
        def futures = (0..<4).collect { worker ->
            pool.submit({
                start.await(10, TimeUnit.SECONDS)
                (0..<250).each { index ->
                    String message = "${worker}:${index}"
                    def level = levels[index % levels.size()]
                    if (index % 2 == 0) {
                        log."${level}"(message)
                    } else {
                        log."${level}"(message, new IOException('test exception'))
                    }
                }
            } as Runnable)
        }
        futures.each { it.get(10, TimeUnit.SECONDS) }

        then:
        log.messages.size() == 1000
        log.messages.toSet() == (0..<4).collectMany { worker ->
            (0..<250).collect { index -> "${levels[index % levels.size()]}:${worker}:${index}".toString() }
        }.toSet()

        cleanup:
        pool.shutdownNow()
        pool.awaitTermination(10, TimeUnit.SECONDS)
    }
}
