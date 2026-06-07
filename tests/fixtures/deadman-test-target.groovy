/**
 * Throwaway fixture for the e2e install-commit regression test (@test("deadman")). NOT a production
 * component: the test installs it via hub_create_app(installAsUserApp:), asserts the install
 * committed (app.installed==true), then deletes it. Lives under tests/fixtures so the groovy-parse
 * lane never treats it as a production file.
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

// Placeholder content rendered on the install page; no test asserts the value (the watchdog
// fire-test that once checked it was removed after being proven on the e2e hub).
def targetMarker() { return "DEADMAN-TARGET-V1" }
