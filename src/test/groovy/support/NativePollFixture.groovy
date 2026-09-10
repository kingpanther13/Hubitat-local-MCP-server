package support

import groovy.json.JsonOutput

class NativePollFixture {
    static void register(HubInternalGetMock hubGet, TestDevice device) {
        // The first HTTP response also supplies preflight metadata and is consumed as
        // the first value sample. No SDK declaration list is part of the native model.
        hubGet.register("/device/fullJson/${device.id}") { Map params ->
            def states = device.currentStates
            def nativeStates = states == null ? null : states.collectEntries { row ->
                [(row.name): NativeInventoryFixture.nativeStateValue(row.value) + [date: row.date]]
            }
            JsonOutput.toJson([
                device: [id: device.id, name: device.name, label: device.label,
                         currentStates: nativeStates],
                commands: device.supportedCommands
            ])
        }
    }
}
