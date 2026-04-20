/*
 * Minimal Hubitat-app skeleton used by RMUtilsSandboxResolutionSpec to
 * confirm the SandboxClassLoader can resolve hubitat.helper.RMUtils
 * (and NetworkUtils) when called from sandbox-loaded production-style
 * code. Without the build-time stubs at src/main/groovy/hubitat/helper/,
 * this script's calls would raise NoClassDefFoundError under the
 * sandbox.
 *
 * Not deployed; lives on the test classpath (resources only).
 */
definition(
    name: "RMUtils Sandbox Probe",
    namespace: "test",
    author: "test",
    description: "diagnostic"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage") { }
}

def callRmUtilsGetRuleList() {
    return hubitat.helper.RMUtils.getRuleList("5.0")
}

def callNetworkUtils() {
    return hubitat.helper.NetworkUtils.sendHubitatCommand([host: 'x'])
}
