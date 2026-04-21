package hubitat.helper

/**
 * BUILD-TIME STUB of Hubitat's `hubitat.helper.RMUtils` helper. Lives on
 * `src/main/groovy/` so HubitatCI's SandboxClassLoader parent chain
 * (AppClassLoader, which sees Gradle's main source-set output) can
 * resolve the class when `support.PassThroughSandboxClassLoader`
 * routes a sandbox-loaded reference here without the standard
 * `mapClassName` remap. Test-side direct calls (e.g. from
 * `RMUtilsMockSpec`) also resolve to this class.
 *
 * **Not deployed to Hubitat.** `packageManifest.json` deploys only the
 * top-level `hubitat-mcp-server.groovy` and `hubitat-mcp-rule.groovy`
 * files via raw GitHub URLs; nothing under `src/main/groovy/` is shipped.
 * On a real hub, `hubitat.helper.RMUtils` is the platform-provided class.
 *
 * Method signatures mirror the surface used by PR #79's
 * `manage_rule_machine` gateway tools (list, run, pause, resume, boolean).
 * Real behaviour is wired per-test via metaclass from
 * `support.RMUtilsMock`.
 */
class RMUtils {
    static List getRuleList() { return [] }
    static List getRuleList(String version) { return [] }
    // Legacy simple form (retained for pre-PR-79 specs that used it).
    static void sendAction(Long ruleId, String action) { }
    // Preferred 4-arg form used by PR #79's sendRmAction wrapper:
    // sendAction([ruleIds], action, appLabel, "5.0") targets the RM 5.x dispatcher.
    static void sendAction(List ruleIds, String action, String appLabel, String version) { }
    // 3-arg fallback form used when the 4-arg signature is absent on older firmware.
    static void sendAction(List ruleIds, String action, String appLabel) { }
    static void pauseRule(Long ruleId) { }
    static void resumeRule(Long ruleId) { }
    static void setRuleBoolean(Long ruleId, Boolean value) { }
}
