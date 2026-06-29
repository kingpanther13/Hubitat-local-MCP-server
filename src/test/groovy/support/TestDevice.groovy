package support

/**
 * Device-like class for tool specs. Exposes the methods + properties the
 * MCP server reads via Groovy property syntax (`device.id`, `device.label`,
 * etc.) so Spock Mock/Stub works cleanly — `Mock(Object)` can't stub these
 * because Object doesn't declare them.
 *
 * Command dispatch goes through `invokeCommand(name, args)`; a subclass or
 * Spock Spy can record/assert against it. Individual command methods (`on()`,
 * `off()`, `setLevel(Integer)`) delegate to `invokeCommand` so specs can
 * assert via `1 * device.invokeCommand('off', [])` or use Spock's method
 * verification on specific commands.
 */
class TestDevice {
    Integer id
    String name
    String label
    String roomName
    String deviceNetworkId
    List capabilities = []
    List supportedAttributes = []
    List supportedCommands = []
    Map attributeValues = [:]
    // Explicit override for currentStates. When a spec sets this (constructor
    // `currentStates: [...]` or `dev.currentStates = [...]`), getCurrentStates()
    // returns it verbatim. When left null, getCurrentStates() DERIVES a State-like
    // list from attributeValues so a device seeded with [switch: 'on'] reads back
    // through currentStates (the source the poll engine and the snapshot both read).
    private List _currentStatesOverride = null

    // Hub property read by toolListVirtualDevices (device.typeName ?: device.name).
    // Default null so callers that don't set it fall through to the device.name fallback.
    String typeName = null

    // The device's disabled flag. Default null = unset (the device exposes no explicit value).
    Boolean disabled = null

    // Device data values -- backing store for getDataValue/updateDataValue.
    // toolCreateVirtualDevice persists mcpDriverNamespace here; toolListVirtualDevices reads it back.
    Map dataValues = [:]

    Object getDataValue(String key) {
        dataValues[key]
    }

    void updateDataValue(String key, String value) {
        dataValues[key] = value
    }

    // Events returned by eventsSince(). Default [] keeps existing specs green;
    // rule-engine device_was tests seed this with maps like
    // [[name: 'switch', value: 'on'], ...] to drive the lookback check.
    List events = []

    Object currentValue(String attr) {
        attributeValues[attr]
    }

    // currentState(attr) and currentValue(attr) are BOTH cached by the hub at request
    // start and never refresh within a request -- on a real async device (Matter/Zigbee/
    // Z-Wave/cloud) they return the PRE-command value for the whole poll. Only the full
    // currentStates list re-reads the live event store. So the poll engine reads
    // currentStates (not currentState(attr) or currentValue(attr)); these per-attribute
    // accessors derive from the same attributeValues seed for the one-shot read paths,
    // but poll specs drive convergence via getCurrentStates(), not these.
    Object currentState(String attr) {
        attributeValues.containsKey(attr) && attributeValues[attr] != null ? [value: attributeValues[attr]?.toString()] : null
    }

    void setCurrentStates(List states) {
        _currentStatesOverride = states
    }

    // When a spec set currentStates explicitly, return that. Otherwise derive a State-like
    // list ([name, value, date]) from attributeValues so a plain new TestDevice(attributeValues:
    // [...]) drives the poll engine (which reads currentStates) and the command snapshot.
    // An attribute absent from attributeValues is absent from the list -- find(...) returns
    // null, which is the poll's neverReported signal.
    List getCurrentStates() {
        if (_currentStatesOverride != null) return _currentStatesOverride
        if (attributeValues == null) return null
        def states = []
        attributeValues.each { k, v ->
            if (v != null) states << [name: k, value: v.toString(), date: null]
        }
        return states
    }

    /**
     * Stubbed device event history — rule engine's device_was condition
     * filters by attribute and looks for recent value changes. Tests seed
     * {@code events} with the history they want the condition to see.
     * The {@code since} and {@code opts} params are ignored here (we return
     * the full seeded list); filtering happens in the rule engine's own
     * `.findAll { it.name == ... }` over the returned list.
     */
    List eventsSince(Date since, Map opts = null) {
        return events
    }

    void on() { invokeCommand('on', []) }
    void off() { invokeCommand('off', []) }
    void setLevel(Integer level) { invokeCommand('setLevel', [level]) }
    void setLevel(Integer level, Integer duration) { invokeCommand('setLevel', [level, duration]) }

    // Command surface used by rule-engine action specs (#75). Each delegates
    // to invokeCommand so Spy(TestDevice) { 1 * setColor(_) } still works,
    // and so any not-yet-mocked spec doesn't blow up on the dynamic dispatch
    // `device."${action.command}"(...)` path the rule engine uses.
    void setColor(Map colorMap) { invokeCommand('setColor', [colorMap]) }
    void setColorTemperature(Integer temp) { invokeCommand('setColorTemperature', [temp]) }
    void setColorTemperature(Integer temp, Integer level) { invokeCommand('setColorTemperature', [temp, level]) }
    void lock() { invokeCommand('lock', []) }
    void unlock() { invokeCommand('unlock', []) }
    void deviceNotification(String msg) { invokeCommand('deviceNotification', [msg]) }
    void setThermostatMode(String mode) { invokeCommand('setThermostatMode', [mode]) }
    void setHeatingSetpoint(def value) { invokeCommand('setHeatingSetpoint', [value]) }
    void setCoolingSetpoint(def value) { invokeCommand('setCoolingSetpoint', [value]) }
    void setThermostatFanMode(String mode) { invokeCommand('setThermostatFanMode', [mode]) }
    void setVolume(Integer volume) { invokeCommand('setVolume', [volume]) }
    void speak(String msg) { invokeCommand('speak', [msg]) }
    void open() { invokeCommand('open', []) }
    void close() { invokeCommand('close', []) }
    void setPosition(Integer position) { invokeCommand('setPosition', [position]) }
    void setSpeed(String speed) { invokeCommand('setSpeed', [speed]) }

    /** Capability check for capture_state action (filters which attrs to snapshot). */
    boolean hasCapability(String cap) { capabilities?.contains(cap) }
    /** Command-presence check for loop-guard notifyLoopGuard fallback. */
    boolean hasCommand(String cmd) { supportedCommands?.contains(cmd) }

    /** Override via Spy or subclass to observe command dispatch. */
    void invokeCommand(String commandName, List args) {}
}
