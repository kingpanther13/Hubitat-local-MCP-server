package support

import groovy.json.JsonOutput

/** Explicit HTTP fixture adapter for inventory specs; never installed in the global harness. */
class NativeInventoryFixture {
    static Map nativeStateValue(Object raw) {
        def row = [value: raw?.toString()]
        if (raw instanceof Number) {
            row.dataType = 'NUMBER'
            row.numberValue = raw
        }
        row
    }

    /**
     * One /hub2/devicesList node for a TestDevice, with `kids` nested as its children. Strings as
     * the Devices page carries them: label as `name`, the device name as `secondaryName` when a
     * label is set, "" for an unset room/tags/notes, and lastActivity as yyyy-MM-dd'T'HH:mm:ssZ.
     */
    static Map pageNode(Object device, List kids = []) {
        def la = device.lastActivity
        def data = [id: device.id, name: device.label ?: device.name,
            secondaryName: device.label && device.name ? device.name : '', type: device.typeName ?: device.name,
            dni: device.deviceNetworkId, disabled: device.disabled == true,
            lastActivity: la == null ? null : la.format("yyyy-MM-dd'T'HH:mm:ssZ", TimeZone.getTimeZone('UTC')),
            roomId: null, roomName: device.roomName ?: '', tags: '', notes: '', isVirtual: true,
            currentStates: (device.currentStates ?: []).collect { [key: it.name, value: it.value?.toString()] }]
        [key: "DEV-${device.id}".toString(), data: data, children: kids.collect { pageNode(it) },
         parent: !kids.isEmpty(), child: false]
    }

    /**
     * Registers the Devices page and the capability feed for the devices `supplier` returns, read at
     * request time so a spec may add devices after registering.
     */
    static void registerDevicesPage(HubInternalGetMock hubGet, Closure supplier) {
        hubGet.register('/hub2/devicesList') { JsonOutput.toJson([devices: supplier().collect { pageNode(it) }]) }
        hubGet.register('/hub2/vrb/devices') {
            JsonOutput.toJson(supplier().collect { d ->
                [id: d.id, label: d.label ?: d.name, capabilities: (d.capabilities ?: []).collect { it instanceof Map ? it.name : it.toString() }]
            })
        }
    }

    static void register(HubInternalGetMock hubGet, String id, Closure lookup) {
        hubGet.register("/device/fullJson/${id}") {
            def device = lookup()
            if (device == null) throw new IOException("No native fixture ${id}")
            def states = [:]
            device.currentStates?.each { st ->
                def raw = device.attributeValues?.containsKey(st.name) ? device.attributeValues[st.name] : st.value
                def row = nativeStateValue(raw) + [unit: st.unit]
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
