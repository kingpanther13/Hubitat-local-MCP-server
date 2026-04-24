package server

import support.ToolSpecBase

/**
 * Regression spec for hubitat-mcp-server.groovy::normalizeTrigger — the
 * silent-failure fix (commit 2a6da11, v0.2.12 follow-up → v0.3.x) that
 * addressed the "rule with a sunrise trigger never fires" bug.
 *
 * Root cause: the rule engine only recognised the canonical shape
 * {@code {type:'time', sunrise:true}} / {@code {type:'time', sunset:true}},
 * but {@code create_rule} / {@code update_rule} persisted whatever the
 * LLM emitted — and the LLM naturally emitted four additional shapes
 * (e.g. {@code {type:'sunrise'}}, {@code {type:'time', time:'sunrise'}}).
 * Rules saved under those shapes, never threw during save, and then
 * silently never fired — no log, no error, no hint. normalizeTrigger
 * is the server-side bridge that now coerces every accepted input
 * shape to canonical form before persistence, so the rule engine only
 * ever sees one shape.
 *
 * These tests guard the five input shapes from the original bug report
 * plus the passthrough of the canonical form. normalizeTrigger itself
 * reads no state, but we still extend {@link ToolSpecBase} for
 * consistency with the other server specs (and so future additions
 * don't have to re-wire the sandbox).
 */
class NormalizeTriggerSpec extends ToolSpecBase {

    def "converts {type: 'sunrise'} to the canonical {type: 'time', sunrise: true} shape"() {
        when:
        def out = script.normalizeTrigger([type: 'sunrise'])

        then:
        out.type == 'time'
        out.sunrise == true
        !out.containsKey('sunset')
    }

    def "converts {type: 'sunset'} to the canonical {type: 'time', sunset: true} shape"() {
        when:
        def out = script.normalizeTrigger([type: 'sunset'])

        then:
        out.type == 'time'
        out.sunset == true
        !out.containsKey('sunrise')
    }

    def "converts {type: 'sun', event: '#event'} to the canonical sunrise/sunset flag and drops the event field"() {
        when:
        def out = script.normalizeTrigger([type: 'sun', event: event])

        then:
        out.type == 'time'
        out[event] == true
        !out.containsKey('event')

        where:
        event << ['sunrise', 'sunset']
    }

    def "converts {type: 'time', time: '#event'} to the canonical sunrise/sunset flag and drops the time field"() {
        when:
        def out = script.normalizeTrigger([type: 'time', time: event])

        then:
        out.type == 'time'
        out[event] == true
        !out.containsKey('time')

        where:
        event << ['sunrise', 'sunset']
    }

    def "converts {type: 'time', sunEvent: 'sunrise', offsetMinutes: N} and renames offsetMinutes to offset"() {
        when:
        def out = script.normalizeTrigger([type: 'time', sunEvent: 'sunrise', offsetMinutes: 15])

        then:
        out.type == 'time'
        out.sunrise == true
        out.offset == 15
        !out.containsKey('sunEvent')
        !out.containsKey('offsetMinutes')
    }

    def "preserves an existing offset when normalising {type: 'time', sunEvent, offsetMinutes}"() {
        given: 'caller already set a canonical `offset` — the legacy `offsetMinutes` should not overwrite it'
        when:
        def out = script.normalizeTrigger([type: 'time', sunEvent: 'sunset', offset: -30, offsetMinutes: 99])

        then: 'canonical offset wins; offsetMinutes is dropped silently'
        out.sunset == true
        out.offset == -30
        !out.containsKey('offsetMinutes')
    }

    def "passes a canonical {type: 'time', sunrise: true} trigger through unchanged"() {
        given:
        def input = [type: 'time', sunrise: true, offset: 10]

        when:
        def out = script.normalizeTrigger(input)

        then:
        out.type == 'time'
        out.sunrise == true
        out.offset == 10
    }

    def "does not mutate an unrelated trigger type"() {
        given:
        def input = [type: 'device_event', deviceId: 42, attribute: 'switch', value: 'on']

        when:
        def out = script.normalizeTrigger(input)

        then: 'every field preserved — normalizeTrigger only rewrites time/sun shapes'
        out.type == 'device_event'
        out.deviceId == 42
        out.attribute == 'switch'
        out.value == 'on'
    }

    def "returns a fresh map — mutating the output does not affect the caller's input"() {
        given:
        def input = [type: 'sunrise']

        when:
        def out = script.normalizeTrigger(input)
        out.sunrise = 'MUTATED'

        then: 'the input map still has its original shape (no sunrise field yet)'
        input.type == 'sunrise'
        !input.containsKey('sunrise')
    }
}
