package support

import spock.lang.Specification

/**
 * Self-tests for {@link SubscriptionRecorder}. Keeps the record / fireEvent /
 * reset lifecycle pinned independently from the specs that use the recorder —
 * without this, a harness bug that dropped subscribes on reset or matched
 * the wrong source would cascade across every rule-engine integration spec
 * and be hard to localize.
 */
class SubscriptionRecorderSpec extends Specification {

    /** Tiny script stand-in — the recorder calls script."${handlerName}"(event). */
    static class FakeScript {
        List<Map> handledDevice = []
        List<Map> handledMode = []

        Object handleDeviceEvent(Map evt) {
            handledDevice << evt
            return 'device-handled'
        }

        Object handleModeEvent(Map evt) {
            handledMode << evt
            return 'mode-handled'
        }
    }

    SubscriptionRecorder recorder
    FakeScript script

    def setup() {
        recorder = new SubscriptionRecorder()
        script = new FakeScript()
    }

    def "record appends each subscription in call order"() {
        given:
        def dev1 = new Object()
        def dev2 = new Object()

        when:
        recorder.record(dev1, 'switch', 'handleDeviceEvent')
        recorder.record(dev2, 'contact', 'handleDeviceEvent')

        then:
        recorder.subscriptions.size() == 2
        recorder.subscriptions[0].source.is(dev1)
        recorder.subscriptions[1].source.is(dev2)
        recorder.subscriptions[1].attribute == 'contact'
    }

    def "fireEvent dispatches to handlers matching source + attribute by identity"() {
        given:
        def dev = new Object()
        recorder.record(dev, 'switch', 'handleDeviceEvent')

        when:
        recorder.fireEvent(script, dev, 'switch', 'on')

        then:
        script.handledDevice.size() == 1
        script.handledDevice[0].device.is(dev)
        script.handledDevice[0].name == 'switch'
        script.handledDevice[0].value == 'on'
    }

    def "fireEvent throws IllegalStateException when no subscription matches"() {
        given:
        def dev = new Object()
        def other = new Object()
        recorder.record(dev, 'switch', 'handleDeviceEvent')

        when:
        recorder.fireEvent(script, other, 'switch', 'on')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('no matching subscription')
    }

    def "fireEvent uses identity comparison, not equality — avoids stringly matches"() {
        given: 'two Maps with identical content but distinct references'
        def srcA = [id: 1]
        def srcB = [id: 1]
        recorder.record(srcA, 'switch', 'handleDeviceEvent')

        when: 'fire with the content-equal but reference-different source'
        recorder.fireEvent(script, srcB, 'switch', 'on')

        then:
        thrown(IllegalStateException)
    }

    def "fireEvent dispatches a defensive copy so handler mutations do not leak across handlers"() {
        given: 'two subscriptions on the same source+attribute, first one mutates its event'
        def dev = new Object()
        def mutatingScript = new FakeScript() {
            Object handleDeviceEvent(Map evt) {
                evt.value = 'TAMPERED'  // attempt to change what the second handler sees
                super.handleDeviceEvent(evt)
            }
        }
        recorder.record(dev, 'switch', 'handleDeviceEvent')
        recorder.record(dev, 'switch', 'handleDeviceEvent')

        when:
        recorder.fireEvent(mutatingScript, dev, 'switch', 'on')

        then: 'both handlers ran, but each saw the original value — the mutation did not escape the first copy'
        mutatingScript.handledDevice.size() == 2
        mutatingScript.handledDevice[0].value == 'TAMPERED'   // first saw its own mutation
        mutatingScript.handledDevice[1].value == 'on'         // second saw the pristine original
    }

    def "fireEvent passes overrides merged over the defaults"() {
        given:
        def dev = new Object()
        recorder.record(dev, 'switch', 'handleDeviceEvent')

        when:
        recorder.fireEvent(script, dev, 'switch', 'on', [isStateChange: true, source: 'DEVICE'])

        then:
        def evt = script.handledDevice[0]
        evt.isStateChange == true
        evt.source == 'DEVICE'
        // Defaults still present for keys overrides didn't touch
        evt.name == 'switch'
        evt.value == 'on'
    }

    def "reset clears all recorded subscriptions"() {
        given:
        recorder.record(new Object(), 'switch', 'handleDeviceEvent')
        recorder.record(new Object(), 'contact', 'handleDeviceEvent')
        assert recorder.subscriptions.size() == 2

        when:
        recorder.reset()

        then:
        recorder.subscriptions.isEmpty()
    }

    def "reset is idempotent"() {
        when:
        recorder.reset()
        recorder.reset()

        then:
        recorder.subscriptions.isEmpty()
    }
}
