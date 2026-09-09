import groovy.json.JsonOutput

metadata {
    definition(name: "E2E_PERM_Configuration", namespace: "mcptest", author: "ci", singleThreaded: true) {
        capability "Switch"
        attribute "nativeConfiguration", "string"
        attribute "nativeDeviceInfo", "string"
        attribute "lanProbeAck", "string"
        command "captureConfiguration", [[name: "nonce", type: "STRING"]]
        command "captureDeviceEnabled", [[name: "nonce", type: "STRING"], [name: "deviceId", type: "STRING"]]
        command "seedLargeReadProbe"
        command "changeLargeReadProbe"
        command "clearLargeReadProbe"
        command "sendLanProbe", [[name: "nonce", type: "STRING"]]
        command "clearLanProbe"
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
def changeLargeReadProbe() { state.largeReadProbe = 'y' * 180000 }
def clearLargeReadProbe() { state.remove('largeReadProbe') }

def sendLanProbe(String nonce) {
    if (!(nonce ==~ /[0-9]{1,30}/)) throw new IllegalArgumentException('A numeric probe nonce is required')
    if (state.lanNonce) throw new IllegalStateException('A LAN probe is still pending; inspect its result before resetting')
    state.lanNonce = nonce
    sendEvent(name: 'lanProbeAck', value: 'pending', isStateChange: true)
    def action = new hubitat.device.HubAction([
        method: 'GET', path: '/local/E2E_PERM_Configuration_Response.json', headers: [HOST: '127.0.0.1:8080']
    ], null, [callback: 'lanProbeReply'])
    sendHubCommand(action)
}

def lanProbeReply(response) {
    if (!state.lanNonce) return
    def body = response.json
    if (body?.fixtureVersion != 2 || body?.marker != 'configuration-explicit-lan' || body?.ack != true) {
        sendEvent(name: 'lanProbeAck', value: 'invalid-response', isStateChange: true)
        return
    }
    sendEvent(name: 'lanProbeAck', value: state.lanNonce.toString(), isStateChange: true)
    state.remove('lanNonce')
}

def clearLanProbe() {
    if (state.lanNonce) throw new IllegalStateException('An unconsumed LAN response is pending; automatic reset would allow stale acknowledgment')
    sendEvent(name: 'lanProbeAck', value: 'idle', isStateChange: true)
}

def captureDeviceEnabled(String nonce, String targetId) {
    if (!(targetId ==~ /[0-9]+/)) throw new IllegalArgumentException('A numeric fixture device ID is required')
    httpGet([uri: 'http://127.0.0.1:8080', path: "/device/fullJson/${targetId}", timeout: 5]) { response ->
        def target = response.data?.device
        if (!(target instanceof Map) || !target.label?.toString()?.startsWith('BAT_E2E_KEEP_Configuration_')) {
            throw new IllegalArgumentException('The enabled observer only reads owned configuration fixtures')
        }
        if (!(target.disabled in [true, false, 'true', 'false'])) {
            throw new IllegalStateException('Native disabled value is unavailable')
        }
        sendEvent(name: 'nativeDeviceInfo', value: JsonOutput.toJson([
            nonce: nonce, deviceId: targetId, fixtureVersion: 2,
            enabled: !(target.disabled == true || target.disabled == 'true')
        ]), isStateChange: true)
    }
}

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
        def snapshot = [nonce: nonce, deviceId: device.id.toString(), fixtureVersion: 2, settings: rows, inputValues: values,
                        runtimeMultiple: settings.probeMultiple, runtimeMultipleIsList: settings.probeMultiple instanceof List]
        def info = [nonce: nonce, deviceId: device.id.toString(), fixtureVersion: 2,
                    largeReadProbePresent: state.containsKey('largeReadProbe')]
        ["name", "label", "deviceNetworkId", "deviceTypeId", "notes", "maxEvents", "maxStates",
         "spammyThreshold", "tags", "defaultIcon", "showOnHome", "defaultCurrentState",
         "roomId", "roomName", "isComponent", "meshSelectionEnabled", "retryAvailable", "retryEnabled",
         "parentAppId", "parentDeviceId", "virtual", "controllerType", "zigbeeId", "linkedDevice",
         "linkedLocally", "meshEnabled", "meshFullSync"].each { key ->
            if (nativePage.device.containsKey(key)) info.put(key, nativePage.device.get(key))
        }
        if (nativePage.device.disabled in [true, false, 'true', 'false']) {
            info.enabled = !(nativePage.device.disabled == true || nativePage.device.disabled == 'true')
        }
        if (nativePage.device.containsKey('data')) info.dataValues = nativePage.device.get('data')
        ["hasDashboards", "commandRetrySelectionEnabled", "hubMeshRefreshEnabled", "homeKitSelectionEnabled",
         "homeKitEnabled", "amazonAlexaInstalled", "amazonAlexaSupported", "amazonAlexaEnabled",
         "googleHomeInstalled", "googleHomeSupported", "googleHomeEnabled"].each { key ->
            if (nativePage.containsKey(key)) info.put(key, nativePage.get(key))
        }
        if (nativePage.dashboards instanceof List) {
            info.dashboardIds = nativePage.dashboards.findAll { it.selected == true || it.selected == 'true' }.collect { it.id }
        }
        [nativeConfiguration: snapshot, nativeDeviceInfo: info].each { attributeName, data ->
            def encoded = JsonOutput.toJson(data)
            if (encoded.length() > 4096) throw new IllegalStateException("Native fixture snapshot exceeds 4096 characters")
            sendEvent(name: attributeName, value: encoded, isStateChange: true)
        }
    }
}
