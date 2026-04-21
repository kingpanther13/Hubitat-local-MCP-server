package me.biocomp.hubitat_ci.api.helper

/**
 * Sandbox-target alias for `hubitat.helper.RMUtils`. Exists to satisfy
 * HubitatCI's `SandboxClassLoader.mapClassName` default catch-all
 * (`hubitat.X` → `me.biocomp.hubitat_ci.api.X`) when a spec uses the
 * stock `SandboxClassLoader` instead of `support.PassThroughSandboxClassLoader`.
 *
 * Specs that DO use the pass-through classloader (the supported path
 * for sandbox-resolution of platform helpers; see
 * `support.PassThroughAppValidator`) bypass `mapClassName` for
 * `hubitat.helper.RMUtils` and resolve the literal
 * `hubitat.helper.RMUtils` stub directly. This delegating class is the
 * fallback for any non-pass-through code path.
 *
 * Each method delegates to the same-named method on
 * `hubitat.helper.RMUtils` so `support.RMUtilsMock`'s metaClass
 * installation pattern records calls regardless of which path was used.
 *
 * Not deployed to Hubitat — see {@link hubitat.helper.RMUtils}.
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
