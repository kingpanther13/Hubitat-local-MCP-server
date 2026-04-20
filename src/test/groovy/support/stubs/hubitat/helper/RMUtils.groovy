package hubitat.helper

/**
 * Test-only stub of Hubitat's RMUtils helper. Method signatures mirror
 * the surface used by PR #79's manage_rule_machine gateway tools
 * (list, run, pause, resume, boolean). Real behaviour is wired per-test
 * via metaclass from RMUtilsMock. This class exists solely so production
 * code that references hubitat.helper.RMUtils can load under the test JVM.
 */
class RMUtils {
    static List getRuleList(String version = '5.0') { return [] }
    static void sendAction(Long ruleId, String action) { }
    static void pauseRule(Long ruleId) { }
    static void resumeRule(Long ruleId) { }
    static void setRuleBoolean(Long ruleId, Boolean value) { }
}
