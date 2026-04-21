/*
 * Probe script for RMUtilsSandboxInterceptionSpec.
 *
 * Deliberately tiny — exists only to let the harness load a Hubitat
 * app via HubitatAppSandbox and invoke methods that reference
 * `hubitat.helper.RMUtils` from inside the sandbox. Resolution goes
 * through `support.PassThroughSandboxClassLoader`, which bypasses
 * eighty20results' standard name remap for that class so the
 * literal-named main-source-set stub at
 * `src/main/groovy/hubitat/helper/RMUtils.groovy` resolves cleanly.
 * The regression spec installs `RMUtilsMock` on that stub's static
 * metaClass, so every probe call lands on the mock.
 *
 * NOT deployed to Hubitat — never referenced by production code paths.
 */

definition(
    name: "RMUtils Sandbox Probe",
    namespace: "test",
    author: "test",
    description: "Test-only probe for sandbox RMUtils interception",
    category: "test"
)

preferences {
    page(name: "mainPage") {}
}

def probeGetRuleList(String version = '5.0') {
    return hubitat.helper.RMUtils.getRuleList(version)
}

def probeSendAction(Long ruleId, String action) {
    hubitat.helper.RMUtils.sendAction(ruleId, action)
}

def probePauseRule(Long ruleId) {
    hubitat.helper.RMUtils.pauseRule(ruleId)
}

def probeResumeRule(Long ruleId) {
    hubitat.helper.RMUtils.resumeRule(ruleId)
}

def probeSetRuleBoolean(Long ruleId, Boolean value) {
    hubitat.helper.RMUtils.setRuleBoolean(ruleId, value)
}
