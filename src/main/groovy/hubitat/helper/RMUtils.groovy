package hubitat.helper

/**
 * DIAGNOSTIC iteration on PR #99. See previous version's doc — this
 * revision adds System.err at static-init + each method entry so CI
 * shows whether the test-target stub gets loaded and whether the
 * delegate from me.biocomp.hubitat_ci.api.helper.RMUtils reaches it.
 */
class RMUtils {
    static {
        System.err.println("=== STUB-INIT: hubitat.helper.RMUtils loaded by classLoader=" + RMUtils.class.classLoader)
    }

    static List getRuleList(String version = '5.0') {
        System.err.println("=== STUB-CALL: hubitat.helper.RMUtils.getRuleList version=" + version)
        return []
    }
    static void sendAction(Long ruleId, String action) {
        System.err.println("=== STUB-CALL: hubitat.helper.RMUtils.sendAction ruleId=${ruleId} action=${action}")
    }
    static void pauseRule(Long ruleId) {
        System.err.println("=== STUB-CALL: hubitat.helper.RMUtils.pauseRule ruleId=${ruleId}")
    }
    static void resumeRule(Long ruleId) {
        System.err.println("=== STUB-CALL: hubitat.helper.RMUtils.resumeRule ruleId=${ruleId}")
    }
    static void setRuleBoolean(Long ruleId, Boolean value) {
        System.err.println("=== STUB-CALL: hubitat.helper.RMUtils.setRuleBoolean ruleId=${ruleId} value=${value}")
    }
}
