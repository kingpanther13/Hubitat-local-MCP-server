definition(
    name: "Deadman Test Target Update",
    namespace: "mcptest",
    author: "ci",
    description: "Throwaway e2e app-code update-leg target",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    oauth: true
)

preferences {
    page(name: "p", title: "Update Leg Target", install: true, uninstall: true) {
        section { paragraph "Throwaway update-leg target. Marker: ${updateLegMarker()}" }
    }
}

mappings {
    path("/ping") { action: [GET: "ping"] }
}

def installed() {}
def updated() {}
def ping() { render contentType: "text/plain", data: "ok" }

def updateLegMarker() { return "UPDATE-LEG-MARKER-V1" }
