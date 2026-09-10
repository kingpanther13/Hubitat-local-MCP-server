package support

import groovy.json.JsonOutput

/** Explicit HTTP fixture adapter for inventory specs; never installed in the global harness. */
class NativeInventoryFixture {
    static void register(HubInternalGetMock hubGet, String id, Closure lookup) {
        hubGet.register("/device/fullJson/${id}") {
            def device = lookup()
            if (device == null) throw new IOException("No native fixture ${id}")
            def states = [:]
            device.currentStates?.each { st ->
                def raw = device.attributeValues?.containsKey(st.name) ? device.attributeValues[st.name] : st.value
                def row = [value: raw?.toString(), unit: st.unit]
                if (raw instanceof Number) {
                    row.dataType = 'NUMBER'
                    row.numberValue = raw
                }
                states[st.name] = row
            }
            JsonOutput.toJson([device: [id: device.id, name: device.name, label: device.label,
                roomName: device.roomName, disabled: device.disabled, deviceNetworkId: device.deviceNetworkId,
                lastActivityTime: device.lastActivity?.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                capabilities: device.capabilities.collect { it instanceof Map ? it.name : it }, currentStates: states],
                commands: device.supportedCommands])
        }
    }
}
