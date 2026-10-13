definition(name: "BAT529 CompileStatic Probe", namespace: "codex.bat529", author: "kingpanther13", description: "Temporary inert static compilation compatibility probe", category: "Convenience", iconUrl: "", iconX2Url: "")
preferences {
    page(name: "mainPage")
}
def mainPage() {
    dynamicPage(name: "mainPage", title: "Static compilation probe", install: true, uninstall: true) {
        section { paragraph "Result: ${probeSum(2, 3)} (expected 5)" }
    }
}
@groovy.transform.CompileStatic
int probeSum(int a, int b) {
    return a + b
}
def installed() {}
def updated() {}

