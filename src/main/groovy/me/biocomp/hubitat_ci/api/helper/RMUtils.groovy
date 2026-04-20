package me.biocomp.hubitat_ci.api.helper

/**
 * Sandbox-target stub for hubitat.helper.RMUtils.
 *
 * HubitatCI's SandboxClassLoader.mapClassName remaps any
 * `hubitat.<x>.<Y>` reference from sandbox-loaded production code into
 * `me.biocomp.hubitat_ci.api.<x>.<Y>` (default catch-all). So when the
 * server's `toolListRmRules` (PR #79) calls
 * `hubitat.helper.RMUtils.getRuleList(...)`, the sandbox actually looks
 * up `me.biocomp.hubitat_ci.api.helper.RMUtils.getRuleList(...)` —
 * THIS class. A stub at `hubitat.helper.RMUtils` alone never resolves.
 *
 * To keep `support.RMUtilsMock`'s metaClass installation pattern
 * working (it stubs `hubitat.helper.RMUtils.metaClass.static.X`), each
 * method here delegates to the same-named method on
 * `hubitat.helper.RMUtils`. Production calls flow:
 *   sandbox script -> me.biocomp...helper.RMUtils.X (this class)
 *                  -> hubitat.helper.RMUtils.X
 *                  -> RMUtilsMock metaClass interception
 *
 * Test-JVM direct calls to `hubitat.helper.RMUtils` (e.g.,
 * RMUtilsMockSpec) skip this class and hit the metaClass directly.
 *
 * Not deployed to Hubitat — packageManifest.json ships only the
 * top-level `.groovy` files. On a real hub the platform provides
 * `hubitat.helper.RMUtils`; this remap path doesn't exist outside
 * HubitatCI.
 */
class RMUtils {
    static List getRuleList(String version = '5.0') {
        return hubitat.helper.RMUtils.getRuleList(version)
    }

    static void sendAction(Long ruleId, String action) {
        hubitat.helper.RMUtils.sendAction(ruleId, action)
    }

    static void pauseRule(Long ruleId) {
        hubitat.helper.RMUtils.pauseRule(ruleId)
    }

    static void resumeRule(Long ruleId) {
        hubitat.helper.RMUtils.resumeRule(ruleId)
    }

    static void setRuleBoolean(Long ruleId, Boolean value) {
        hubitat.helper.RMUtils.setRuleBoolean(ruleId, value)
    }
}
