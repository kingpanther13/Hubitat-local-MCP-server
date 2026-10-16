definition(
    name: "BAT_E2E_Wizard_Walk",
    namespace: "mcptest",
    author: "ci",
    description: "Install-from-URL wizard whose main page clears the pending URL, as Hubitat Package Manager's does",
    category: "Utility",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "prefOptions")
    page(name: "prefPkgInstall")
    page(name: "prefPkgInstallUrl")
    page(name: "prefInstallChoices")
}

def prefOptions() {
    // Rendering the main page abandons a pending install: a walker that re-renders it
    // between steps loses the URL the previous step wrote.
    app.removeSetting("pkgInstall")
    state.remove("mainMenu")
    return dynamicPage(name: "prefOptions", title: "Main Menu", install: true, uninstall: true) {
        section {
            href(name: "prefPkgInstall", title: "Install New Package", page: "prefPkgInstall")
        }
    }
}

def prefPkgInstall() {
    return dynamicPage(name: "prefPkgInstall", title: "Install New Package", install: false, uninstall: false) {
        section {
            href(name: "prefPkgInstallUrl", title: "From a URL", page: "prefPkgInstallUrl")
        }
    }
}

def prefPkgInstallUrl() {
    if (state.mainMenu) return prefOptions()
    return dynamicPage(name: "prefPkgInstallUrl", title: "", nextPage: "prefInstallChoices", install: false, uninstall: false) {
        section {
            input "pkgInstall", "text", title: "Package manifest URL", required: true, submitOnChange: true
            input "btnMainMenu", "button", title: "Main Menu"
        }
    }
}

def prefInstallChoices() {
    return dynamicPage(name: "prefInstallChoices", title: "Install", install: true, uninstall: false) {
        section {
            paragraph "Pending install: ${settings.pkgInstall}"
        }
    }
}

def appButtonHandler(String btn) {
    if (btn == "btnMainMenu") state.mainMenu = true
}

def installed() {}
def updated() {}
