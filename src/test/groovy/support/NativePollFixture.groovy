package support

import groovy.json.JsonOutput

class NativePollFixture {
    static void register(HubInternalGetMock hubGet, TestDevice device) {
        // Read the scripted state only inside the HTTP responder so poll ticks retain
        // their sequence. SDK declarations remain separate from reported native states.
        hubGet.register("/device/fullJson/${device.id}") { Map params ->
            def states = device.currentStates
            def nativeStates = states == null ? null : states.collectEntries { row ->
                [(row.name): [value: row.value, date: row.date]]
            }
            JsonOutput.toJson([
                device: [id: device.id, name: device.name, label: device.label,
                         currentStates: nativeStates],
                commands: device.supportedCommands
            ])
        }
    }
}
