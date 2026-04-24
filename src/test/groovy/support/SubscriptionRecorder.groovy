package support

/**
 * Records {@code subscribe(source, attribute, handler)} calls the rule
 * engine makes at install time, and plays them back by firing synthetic
 * events into the original handler method.
 *
 * The existing rule specs (e.g. {@code HandleDeviceEventSpec}) drive the
 * handler directly with a hand-built event Map: they skip the subscribe
 * step entirely and assume the right handler was wired up. That's fine for
 * unit-testing the handler body but leaves two gaps on the real-hub
 * integration:
 *
 *   1. {@code subscribeToTriggers()} could register subscriptions against
 *      the wrong device / attribute / handler name and no test would
 *      notice — the handler-body specs build events out of thin air.
 *   2. Per-trigger wiring logic that branches on trigger type (device_event
 *      vs button_event vs mode_change etc.) is exercised only via its side
 *      effects; a regression that silently dropped a subscribe call
 *      wouldn't fail any existing spec.
 *
 * This recorder sits on the AppExecutor Mock's {@code subscribe(...)} stub
 * (wired in {@code RuleHarnessSpec.setupSpec()}), captures every call, and
 * exposes a {@code fireEvent()} helper that locates a matching subscription
 * and dispatches a synthetic event to its handler on the loaded script.
 * Specs assert both the shape of the subscription set ("a device_event
 * rule subscribed device 1 for attribute 'switch' to handleDeviceEvent")
 * and the end-to-end behavior ("firing a matching event ran the rule's
 * action on the target device").
 *
 * State lifecycle: cleared in {@code RuleHarnessSpec.setup()} each test.
 *
 * <h4>What this does not cover (out of scope):</h4>
 * <ul>
 *   <li>{@code schedule(cronExpr, handler)} and {@code runOnce(date, handler)} —
 *     the time-trigger subscription path lives in different AppExecutor
 *     methods that {@code RuleHarnessSpec} already records via
 *     {@code runInCalls} / {@code runOnceCalls} and {@code unschedule}
 *     counters. Extending this recorder to play scheduled events back is
 *     a follow-on if #77 grows into broader time-driven E2E coverage.</li>
 *   <li>The dynamic-dispatch {@code script."${handler}"(event)} path — we
 *     call the handler by name on the loaded script. If production code
 *     subscribes with a handler name that isn't a {@code def} on the
 *     script, MissingMethodException surfaces at {@code fireEvent()} time
 *     which is loud enough; we don't pre-validate.</li>
 * </ul>
 */
class SubscriptionRecorder {

    /**
     * Single captured subscribe call. {@code source} is whatever the script
     * passed as the first arg — commonly a {@link TestDevice}, a
     * {@link TestLocation}, or a plain Map for specs that don't care about
     * the source identity beyond assertion.
     */
    static class Subscription {
        Object source
        String attribute
        String handler
    }

    final List<Subscription> subscriptions = []

    /**
     * Called from the AppExecutor Mock's {@code subscribe(_, _, _)} stub.
     * Signature matches the 3-arg form the rule engine uses
     * ({@code subscribe(device, attributeName, handlerName)}) — the 2-arg
     * and 4-arg variants in Hubitat's API aren't used by this app, but if
     * a future tool starts calling them the stub in RuleHarnessSpec will
     * need a second method here.
     */
    void record(Object source, String attribute, String handler) {
        subscriptions << new Subscription(
            source: source, attribute: attribute, handler: handler)
    }

    /**
     * Locate subscriptions matching the given source + attribute and
     * invoke each recorded handler on the loaded script with a synthetic
     * event. The event Map mirrors the shape the hub dispatches at
     * runtime: {@code device}, {@code name}, {@code value}. Overrides
     * merge into the event Map after the defaults so a spec can add
     * {@code isStateChange}, {@code source}, etc.
     *
     * For location-attribute subscriptions (mode, hsmStatus) the source
     * is the {@link TestLocation} instance — pass the same instance here
     * and the match fires. A plain-value source would fail identity
     * comparison, which is the behavior we want: callers should use the
     * exact object they passed into subscribe.
     *
     * Throws {@link IllegalStateException} if no matching subscription
     * is found — silent no-op would be a test-authoring footgun.
     */
    List<Object> fireEvent(Object script, Object source, String attribute, Object value, Map overrides = [:]) {
        def matches = subscriptions.findAll {
            it.source.is(source) && it.attribute == attribute
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                "fireEvent(source=${source}, attribute=${attribute}) found no " +
                "matching subscription. Check that subscribeToTriggers() ran and " +
                "that the source instance matches (identity compare, not equality).")
        }
        Map event = [
            device: source,
            name: attribute,
            value: value
        ]
        event.putAll(overrides)
        matches.collect { Subscription sub -> script."${sub.handler}"(event) }
    }

    /** Clear all recorded subscriptions. Called from {@code RuleHarnessSpec.setup()}. */
    void reset() {
        subscriptions.clear()
    }
}
