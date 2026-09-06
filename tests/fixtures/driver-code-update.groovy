metadata {
    definition(name: "Deadman Test Target Driver", namespace: "mcptest", author: "ci") {
        capability "Switch"
    }
}

def installed() {}
def updated() {}
def on() { sendEvent(name: "switch", value: "on") }
def off() { sendEvent(name: "switch", value: "off") }

def driverLegMarker() { return "DRIVER-LEG-MARKER-V1" }
