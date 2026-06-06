/**
 * Throwaway target app for exercising the E2E Dead-Man Watchdog safely.
 *
 * NOT a production component. The watchdog test restores THIS app (never the real
 * MCP Rule Server) so a misfire can't touch anything that matters. This file is the
 * "v1 / known-good" source; the test deploys it, mutates the installed copy to a "v2"
 * marker, arms the watchdog at this app's class, and verifies the watchdog restores
 * the v1 marker. Lives under tests/fixtures so the groovy-parse lane (root *.groovy +
 * libraries/**) never treats it as a production file.
 */
definition(
    name: "Deadman Test Target",
    namespace: "mcptest",
    author: "ci",
    description: "Throwaway e2e dead-man watchdog test target",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png"
)

preferences {
    page(name: "p", title: "Deadman Test Target", install: true, uninstall: true) {
        section { paragraph "Throwaway test target. Marker: ${targetMarker()}" }
    }
}

def installed() {}
def updated() {}

// The test asserts this marker flips back to V1 after the watchdog restores.
def targetMarker() { return "DEADMAN-TARGET-V1" }
