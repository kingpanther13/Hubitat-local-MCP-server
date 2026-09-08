import groovy.json.JsonOutput

metadata {
    definition(name: "Deadman Test Target Configuration RUN_TOKEN", namespace: "mcptest", author: "ci") {
        capability "Switch"
        attribute "nativeConfiguration", "string"
        attribute "nativeDeviceInfo", "string"
        command "captureConfiguration", [[name: "nonce", type: "STRING"]]
        command "seedLargeReadProbe"
        command "clearLargeReadProbe"
    }
    preferences {
        input name: "probeBool", type: "bool", title: "Description logging", defaultValue: true
        input name: "probeNumber", type: "number", title: "Numeric probe", defaultValue: 7, range: "0..20"
        input name: "probeText", type: "text", title: "Text probe", defaultValue: "declaration default"
        input name: "probeEnum", type: "enum", title: "Profile", options: [eco: "Economy", comfort: "Comfort"], defaultValue: "comfort"
        input name: "probeMultiple", type: "enum", title: "Colors", options: [red: "Red", blue: "Blue"], multiple: true
    }
}

def installed() {
    device.updateSetting("probeBool", [type: "bool", value: false])
    device.updateSetting("probeNumber", [type: "number", value: 3])
    device.updateSetting("probeText", [type: "text", value: "original saved text"])
    device.updateSetting("probeEnum", [type: "enum", value: "eco"])
    device.updateSetting("probeMultiple", [type: "enum", value: ["red"]])
    off()
}

def updated() {}
def on() { sendEvent(name: "switch", value: "on") }
def off() { sendEvent(name: "switch", value: "off") }
def seedLargeReadProbe() { state.largeReadProbe = 'x' * 180000 }
def clearLargeReadProbe() { state.remove('largeReadProbe') }

def captureConfiguration(String nonce) {
    // This test-only observer reads its own native page, independent of the MCP parser.
    httpGet([uri: "http://127.0.0.1:8080", path: "/device/fullJson/${device.id}", timeout: 5]) { response ->
        def nativePage = response.data
        if (!(nativePage instanceof Map) || !(nativePage.device instanceof Map)) {
            throw new IllegalStateException("Native fixture page has no device object; check Hub Security and /device/fullJson access")
        }
        def names = ["probeBool", "probeNumber", "probeText", "probeEnum", "probeMultiple"]
        def rows = nativePage.settings instanceof List ? nativePage.settings.findAll { names.contains(it.name) }.collect {
            [name: it.name, type: it.type, multiple: it.multiple, multiplePresent: it.containsKey("multiple"),
             value: it.value, valuePresent: it.containsKey("value"),
             storageId: it.id, storageIdPresent: it.containsKey("id"),
             storageDeviceId: it.deviceId, storageDeviceIdPresent: it.containsKey("deviceId"), defaultValue: it.defaultValue]
        } : null
        def values = nativePage.inputValues instanceof List ? nativePage.inputValues.findAll { names.contains(it.name) }.collect {
            [name: it.name, inputValue: it.inputValue]
        } : null
        def snapshot = [nonce: nonce, deviceId: device.id.toString(), settings: rows, inputValues: values,
                        runtimeMultiple: settings.probeMultiple, runtimeMultipleIsList: settings.probeMultiple instanceof List]
        def info = [nonce: nonce, deviceId: device.id.toString()]
        ["name", "label", "deviceNetworkId", "deviceTypeId", "notes", "maxEvents", "maxStates",
         "spammyThreshold", "tags", "defaultIcon", "showOnHome", "defaultCurrentState",
         "roomId", "roomName", "isComponent", "meshSelectionEnabled", "retryAvailable", "retryEnabled"].each { key ->
            if (nativePage.device.containsKey(key)) info[key] = nativePage.device[key]
        }
        [nativeConfiguration: snapshot, nativeDeviceInfo: info].each { attributeName, data ->
            def encoded = JsonOutput.toJson(data)
            if (encoded.length() > 4096) throw new IllegalStateException("Native fixture snapshot exceeds 4096 characters")
            sendEvent(name: attributeName, value: encoded, isStateChange: true)
        }
    }
}
