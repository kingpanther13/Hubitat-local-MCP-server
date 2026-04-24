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

    def "fireEvent dispatches a defensive copy so one handler's mutation does not leak into the next handler's event"() {
        given: 'two distinct handlers on the same source+attribute — first mutates, second observes'
        def dev = new Object()
        def twoHandlerScript = new TwoHandlerScript()
        recorder.record(dev, 'switch', 'mutatingHandler')
        recorder.record(dev, 'switch', 'observingHandler')

        when:
        recorder.fireEvent(twoHandlerScript, dev, 'switch', 'on')

        then: 'observing handler got a fresh copy where value is still "on", not "TAMPERED"'
        twoHandlerScript.mutatingReceived.value == 'TAMPERED'   // first handler mutated its own copy
        twoHandlerScript.observingReceived.value == 'on'        // second handler's copy was not affected

        and: 'the two handlers received distinct Map instances (defensive-copy proof)'
        !twoHandlerScript.mutatingReceived.is(twoHandlerScript.observingReceived)
    }

    /**
     * Two-handler script — first mutates its event, second only observes. Lets a spec
     * verify that the recorder's defensive copy isolates handler-side mutations per
     * dispatch. A self-mutating single handler can't prove isolation because the
     * second dispatch's fresh copy also gets mutated by the same handler code.
     */
    static class TwoHandlerScript {
        Map mutatingReceived = null
        Map observingReceived = null

        Object mutatingHandler(Map evt) {
            mutatingReceived = evt
            evt.value = 'TAMPERED'
            return null
        }

        Object observingHandler(Map evt) {
            observingReceived = evt
            return null
        }
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
