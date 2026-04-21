/*
 * Probe script for RMUtilsSandboxInterceptionSpec.
 *
 * Deliberately tiny — exists only to let the harness load a Hubitat
 * app via HubitatAppSandbox and invoke methods that reference
 * `hubitat.helper.RMUtils` from inside the sandbox. That reference
 * gets rewritten by eighty20results' SandboxClassLoader to
 * `me.biocomp.hubitat_ci.api.common_api.RMUtils`; the regression spec
 * installs RMUtilsMock (which metaClasses both the raw and mapped
 * class) and asserts the mock records the call.
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
