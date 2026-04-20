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

    Object currentValue(String attr) {
        attributeValues[attr]
    }

    void on() { invokeCommand('on', []) }
    void off() { invokeCommand('off', []) }
    void setLevel(Integer level) { invokeCommand('setLevel', [level]) }
    void setLevel(Integer level, Integer duration) { invokeCommand('setLevel', [level, duration]) }

    /** Override via Spy or subclass to observe command dispatch. */
    void invokeCommand(String commandName, List args) {}
}
