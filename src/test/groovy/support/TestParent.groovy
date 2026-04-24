package support

/**
 * Common base for rule-engine spec parent stubs. The rule engine reads
 * {@code parent.findDevice(id)} during subscribeToTriggers() and action
 * dispatch, and {@code parent?.settings?.loopGuardMax} in executeRule() —
 * the null-safe chain short-circuits null receivers but throws
 * MissingPropertyException on a non-null receiver without the key, so
 * {@code settings} must exist even when empty.
 *
 * Specs that need device-finder + settings bag can use this base
 * directly; specs with additional lookup responsibilities (variables,
 * counters, etc.) extend it and add the extra fields they need.
 *
 * Device lookup matches the rule engine's {@code (id as Long)} coercion,
 * so both numeric and String trigger.deviceId values resolve as long as
 * the fixture map is keyed by Long.
 */
class TestParent {
    Map<Long, TestDevice> devices = [:]
    Map settings = [:]

    Object findDevice(id) {
        devices[(id as Long)]
    }
}
