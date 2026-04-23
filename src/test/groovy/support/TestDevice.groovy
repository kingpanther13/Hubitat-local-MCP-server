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
    List capabilities = []
    List supportedAttributes = []
    List supportedCommands = []
    Map attributeValues = [:]
    // Some tools iterate device.currentStates (e.g. toolGetRoom). Default
    // null so the ?.each in the tool is a no-op; tests that care can set
    // a list of maps like [[name: 'switch', value: 'on']].
    List currentStates = null

    // Events returned by eventsSince(). Default [] keeps existing specs green;
    // rule-engine device_was tests seed this with maps like
    // [[name: 'switch', value: 'on'], ...] to drive the lookback check.
    List events = []

    Object currentValue(String attr) {
        attributeValues[attr]
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
