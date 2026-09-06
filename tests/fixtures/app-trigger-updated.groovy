definition(
    name: "Deadman Test Target Trigger",
    namespace: "mcptest",
    author: "ci",
    description: "Throwaway e2e triggerUpdated target",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png"
)

preferences {
    page(name: "p", title: "Trigger Leg Target", install: true, uninstall: true) {
        section {
            input name: "refreshProbe", type: "bool", title: "Round-trip probe", defaultValue: false
            input name: "probeSwitches", type: "capability.switch", title: "Round-trip devices", multiple: true, required: false
            input name: "lifecycleStamp", type: "text", title: "Lifecycle stamp", required: false
            paragraph "Throwaway triggerUpdated target. Marker: ${triggerLegMarker()}"
        }
    }
}

def installed() { updateStamp("installed") }
def updated() { updateStamp("updated") }

// The stamp goes into a SETTING, not state: hub_get_app_config(includeSettings) can read a
// setting, which is what lets the test prove updated() actually RAN. updatedFired only proves
// the hub accepted the Done POST.
def updateStamp(String which) { app.updateSetting("lifecycleStamp", [type: "text", value: which]) }

def triggerLegMarker() { return "TRIGGER-LEG-MARKER-V1" }
