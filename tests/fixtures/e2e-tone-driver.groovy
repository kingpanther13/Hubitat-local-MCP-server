metadata {
    definition(name: "BAT_E2E_KEEP Virtual Tone", namespace: "mcptest", author: "ci") {
        capability "Tone"
        capability "Actuator"
        attribute "beepCount", "number"
    }
}

def installed() { sendEvent(name: "beepCount", value: 0) }
def updated() {}
def beep() { sendEvent(name: "beepCount", value: ((device.currentValue("beepCount") ?: 0) as Integer) + 1) }
