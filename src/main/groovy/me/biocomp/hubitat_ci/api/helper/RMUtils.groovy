package me.biocomp.hubitat_ci.api.helper

/**
 * DIAGNOSTIC iteration on PR #99. See class doc on the previous version
 * — this revision adds System.err logging at static-init + each method
 * entry + the inner-call try/catch so CI surfaces exactly which
 * lookup/dispatch step is failing.
 */
class RMUtils {
    static {
        System.err.println("=== STUB-INIT: me.biocomp.hubitat_ci.api.helper.RMUtils loaded by classLoader=" + RMUtils.class.classLoader)
    }

    static List getRuleList(String version = '5.0') {
        System.err.println("=== STUB-CALL: me.biocomp.hubitat_ci.api.helper.RMUtils.getRuleList version=" + version)
        try {
            def result = hubitat.helper.RMUtils.getRuleList(version)
            System.err.println("=== STUB-RESULT: me.biocomp...RMUtils.getRuleList -> ${result}")
            return result
        } catch (Throwable t) {
            System.err.println("=== STUB-ERROR: me.biocomp...RMUtils.getRuleList threw ${t.getClass().name}: ${t.message}")
            throw t
        }
    }

    static void sendAction(Long ruleId, String action) {
        System.err.println("=== STUB-CALL: me.biocomp.hubitat_ci.api.helper.RMUtils.sendAction ruleId=${ruleId} action=${action}")
        hubitat.helper.RMUtils.sendAction(ruleId, action)
    }

    static void pauseRule(Long ruleId) {
        System.err.println("=== STUB-CALL: me.biocomp.hubitat_ci.api.helper.RMUtils.pauseRule ruleId=${ruleId}")
        hubitat.helper.RMUtils.pauseRule(ruleId)
    }

    static void resumeRule(Long ruleId) {
        System.err.println("=== STUB-CALL: me.biocomp.hubitat_ci.api.helper.RMUtils.resumeRule ruleId=${ruleId}")
        hubitat.helper.RMUtils.resumeRule(ruleId)
    }

    static void setRuleBoolean(Long ruleId, Boolean value) {
        System.err.println("=== STUB-CALL: me.biocomp.hubitat_ci.api.helper.RMUtils.setRuleBoolean ruleId=${ruleId} value=${value}")
        hubitat.helper.RMUtils.setRuleBoolean(ruleId, value)
    }
}
